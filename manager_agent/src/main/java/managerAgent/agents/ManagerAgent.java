package managerAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.agent.Event;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.tool.Toolkit;
import config.AgentScopeProperties;
import managerAgent.config.ManagerAgentProperties;
import managerAgent.hook.PlanHook;
import managerAgent.hook.TraceHook;
import managerAgent.plan.TripPlan;
import managerAgent.tool.RemoteAgentTool;
import managerAgent.trace.RunTrace;
import managerAgent.trace.RunTraceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import utils.AgentUtils;
import utils.NacosUtil;
import utils.ToolUtils;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * author: Imooc
 * description: 主管Agent —— 自主拆解任务 + 决策派发给远程 Agent + 汇总结果
 * date: 2026
 *
 * <p>一次请求 = 一个全新的 ReActAgent 实例。这样做的好处：</p>
 * <ul>
 *   <li>请求之间不会共享 Memory，避免串话；</li>
 *   <li>ReActAgent 内部有"正在运行"标志位，共享实例并发调用会直接抛异常。</li>
 * </ul>
 */
@Component
public class ManagerAgent {

    private static final Logger log = LoggerFactory.getLogger(ManagerAgent.class);

    /** 内置系统提示词：主管 Agent 的行为准则 */
    private static final String DEFAULT_SYS_PROMPT = """
            你是 AiTripPlan 的「主管智能体 ManagerAgent」，负责统筹整个行程规划流程。

            【你的职责】
            1. 先解析用户需求中的关键要素：出发地、目的地、日期、天数、交通方式、人数、预算与偏好。
            2. 使用计划工具（PlanNotebook）把复杂需求拆解为若干可独立执行的子任务。
            3. 通过工具调用，把子任务派发给注册在 Nacos 上的远程专业 Agent，不要自己臆造路线和景点细节。
            4. 汇总所有子任务的返回结果，输出一份完整、可直接执行的行程方案。

            【硬性要求】
            - 每个子任务都必须注明是由哪个 Agent 完成的（例如「由 RouteMakingAgent 完成」）。
            - ★ 互相独立的子任务，必须在【同一次回复里一次性发出多个工具调用】，
              这样它们会被并行执行，总耗时取最慢的那个，而不是相加。
              例如路线的路线制定和景点的行程规划互不依赖，就应该在同一轮里一起派发；
              但「先查天气、再据此排行程」这种有先后依赖的，必须分轮次串行执行。
            - 调用远程 Agent 时，task 参数要写清楚：出发地、目的地、日期、天数、偏好等全部约束，
              不要让下游 Agent 反问。
            - 如果某个远程 Agent 调用失败，如实说明失败原因，不要编造结果。
            - 最终输出使用 Markdown：包含①总体路线与时间安排 ②每日行程 ③餐饮推荐 ④住宿建议
              ⑤天气与注意事项 ⑥预算估算。
            """;

    private final io.agentscope.core.model.Model model;
    private final ManagerAgentProperties properties;
    private final AgentScopeProperties commonsProperties;
    private final RunTraceRegistry traceRegistry;
    private final ObjectProvider<AgentCardResolver> agentCardResolverProvider;

    /** 兜底用的 Resolver：万一 starter 没有提供 AgentCardResolver Bean，就用自己建的 */
    private volatile AgentCardResolver fallbackResolver;

    public ManagerAgent(io.agentscope.core.model.Model model,
                        ManagerAgentProperties properties,
                        AgentScopeProperties commonsProperties,
                        RunTraceRegistry traceRegistry,
                        ObjectProvider<AgentCardResolver> agentCardResolverProvider) {
        this.model = model;
        this.properties = properties;
        this.commonsProperties = commonsProperties;
        this.traceRegistry = traceRegistry;
        this.agentCardResolverProvider = agentCardResolverProvider;
    }

    /**
     * author: Imooc
     * description: 组装一个全新的主管 Agent
     * @param trace: 本次运行的轨迹对象，会被 TraceHook 写入
     * @return io.agentscope.core.ReActAgent
     */
    public ReActAgent newAgent(RunTrace trace) {

        // ★ 关键：必须显式开并行。AgentScope 的 ToolkitConfig.parallel 默认是 false，
        //   用 new Toolkit() 会让同一轮里的多个工具调用串行执行（Flux.concat），
        //   总耗时 = 各工具耗时之和；开成 true 后走 Flux.mergeSequential，
        //   同时订阅所有工具，总耗时 ≈ 最慢那个工具。
        Toolkit toolkit = ToolUtils.createToolkit(commonsProperties.isToolParallel());

        //1. 把配置里声明的每一个远程 Agent 都包装成 LLM 可调用的工具
        int mounted = 0;
        for (ManagerAgentProperties.RemoteAgent spec : properties.getRemoteAgents()) {
            if (!spec.isEnabled() || !StringUtils.hasText(spec.getName())) {
                continue;
            }
            toolkit.registration()
                    .agentTool(new RemoteAgentTool(spec, resolver(),
                            commonsProperties.getRemoteCallTimeout(),
                            properties.getRemoteResultMaxChars(),
                            trace))
                    .apply();
            mounted++;
        }
        log.info("[ManagerAgent] 已挂载 {} 个远程 Agent 工具：{}（工具并行={}）",
                mounted, toolkit.getToolNames(), commonsProperties.isToolParallel());

        //2. PlanNotebook：自主拆解任务的核心
        PlanNotebook planNotebook = new TripPlan(properties).getPlan();

        //3. 组装 Agent
        return AgentUtils.getReActAgentBuilder(
                        properties.getName(),
                        properties.getDescription(),
                        model,
                        StringUtils.hasText(properties.getSysPrompt())
                                ? properties.getSysPrompt()
                                : DEFAULT_SYS_PROMPT)
                .maxIters(properties.getMaxIters())
                // 工具执行超时（框架兜底）必须大于 remote-call-timeout，
                // 这样超时时先由 RemoteAgentTool 自己的超时逻辑给出可读提示
                .toolExecutionConfig(ExecutionConfig.builder()
                        .timeout(commonsProperties.getToolExecutionTimeout())
                        .build())
                //工具包
                .toolkit(toolkit)
                //自主规划
                .planNotebook(planNotebook)
                //计划过程日志
                .hook(new PlanHook(planNotebook))
                //运行轨迹（可观测）
                .hook(new TraceHook(trace))
                .build();
    }

    /**
     * author: Imooc
     * description: 同步执行一次：发给主管 Agent，等它把整条链路跑完
     * @param prompt: 用户 Prompt
     * @return managerAgent.trace.RunTrace
     */
    public RunTrace invoke(String prompt) {
        RunTrace trace = newTrace(prompt);
        try {
            ReActAgent agent = newAgent(trace);
            String answer = AgentUtils.collectFinalText(
                    AgentUtils.streamResponse(agent, prompt),
                    commonsProperties.getRunTimeout());
            trace.finish(answer);
        } catch (Exception e) {
            log.error("[ManagerAgent] 运行失败", e);
            trace.fail(e);
        }
        return trace;
    }

    /**
     * author: Imooc
     * description: 流式执行一次，返回事件流 + 轨迹对象（控制器负责转成 SSE）
     * @param prompt: 用户 Prompt
     * @return managerAgent.agents.ManagerAgent.StreamedRun
     */
    public StreamedRun streamRun(String prompt) {
        RunTrace trace = newTrace(prompt);
        ReActAgent agent = newAgent(trace);

        AtomicReference<String> lastText = new AtomicReference<>("");

        Flux<Event> events = AgentUtils.streamResponse(agent, prompt, AgentUtils.CHUNK_EVENTS)
                .doOnNext(event -> {
                    String text = AgentUtils.textOf(event.getMessage());
                    if (!text.isBlank()) {
                        lastText.set(text);
                    }
                })
                .doOnComplete(() -> trace.finish(lastText.get()))
                .doOnError(trace::fail);

        return new StreamedRun(trace, events);
    }

    private RunTrace newTrace(String prompt) {
        RunTrace trace = new RunTrace(UUID.randomUUID().toString().replace("-", "").substring(0, 16), prompt);
        traceRegistry.put(trace);
        return trace;
    }

    /**
     * 优先使用 AgentScope Nacos starter 提供的 Resolver（自带卡片缓存与订阅刷新），
     * 拿不到时退化为自己创建的 Resolver。
     */
    private AgentCardResolver resolver() {
        AgentCardResolver resolver = agentCardResolverProvider.getIfAvailable();
        if (resolver != null) {
            return resolver;
        }
        AgentCardResolver local = fallbackResolver;
        if (local == null) {
            synchronized (this) {
                if (fallbackResolver == null) {
                    try {
                        fallbackResolver = new NacosAgentCardResolver(
                                NacosUtil.getNacosClient(commonsProperties.getNacos().getServerAddr()));
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "无法创建 Nacos AgentCardResolver，请检查 Nacos 是否已启动：" + e.getMessage(), e);
                    }
                }
                local = fallbackResolver;
            }
        }
        return local;
    }

    /**
     * 一次流式运行：轨迹 + 事件流
     */
    public record StreamedRun(RunTrace trace, Flux<Event> events) {
    }

    /** 对外暴露超时时间，控制器做 SSE 超时用 */
    public Duration callTimeout() {
        return commonsProperties.getRemoteCallTimeout();
    }
}

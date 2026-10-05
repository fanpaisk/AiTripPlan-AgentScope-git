package managerAgent.agents;

import config.AgentScopeProperties;
import context.ArtifactStore;
import context.ContextProperties;
import context.ReadArtifactTool;
import context.ToolRouter;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.agent.Event;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import managerAgent.config.ManagerAgentProperties;
import managerAgent.hook.PlanHook;
import managerAgent.hook.TraceHook;
import managerAgent.plan.TripPlan;
import managerAgent.tool.RemoteAgentTool;
import managerAgent.trace.RunTrace;
import managerAgent.trace.RunTraceRegistry;
import mcp.BaiduMapMCP;
import mcp.OffloadingMcpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import tools.Calculate;
import utils.AgentUtils;
import utils.NacosUtil;
import utils.SkillUtils;
import utils.ToolUtils;

import java.time.Duration;
import java.util.List;
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
 *
 * <p><b>两种运行模式（EXP-001 对照实验）：</b></p>
 * <ul>
 *   <li>{@code multi}（默认）：用 PlanNotebook 拆任务，把子任务经 A2A 派发给远程专业 Agent；</li>
 *   <li>{@code single}：一个 Agent 自己持有全部能力（地图 MCP + Skills + 计算工具 + PlanNotebook），
 *       <b>不做任何派发</b> —— 用来回答"多 Agent 的分工到底值不值它多花的 token"。</li>
 * </ul>
 * <p>模式可由配置 {@code app.manager.mode} 决定，也可由请求体 {@code mode} 字段逐次覆盖。
 * 用请求级覆盖而不是重启服务来切臂，是为了避免 JIT 预热差异污染延迟对比。</p>
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

    /**
     * 单 Agent 模式（EXP-001 对照实验的 A2 臂）的系统提示词。
     *
     * <p><b>撰写原则（关系到实验是否公平）：</b>
     * 必须与多 Agent 臂「最终作答」的要求对齐 —— 同样的六段输出结构、同样要求真实数据、
     * 同样要求独立调用一次性批量发出（否则 toolBatches 并行度就没法对比）、
     * 同样要求失败如实说明（对应已知缺陷 D2）。
     * 差别只应有一处：<b>它没有子 Agent 可派发，必须自己做完全部工作</b>。</p>
     */
    private static final String SINGLE_SYS_PROMPT = """
            你是 AiTripPlan 的「全能行程规划智能体」，需要独立完成从路线到行程的全部规划工作。

            【你的能力】
            1. 计划工具（PlanNotebook）：把复杂需求拆解为可执行的步骤并跟踪进度。
            2. 百度地图工具：地理编码、驾车路线规划、距离与耗时、路况、周边 POI、逆地理编码。
            3. 技能（Skill）：按需加载，获取景点推荐与表格制作的规则说明。
            4. 计算工具：预算、油耗等数值必须用它算，不要心算。

            【工作方式】
            1. 先解析需求中的关键要素：出发地、目的地、日期、天数、交通方式、人数、预算与偏好。
            2. ★ 需要真实路线数据时，调用百度地图工具获取真实距离与耗时；
               严禁把估算值当成真实数据，拿不到时必须显式说明，并标注哪些数字是估算值。
            3. ★ 互相独立的工具调用（例如多个地点的地理编码、路线与距离），
               必须在【同一次回复里一次性发出多个工具调用】，这样它们会被并行执行，
               总耗时取最慢的那个而不是相加；有先后依赖的（先查天气再据此排行程）必须分轮次串行。
            4. ★ 技能只需加载一次了解规则即可，不要反复读取技能里的资源文件；
               某个资源不存在就跳过，不要重试。
            5. 如果某个工具调用失败，如实说明失败原因，不要编造结果，也不要反复重试无望的接口。

            【输出要求】
            使用 Markdown，最终输出一份完整、可直接执行的行程方案，包含：
            ①总体路线与时间安排 ②每日行程 ③餐饮推荐 ④住宿建议 ⑤天气与注意事项 ⑥预算估算。
            直接给结论，不要长篇分析，也不要反复向用户确认。

            ★ 最后一条回复必须【就是方案正文本身】：
            - 不要把方案写在中间轮次、末尾只写「已完成 / 上方已给出」之类的收尾总结；
            - 不要在末尾附加「如需我再帮你生成 1 人版 / 导出表格」之类的后续服务建议；
            - 用户拿到的就是最后这一条消息，它必须能独立阅读、内容完整。
            """;

    /**
     * 未挂载地图工具时追加到系统提示词后面的说明（机制 A 的配套）。
     *
     * <p><b>为什么必须跟着工具集改提示词：</b>工具没挂却仍要求模型去查真实路线，它只会浪费轮次
     * 反复尝试不存在的工具，最后还可能编一个失败原因（这正是已知缺陷 D2 的形态）。</p>
     */
    private static final String NO_MAP_TOOLS_SUFFIX = """

            【本次运行的特殊说明】
            本次【没有】挂载地图工具，无法查询真实的路线、距离与耗时。
            请基于通用地理常识给出方案，并【明确标注哪些数字是估算值】；
            不要尝试调用不存在的地图工具，也不要因为拿不到地图数据就停止输出。
            """;

    private final io.agentscope.core.model.Model model;
    private final ManagerAgentProperties properties;
    private final AgentScopeProperties commonsProperties;
    private final RunTraceRegistry traceRegistry;
    private final ObjectProvider<AgentCardResolver> agentCardResolverProvider;

    /** 百度地图 MCP 客户端：单 Agent 模式需要它来提供真实路线数据（Bean 在 commons，懒加载） */
    private final BaiduMapMCP baiduMapMCP;

    /** 上下文预算配置：工具结果外置阈值、预览与取回长度（EXP-002 机制 B） */
    private final ContextProperties contextProperties;

    /**
     * Skills：单 Agent 模式要挂载，与行程规划的子 Agent 用的是同一套（都从 classpath:skills 载入）。
     * 启动时载入一次，避免每次创建 Agent 都读 jar。多 Agent 模式下不使用它。
     */
    private final List<AgentSkill> skills;

    /** 兜底用的 Resolver：万一 starter 没有提供 AgentCardResolver Bean，就用自己建的 */
    private volatile AgentCardResolver fallbackResolver;

    public ManagerAgent(io.agentscope.core.model.Model model,
                        ManagerAgentProperties properties,
                        AgentScopeProperties commonsProperties,
                        RunTraceRegistry traceRegistry,
                        ObjectProvider<AgentCardResolver> agentCardResolverProvider,
                        BaiduMapMCP baiduMapMCP,
                        ContextProperties contextProperties) {
        this.model = model;
        this.properties = properties;
        this.commonsProperties = commonsProperties;
        this.traceRegistry = traceRegistry;
        this.agentCardResolverProvider = agentCardResolverProvider;
        this.baiduMapMCP = baiduMapMCP;
        this.contextProperties = contextProperties;
        this.skills = SkillUtils.loadClasspathSkills("skills", properties.getName());
    }

    /**
     * author: Imooc
     * description: 组装一个全新的 Agent（按配置里的默认模式）
     * @param trace: 本次运行的轨迹对象，会被 TraceHook 写入
     * @return io.agentscope.core.ReActAgent
     */
    public ReActAgent newAgent(RunTrace trace) {
        return newAgent(trace, properties.isSingleMode(), contextProperties.isOffloadEnabled());
    }

    /**
     * author: Imooc
     * description: 组装一个全新的 Agent（显式指定模式）
     * @param trace: 本次运行的轨迹对象
     * @param single: true = 单 Agent 模式（自己做完）；false = 多 Agent 模式（派发给远程子 Agent）
     * @return io.agentscope.core.ReActAgent
     */
    public ReActAgent newAgent(RunTrace trace, boolean single) {
        return newAgent(trace, single, contextProperties.isOffloadEnabled());
    }

    /**
     * author: Imooc
     * description: 组装一个全新的 Agent（模式与外置开关都可显式指定）
     * @param trace: 本次运行的轨迹对象
     * @param single: true = 单 Agent 模式
     * @param offload: true = 启用工具结果外置（EXP-002 机制 B）
     * @return io.agentscope.core.ReActAgent
     */
    public ReActAgent newAgent(RunTrace trace, boolean single, boolean offload) {
        return single ? newSingleAgent(trace, offload) : newMultiAgent(trace);
    }

    /**
     * 多 Agent 臂：主管 + PlanNotebook + 远程子 Agent（现状行为，未改动）。
     */
    private ReActAgent newMultiAgent(RunTrace trace) {

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
     * 单 Agent 臂（EXP-001 的 A2）：一个 Agent 持有全部能力，不做任何派发。
     *
     * <p>能力对齐清单（与多 Agent 臂合起来拥有的能力一致）：</p>
     * <ul>
     *   <li>百度地图 MCP 全部工具（与路线子 Agent 同一份客户端实现，见 commons 的 {@code mcp.BaiduMapMCP}）</li>
     *   <li>Skills（与行程子 Agent 同一套，从 classpath:skills 载入）</li>
     *   <li>计算工具 {@code tools.Calculate}（与行程子 Agent 同一个类）</li>
     *   <li>PlanNotebook（主管本来就有，保留 —— 它是"规划工具"而不是"派发"，去掉会变成对单 Agent 不公平）</li>
     * </ul>
     *
     * <p>唯一的差别就是：<b>没有子 Agent 可以派发</b>。这正是本实验要测量的变量。</p>
     */
    private ReActAgent newSingleAgent(RunTrace trace, boolean offload) {

        //1. 工具包：地图 MCP 工具 + 计算工具
        ToolUtils toolUtils = new ToolUtils(commonsProperties.isToolParallel());

        // ★ 机制 A：按需挂载工具组。计量显示每次调用的固定开销（工具 Schema）约占首轮输入的 78%，
        //   所以先用一次极小的模型调用判断本需求是否需要真实地图数据；不需要就不挂那 10 个地图工具。
        boolean mountMap = true;
        if (contextProperties.isToolGating()) {
            mountMap = new ToolRouter(model, Duration.ofSeconds(30)).needsMapTools(trace.getPrompt());
        } else {
            log.info("[ManagerAgent] 单 Agent 模式：工具按需挂载【关闭】，挂载全部工具组");
        }

        ArtifactStore artifactStore = new ArtifactStore();
        Toolkit toolkit;

        if (mountMap) {
            // 机制 B：把超长的地图返回「外置」，上下文里只留摘要 + id。
            // 必须用装饰后的客户端注册，否则 toolkit 内部持有的是原始客户端，拦截不到。
            McpClientWrapper mcpClient = baiduMapMCP.initBaiduMapMCP();
            if (offload && mcpClient != null) {
                mcpClient = new OffloadingMcpClient(mcpClient, artifactStore,
                        contextProperties.getOffloadThresholdChars(),
                        contextProperties.getPreviewChars());
                log.info("[ManagerAgent] 单 Agent 模式：工具结果外置已开启（阈值 {} 字符，预览 {} 字符）",
                        contextProperties.getOffloadThresholdChars(), contextProperties.getPreviewChars());
            } else if (mcpClient != null) {
                log.info("[ManagerAgent] 单 Agent 模式：工具结果外置【关闭】（基线组）");
            }
            toolkit = toolUtils.registerMcpClient(mcpClient);

            // 与结果外置配套的「按需取回」工具：Agent 需要细节时自己去取，而不是让全部内容常驻上下文
            if (offload) {
                toolkit.registration()
                        .tool(new ReadArtifactTool(artifactStore, contextProperties.getRetrieveChars()))
                        .apply();
            }
        } else {
            toolkit = ToolUtils.createToolkit(commonsProperties.isToolParallel());
            log.info("[ManagerAgent] 按需挂载：本次需求不需要真实地图数据，已跳过地图工具组"
                    + "（省下 10 个工具的 Schema）");
        }

        // 计算工具（预算/油耗等），与行程子 Agent 用的是同一个类
        toolkit.registration().tool(new Calculate()).apply();

        log.info("[ManagerAgent] 单 Agent 模式：已挂载 {} 个工具：{}（工具并行={}）",
                toolkit.getToolNames().size(), toolkit.getToolNames(), commonsProperties.isToolParallel());

        //2. Skills：与行程子 Agent 同一套
        SkillBox skillBox = new SkillBox(toolkit);
        for (AgentSkill skill : skills) {
            skillBox.registerSkill(skill);
        }
        log.info("[ManagerAgent] 单 Agent 模式：已注册 {} 个 Skill：{}",
                skillBox.getAllSkillIds().size(), skillBox.getAllSkillIds());

        //3. PlanNotebook：与多 Agent 臂保持一致（规划能力不属于"派发"）
        PlanNotebook planNotebook = new TripPlan(properties).getPlan();

        //4. 组装 Agent。名字带 Single 后缀，便于在 Langfuse 里区分两条臂的 trace
        String sysPrompt = mountMap ? SINGLE_SYS_PROMPT : SINGLE_SYS_PROMPT + NO_MAP_TOOLS_SUFFIX;
        return AgentUtils.getReActAgentBuilder(
                        properties.getName() + "Single",
                        "单Agent基线：自己完成从路线到行程的全部规划（EXP-001 对照实验用）",
                        model,
                        sysPrompt)
                // 单 Agent 要独自完成子 Agent 们的工作，迭代上限给得更宽，
                // 目的是让两条臂都【不会触顶】—— 触顶了比的就是预算而不是架构了
                .maxIters(properties.getSingleMaxIters())
                .toolExecutionConfig(ExecutionConfig.builder()
                        .timeout(commonsProperties.getToolExecutionTimeout())
                        .build())
                .toolkit(toolkit)
                .skillBox(skillBox)
                .planNotebook(planNotebook)
                .hook(new PlanHook(planNotebook))
                .hook(new TraceHook(trace))
                .build();
    }

    /**
     * author: Imooc
     * description: 同步执行一次：发给 Agent，等它把整条链路跑完（用配置的默认模式）
     * @param prompt: 用户 Prompt
     * @return managerAgent.trace.RunTrace
     */
    public RunTrace invoke(String prompt) {
        return invoke(prompt, null, null);
    }

    /**
     * author: Imooc
     * description: 同步执行一次（可逐请求覆盖模式）
     * @param prompt: 用户 Prompt
     * @param modeOverride: 请求级模式覆盖（"multi" / "single"）；null 或空 = 用配置默认值
     * @return managerAgent.trace.RunTrace
     */
    public RunTrace invoke(String prompt, String modeOverride) {
        return invoke(prompt, modeOverride, null);
    }

    /**
     * author: Imooc
     * description: 同步执行一次（模式与上下文预算都可逐请求覆盖）
     * @param prompt: 用户 Prompt
     * @param modeOverride: 请求级模式覆盖；null 或空 = 用配置默认值
     * @param budgetOverride: 是否启用工具结果外置；null = 用配置默认值
     * @return managerAgent.trace.RunTrace
     */
    public RunTrace invoke(String prompt, String modeOverride, Boolean budgetOverride) {
        RunTrace trace = newTrace(prompt);
        boolean single = resolveSingle(modeOverride);
        boolean offload = budgetOverride == null ? contextProperties.isOffloadEnabled() : budgetOverride;
        trace.setMode(single ? "single" : "multi");
        trace.setContextBudget(offload);
        try {
            ReActAgent agent = newAgent(trace, single, offload);
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
        return streamRun(prompt, null, null);
    }

    /**
     * author: Imooc
     * description: 流式执行一次（可逐请求覆盖模式）
     * @param prompt: 用户 Prompt
     * @param modeOverride: 请求级模式覆盖；null 或空 = 用配置默认值
     * @return managerAgent.agents.ManagerAgent.StreamedRun
     */
    public StreamedRun streamRun(String prompt, String modeOverride) {
        return streamRun(prompt, modeOverride, null);
    }

    /**
     * author: Imooc
     * description: 流式执行一次（模式与上下文预算都可逐请求覆盖）
     * @param prompt: 用户 Prompt
     * @param modeOverride: 请求级模式覆盖；null 或空 = 用配置默认值
     * @param budgetOverride: 是否启用工具结果外置；null = 用配置默认值
     * @return managerAgent.agents.ManagerAgent.StreamedRun
     */
    public StreamedRun streamRun(String prompt, String modeOverride, Boolean budgetOverride) {
        RunTrace trace = newTrace(prompt);
        boolean single = resolveSingle(modeOverride);
        boolean offload = budgetOverride == null ? contextProperties.isOffloadEnabled() : budgetOverride;
        trace.setMode(single ? "single" : "multi");
        trace.setContextBudget(offload);
        ReActAgent agent = newAgent(trace, single, offload);

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

    /** 解析最终生效的模式：请求级覆盖优先，否则用配置默认值 */
    private boolean resolveSingle(String modeOverride) {
        return StringUtils.hasText(modeOverride)
                ? ManagerAgentProperties.isSingle(modeOverride)
                : properties.isSingleMode();
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

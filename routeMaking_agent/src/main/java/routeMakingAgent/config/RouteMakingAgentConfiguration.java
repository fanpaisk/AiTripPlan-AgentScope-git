package routeMakingAgent.config;

import config.AgentScopeProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import mcp.BaiduMapMCP;
import utils.AgentUtils;
import utils.ToolUtils;

/**
 * author: Imooc
 * description: 路线制定Agent 装配
 * date: 2026
 *
 * <p>课程原版把 {@code @Bean} 方法写在 {@code @Component RouteMakingAgent} 里，
 * 属于 Spring 的 "lite mode"，@Bean 之间不能互相引用、也不走 CGLIB 代理；
 * 这里改成标准的 {@code @Configuration}。</p>
 *
 * <p><b>为什么是 prototype？</b>
 * AgentScope 的 A2A 服务端（{@code ReActAgentWithStarterRunner}）会用
 * {@code ObjectProvider.getObject()} 为每个会话创建一个 Agent。
 * 如果 bean 是单例，所有会话会共用同一个 Agent 实例（Memory 串话 + 并发报错）。
 * 声明成 prototype 后，每个 A2A 会话都会拿到独立实例。</p>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RouteMakingAgentConfiguration {

    public static final String AGENT_NAME = "RouteMakingAgent";

    private static final String SYS_PROMPT = """
            你是「路线制定智能体 RouteMakingAgent」，专门负责自驾游 / 公共交通的路线与交通方案。

            【工作方式】
            1. 优先调用百度地图 MCP 提供的工具获取真实数据：地理编码、驾车路线规划、
               距离与耗时、途经点、路况、周边 POI。
            2. ★ 如果地图工具返回鉴权类错误（例如「APP IP校验失败」「AK 无效」「配额不足」），
               不要反复换工具重试——最多再试 1 个接口确认，然后立即停止调用地图工具，
               直接说明「地图数据因鉴权失败无法获取」，并基于通用地理常识给出行程框架，
               同时明确标注哪些数字是估算值。
               （实测：无脑重试所有地图工具会把单次执行拖到 10 分钟以上，并拖垮上游。）
            3. 严禁把估算值当成真实数据；真实数据拿不到时必须显式说明。
            4. 输出结构：①总体路线概览 ②分段路线（每段给出距离/预计耗时/主要道路或高速）
               ③建议出发时间与休息点 ④导航关键节点 ⑤备选路线与风险提示。

            【输出要求】
            使用 Markdown 表格呈现分段路线；控制在 1500 字以内，直接给结论，不要长篇分析。
            """;

    /**
     * author: Imooc
     * description: 路线制定Agent（挂载百度地图 MCP 工具）
     * <p>只要容器里存在 ReActAgent 类型的 Bean，AgentScope 的
     * AgentscopeA2aAutoConfiguration 就会自动把它包装成 A2A 服务端并注册到 Nacos，
     * 不需要手写 AgentScopeA2aServer。</p>
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public ReActAgent routeMakingAgent(Model model,
                                       BaiduMapMCP baiduMapMCP,
                                       AgentScopeProperties commonsProperties) {

        //1. 初始化百度地图 MCP 客户端（失败时返回 null，不影响 Agent 启动）
        McpClientWrapper mcpClient = baiduMapMCP.initBaiduMapMCP();

        //2. 把 MCP 服务端的所有工具注册进工具包（并行开关见 ToolUtils）
        ToolUtils toolUtils = new ToolUtils(commonsProperties.isToolParallel());
        Toolkit toolkit = toolUtils.registerMcpClient(mcpClient);

        if (toolkit.getToolNames().isEmpty()) {
            log.warn("[{}] 当前没有挂载任何工具（百度地图 MCP 不可用），"
                    + "Agent 仍会启动并注册到 Nacos，但无法提供真实路线数据。", AGENT_NAME);
        } else {
            log.info("[{}] 已挂载的工具：{}", AGENT_NAME, toolkit.getToolNames());
        }

        //3. 组装 Agent
        return AgentUtils.getReActAgentBuilder(
                        AGENT_NAME,
                        "擅长处理自驾游路线制定（基于百度地图MCP的真实距离、耗时与路况）",
                        model,
                        SYS_PROMPT)
                .maxIters(commonsProperties.getMaxIters())
                // 子 Agent 也必须配工具执行超时：它要调百度地图 MCP 工具，
                // 不配就用框架默认的 5 分钟，长任务会被从中间掐断
                .toolExecutionConfig(ExecutionConfig.builder()
                        .timeout(commonsProperties.getToolExecutionTimeout())
                        .build())
                //工具包
                .toolkit(toolkit)
                .build();
    }
}

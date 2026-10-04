package tripPlannerAgent.config;

import config.AgentScopeProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import tripPlannerAgent.agents.SuggestSightAgent;
import utils.AgentUtils;
import utils.ToolUtils;

/**
 * author: Imooc
 * description: 行程规划Agent 装配
 * date: 2026
 *
 * <p>TripPlannerAgent 是"二级编排"：</p>
 * <pre>
 *   ManagerAgent(主管)
 *        └─ A2A ─→ TripPlannerAgent(行程规划，注册在 Nacos)
 *                       └─ SubAgentTool ─→ SuggestSightAgent(景点推荐，进程内子Agent)
 *                                              ├─ Skills(Suggest-Sights / Make-A-Table)
 *                                              └─ 工具(Calculate)
 * </pre>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class TripPlannerAgentConfiguration {

    public static final String AGENT_NAME = "TripPlannerAgent";

    private static final String SYS_PROMPT = """
            你是「行程规划智能体 TripPlannerAgent」，负责把用户的目的地需求变成一份可执行的逐日行程。

            【工作方式】
            1. 先把需求拆成：景点游览、餐饮、住宿、天气、预算 五块。
            2. 需要景点/美食/住宿的具体建议时，调用子 Agent 工具「SuggestSightAgent」，
               把目的地、日期、天数、人数、预算一次说清楚。★ 最多调用 1~2 次，不要反复调用。
            3. 需要算钱时使用计算工具，不要心算。
            4. ★ 技能（Skill）只需加载一次了解规则即可，不要反复读取技能里的资源文件；
               如果某个资源不存在，直接跳过，不要重试。
            5. 输出结构：①行程总览 ②逐日行程（上午/下午/晚上，含景点、餐饮、交通方式与耗时）
               ③住宿建议 ④天气与穿衣提示 ⑤预算明细表 ⑥备选方案与注意事项。

            【硬性要求】
            - 每个子任务注明由哪个 Agent 完成（例如「由 SuggestSightAgent 完成」）。
            - 使用 Markdown，表格用 Markdown 表格语法。
            - ★ 直接给结论，控制在 2000 字以内，不要长篇分析和反复确认。
            """;

    /**
     * author: Imooc
     * description: 行程规划Agent
     * <p>容器中存在 ReActAgent Bean 时，AgentScope 的 A2A starter 会自动：
     * 1) 构建 AgentScopeA2aServer；2) 暴露 /.well-known/agent-card.json；
     * 3) 把 AgentCard 注册到 Nacos。</p>
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public ReActAgent tripPlannerAgent(Model model,
                                       SuggestSightAgent suggestSightAgent,
                                       AgentScopeProperties properties) {

        Toolkit toolkit = ToolUtils.createToolkit(properties.isToolParallel());

        //把智能体(子Agent)作为工具挂载：模型看到的是一个名为 SuggestSightAgent 的工具
        toolkit.registration()
                .subAgent(() -> suggestSightAgent.getSuggestSightAgent())
                .apply();

        log.info("[{}] 已挂载工具：{}", AGENT_NAME, toolkit.getToolNames());

        return AgentUtils.getReActAgentBuilder(
                        AGENT_NAME,
                        "擅长处理景点行程规划（景点推荐、天气、住宿、美食与预算）",
                        model,
                        SYS_PROMPT)
                .maxIters(properties.getMaxIters())
                // 子 Agent 也必须配工具执行超时：它内部要调 SuggestSightAgent，
                // 不配就用框架默认的 5 分钟，长任务会被从中间掐断（实测踩过这个坑）
                .toolExecutionConfig(ExecutionConfig.builder()
                        .timeout(properties.getToolExecutionTimeout())
                        .build())
                //挂载工具包
                .toolkit(toolkit)
                .build();
    }
}

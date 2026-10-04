package tripPlannerAgent.agents;

import config.AgentScopeProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.util.JarSkillRepositoryAdapter;
import io.agentscope.core.tool.Toolkit;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tripPlannerAgent.tool.Calculate;
import utils.AgentUtils;
import utils.ToolUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * author: Imooc
 * description: 景点推荐Agent（行程规划Agent 的子 Agent，同时挂载 Skills）
 * date: 2026
 *
 * <p>Skills 的加载链路：</p>
 * <pre>
 *   classpath:skills/&lt;SkillName&gt;/SKILL.md   （元信息 + 技能说明）
 *   classpath:skills/&lt;SkillName&gt;/scripts/**  （随技能分发的脚本，作为 resources 一起载入）
 *          ↓ JarSkillRepositoryAdapter
 *        AgentSkill  →  SkillBox.registerSkill(skill)
 *          ↓
 *   ReActAgent.skillBox(...)  →  模型可以按需"load skill"拿到技能说明
 * </pre>
 *
 * <p>与课程原版的差异：修复了 {@code skillBox.registration().tool(...)} 忘记调用
 * {@code apply()} 导致工具根本没注册的问题（AgentScope 的 Registration 是 builder 模式，
 * 不调 apply() 什么都不会发生）。</p>
 */
@Slf4j
@Component
public class SuggestSightAgent {

    public static final String AGENT_NAME = "SuggestSightAgent";

    private static final String SYS_PROMPT = """
            你是「景点推荐智能体 SuggestSightAgent」，专注于在有限预算内规划出精彩的旅行体验。

            【工作方式】
            1. 先判断任务需要用到哪个 Skill，调用技能加载工具读取对应的 SKILL.md，再按技能说明执行。
               ★ 每个技能最多加载 1 次；如果技能里的某个资源文件不存在，直接跳过，不要重试。
            2. 需要在预算内做取舍时，使用计算工具算清楚，不要心算。
            3. 输出结构：①景点推荐（含免费/付费、最佳游览时间、避峰建议）②美食推荐
               ③拍照打卡点 ④住宿方案（经济型 / 舒适型）⑤天气与穿衣建议。

            【回答原则】
            - 务实优先：所有建议都要考虑实际可行性和经济性
            - 信息准确：给出具体名称、大致地址、价格区间、开放时间
            - 因地制宜：结合季节与天气给出建议
            - ★ 直接给结论，控制在 1200 字以内，不要反复分析或重复确认
            """;

    private final Model model;
    private final AgentScopeProperties properties;

    /** 启动时一次性载入，避免每次创建 Agent 都去读磁盘/jar */
    private final List<AgentSkill> skills = new ArrayList<>();

    public SuggestSightAgent(Model model, AgentScopeProperties properties) {
        this.model = model;
        this.properties = properties;
    }

    @PostConstruct
    void loadSkills() {
        try (JarSkillRepositoryAdapter repository = new JarSkillRepositoryAdapter("skills")) {
            List<AgentSkill> loaded = repository.getAllSkills();
            if (loaded != null) {
                skills.addAll(loaded);
            }
            log.info("[{}] 从 classpath:skills 载入 {} 个 Skill：{}",
                    AGENT_NAME, skills.size(),
                    skills.stream().map(AgentSkill::getName).toList());
        } catch (Exception e) {
            log.error("[{}] 载入 skills 失败，Agent 将在没有技能的情况下运行：{}",
                    AGENT_NAME, e.getMessage());
        }
    }

    /**
     * author: Imooc
     * description: 创建一个景点推荐 Agent（每次调用返回独立实例，供 SubAgentTool 使用）
     * @return io.agentscope.core.ReActAgent
     */
    public ReActAgent getSuggestSightAgent() {

        Toolkit toolkit = ToolUtils.createToolkit(properties.isToolParallel());
        //构建 SkillBox，并把工具包和 Skill 结合
        SkillBox skillBox = new SkillBox(toolkit);

        //注册 Skill（ReActAgent.build() 内部会自动 bindToolkit + registerSkillLoadTool + 挂 SkillHook，
        //所以模型能按需"加载技能"拿到 SKILL.md 的说明）
        for (AgentSkill skill : skills) {
            skillBox.registerSkill(skill);
        }

        /*
         * 注册工具：通用工具走 toolkit.registration()。
         *
         * ⚠️ 这里有个容易踩的坑：
         *   skillBox.registration() 是用来把工具【绑定到某个具体技能】的（技能激活时才可用），
         *   它要求"必须先用 .skill(xxx) 指定技能"，否则 apply() 直接抛：
         *       IllegalStateException: Must call skill() before apply()
         *   所以像 Calculate 这种通用计算工具应该注册到 toolkit 上，全局可用。
         *
         *   另外 AgentScope 的 Registration 都是 builder 模式，最后必须调用 apply() 才真正生效，
         *   不调用会静默什么都不做（课程原代码正是漏了这一步）。
         *
         *   若确实要把工具绑定到某个技能，写法是：
         *       skillBox.registration().skill(someSkill).tool(someTool).apply();
         */
        toolkit.registration()
                .tool(new Calculate())
                .apply();

        ReActAgent agent = AgentUtils.getReActAgentBuilder(
                        AGENT_NAME,
                        "擅长景点推荐、美食与住宿建议（在有限预算内规划精彩旅行体验）",
                        model,
                        SYS_PROMPT)
                .maxIters(properties.getMaxIters())
                //挂载工具包
                .toolkit(toolkit)
                //挂载Skills
                .skillBox(skillBox)
                .build();

        log.info("[{}] 已创建，技能 {} 个，工具 {} 个",
                AGENT_NAME, skillBox.getAllSkillIds().size(), toolkit.getToolNames().size());
        return agent;
    }
}

package utils;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.JarSkillRepositoryAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * author: Imooc
 * description: Skill 载入工具类
 * date: 2026
 *
 * <p>从 classpath 的指定目录（约定为 {@code skills}）载入 Skill 集合，
 * 目录里每个子目录是一个技能（{@code SKILL.md} + 可选 {@code scripts/}）。</p>
 *
 * <p><b>为什么抽到 commons：</b>行程规划的子 Agent 与（EXP-001 对照实验的）单 Agent
 * 都要挂载同一套 Skill，能力的实现只应有一份，否则两边会各自腐化。</p>
 *
 * <p><b>目录放在哪个 jar 都能扫到</b>：实测把 {@code skills/} 放在 commons 的 jar 里，
 * 三个服务的 fat jar 都仍能正常载入（Spring Boot 的嵌套 jar classloader 支持该扫描）。</p>
 */
public final class SkillUtils {

    private static final Logger log = LoggerFactory.getLogger(SkillUtils.class);

    private SkillUtils() {
    }

    /**
     * author: Imooc
     * description: 从 classpath 目录载入全部 Skill；失败只告警返回空列表，不阻断启动
     * @param resourceDir: classpath 下的目录名，例如 "skills"
     * @param owner: 调用方名称，仅用于日志
     * @return java.util.List<io.agentscope.core.skill.AgentSkill>，可能为空列表
     */
    public static List<AgentSkill> loadClasspathSkills(String resourceDir, String owner) {

        List<AgentSkill> skills = new ArrayList<>();
        try (JarSkillRepositoryAdapter repository = new JarSkillRepositoryAdapter(resourceDir)) {
            List<AgentSkill> loaded = repository.getAllSkills();
            if (loaded != null) {
                skills.addAll(loaded);
            }
            log.info("[{}] 从 classpath:{} 载入 {} 个 Skill：{}", owner, resourceDir, skills.size(),
                    skills.stream().map(AgentSkill::getName).toList());
        } catch (Exception e) {
            log.error("[{}] 载入 skills 失败，Agent 将在没有技能的情况下运行：{}", owner, e.getMessage());
        }
        return skills;
    }
}

package managerAgent.dto;

/**
 * author: Imooc
 * description: 用户请求体
 * date: 2026
 *
 * @param prompt    用户自然语言需求
 * @param sessionId 会话标识，预留字段（当前每次请求独立运行，后续可扩展成多轮对话）
 * @param mode      运行模式覆盖："multi"（默认，主管 + 远程子 Agent）或 "single"（单 Agent 自己做完）。
 *                  留空则用配置 {@code app.manager.mode} 的值。
 *                  做成请求级开关是为了让 EXP-001 对照实验能在【同一个 JVM、同一次启动】里切换两条臂，
 *                  避免 JIT 预热差异污染延迟对比。
 * @param contextBudget 是否启用「工具结果外置」（EXP-002 机制 B）。留空则用配置
 *                  {@code app.agentscope.context.enabled} 的值。
 *                  同样做成请求级，是为了让**基线组与实验组能在同一次启动内交错运行** ——
 *                  否则两次运行之间要改配置并重启，时间漂移会与机制效果混在一起。
 */
public record ChatRequest(String prompt, String sessionId, String mode, Boolean contextBudget) {

    public static final String DEFAULT_SESSION = "default";
}

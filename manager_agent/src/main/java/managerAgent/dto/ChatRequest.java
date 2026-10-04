package managerAgent.dto;

/**
 * author: Imooc
 * description: 用户请求体
 * date: 2026
 *
 * @param prompt    用户自然语言需求
 * @param sessionId 会话标识，预留字段（当前每次请求独立运行，后续可扩展成多轮对话）
 */
public record ChatRequest(String prompt, String sessionId) {

    public static final String DEFAULT_SESSION = "default";
}

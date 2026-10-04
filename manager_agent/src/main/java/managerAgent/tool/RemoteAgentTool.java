package managerAgent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import managerAgent.config.ManagerAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * author: Imooc
 * description: 把注册在 Nacos 上的远程 Agent 动态封装成 LLM 可调用的工具
 * date: 2026
 *
 * <h3>为什么不用框架的 {@code A2aAgent}？</h3>
 *
 * <p>AgentScope 的 {@code A2aAgent} 走的是 A2A 的<b>流式</b>接口（{@code message/stream}，SSE）。
 * 实测发现它在长任务下不可用：Agent 产生的流式事件速度超过客户端消费速度时，
 * 服务端 Reactor 管道会因背压缓冲打满而抛：</p>
 *
 * <pre>
 * IllegalStateException: The following item cannot be propagated because
 *   there is no demand and the overflow buffer is full: SendStreamingMessageResponse
 * </pre>
 *
 * <p>连接被直接掐断，客户端只看到 {@code EOF reached while reading}——
 * <b>子 Agent 的真实错误（配额耗尽、模型报错等）完全传不回来</b>。任务越复杂越容易触发，
 * 表现为"短任务能跑通、长任务必挂"。</p>
 *
 * <h3>这里的做法</h3>
 *
 * <p>改用 A2A 的<b>非流式</b> JSON-RPC 接口 {@code message/send} 直接发请求：
 * 服务端会累积完整结果后一次性返回，不存在背压问题，
 * 而且失败时 {@code error} 字段能把真实原因带回来。</p>
 */
public class RemoteAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentTool.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PARAM_TASK = "task";

    private final ManagerAgentProperties.RemoteAgent spec;
    private final AgentCardResolver agentCardResolver;
    private final Duration callTimeout;
    private final int resultMaxChars;
    private final HttpClient httpClient;

    /** 用于上报本工具的真实耗时（Hook 拿不到单个工具的耗时，只能靠工具自己上报） */
    private final managerAgent.trace.RunTrace trace;

    public RemoteAgentTool(ManagerAgentProperties.RemoteAgent spec,
                           AgentCardResolver agentCardResolver,
                           Duration callTimeout,
                           int resultMaxChars,
                           managerAgent.trace.RunTrace trace) {
        this.spec = spec;
        this.agentCardResolver = agentCardResolver;
        this.callTimeout = callTimeout;
        this.resultMaxChars = resultMaxChars > 0 ? resultMaxChars : 8000;
        this.trace = trace;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String getName() {
        return spec.resolvedToolName();
    }

    @Override
    public String getDescription() {
        return spec.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        PARAM_TASK, Map.of(
                                "type", "string",
                                "description", spec.getTaskDescription()
                        )
                ),
                "required", List.of(PARAM_TASK)
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {

        ToolUseBlock toolUse = param.getToolUseBlock();
        String task = extractTask(param.getInput());

        if (task.isBlank()) {
            return Mono.just(result(toolUse,
                    "子任务内容为空。请重新调用本工具，并把要交给「" + spec.getName() + "」完成的具体任务写进 task 参数。"));
        }

        log.info("[A2A] 主管 Agent 派发子任务 -> {} : {}", spec.getName(), abbreviate(task));
        long start = System.currentTimeMillis();

        return invokeRemoteAgent(task)
                .map(payload -> {
                    long cost = System.currentTimeMillis() - start;
                    reportDuration(cost);
                    if (payload.ok()) {
                        log.info("[A2A] 远程 Agent {} 返回成功，耗时 {} ms，原始 {} 字",
                                spec.getName(), cost, payload.text().length());
                        return result(toolUse, clamp(payload.text()));
                    }
                    log.error("[A2A] 远程 Agent {} 返回失败，耗时 {} ms：{}",
                            spec.getName(), cost, payload.errorMessage());
                    return result(toolUse, failureHint(payload.errorMessage()));
                })
                .onErrorResume(error -> {
                    long cost = System.currentTimeMillis() - start;
                    reportDuration(cost);
                    boolean timeout = isTimeout(error);
                    log.error("[A2A] 调用远程 Agent {} {}（{} ms）：{}",
                            spec.getName(), timeout ? "超时" : "异常", cost, rootMessage(error));
                    return Mono.just(result(toolUse,
                            timeout ? timeoutResultHint() : failureHint(rootMessage(error))));
                });
    }

    /** 把本工具的真实耗时上报给 RunTrace，用于计算并行加速比 */
    private void reportDuration(long costMillis) {
        if (trace != null) {
            trace.recordToolDuration(spec.getName(), costMillis);
        }
    }

    // ==================================================================
    //  A2A 非流式调用（message/send）
    // ==================================================================

    /** 远程调用结果 */
    private record RemotePayload(boolean ok, String text, String errorMessage) {
    }

    private Mono<RemotePayload> invokeRemoteAgent(String task) {

        // 1) 通过 Nacos 解析 AgentCard 拿到真实地址（这一步是阻塞式 IO，放弹性线程池）
        Mono<AgentCard> cardMono = Mono.fromCallable(() -> agentCardResolver.getAgentCard(spec.getName()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(card -> {
                    if (card == null) {
                        throw new IllegalStateException(
                                "Nacos 中找不到名为「" + spec.getName() + "」的 AgentCard");
                    }
                    return card;
                });

        return cardMono.flatMap(card -> {

            String url = normalize(card.url());
            String requestBody = buildSendMessageRequest(task);

            log.info("[A2A] -> {} ({}) message/send，task 长度 {} 字",
                    spec.getName(), url, task.length());

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(callTimeout)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)))
                    .map(response -> parseResponse(response.statusCode(), response.body()));
        });
    }

    private static String normalize(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("AgentCard 里没有可用的 url");
        }
        return url.endsWith("/") ? url : url + "/";
    }

    /** 构造 A2A JSON-RPC 请求体（kind 字段是必填的，漏了会返回 -32602） */
    private static String buildSendMessageRequest(String task) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", UUID.randomUUID().toString());
        root.put("method", "message/send");

        ObjectNode message = root.putObject("params").putObject("message");
        message.put("kind", "message");
        message.put("role", "user");
        message.put("messageId", UUID.randomUUID().toString());

        ArrayNode parts = message.putArray("parts");
        parts.addObject().put("kind", "text").put("text", task);

        return root.toString();
    }

    /** 解析 JSON-RPC 响应：同时兼容 result=Message 与 result=Task 两种形态 */
    private RemotePayload parseResponse(int statusCode, String body) {

        if (body == null || body.isBlank()) {
            return new RemotePayload(false, "", "HTTP " + statusCode + " 但响应体为空");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            return new RemotePayload(false, "",
                    "HTTP " + statusCode + "，响应不是合法 JSON：" + abbreviate(body));
        }

        // JSON-RPC 顶层 error
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            return new RemotePayload(false, "",
                    "JSON-RPC error " + error.path("code").asText("")
                            + " | " + error.path("message").asText(""));
        }

        JsonNode result = root.path("result");
        if (result.isMissingNode() || result.isNull()) {
            return new RemotePayload(false, "",
                    "HTTP " + statusCode + "，响应里既没有 result 也没有 error：" + abbreviate(body));
        }

        // 形态一：result 直接是一条 Message
        String text = extractPartsText(result.path("parts"));
        if (!text.isBlank()) {
            return new RemotePayload(true, text, null);
        }

        // 形态二：result 是一个 Task —— 依次尝试 artifacts 和 status.message
        for (JsonNode artifact : result.path("artifacts")) {
            String artifactText = extractPartsText(artifact.path("parts"));
            if (!artifactText.isBlank()) {
                return new RemotePayload(true, artifactText, null);
            }
        }

        String statusText = extractPartsText(result.path("status").path("message").path("parts"));
        if (!statusText.isBlank()) {
            String state = result.path("status").path("state").asText("");
            // 任务失败时 state 会是 failed/rejected，此时 status.message 里往往带着真实错误
            boolean failed = "failed".equalsIgnoreCase(state)
                    || "rejected".equalsIgnoreCase(state)
                    || "canceled".equalsIgnoreCase(state);
            return failed
                    ? new RemotePayload(false, "", statusText)
                    : new RemotePayload(true, statusText, null);
        }

        String state = result.path("status").path("state").asText("unknown");
        return new RemotePayload(false, "",
                "远程任务结束（state=" + state + "）但没有产出任何文本。" + abbreviate(body));
    }

    private static String extractPartsText(JsonNode parts) {
        if (parts == null || !parts.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (!text.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text);
            }
        }
        return sb.toString().trim();
    }

    // ==================================================================
    //  工具结果与排错提示
    // ==================================================================

    private ToolResultBlock result(ToolUseBlock toolUse, String text) {
        String id = toolUse == null ? null : toolUse.getId();
        String name = toolUse == null ? getName() : toolUse.getName();
        return ToolResultBlock.of(id, name, TextBlock.builder().text(text).build());
    }

    /**
     * 截断过长的子 Agent 返回。
     * 不截断的话，两三份上万字的报告会把主管 Agent 的上下文撑爆，
     * 下一轮模型调用就可能超时或被连接重置（实测踩过）。
     */
    private String clamp(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= resultMaxChars) {
            return text;
        }
        return text.substring(0, resultMaxChars)
                + "\n\n...[「" + spec.getName() + "」返回内容过长，已截断；原始长度 "
                + text.length() + " 字，以上为前 " + resultMaxChars + " 字]";
    }

    private String timeoutResultHint() {
        return """
                调用远程 Agent「%s」超时（超过 %d 秒未返回）。
                说明：主管 Agent 与多个子 Agent 是【并行】派发的，多个子 Agent 同时慢不会互相累加，
                真正的问题是「%s」自己跑了太久。
                常见原因：
                  1) 它内部在调大模型，上下文长 + 多轮工具调用，单次执行确实要几分钟；
                  2) 它挂载的 MCP 服务（如百度地图）响应很慢或卡住；
                  3) 它内部陷入了反复试错的循环。
                可调项：app.agentscope.remote-call-timeout（当前 %d 秒）。
                在拿到真实结果之前，不要编造该 Agent 的输出。"""
                .formatted(spec.getName(), callTimeout.toSeconds(), spec.getName(), callTimeout.toSeconds());
    }

    private String failureHint(String realError) {
        String root = realError == null ? "" : realError;

        if (root.contains("EOF") || root.contains("chunked transfer encoding")
                || root.contains("Connection reset") || root.contains("Broken pipe")) {
            return """
                    远程 Agent「%s」的连接在返回途中被中断了（%s）。
                    ★ 这通常意味着【它自己内部出错了】。请打开「%s」服务的日志搜索 ERROR，
                    那里才有真实原因（例如模型返回 403 配额耗尽、MCP 鉴权失败等）。
                    在拿到真实结果之前，不要编造该 Agent 的输出。"""
                    .formatted(spec.getName(), abbreviate(root), spec.getName());
        }

        return """
                调用远程 Agent「%s」失败：%s
                排查顺序：
                  1) 调 GET /api/agents（主管 Agent 的 8081 端口）看 registered 与 error 字段；
                  2) 看「%s」服务的日志，确认它内部是否报错（模型配额 / MCP 鉴权 / 工具超时）；
                  3) 若错误里含 401/403/InvalidApiKey/Quota，说明问题在它调大模型那一层。
                在拿到真实结果之前，不要编造该 Agent 的输出。"""
                .formatted(spec.getName(), abbreviate(root), spec.getName());
    }

    private String extractTask(Map<String, Object> input) {
        if (input == null) {
            return "";
        }
        Object value = input.get(PARAM_TASK);
        if (value == null) {
            value = input.getOrDefault("message", input.get("prompt"));
        }
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String name = current.getClass().getName();
            String message = current.getMessage() == null ? "" : current.getMessage();
            if (name.contains("Timeout") || message.contains("timeout") || message.contains("timed out")) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}

package managerAgent.controller;

import managerAgent.agents.ManagerAgent;
import managerAgent.dto.ChatRequest;
import managerAgent.dto.ChatResponse;
import managerAgent.dto.StreamChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import utils.AgentUtils;

/**
 * author: Imooc
 * description: 用户和 Agent 互动的 Api 接口
 * date: 2026
 *
 * <p>与课程原版的差异：</p>
 * <ul>
 *   <li>原版 {@code @RequestMapping(name = "/app")} 用法是错的：
 *       {@code name} 是"给这个映射起个名字"，不是访问路径，所以接口根本访问不到。
 *       这里改成 {@code @RequestMapping("/app")} + {@code @PostMapping}。</li>
 *   <li>原版每次请求 {@code new ManagerAgent()}，且方法无返回值；
 *       这里改为单例 Bean + 明确的请求/响应体，并额外提供 SSE 流式接口。</li>
 * </ul>
 *
 * <p>注意：SSE 直接返回 {@code ServerSentEvent<StreamChunk>} 让 Spring MVC 用容器里
 * 配置好的消息转换器序列化，不要自己注入 ObjectMapper ——
 * Spring Boot 4 默认用的是 Jackson 3（{@code tools.jackson}），
 * 而 AgentScope 依赖的是 Jackson 2（{@code com.fasterxml.jackson}），
 * 两者不是同一个 Bean，混用会直接启动失败。</p>
 */
@RestController
@RequestMapping("/app")
public class AppController {

    private static final Logger log = LoggerFactory.getLogger(AppController.class);

    private final ManagerAgent managerAgent;

    public AppController(ManagerAgent managerAgent) {
        this.managerAgent = managerAgent;
    }

    /**
     * 同步接口：一次性返回完整行程方案 + 本次运行的轨迹
     * <pre>
     * POST http://127.0.0.1:8081/app
     * Content-Type: application/json
     * {"prompt": "帮我制定2026年元旦，深圳到惠州3日游自驾游计划，请包含吃住行、天气、酒店、餐饮美食"}
     * </pre>
     */
    @PostMapping
    public ResponseEntity<ChatResponse> app(@RequestBody(required = false) ChatRequest request) {
        String prompt = requirePrompt(request);

        long start = System.currentTimeMillis();
        // mode 与 contextBudget 都是请求级覆盖：留空则用配置默认值
        var trace = managerAgent.invoke(prompt,
                request == null ? null : request.mode(),
                request == null ? null : request.contextBudget());
        log.info("[AppController] 同步请求完成，runId={}, 耗时 {} ms", trace.getRunId(),
                System.currentTimeMillis() - start);

        return ResponseEntity.ok(ChatResponse.of(trace));
    }

    /**
     * 流式接口（SSE）：边想边推，前端可以做出"打字机"效果
     * <pre>
     * POST http://127.0.0.1:8081/app/stream
     * Accept: text/event-stream
     * {"prompt": "..."}
     * </pre>
     * 事件类型：chunk（推理 / 工具调用分片）、done（最终回答）、error
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamChunk>> stream(@RequestBody(required = false) ChatRequest request) {
        String prompt = requirePrompt(request);
        ManagerAgent.StreamedRun run = managerAgent.streamRun(prompt,
                request == null ? null : request.mode(),
                request == null ? null : request.contextBudget());
        String runId = run.trace().getRunId();

        log.info("[AppController] 收到流式请求 runId={}", runId);

        Flux<ServerSentEvent<StreamChunk>> chunks = run.events()
                .map(event -> sse("chunk", StreamChunk.of(
                        runId,
                        event.getType().name(),
                        AgentUtils.textOf(event.getMessage()),
                        event.isLast())));

        return chunks
                .onErrorResume(error -> {
                    log.error("[AppController] 流式请求失败 runId={}", runId, error);
                    return Flux.just(sse("error", StreamChunk.failed(runId, String.valueOf(error.getMessage()))));
                })
                // 正常结束后补一个 done 事件，把最终回答与总耗时带给前端
                .concatWith(Flux.defer(() -> Flux.just(sse("done", StreamChunk.done(run.trace())))));
    }

    private ServerSentEvent<StreamChunk> sse(String eventName, StreamChunk payload) {
        return ServerSentEvent.<StreamChunk>builder(payload)
                .event(eventName)
                .build();
    }

    private String requirePrompt(ChatRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("请求体不能为空，格式示例：{\"prompt\":\"帮我制定深圳到惠州3日游计划\"}");
        }
        return request.prompt().trim();
    }
}

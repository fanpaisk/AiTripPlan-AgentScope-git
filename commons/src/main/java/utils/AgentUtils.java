package utils;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * author: Imooc
 * description: ReActAgent 工具类
 * date: 2026
 *
 * <p>和课程原版相比改了两点：</p>
 * <ol>
 *   <li>不再硬编码 apiKey，模型由 Spring 容器统一注入（见 {@code CommonsAutoConfiguration}）；</li>
 *   <li>补齐了消息构造、最终结果提取、流式选项等公共方法，避免每个 Agent 各写一遍。</li>
 * </ol>
 */
public final class AgentUtils {

    private AgentUtils() {
    }

    /** 订阅全部事件类型，且不做增量切分：每个事件里的消息都是完整内容，便于直接落库/打日志 */
    public static final StreamOptions FULL_EVENTS = StreamOptions.builder()
            .eventTypes(EventType.REASONING,
                    EventType.TOOL_RESULT,
                    EventType.HINT,
                    EventType.AGENT_RESULT,
                    EventType.SUMMARY)
            .incremental(false)
            .includeReasoningResult(true)
            .build();

    /** 面向 SSE 前端的增量流式：思考过程与工具调用都按 chunk 吐出，体验最好 */
    public static final StreamOptions CHUNK_EVENTS = StreamOptions.builder()
            .eventTypes(EventType.REASONING,
                    EventType.TOOL_RESULT,
                    EventType.HINT,
                    EventType.AGENT_RESULT,
                    EventType.SUMMARY)
            .incremental(true)
            .includeReasoningChunk(true)
            .includeReasoningResult(true)
            .includeActingChunk(true)
            .build();

    /**
     * author: Imooc
     * description: 创建 ReAct Agent Builder
     * @param name: Agent 名称（同时也是注册到 Nacos 的 AgentCard 名称）
     * @param description: Agent 能力描述，主管 Agent 就是靠它来决定把子任务派给谁
     * @param model: 语言模型
     * @return io.agentscope.core.ReActAgent.Builder
     */
    public static ReActAgent.Builder getReActAgentBuilder(String name, String description, Model model) {
        return ReActAgent.builder()
                .name(name)
                .description(description)
                .model(model);
    }

    /**
     * author: Imooc
     * description: 创建带系统提示词的 ReAct Agent Builder
     * @param sysPrompt: 系统提示词，为空时使用框架默认
     */
    public static ReActAgent.Builder getReActAgentBuilder(String name,
                                                          String description,
                                                          Model model,
                                                          String sysPrompt) {
        ReActAgent.Builder builder = getReActAgentBuilder(name, description, model);
        if (sysPrompt != null && !sysPrompt.isBlank()) {
            builder.sysPrompt(sysPrompt);
        }
        return builder;
    }

    /**
     * author: Imooc
     * description: 构造一条用户消息
     * @param text: 消息内容
     * @return io.agentscope.core.message.Msg
     */
    public static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    /**
     * author: Imooc
     * description: 构造一条指定角色的消息
     */
    public static Msg msg(MsgRole role, String text) {
        return Msg.builder()
                .role(role)
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    /**
     * author: Imooc
     * description: 安全地取消息文本（可能为 null）
     */
    public static String textOf(Msg msg) {
        if (msg == null) {
            return "";
        }
        String text = msg.getTextContent();
        return text == null ? "" : text;
    }

    /**
     * author: Imooc
     * description: ReAct Agent 流式响应（完整事件，适合后端同步接口 / 打日志）
     * @param agent: Agent
     * @param prompt: 用户 Prompt
     * @return reactor.core.publisher.Flux<io.agentscope.core.agent.Event>
     */
    public static Flux<Event> streamResponse(AgentBase agent, String prompt) {
        return streamResponse(agent, prompt, FULL_EVENTS);
    }

    /**
     * author: Imooc
     * description: ReAct Agent 流式响应（可指定 StreamOptions）
     */
    public static Flux<Event> streamResponse(AgentBase agent, String prompt, StreamOptions options) {
        return agent.stream(userMsg(prompt), options);
    }

    /**
     * author: Imooc
     * description: 阻塞收集一次调用的最终回答
     * <p>优先取 AGENT_RESULT 事件，取不到就退化成"最后一个非空文本"，
     * 这样即使 Agent 直接以思考内容结束也不会返回空串。</p>
     * @param stream: Agent 事件流
     * @param timeout: 超时时间
     * @return java.lang.String
     */
    public static String collectFinalText(Flux<Event> stream, Duration timeout) {
        AtomicReference<String> lastAnyText = new AtomicReference<>("");
        AtomicReference<String> agentResult = new AtomicReference<>("");

        stream.doOnNext(event -> {
            String text = textOf(event.getMessage());
            if (!text.isBlank()) {
                lastAnyText.set(text);
                if (event.getType() == EventType.AGENT_RESULT) {
                    agentResult.set(text);
                }
            }
        }).blockLast(timeout);

        String result = agentResult.get();
        return result.isBlank() ? lastAnyText.get() : result;
    }

    /**
     * author: Imooc
     * description: 从事件中提取适合直接展示给用户的文本，取不到返回 null（调用方据此跳过）
     */
    public static String displayTextOf(Event event) {
        String text = textOf(event.getMessage());
        return text.isBlank() ? null : text;
    }
}

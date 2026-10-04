package managerAgent.hook;

import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import managerAgent.trace.RunTrace;
import reactor.core.publisher.Mono;
import utils.AgentUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * author: Imooc
 * description: 运行轨迹拦截器：把 Agent 的推理 / 工具调用过程写进 RunTrace
 * date: 2026
 *
 * <p>Hook 是 AgentScope 里唯一能"无侵入"观测 Agent 内部过程的扩展点。
 * 一次 HTTP 请求 = 一个 ReActAgent 实例 = 一个 TraceHook = 一条 RunTrace。</p>
 *
 * <p>工具耗时通过 PreActing → PostActing 配对计算，
 * 这样 {@code /api/runs/{runId}} 里能直接看到"哪个子 Agent 花了多久"，而不是一堆 0。</p>
 */
public class TraceHook implements Hook {

    private final RunTrace trace;

    /** toolUseId -> 开始时间（纳秒），用于配对计算工具真实耗时 */
    private final Map<String, Long> toolStartNanos = new ConcurrentHashMap<>();

    /** 最近一轮推理的起点 */
    private volatile long reasoningStartNanos;

    /**
     * 当前工具批次的统计。
     * <p>AgentScope 会先把一批工具的 PreActing 全部通知完，执行完整批之后再逐个通知 PostActing。
     * 所以「本批第 1 个 PreActing」到「本批最后一个 PostActing」就是这批工具的墙钟耗时，
     * 结合工具自己上报的耗时即可算出真实并行度。</p>
     */
    private volatile long batchStartNanos;
    private volatile int batchSize;
    private volatile int batchCompleted;

    public TraceHook(RunTrace trace) {
        this.trace = trace;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {

        if (event instanceof PreReasoningEvent e) {
            reasoningStartNanos = System.nanoTime();
            var messages = e.getInputMessages();
            if (!messages.isEmpty()) {
                trace.addStep(RunTrace.StepType.HINT, "PreReasoning",
                        AgentUtils.textOf(messages.get(messages.size() - 1)));
            }

        } else if (event instanceof PostReasoningEvent e) {
            Msg reasoning = e.getReasoningMessage();
            String text = AgentUtils.textOf(reasoning);
            trace.addStep(RunTrace.StepType.REASONING,
                    e.getAgent().getName(),
                    text.isBlank() ? "(本轮无文本输出，直接调用了工具)" : text,
                    elapsedMillis(reasoningStartNanos));

        } else if (event instanceof PreActingEvent e) {
            ToolUseBlock use = e.getToolUse();
            synchronized (this) {
                if (batchSize == 0) {
                    // 新的一批工具开始
                    batchStartNanos = System.nanoTime();
                }
                batchSize++;
            }
            if (use != null) {
                toolStartNanos.put(use.getId(), System.nanoTime());
            }
            trace.addStep(RunTrace.StepType.TOOL_CALL,
                    use == null ? "unknown" : use.getName(),
                    String.valueOf(use == null ? "" : use.getInput()));

        } else if (event instanceof PostActingEvent e) {
            ToolUseBlock use = e.getToolUse();
            String toolName = use == null ? "unknown" : use.getName();
            long costMillis = 0L;
            if (use != null) {
                Long start = toolStartNanos.remove(use.getId());
                if (start != null) {
                    costMillis = (System.nanoTime() - start) / 1_000_000L;
                }
            }
            trace.addStep(RunTrace.StepType.TOOL_RESULT,
                    toolName,
                    e.getToolResult() == null ? "" : summarize(e.getToolResult().getOutput()),
                    costMillis);

            // 整批结束时记录批次统计（用于判断串行/并行）
            synchronized (this) {
                batchCompleted++;
                if (batchCompleted >= batchSize && batchSize > 0) {
                    long wallMillis = (System.nanoTime() - batchStartNanos) / 1_000_000L;
                    trace.recordToolBatch(batchSize, wallMillis);
                    batchSize = 0;
                    batchCompleted = 0;
                }
            }
        } else if (event instanceof ErrorEvent e) {
            trace.addStep(RunTrace.StepType.HINT, "ERROR", String.valueOf(e.getError()));
        }

        // 必须返回原事件，Hook 链才能继续
        return Mono.just(event);
    }

    private static long elapsedMillis(long startNanos) {
        return startNanos == 0L ? 0L : (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String summarize(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }
}

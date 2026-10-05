package meter;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * author: Imooc
 * description: 模型装饰器 —— 计量每次 LLM 调用的「上下文构成」
 * date: 2026
 *
 * <p><b>为什么不能只看 token 总量：</b>EXP-001 只知道"单 Agent 一次调用 19 万 token"，
 * 但不知道这些 token 是<b>工具 schema</b>撑的、还是<b>累积的历史与工具结果</b>撑的 ——
 * 而这两者的解法完全不同（前者裁 schema，后者外置结果）。
 * <b>可观测性必须能测到你要优化的那个指标</b>（这是本项目 D-006 的教训），
 * 所以先补上这个计量器，再谈优化。</p>
 *
 * <p>{@code Model} 只有 {@code stream} / {@code getModelName} 两个方法，因此可以干净地装饰。
 * 每次调用打印：真实输入/输出 token（取自厂商返回的 usage）、消息条数与字符数、
 * 工具个数与 schema 字符数，以及**估算的 schema 占比**。</p>
 *
 * <p><b>口径说明：</b>字符数由 {@code getTextContent()} 与工具 schema 的字段长度求和得到，
 * 是**估算**（用于横向对比各机制的效果，不是精确账单）。真实 token 数一律以 usage 为准。</p>
 */
public class ContextMeterModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(ContextMeterModel.class);

    private final Model delegate;
    private final String label;

    private final AtomicLong callSeq = new AtomicLong();
    private final AtomicLong inputSum = new AtomicLong();
    private final AtomicLong outputSum = new AtomicLong();
    private final AtomicLong maxInput = new AtomicLong();

    public ContextMeterModel(Model delegate, String label) {
        this.delegate = delegate;
        this.label = label == null ? "model" : label;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

        final long seq = callSeq.incrementAndGet();
        final int msgCount = messages == null ? 0 : messages.size();
        final long msgChars = sumMsgChars(messages);
        final int toolCount = tools == null ? 0 : tools.size();
        final long toolChars = sumToolChars(tools);

        return delegate.stream(messages, tools, options)
                .doOnNext(resp -> record(seq, msgCount, msgChars, toolCount, toolChars, resp));
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    private void record(long seq, int msgCount, long msgChars, int toolCount, long toolChars, ChatResponse resp) {

        ChatUsage usage = resp == null ? null : resp.getUsage();
        // 流式响应只在最后一个分片带 usage，其余分片为 0 —— 只在有值时记录，保证一次调用只打一行
        if (usage == null || usage.getInputTokens() <= 0) {
            return;
        }

        long in = usage.getInputTokens();
        long out = usage.getOutputTokens();
        inputSum.addAndGet(in);
        outputSum.addAndGet(out);
        maxInput.accumulateAndGet(in, Math::max);

        // 估算：输入 ≈ (消息字符 + schema 字符) / 4
        // ⚠️ 这是**粗估**：中文约 1 token/字符，英文 JSON 约 4 字符/token，所以比例只用于横向对比，
        //    绝对值一律以 usage 的 inputTokens 为准。要精确归因请用「外置开/关两条曲线的差值」。
        long estTotal = Math.max(1, msgChars + toolChars);
        int schemaShare = (int) Math.round(100.0 * toolChars / estTotal);
        int historyShare = 100 - schemaShare;

        log.info("[ContextMeter] {} call#{} 输入={} 输出={} | 消息={}条/{}字符 工具={}个/schema={}字符 "
                        + "| 估算构成：schema≈{}%  系统+历史+工具结果≈{}%  | 进程累计输入={} 进程峰值输入={}",
                label, seq, in, out, msgCount, msgChars, toolCount, toolChars,
                schemaShare, historyShare, inputSum.get(), maxInput.get());
    }

    private static long sumMsgChars(List<Msg> messages) {
        if (messages == null) {
            return 0;
        }
        long sum = 0;
        for (Msg m : messages) {
            if (m == null) {
                continue;
            }
            try {
                // ★ 必须遍历全部 content block，不能只用 getTextContent()：
                //   工具结果是独立的内容块，getTextContent() 取不到 —— 实测表现为
                //   「消息字符几乎不涨、但输入 token 涨了 4 倍」，正是工具结果在偷偷累积。
                var blocks = m.getContent();
                if (blocks == null) {
                    continue;
                }
                for (var b : blocks) {
                    if (b != null) {
                        sum += String.valueOf(b).length();
                    }
                }
            } catch (Exception ignored) {
                // 计量失败不能影响业务
            }
        }
        return sum;
    }

    private static long sumToolChars(List<ToolSchema> tools) {
        if (tools == null) {
            return 0;
        }
        long sum = 0;
        for (ToolSchema t : tools) {
            if (t == null) {
                continue;
            }
            try {
                sum += len(t.getName()) + len(t.getDescription()) + len(String.valueOf(t.getParameters()));
            } catch (Exception ignored) {
                // 同上
            }
        }
        return sum;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /** 本次进程累计的输入 token（跨请求累计，仅用于排查） */
    public long totalInputTokens() {
        return inputSum.get();
    }

    public long totalOutputTokens() {
        return outputSum.get();
    }

    public long callCount() {
        return callSeq.get();
    }

    public long maxInputTokens() {
        return maxInput.get();
    }
}

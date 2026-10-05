package meter;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * author: Imooc
 * description: 历史压缩 —— 上下文超预算时压缩【较早的工具结果】（EXP-002 机制 C）
 * date: 2026
 *
 * <p><b>与机制 B 的分工：</b>B（工具结果外置）在**工具返回的那一刻**就把大结果换掉，治的是"新进来的巨物"；
 * C 治的是"已经躺在历史里的旧内容" —— 包括 B 还没覆盖到的情形（B 关闭时、或结果虽未超阈值但条数很多时）。</p>
 *
 * <p><b>安全性设计（很重要）：</b></p>
 * <ul>
 *   <li><b>只改"发给模型的内容"，不动 Agent 自己的 Memory</b> —— 装饰的是 {@code Model.stream(...)} 的入参，
 *       Agent 的记忆与状态完全不受影响，因此压缩是"只读改写"，不会让后续轮次丢失上下文；</li>
 *   <li><b>只压缩 {@code TOOL} 角色的消息</b>，不动 SYSTEM / USER / ASSISTANT ——
 *       系统提示、用户需求、模型自己的推理与计划必须原样保留；</li>
 *   <li>重建消息时**保留 id / name / role / metadata / timestamp**，只替换内容，避免破坏工具调用与结果的配对关系；</li>
 *   <li>保留最近 {@code keepRecent} 条消息**完全不动** —— 当前正在推理所依赖的信息不能被削。</li>
 * </ul>
 *
 * <p><b>不做 LLM 摘要</b>：那会额外花钱、且不可预测。这里做的是确定性的"掐头去尾留中段标记"，
 * 成本为零、行为可解释、可开关。若将来需要更好的压缩质量，再考虑引入模型摘要并单独对照。</p>
 */
public class CompactingModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(CompactingModel.class);

    /** metadata 里小于等于该长度的字段视为"配对/标识信息"，压缩时保留 */
    private static final int METADATA_KEEP_CHARS = 200;

    private final Model delegate;
    private final int thresholdChars;
    private final int keepRecent;
    private final int headChars;
    private final int tailChars;

    private long compactedRounds = 0;
    private long savedChars = 0;

    public CompactingModel(Model delegate, int thresholdChars, int keepRecent, int headChars, int tailChars) {
        this.delegate = delegate;
        this.thresholdChars = Math.max(2000, thresholdChars);
        this.keepRecent = Math.max(0, keepRecent);
        this.headChars = Math.max(0, headChars);
        this.tailChars = Math.max(0, tailChars);
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

        if (messages == null || messages.isEmpty()) {
            return delegate.stream(messages, tools, options);
        }

        long total = totalChars(messages);
        if (total <= thresholdChars) {
            return delegate.stream(messages, tools, options);
        }

        List<Msg> compacted = compact(messages, total);
        return delegate.stream(compacted, tools, options);
    }

    private List<Msg> compact(List<Msg> messages, long totalChars) {

        int n = messages.size();
        int untouchedFrom = Math.max(0, n - keepRecent);   // 这个下标之后的消息一律不动

        List<Msg> out = new ArrayList<>(n);
        int compactedCount = 0;
        long saved = 0;

        for (int i = 0; i < n; i++) {
            Msg m = messages.get(i);
            if (m == null || i >= untouchedFrom || m.getRole() != MsgRole.TOOL) {
                out.add(m);
                continue;
            }

            // ★ 关键：真正占 token 的负载**不一定在文本内容里**。实测发现工具结果可能是
            //   Msg 的结构化数据（metadata），只统计/压缩文本会出现"压了等于没压"的空转。
            //   所以这里取「文本内容 + 最长的 metadata 值」作为负载来掐头去尾。
            String payload = payloadOf(m);
            if (payload.length() <= headChars + tailChars + 200) {
                out.add(m);   // 本来就不长，压了没意义还伤信息
                continue;
            }

            String head = payload.substring(0, Math.min(headChars, payload.length()));
            String tail = payload.length() > tailChars
                    ? payload.substring(payload.length() - tailChars)
                    : "";
            String replaced = """
                    %s
                    ... [历史工具结果已压缩：原文 %d 字符，省略中间 %d 字符；如需该数据请重新调用对应工具] ...
                    %s"""
                    .formatted(head, payload.length(), payload.length() - head.length() - tail.length(), tail);

            Msg rebuilt;
            try {
                rebuilt = Msg.builder()
                        .id(m.getId())
                        .name(m.getName())
                        .role(m.getRole())
                        // metadata 只保留**小字段**：大字段（结构化负载）是压缩的目标，
                        // 而小字段往往是工具调用 id 之类的配对信息，丢了可能导致请求被拒。
                        .metadata(smallEntriesOnly(m.getMetadata()))
                        .timestamp(m.getTimestamp())
                        .content(List.of(TextBlock.builder().text(replaced).build()))
                        .build();
            } catch (Exception e) {
                out.add(m);   // 重建失败就原样保留，压缩绝不能影响业务
                continue;
            }
            out.add(rebuilt);
            compactedCount++;
            saved += msgChars(m) - rebuiltCharEstimate(replaced);
        }

        if (compactedCount > 0) {
            compactedRounds++;
            savedChars += Math.max(0, saved);
            log.info("[CompactingModel] 历史压缩：消息总字符 {} 超阈值 {}，压缩 {} 条较早的工具结果，"
                            + "省下约 {} 字符（保留最近 {} 条不动）",
                    totalChars, thresholdChars, compactedCount, Math.max(0, saved), keepRecent);
        }
        return out;
    }

    /** 负载 = 文本内容；若文本很短而 metadata 很长，则以最长的 metadata 值为准 */
    private static String payloadOf(Msg m) {
        String text = m.getTextContent() == null ? "" : m.getTextContent();
        String largestMeta = "";
        if (m.getMetadata() != null) {
            for (Object v : m.getMetadata().values()) {
                String s = v == null ? "" : String.valueOf(v);
                if (s.length() > largestMeta.length()) {
                    largestMeta = s;
                }
            }
        }
        return largestMeta.length() > text.length() ? largestMeta : text;
    }

    /** 只保留小字段（默认 ≤200 字符）：大字段是压缩对象，小字段多为配对/标识信息 */
    private static java.util.Map<String, Object> smallEntriesOnly(java.util.Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        java.util.Map<String, Object> kept = new java.util.LinkedHashMap<>();
        for (var e : meta.entrySet()) {
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if (v.length() <= METADATA_KEEP_CHARS) {
                kept.put(e.getKey(), e.getValue());
            }
        }
        return kept.isEmpty() ? null : kept;
    }

    private static long rebuiltCharEstimate(String text) {
        return text == null ? 0 : text.length();
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    private static long totalChars(List<Msg> messages) {
        long sum = 0;
        for (Msg m : messages) {
            sum += msgChars(m);
        }
        return sum;
    }

    /** 一条消息的字符数：文本内容 + metadata（两者都要算，见 compact 里的说明） */
    private static long msgChars(Msg m) {
        if (m == null) {
            return 0;
        }
        long sum = 0;
        if (m.getContent() != null) {
            for (var b : m.getContent()) {
                if (b != null) {
                    sum += String.valueOf(b).length();
                }
            }
        }
        if (m.getMetadata() != null && !m.getMetadata().isEmpty()) {
            sum += String.valueOf(m.getMetadata()).length();
        }
        return sum;
    }

    public long compactedRounds() {
        return compactedRounds;
    }

    public long savedChars() {
        return savedChars;
    }
}

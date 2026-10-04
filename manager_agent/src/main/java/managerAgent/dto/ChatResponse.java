package managerAgent.dto;

import managerAgent.trace.RunTrace;

import java.util.List;

/**
 * author: Imooc
 * description: 同步接口响应体
 * date: 2026
 *
 * <p>除了最终答案与轨迹，还额外返回 {@code toolBatches} 与 {@code parallelismSummary}：</p>
 * <ul>
 *   <li>{@code toolBatches} —— 每一批「同一轮里一起派发的工具」的墙钟耗时、各工具耗时之和与加速比</li>
 *   <li>{@code parallelismSummary} —— 一句话结论（PARALLEL / SEQUENTIAL）</li>
 * </ul>
 * <p>因为 AgentScope 的 Hook 是批量通知的，光看步骤时间戳区分不出串行/并行，
 * 必须靠「批内各工具耗时之和 ÷ 批次墙钟耗时」这个比值来判断。</p>
 */
public record ChatResponse(String runId,
                           String status,
                           String answer,
                           String error,
                           long durationMillis,
                           List<RunTrace.Step> steps,
                           List<RunTrace.ToolBatch> toolBatches,
                           String parallelismSummary) {

    public static ChatResponse of(RunTrace trace) {
        List<RunTrace.ToolBatch> batches = trace.getParallelizableBatches();
        return new ChatResponse(
                trace.getRunId(),
                trace.getStatus(),
                trace.getAnswer(),
                trace.getError(),
                trace.getDurationMillis(),
                trace.getSteps(),
                batches,
                summarize(batches));
    }

    /** 一句话给出本轮的并行结论，方便直接看 */
    private static String summarize(List<RunTrace.ToolBatch> batches) {
        if (batches.isEmpty()) {
            return "本轮没有出现「同一轮里多个工具」的情况，无法体现并行"
                    + "（模型每轮只调了一个工具，或确实存在先后依赖）。";
        }
        StringBuilder sb = new StringBuilder();
        for (RunTrace.ToolBatch b : batches) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append("并发 ").append(b.getSize()).append(" 个工具，墙钟 ")
                    .append(b.getWallMillis()).append("ms，各工具耗时之和 ")
                    .append(b.getSumMillis()).append("ms → ").append(b.getVerdict());
        }
        return sb.toString();
    }
}

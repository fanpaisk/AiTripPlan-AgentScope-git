package managerAgent.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * author: Imooc
 * description: 一次 Agent 运行的完整轨迹（可观测性）
 * date: 2026
 *
 * <p>面试/简历里比"我用了什么框架"更有说服力的是"我能把 Agent 的推理过程讲清楚"。
 * RunTrace 记录一次请求中：模型思考了哪些步骤、调用了哪些工具、每个工具耗时多久、最终回答是什么。</p>
 */
public class RunTrace {

    /** 步骤类型 */
    public enum StepType {
        /** 模型思考 */
        REASONING,
        /** 准备调用工具 */
        TOOL_CALL,
        /** 工具返回 */
        TOOL_RESULT,
        /** 系统提示（例如计划生成） */
        HINT
    }

    /**
     * 一条轨迹步骤
     */
    public static class Step {

        private final StepType type;
        private final String name;
        private final String detail;
        private final long atMillis;
        private final long durationMillis;

        public Step(StepType type, String name, String detail, long atMillis, long durationMillis) {
            this.type = type;
            this.name = name;
            this.detail = detail;
            this.atMillis = atMillis;
            this.durationMillis = durationMillis;
        }

        public StepType getType() {
            return type;
        }

        public String getName() {
            return name;
        }

        public String getDetail() {
            return detail;
        }

        public long getAtMillis() {
            return atMillis;
        }

        public long getDurationMillis() {
            return durationMillis;
        }
    }

    private final String runId;
    private final String prompt;
    private final long startedAt;

    private volatile long finishedAt;
    private volatile String status = "RUNNING";
    private volatile String answer = "";
    private volatile String error;

    /**
     * 本次运行生效的模式："multi"（主管 + 远程子 Agent）或 "single"（单 Agent 自己做完）。
     * 由 EXP-001 对照实验引入：跑批时要能直接从轨迹里读出击的是哪条臂，
     * 而不是靠事后猜时间窗口。
     */
    private volatile String mode = "multi";

    /** 本次运行是否启用了「工具结果外置」（EXP-002 机制 B）：跑批时据此判定这一轮属于哪个对照档 */
    private volatile boolean contextBudget = true;

    private final List<Step> steps = Collections.synchronizedList(new ArrayList<>());

    /**
     * 同一轮里一批工具调用的记录。
     *
     * <p>AgentScope 的 Hook 是<b>批量通知</b>的（PreActing 对一批工具一次性触发，
     * PostActing 等整批执行完再一次性触发），所以光看事件时间戳<b>无法区分</b>串行还是并行。
     * 这里额外记录「批次墙钟耗时」和「批次内各工具自己的耗时」，
     * 用 {@code sum / wall} 的比值即可算出真实并行度。</p>
     */
    public static class ToolBatch {

        private final int size;
        /** 整批从开始到结束的墙钟耗时 */
        private final long wallMillis;
        /** 批次内各工具自身耗时（由工具实现上报，如 RemoteAgentTool） */
        private final List<Long> toolMillis;

        public ToolBatch(int size, long wallMillis, List<Long> toolMillis) {
            this.size = size;
            this.wallMillis = wallMillis;
            this.toolMillis = toolMillis == null ? List.of() : List.copyOf(toolMillis);
        }

        public int getSize() {
            return size;
        }

        public long getWallMillis() {
            return wallMillis;
        }

        public List<Long> getToolMillis() {
            return toolMillis;
        }

        /** 批次内各工具耗时之和 */
        public long getSumMillis() {
            return toolMillis.stream().mapToLong(Long::longValue).sum();
        }

        /** 上报了耗时的工具个数（本地瞬时工具不会上报，不参与并行度判断） */
        public int getReportedCount() {
            return toolMillis.size();
        }

        /** 并行加速比：串行总耗时 / 实际墙钟耗时 */
        public double getSpeedup() {
            long sum = getSumMillis();
            if (sum <= 0 || wallMillis <= 0) {
                return 0d;
            }
            return Math.round(((double) sum / wallMillis) * 100) / 100d;
        }

        /**
         * 结论：根据加速比判断这批工具实际是串行还是并行执行的。
         *
         * <p>注意比较基准是<b>上报了耗时的工具个数</b>而不是批次总大小 ——
         * 批次里往往还夹着 {@code update_subtask_state} 这类毫秒级本地工具，
         * 它们不影响并行度判断。</p>
         */
        public String getVerdict() {
            int reported = getReportedCount();
            if (reported <= 1) {
                return "SINGLE（有效工具只有 1 个，无需并行）";
            }
            double speedup = getSpeedup();
            if (speedup >= reported * 0.7) {
                return "PARALLEL（完全并行，" + reported + " 个并发，加速比 " + speedup + "x）";
            }
            if (speedup >= 1.5) {
                return "PARTIAL（部分并行，" + reported + " 个并发，加速比 " + speedup + "x）";
            }
            return "SEQUENTIAL（串行，" + reported + " 个工具，加速比 " + speedup + "x）";
        }
    }

    /** 已完成的工具批次 */
    private final List<ToolBatch> toolBatches = Collections.synchronizedList(new ArrayList<>());

    /** 当前批次里各工具自己的耗时（由工具实现写入） */
    private final List<Long> currentBatchToolMillis = Collections.synchronizedList(new ArrayList<>());

    public RunTrace(String runId, String prompt) {
        this.runId = runId;
        this.prompt = prompt;
        this.startedAt = System.currentTimeMillis();
    }

    /**
     * 工具实现可上报自己的真实耗时（Hook 拿不到单个工具的耗时）。
     * @param toolName 工具名（仅用于日志）
     * @param millis   该工具自身耗时
     */
    public void recordToolDuration(String toolName, long millis) {
        currentBatchToolMillis.add(millis);
    }

    /** 记录一个工具批次（由 TraceHook 在整批完成时调用） */
    public void recordToolBatch(int size, long wallMillis) {
        List<Long> millis;
        synchronized (currentBatchToolMillis) {
            millis = new ArrayList<>(currentBatchToolMillis);
            currentBatchToolMillis.clear();
        }
        toolBatches.add(new ToolBatch(size, wallMillis, millis));
    }

    public List<ToolBatch> getToolBatches() {
        synchronized (toolBatches) {
            return List.copyOf(toolBatches);
        }
    }

    /** 只看能体现并行效果的批次：至少 2 个工具有真实耗时上报 */
    public List<ToolBatch> getParallelizableBatches() {
        return getToolBatches().stream()
                .filter(b -> b.getReportedCount() > 1)
                .toList();
    }

    public void addStep(StepType type, String name, String detail) {
        steps.add(new Step(type, name, truncate(detail), System.currentTimeMillis(), 0L));
    }

    public void addStep(StepType type, String name, String detail, long durationMillis) {
        steps.add(new Step(type, name, truncate(detail), System.currentTimeMillis(), durationMillis));
    }

    public void finish(String answer) {
        this.answer = answer == null ? "" : answer;
        this.status = "SUCCESS";
        this.finishedAt = System.currentTimeMillis();
    }

    public void fail(Throwable error) {
        this.error = error == null ? "unknown" : String.valueOf(error.getMessage());
        this.status = "FAILED";
        this.finishedAt = System.currentTimeMillis();
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 800 ? trimmed : trimmed.substring(0, 800) + " ...[truncated]";
    }

    public String getRunId() {
        return runId;
    }

    public String getPrompt() {
        return prompt;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long getFinishedAt() {
        return finishedAt;
    }

    public long getDurationMillis() {
        return (finishedAt == 0 ? System.currentTimeMillis() : finishedAt) - startedAt;
    }

    public String getStatus() {
        return status;
    }

    public String getAnswer() {
        return answer;
    }

    public String getError() {
        return error;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isContextBudget() {
        return contextBudget;
    }

    public void setContextBudget(boolean contextBudget) {
        this.contextBudget = contextBudget;
    }

    public List<Step> getSteps() {
        synchronized (steps) {
            return List.copyOf(steps);
        }
    }
}

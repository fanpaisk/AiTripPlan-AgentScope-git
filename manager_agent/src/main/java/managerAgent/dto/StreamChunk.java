package managerAgent.dto;

import managerAgent.trace.RunTrace;

/**
 * author: Imooc
 * description: SSE 流式分片
 * date: 2026
 */
public record StreamChunk(String runId,
                          String type,
                          String text,
                          boolean last,
                          Long durationMillis) {

    public static StreamChunk of(String runId, String type, String text, boolean last) {
        return new StreamChunk(runId, type, text, last, null);
    }

    public static StreamChunk done(RunTrace trace) {
        return new StreamChunk(trace.getRunId(), "DONE", trace.getAnswer(),
                true, trace.getDurationMillis());
    }

    public static StreamChunk failed(String runId, String message) {
        return new StreamChunk(runId, "ERROR", message, true, null);
    }
}

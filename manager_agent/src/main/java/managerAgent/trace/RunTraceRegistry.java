package managerAgent.trace;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * author: Imooc
 * description: 运行轨迹内存仓库（有界）
 * date: 2026
 *
 * <p>只保留最近 N 次运行，避免长跑内存泄漏。
 * 后续想做成持久化，只用把这里换成 JPA / Redis 实现即可。</p>
 */
@Component
public class RunTraceRegistry {

    private static final int MAX_ENTRIES = 200;

    private final Map<String, RunTrace> traces = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RunTrace> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public void put(RunTrace trace) {
        synchronized (traces) {
            traces.put(trace.getRunId(), trace);
        }
    }

    public RunTrace get(String runId) {
        synchronized (traces) {
            return traces.get(runId);
        }
    }

    public List<RunTrace> latest(int limit) {
        synchronized (traces) {
            List<RunTrace> all = new ArrayList<>(traces.values());
            int from = Math.max(0, all.size() - limit);
            List<RunTrace> tail = new ArrayList<>(all.subList(from, all.size()));
            java.util.Collections.reverse(tail);
            return tail;
        }
    }
}

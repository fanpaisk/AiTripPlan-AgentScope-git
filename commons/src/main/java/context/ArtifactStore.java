package context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * author: Imooc
 * description: 单次运行的「大结果外置仓库」
 * date: 2026
 *
 * <p>把超长的工具返回（地图算路、POI 列表等）原样存进来，上下文里只留摘要 + 一个 id，
 * Agent 需要细节时再用 {@code read_artifact} / {@code read_artifact_range} 按需取回。</p>
 *
 * <p><b>为什么按「运行」而不是全局：</b>跨运行共享会让上一次请求的地图数据污染下一次的决策；
 * 而且单次运行结束后引用就失效，天然避免无限增长。所以 store 由
 * {@code ManagerAgent} 在每次组装 Agent 时新建一个。</p>
 */
public class ArtifactStore {

    /** 一条外置记录 */
    public record Artifact(String id, String toolName, String content, long createdAt) {

        public int length() {
            return content == null ? 0 : content.length();
        }
    }

    private final Map<String, Artifact> artifacts = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    /** 累计外置字符数与次数：用来量化"这条机制到底省了多少" */
    private final AtomicLong offloadedChars = new AtomicLong();
    private final AtomicLong offloadedCount = new AtomicLong();
    private final AtomicLong retrievedChars = new AtomicLong();
    private final AtomicLong retrievedCount = new AtomicLong();

    /**
     * 存入一段超长结果。
     * @param toolName 产生它的工具名（便于 Agent 判断该不该取回）
     * @param content 完整内容
     * @return 该记录的 id
     */
    public String store(String toolName, String content) {
        if (content == null) {
            content = "";
        }
        String id = "art-" + seq.incrementAndGet();
        artifacts.put(id, new Artifact(id, toolName, content, System.currentTimeMillis()));
        offloadedChars.addAndGet(content.length());
        offloadedCount.incrementAndGet();
        return id;
    }

    public Artifact get(String id) {
        return id == null ? null : artifacts.get(id);
    }

    public int size() {
        return artifacts.size();
    }

    /** 列出本次运行已外置的全部结果（id + 工具名 + 长度），供 Agent 决定取哪个 */
    public List<Artifact> list() {
        return new ArrayList<>(artifacts.values());
    }

    /**
     * 按区间取回内容（Agent 只想看其中一段时用，避免又把整段拉回上下文）。
     * @return 取回的内容；id 不存在返回 null
     */
    public String slice(String id, int offset, int length) {
        Artifact a = get(id);
        if (a == null) {
            return null;
        }
        String c = a.content() == null ? "" : a.content();
        int from = Math.max(0, Math.min(offset, c.length()));
        int to = (int) Math.min((long) c.length(), (long) from + Math.max(0, length));
        String out = c.substring(from, to);
        retrievedChars.addAndGet(out.length());
        retrievedCount.incrementAndGet();
        return out;
    }

    /** 计量：本次运行总共外置了多少字符 / 取回了多少字符（报告里要用） */
    public Map<String, Long> stats() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("offloadedCount", offloadedCount.get());
        m.put("offloadedChars", offloadedChars.get());
        m.put("retrievedCount", retrievedCount.get());
        m.put("retrievedChars", retrievedChars.get());
        m.put("netSavedChars", offloadedChars.get() - retrievedChars.get());
        return m;
    }
}

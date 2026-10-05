package context;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * author: Imooc
 * description: 上下文预算配置（app.agentscope.context.*）
 * date: 2026
 *
 * <p><b>为什么需要这个：</b>EXP-001 实测发现单 Agent 每次 LLM 调用平均携带约 19 万输入 token，
 * 其中九成以上是**只进不出的工具结果**（31 次地图/POI 返回全量留在历史里、每次调用重发一遍），
 * 导致端到端 token 是多 Agent 的 2.2 倍、方差高达 21 倍。
 * 模型上下文窗口有 1M，装得下 —— 但**每一次调用都要为同一份历史重复付费**。
 * 所以缺的不是窗口，是「预算」。</p>
 */
@ConfigurationProperties(prefix = "app.agentscope.context")
public class ContextProperties {

    /** 总开关：关掉后行为与引入本功能前完全一致（便于做消融对照） */
    private boolean enabled = true;

    /** 工具结果超过该字符数就外置；小于等于则原样返回（小结果外置不划算） */
    private int offloadThresholdChars = 4000;

    /** 外置后留在上下文里的预览字符数 */
    private int previewChars = 800;

    /** read_artifact 单次最多取回多少字符（取太多等于没外置） */
    private int retrieveChars = 4000;

    /** 是否打印每次 LLM 调用的上下文计量（结果外置的效果要能被看见，否则无法验证） */
    private boolean meterEnabled = true;

    /**
     * 机制 A：工具按需挂载。
     *
     * <p>计量显示每次调用的固定开销（25~28 个工具的 JSON Schema）约占首轮输入的 78%。
     * 开启后用一次极小的模型调用判断该需求是否需要真实地图数据，不需要就不挂那 10 个地图工具。</p>
     */
    private boolean toolGating = true;

    /** 机制 C：历史压缩（超预算时压缩较早的工具结果） */
    private boolean historyCompaction = true;

    /** 触发历史压缩的消息总字符阈值（约 3 万字符 ≈ 1 万 token 量级） */
    private int compactThresholdChars = 30000;

    /** 压缩时保留最近多少条消息完全不动（当前推理所依赖的信息不能被削） */
    private int compactKeepRecent = 6;

    /** 压缩后每条旧工具结果保留的头部字符数 */
    private int compactHeadChars = 500;

    /** 压缩后每条旧工具结果保留的尾部字符数 */
    private int compactTailChars = 500;

    public boolean isToolGating() {
        return enabled && toolGating;
    }

    public void setToolGating(boolean toolGating) {
        this.toolGating = toolGating;
    }

    public boolean isHistoryCompaction() {
        return enabled && historyCompaction;
    }

    public void setHistoryCompaction(boolean historyCompaction) {
        this.historyCompaction = historyCompaction;
    }

    public int getCompactThresholdChars() {
        return compactThresholdChars;
    }

    public void setCompactThresholdChars(int compactThresholdChars) {
        this.compactThresholdChars = compactThresholdChars;
    }

    public int getCompactKeepRecent() {
        return compactKeepRecent;
    }

    public void setCompactKeepRecent(int compactKeepRecent) {
        this.compactKeepRecent = compactKeepRecent;
    }

    public int getCompactHeadChars() {
        return compactHeadChars;
    }

    public void setCompactHeadChars(int compactHeadChars) {
        this.compactHeadChars = compactHeadChars;
    }

    public int getCompactTailChars() {
        return compactTailChars;
    }

    public void setCompactTailChars(int compactTailChars) {
        this.compactTailChars = compactTailChars;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isOffloadEnabled() {
        return enabled;
    }

    public boolean isMeterEnabled() {
        return enabled && meterEnabled;
    }

    public int getOffloadThresholdChars() {
        return offloadThresholdChars;
    }

    public void setOffloadThresholdChars(int offloadThresholdChars) {
        this.offloadThresholdChars = offloadThresholdChars;
    }

    public int getPreviewChars() {
        return previewChars;
    }

    public void setPreviewChars(int previewChars) {
        this.previewChars = previewChars;
    }

    public int getRetrieveChars() {
        return retrieveChars;
    }

    public void setRetrieveChars(int retrieveChars) {
        this.retrieveChars = retrieveChars;
    }

    public boolean isMeterEnabledRaw() {
        return meterEnabled;
    }

    public void setMeterEnabled(boolean meterEnabled) {
        this.meterEnabled = meterEnabled;
    }
}

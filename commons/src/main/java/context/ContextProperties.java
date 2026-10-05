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

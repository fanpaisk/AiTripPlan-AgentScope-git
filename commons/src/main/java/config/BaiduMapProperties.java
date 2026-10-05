package config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * author: Imooc
 * description: 百度地图 MCP 配置（app.baidu-map.*）
 * date: 2026
 *
 * <p><b>为什么这个类在 commons 而不是 routeMaking_agent：</b>
 * 单 Agent 对照实验（EXP-001）需要让主管 Agent 也直接持有地图工具，
 * 与「多 Agent 派发」那一臂能力对齐。地图客户端是纯基础设施、与业务无关，
 * 放在公共模块后两个模块复用同一份实现，避免拷贝一份出来各自腐化。</p>
 */
@ConfigurationProperties(prefix = "app.baidu-map")
public class BaiduMapProperties {

    /**
     * 百度地图 MCP Server 的 SSE 地址。
     * 获取方式：https://modelscope.cn/mcp 搜「百度地图」→ 填入自己的百度地图 AK → 复制 SSE 链接。
     * 形如：https://mcp.api-inference.modelscope.net/xxxxxxxx/sse
     */
    private String mcpSseUrl;

    /** 单次 MCP 请求超时 */
    private Duration requestTimeout = Duration.ofSeconds(120);

    /** MCP 初始化超时 */
    private Duration initializationTimeout = Duration.ofSeconds(60);

    public String getMcpSseUrl() {
        return mcpSseUrl;
    }

    public void setMcpSseUrl(String mcpSseUrl) {
        this.mcpSseUrl = mcpSseUrl;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getInitializationTimeout() {
        return initializationTimeout;
    }

    public void setInitializationTimeout(Duration initializationTimeout) {
        this.initializationTimeout = initializationTimeout;
    }
}

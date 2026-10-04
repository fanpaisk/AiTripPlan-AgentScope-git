package routeMakingAgent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * author: Imooc
 * description: 百度地图 MCP 配置（app.baidu-map.*）
 * date: 2026
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

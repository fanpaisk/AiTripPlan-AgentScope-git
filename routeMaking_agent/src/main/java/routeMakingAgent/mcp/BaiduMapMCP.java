package routeMakingAgent.mcp;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import routeMakingAgent.config.BaiduMapProperties;

/**
 * author: Imooc
 * description: 百度地图 MCP Server 客户端（SSE 方式接入）
 * date: 2026
 *
 * <p>与课程原版的差异：SSE 地址从 {@code app.baidu-map.mcp-sse-url} 读取，
 * 并且<b>地址缺失 / 连接失败时只告警不抛异常</b>——
 * 这样即使百度地图 MCP 挂了，路线制定 Agent 依然能启动并注册到 Nacos，
 * 主管 Agent 调用它时会拿到"地图工具不可用"的明确提示，而不是整个服务起不来。</p>
 */
@Slf4j
@Component
public class BaiduMapMCP {

    private static final String CLIENT_NAME = "BaiduMap-mcp";

    private final BaiduMapProperties properties;

    /** MCP 客户端（懒加载，全局单例，多个 Agent 实例共享同一份工具定义） */
    private volatile McpClientWrapper baiduMapMCP;

    private volatile boolean initFailed;

    public BaiduMapMCP(BaiduMapProperties properties) {
        this.properties = properties;
    }

    /**
     * author: Imooc
     * description: 创建（或复用）百度地图 MCP 客户端
     * @return io.agentscope.core.tool.mcp.McpClientWrapper，创建失败返回 null
     */
    public McpClientWrapper getBaiduMapMCP() {

        McpClientWrapper existing = baiduMapMCP;
        if (existing != null) {
            return existing;
        }

        if (initFailed) {
            return null;
        }

        String sseUrl = properties.getMcpSseUrl();
        if (!StringUtils.hasText(sseUrl)) {
            initFailed = true;
            log.error("""

                    ============================================================
                    没有配置百度地图 MCP 地址，路线制定 Agent 将没有地图工具。
                    配置方式：
                      1) 打开 https://modelscope.cn/mcp ，搜索「百度地图」
                      2) 填入你在 https://lbs.baidu.com/apiconsole/center 申请的 API Key
                      3) 复制生成的 **SSE 地址**（注意是地址，不是 API Key / 令牌）
                      4) 写入 commons/src/main/resources/.env
                         BAIDU_MAP_MCP_SSE_URL=https://mcp.api-inference.modelscope.net/xxxx/sse
                    ============================================================
                    """);
            return null;
        }

        // 格式校验：最常见的坑是把「令牌 / 路径片段」当成地址填进来。
        // 那样 MCP 客户端会连不上，而现象只是"Agent 没有地图工具"，非常难定位。
        String trimmedUrl = sseUrl.trim();
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            initFailed = true;
            log.error("""

                    ============================================================
                    BAIDU_MAP_MCP_SSE_URL 格式不对：必须以 http:// 或 https:// 开头。
                      当前值前若干字符：{}
                      正确形如：https://mcp.api-inference.modelscope.net/xxxxxxxxxxxx/sse
                    请回到 https://modelscope.cn/mcp 的「百度地图」页面，
                    复制完整的 **SSE 地址**（不是 API Key、不是令牌字符串）。
                    ============================================================
                    """, trimmedUrl.length() > 12 ? trimmedUrl.substring(0, 12) + "..." : trimmedUrl);
            return null;
        }

        synchronized (this) {
            if (baiduMapMCP != null) {
                return baiduMapMCP;
            }
            try {
                log.info("[BaiduMapMCP] 正在连接百度地图 MCP Server(SSE)：{}", trimmedUrl);
                baiduMapMCP = McpClientBuilder.create(CLIENT_NAME)
                        //和 MCP Server 以 SSE 方式进行通信
                        .sseTransport(trimmedUrl)
                        //请求超时
                        .timeout(properties.getRequestTimeout())
                        .initializationTimeout(properties.getInitializationTimeout())
                        //异步请求
                        .buildAsync()
                        .block();
                log.info("[BaiduMapMCP] 客户端创建成功");
            } catch (Exception e) {
                initFailed = true;
                log.error("[BaiduMapMCP] 创建 MCP 客户端失败：{}", e.getMessage());
                return null;
            }
            return baiduMapMCP;
        }
    }

    /**
     * author: Imooc
     * description: 初始化百度地图 MCP 客户端并打印工具列表
     * @return io.agentscope.core.tool.mcp.McpClientWrapper，失败返回 null
     */
    public McpClientWrapper initBaiduMapMCP() {

        McpClientWrapper client = getBaiduMapMCP();
        if (client == null) {
            return null;
        }

        try {
            if (!client.isInitialized()) {
                client.initialize().block();
            }
            if (!client.isInitialized()) {
                log.warn("[BaiduMapMCP] 客户端初始化未完成");
                return null;
            }

            log.info("============= 百度地图 MCP 客户端初始化成功 =============");
            client.listTools().block().forEach(tool -> log.info("百度地图 MCP 工具：{}", tool.name()));
            log.info("=======================================================");
            return client;

        } catch (Exception e) {
            initFailed = true;
            log.error("[BaiduMapMCP] 初始化失败：{}", e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void close() {
        McpClientWrapper client = baiduMapMCP;
        if (client != null) {
            try {
                client.close();
                log.info("[BaiduMapMCP] 客户端已关闭");
            } catch (Exception e) {
                log.warn("[BaiduMapMCP] 关闭客户端异常：{}", e.getMessage());
            }
        }
    }
}

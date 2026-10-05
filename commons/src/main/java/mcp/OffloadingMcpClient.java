package mcp;

import context.ArtifactStore;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * author: Imooc
 * description: MCP 客户端装饰器 —— 把超长的工具返回「外置」，上下文里只留摘要 + id
 * date: 2026
 *
 * <p><b>为什么用装饰器而不是包一层工具：</b>AgentScope 的 {@code McpClientWrapper}
 * 是抽象类，工具注册与调用都经过它的 {@code listTools} / {@code callTool}，
 * 因此只要代理这个客户端，就能在不碰框架、也不逐个包装工具的前提下拦截**所有** MCP 返回。</p>
 *
 * <p><b>它解决的具体问题（EXP-001 实测）：</b>单 Agent 平均每次 LLM 调用携带约 19 万输入 token，
 * 九成以上是累积的工具结果 —— 地图算路、POI 列表这些一次几万字符的返回**只进不出**，
 * 之后每一次调用都要重发一遍。外置后上下文中只剩摘要，Agent 需要细节时用
 * {@code read_artifact} 按需取回。</p>
 *
 * <p><b>不做的事：</b>不动非文本内容（图片等），不动错误结果，不动小于阈值的返回 ——
 * 小结果外置只会增加一次工具往返，不划算。</p>
 */
public class OffloadingMcpClient extends McpClientWrapper {

    private static final Logger log = LoggerFactory.getLogger(OffloadingMcpClient.class);

    private final McpClientWrapper delegate;
    private final ArtifactStore store;
    private final int thresholdChars;
    private final int previewChars;

    private final AtomicLong offloadedCount = new AtomicLong();
    private final AtomicLong passedThroughChars = new AtomicLong();

    public OffloadingMcpClient(McpClientWrapper delegate,
                               ArtifactStore store,
                               int thresholdChars,
                               int previewChars) {
        super(delegate.getName());
        this.delegate = delegate;
        this.store = store;
        this.thresholdChars = Math.max(200, thresholdChars);
        this.previewChars = Math.max(100, previewChars);
    }

    @Override
    public boolean isInitialized() {
        return delegate.isInitialized();
    }

    @Override
    public Mono<Void> initialize() {
        return delegate.initialize();
    }

    @Override
    public Mono<List<Tool>> listTools() {
        return delegate.listTools();
    }

    @Override
    public Tool getCachedTool(String toolName) {
        return delegate.getCachedTool(toolName);
    }

    @Override
    public void close() {
        // 只关代理，不替被代理方关连接：被代理的客户端由 BaiduMapMCP 单例统一持有与关闭
        log.debug("[OffloadingMcpClient] 代理关闭（被代理的 {} 由持有方负责关闭）", delegate.getName());
    }

    @Override
    public Mono<CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return delegate.callTool(toolName, arguments).map(result -> offload(toolName, result));
    }

    private CallToolResult offload(String toolName, CallToolResult result) {

        if (result == null || result.content() == null || result.content().isEmpty()) {
            return result;
        }

        // 收集文本内容（非文本内容原样保留）
        StringBuilder sb = new StringBuilder();
        boolean hasText = false;
        for (Content c : result.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                sb.append(tc.text());
                hasText = true;
            }
        }
        if (!hasText) {
            return result;
        }

        String full = sb.toString();
        if (full.length() <= thresholdChars) {
            passedThroughChars.addAndGet(full.length());
            return result;
        }

        String id = store.store(toolName, full);
        String preview = full.substring(0, Math.min(previewChars, full.length()));
        String summary = """
                [结果已外置] 工具 %s 返回了 %d 字符，为控制上下文只保留前 %d 字符。
                完整内容已存为 %s：需要细节请调用 read_artifact("%s")（或 read_artifact_range 取指定片段），
                若不确定要什么，先调用 list_artifacts 看有哪些。
                ---------- 预览 ----------
                %s"""
                .formatted(toolName, full.length(), preview.length(), id, id, preview);

        List<Content> replaced = new ArrayList<>();
        replaced.add(new TextContent(summary));
        for (Content c : result.content()) {
            if (!(c instanceof TextContent)) {
                replaced.add(c);
            }
        }

        long n = offloadedCount.incrementAndGet();
        log.info("[OffloadingMcpClient] 第 {} 次外置：工具={} 原长={} 字符 → 摘要={} 字符，存为 {}",
                n, toolName, full.length(), summary.length(), id);

        return new CallToolResult(replaced, result.isError());
    }

    /** 计量：外置次数 / 被放行的字符数（用于报告与排查） */
    public long offloadedCount() {
        return offloadedCount.get();
    }

    public long passedThroughChars() {
        return passedThroughChars.get();
    }
}

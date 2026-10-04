package utils;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * author: Imooc
 * description: Agent Tool 工具类
 * date: 2026
 *
 * <p>课程原版 {@code Toolkit} 是 final 字段但每次调用都往同一个实例里塞，
 * 且 MCP 为 null 时会直接 NPE。这里补上空值/初始化校验。</p>
 *
 * <p><b>关于并行：</b>{@code new Toolkit()} 走 {@code ToolkitConfig.defaultConfig()}，
 * 其中 {@code parallel = false} —— 同一轮里的多个工具调用是<b>串行</b>执行的
 * （框架用 {@code Flux.concat}）。要并行必须显式构造：
 * {@code new Toolkit(ToolkitConfig.builder().parallel(true).build())}，
 * 框架就会改用 {@code Flux.mergeSequential} 同时订阅所有工具。</p>
 */
public class ToolUtils {

    private static final Logger log = LoggerFactory.getLogger(ToolUtils.class);

    private final Toolkit toolkit;

    public ToolUtils() {
        this(true);
    }

    /**
     * @param parallel 同一轮里的多个工具调用是否并行执行
     */
    public ToolUtils(boolean parallel) {
        //创建工具包（并行开关必须在构造时传入，注册工具之后再改是无效的）
        this.toolkit = createToolkit(parallel);
    }

    /**
     * author: Imooc
     * description: 创建一个工具包，显式指定并行开关
     * @param parallel: true=同一轮多个工具并行（Flux.mergeSequential）；false=串行（Flux.concat）
     * @return io.agentscope.core.tool.Toolkit
     */
    public static Toolkit createToolkit(boolean parallel) {
        log.info("[ToolUtils] 创建 Toolkit: parallel={}（同一轮多个工具{}执行）",
                parallel, parallel ? "并行" : "串行");
        return new Toolkit(ToolkitConfig.builder()
                .parallel(parallel)
                .build());
    }

    /**
     * author: Imooc
     * description: 获取工具包（注册普通 Java 对象，自动扫描 @Tool 注解方法）
     * @param tool: 工具对象
     * @return io.agentscope.core.tool.Toolkit
     */
    public Toolkit getToolkit(Object tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool 不能为 null");
        }
        //把工具添加到工具包，能自动扫描@Tool所注释的方法，作为Agent的工具
        toolkit.registerTool(tool);
        return toolkit;
    }

    /**
     * author: Imooc
     * description: 获取工具包（把 MCP 服务端的所有工具注册进来）
     * @param mcp: MCP 客户端
     * @return io.agentscope.core.tool.Toolkit
     */
    public Toolkit getToolkit(McpClientWrapper mcp) {
        registerMcpClient(mcp);
        return toolkit;
    }

    /**
     * author: Imooc
     * description: 注册 MCP 客户端，未初始化时先初始化；失败只告警不阻断启动
     */
    public Toolkit registerMcpClient(McpClientWrapper mcp) {
        if (mcp == null) {
            log.warn("[ToolUtils] MCP 客户端为 null，跳过注册（不影响其它工具）");
            return toolkit;
        }
        try {
            if (!mcp.isInitialized()) {
                mcp.initialize().block();
            }
            if (!mcp.isInitialized()) {
                log.warn("[ToolUtils] MCP 客户端 {} 初始化未完成，跳过注册", mcp.getName());
                return toolkit;
            }
            //把MCP服务端的所有工具添加到工具包
            toolkit.registerMcpClient(mcp).block();
            log.info("[ToolUtils] MCP 客户端 {} 注册完成，当前工具数：{}",
                    mcp.getName(), toolkit.getToolNames().size());
        } catch (Exception e) {
            log.warn("[ToolUtils] MCP 客户端 {} 注册失败：{}", mcp.getName(), e.getMessage());
        }
        return toolkit;
    }

    /**
     * author: Imooc
     * description: 直接拿到底层工具包
     */
    public Toolkit toolkit() {
        return toolkit;
    }
}

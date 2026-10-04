package config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * author: Imooc
 * description: AiTripPlan 公共配置（对应 application.yml 中的 app.agentscope.*）
 * date: 2026
 *
 * <p>所有敏感信息都通过占位符引用环境变量 / .env 文件，代码里不再出现硬编码的 Key。</p>
 */
@ConfigurationProperties(prefix = "app.agentscope")
public class AgentScopeProperties {

    /**
     * 语言模型配置（<b>厂商中立</b>）。
     *
     * <p>只要厂商提供 OpenAI 兼容接口，改 {@code base-url} + {@code model} + {@code api-key}
     * 三行就能切换：百炼兼容模式、DeepSeek 官方、Kimi、智谱、SiliconFlow，
     * 甚至本地 vLLM / Ollama 都适用，不需要改代码。</p>
     */
    private final Llm llm = new Llm();

    /** Nacos 注册中心 / 发现中心配置 */
    private final Nacos nacos = new Nacos();

    /** 观测（OpenTelemetry → Langfuse）配置 */
    private final Tracing tracing = new Tracing();

    public Tracing getTracing() {
        return tracing;
    }

    /** ReActAgent 最大推理轮数，防止死循环烧 token */
    private int maxIters = 20;

    /**
     * 同一轮推理里，多个工具调用是否<b>并行</b>执行。
     *
     * <p>模型在一轮里可以同时发出多个工具调用（比如同时派发给 RouteMakingAgent 和
     * TripPlannerAgent）。AgentScope 的 {@code ToolkitConfig.parallel} <b>默认是 false</b>，
     * 即用 {@code Flux.concat} 串行执行：总耗时 = 各工具耗时之和。</p>
     *
     * <p>打开后走 {@code Flux.mergeSequential}：同时订阅所有工具，
     * 总耗时 = 最慢那个工具的耗时（结果顺序仍然保持）。</p>
     *
     * <p>⚠️ 前提是这些工具之间<b>没有数据依赖</b>。像"派发给路线 Agent"和"派发给景点 Agent"
     * 这种互相独立的子任务才适合并行；有先后依赖的不能开。</p>
     */
    private boolean toolParallel = true;

    public boolean isToolParallel() {
        return toolParallel;
    }

    public void setToolParallel(boolean toolParallel) {
        this.toolParallel = toolParallel;
    }

    /** 单次远程 Agent(A2A) 调用超时时间（主管 Agent 主动放弃的时间） */
    private Duration remoteCallTimeout = Duration.ofMinutes(10);

    /**
     * 工具执行超时（框架层面的兜底）。
     *
     * <p>必须<b>大于</b> {@link #remoteCallTimeout}，这样超时时先触发我们自己的超时逻辑，
     * 拿到的是可读的「哪个子 Agent 超时了」，而不是框架那句
     * {@code Tool execution failed: Tool execution timeout after PT5M}。</p>
     *
     * <p><b>子 Agent 也必须配这一项</b>：否则它内部调用子 Agent / MCP 工具时会用框架默认的
     * 5 分钟超时，长任务会被从中间掐断（本项目实测踩过这个坑）。</p>
     */
    private Duration toolExecutionTimeout = Duration.ofMinutes(12);

    public Duration getToolExecutionTimeout() {
        return toolExecutionTimeout;
    }

    public void setToolExecutionTimeout(Duration toolExecutionTimeout) {
        this.toolExecutionTimeout = toolExecutionTimeout;
    }

    /**
     * 一次完整请求的总超时（从收到 Prompt 到产出最终回答）。
     *
     * <p>要覆盖「多个子 Agent 并行 + 主管 Agent 自身多轮推理」的总时长，
     * 建议明显大于 {@link #remoteCallTimeout}。</p>
     */
    private Duration runTimeout = Duration.ofMinutes(45);

    public Duration getRunTimeout() {
        return runTimeout;
    }

    public void setRunTimeout(Duration runTimeout) {
        this.runTimeout = runTimeout;
    }

    public Llm getLlm() {
        return llm;
    }

    /**
     * 调用大模型使用的协议
     */
    public enum Provider {
        /** 百炼原生协议：按模型名区分 text-generation / multimodal-generation 两个端点（仅百炼适用） */
        DASHSCOPE,
        /** OpenAI 兼容模式：单一端点，所有模型（含多模态）共用 —— 换厂商用这个 */
        OPENAI_COMPATIBLE
    }

    public Nacos getNacos() {
        return nacos;
    }

    public int getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(int maxIters) {
        this.maxIters = maxIters;
    }

    public Duration getRemoteCallTimeout() {
        return remoteCallTimeout;
    }

    public void setRemoteCallTimeout(Duration remoteCallTimeout) {
        this.remoteCallTimeout = remoteCallTimeout;
    }

    /**
     * 语言模型适配配置（前缀 {@code app.agentscope.llm}）。
     *
     * <p>环境变量：{@code LLM_API_KEY} / {@code LLM_BASE_URL} / {@code LLM_MODEL} / {@code LLM_PROVIDER}</p>
     */
    public static class Llm {

        /** 厂商 API Key（环境变量 LLM_API_KEY，兼容旧名 ALIBABA_DASHCOPE_KEY） */
        private String apiKey;

        /**
         * 协议模式：{@code openai-compatible}（推荐，换厂商都用它）或 {@code dashscope}（百炼原生协议）。
         *
         * <p><b>为什么默认用 openai-compatible：</b>
         * 百炼原生协议下，AgentScope 需要根据「模型名」猜应该走 text-generation 还是
         * multimodal-generation 端点，而它的判断规则是硬编码的（只认 qvq* 前缀和 -vl 后缀）。
         * 百炼后来新增的多模态模型（如 qwen3.8-flash、qwen3.8-max、qwen3.7-plus）名字里
         * 两个特征都没有，就会被送进纯文本端点，直接报 {@code url error, please check url！}。
         * 兼容模式只有一个端点、所有模型共用，从根本上绕开了这个问题。</p>
         */
        private Provider provider = Provider.OPENAI_COMPATIBLE;

        /** 模型名，如 qwen3-max / deepseek-v4-flash */
        private String model = "qwen3-max";

        /**
         * 服务地址（环境变量 LLM_BASE_URL）。
         *
         * <p>示例：</p>
         * <ul>
         *   <li>百炼兼容模式：{@code https://dashscope.aliyuncs.com/compatible-mode/v1}</li>
         *   <li>DeepSeek 官方：{@code https://api.deepseek.com}</li>
         * </ul>
         * 留空则按 provider 使用默认地址。
         */
        private String baseUrl;

        /** 是否开启流式（AgentScope 内部流式解析，建议保持 true） */
        private boolean stream = true;

        /**
         * 单次模型调用的最大输出 token 数（环境变量 LLM_MAX_TOKENS）。
         *
         * <p><b>必须显式设置。</b>不设置时会用厂商的默认值，实测在 OpenAI 兼容路径下
         * 会导致长回答被<b>中途截断</b>（答案断在半句话上，且 {@code finish_reason} 不会报错），
         * 排查起来非常费时。</p>
         */
        private Integer maxTokens = 8192;

        /** 采样温度，留空用厂商默认 */
        private Double temperature;

        /** 是否开启模型侧联网搜索（会额外计费，默认关闭；仅百炼原生协议支持） */
        private boolean enableSearch = false;

        /** 是否开启思考模式（qwen3 系列；仅百炼原生协议支持） */
        private Boolean enableThinking;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Provider getProvider() {
            return provider;
        }

        public void setProvider(Provider provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public boolean isStream() {
            return stream;
        }

        public void setStream(boolean stream) {
            this.stream = stream;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public boolean isEnableSearch() {
            return enableSearch;
        }

        public void setEnableSearch(boolean enableSearch) {
            this.enableSearch = enableSearch;
        }

        public Boolean getEnableThinking() {
            return enableThinking;
        }

        public void setEnableThinking(Boolean enableThinking) {
            this.enableThinking = enableThinking;
        }
    }

    /**
     * 观测配置：把 AgentScope 内部的 LLM 调用 / 工具调用 / Agent 调用
     * 以 OpenTelemetry span 的形式上报到 Langfuse（前缀 {@code app.agentscope.tracing}）。
     *
     * <p>环境变量：{@code LANGFUSE_PUBLIC_KEY} / {@code LANGFUSE_SECRET_KEY}
     * / {@code LANGFUSE_OTLP_ENDPOINT} / {@code LANGFUSE_TRACING_ENABLED}</p>
     *
     * <p><b>为什么默认 enabled=true 却不会报错：</b>没配 Key 时只打一条警告并跳过上报，
     * 不影响服务启动与业务 —— 这样"填完 Key 重启就生效"，不需要再去改开关。</p>
     */
    public static class Tracing {

        /** 是否启用上报（环境变量 LANGFUSE_TRACING_ENABLED，默认 true） */
        private boolean enabled = true;

        /**
         * OTLP/HTTP 的 <b>完整 traces 端点</b>（不是 base 地址）。
         *
         * <p>AgentScope 用的是 {@code OtlpHttpSpanExporter}，它的 {@code setEndpoint()}
         * 收的就是完整 URL，不会再自动拼 {@code /v1/traces}，所以必须写全。</p>
         */
        private String endpoint = "https://cloud.langfuse.com/api/public/otel/v1/traces";

        /** Langfuse 项目的 Public Key（环境变量 LANGFUSE_PUBLIC_KEY） */
        private String publicKey;

        /** Langfuse 项目的 Secret Key（环境变量 LANGFUSE_SECRET_KEY） */
        private String secretKey;

        /** 服务名，留空则取 {@code spring.application.name}；用于在 Langfuse 里区分三个服务 */
        private String serviceName;

        /** 退出时等待剩余 span 上报的最长时间，超时就放弃（不能拖住服务停机） */
        private Duration flushTimeout = Duration.ofSeconds(15);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public Duration getFlushTimeout() {
            return flushTimeout;
        }

        public void setFlushTimeout(Duration flushTimeout) {
            this.flushTimeout = flushTimeout;
        }
    }

    public static class Nacos {
        /** Nacos 地址，环境变量 NACOS_SERVER_ADDR，默认本机 standalone */
        private String serverAddr = "127.0.0.1:8848";

        /** 命名空间，留空为 public */
        private String namespace;

        private String username;

        private String password;

        public String getServerAddr() {
            return serverAddr;
        }

        public void setServerAddr(String serverAddr) {
            this.serverAddr = serverAddr;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}

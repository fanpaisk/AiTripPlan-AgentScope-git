package config;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * author: Imooc
 * description: commons 模块的自动装配入口
 * date: 2026
 *
 * <p>commons 是一个被依赖的 jar，包名是 {@code config} / {@code utils}，
 * 不在三个启动类所在的基础包（managerAgent / routeMakingAgent / tripPlannerAgent）下面，
 * 因此不能靠 @ComponentScan 扫描到。这里用 Spring Boot 官方推荐的
 * {@code AutoConfiguration} + {@code META-INF/spring/...AutoConfiguration.imports}
 * 方式对外提供公共 Bean。</p>
 *
 * <p><b>多厂商可切换：</b>默认走 OpenAI 兼容协议（{@code provider=openai-compatible}），
 * 换厂商只需要改 {@code app.agentscope.llm.base-url} / {@code .model} / {@code .api-key} 三个值，
 * 代码零改动。百炼、DeepSeek、Kimi、智谱、SiliconFlow、本地 vLLM/Ollama 都适用。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentScopeProperties.class)
public class CommonsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CommonsAutoConfiguration.class);

    /** 百炼 OpenAI 兼容模式的默认地址（base-url 留空且 provider=openai-compatible 时使用） */
    public static final String DEFAULT_OPENAI_COMPATIBLE_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /**
     * author: Imooc
     * description: 构造语言模型，全工程共用一个 Model 实例
     * @param properties: app.agentscope.* 配置
     * @return io.agentscope.core.model.Model
     */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    public Model llmChatModel(AgentScopeProperties properties) {

        AgentScopeProperties.Llm cfg = properties.getLlm();

        if (!StringUtils.hasText(cfg.getApiKey())) {
            throw new IllegalStateException("""

                    ============================================================
                    没有读到语言模型 API Key，项目无法启动。
                    请任选一种方式配置：
                      1) 编辑 commons/src/main/resources/.env
                         LLM_API_KEY=sk-xxxxxxxx
                      2) 设置环境变量 LLM_API_KEY
                      3) 启动参数 --app.agentscope.llm.api-key=sk-xxxxxxxx
                    百炼 Key 申请：https://bailian.console.aliyun.com/
                    DeepSeek Key：https://platform.deepseek.com/
                    ============================================================
                    """);
        }

        // 模型名必须 trim：.env 里多一个空格，大模型会直接返回 url error / Model not exist，
        // 而且报错信息完全不提空格的事，非常难查。
        String modelName = cfg.getModel() == null ? "" : cfg.getModel().trim();
        if (!StringUtils.hasText(modelName)) {
            throw new IllegalStateException("app.agentscope.llm.model（或 .env 的 LLM_MODEL）不能为空");
        }

        return switch (cfg.getProvider()) {
            case OPENAI_COMPATIBLE -> buildOpenAiCompatibleModel(cfg, modelName);
            case DASHSCOPE -> buildNativeDashScopeModel(cfg, modelName);
        };
    }

    /**
     * OpenAI 兼容模式（推荐，换厂商都走这条）。
     *
     * <p>只认一个端点，文本模型和多模态模型共用，
     * 不需要框架去猜模型属于哪一类，因此不会出现「模型名与端点不匹配 → url error」的问题。</p>
     */
    private Model buildOpenAiCompatibleModel(AgentScopeProperties.Llm cfg, String modelName) {

        String baseUrl = StringUtils.hasText(cfg.getBaseUrl())
                ? cfg.getBaseUrl().trim()
                : DEFAULT_OPENAI_COMPATIBLE_BASE_URL;

        Model model = OpenAIChatModel.builder()
                .apiKey(cfg.getApiKey().trim())
                .modelName(modelName)
                .stream(cfg.isStream())
                .baseUrl(baseUrl)
                .generateOptions(buildGenerateOptions(cfg))
                .build();

        log.info("""

                [commons] 语言模型已装配（OpenAI 兼容协议）
                  model    = {}
                  baseUrl  = {}
                  stream   = {}
                  maxTokens= {}
                """, modelName, baseUrl, cfg.isStream(), cfg.getMaxTokens());

        return model;
    }

    /**
     * 构造模型调用参数。
     *
     * <p>★ {@code maxTokens} 必须显式设置：不设置时用厂商默认值，实测会让长回答中途截断
     * （答案断在半句话上，也不报错），是最难查的一类问题之一。</p>
     */
    private static GenerateOptions buildGenerateOptions(AgentScopeProperties.Llm cfg) {
        GenerateOptions.Builder b = GenerateOptions.builder();
        if (cfg.getMaxTokens() != null && cfg.getMaxTokens() > 0) {
            b.maxTokens(cfg.getMaxTokens());
        }
        if (cfg.getTemperature() != null) {
            b.temperature(cfg.getTemperature());
        }
        return b.build();
    }

    /**
     * 百炼原生协议（仅百炼适用，一般不推荐）。
     *
     * <p>注意：AgentScope 1.0.8 的 {@code DashScopeHttpClient.selectEndpoint()} 只认
     * {@code qvq*} 前缀和名字里带 {@code -vl} 的模型为多模态，其余一律走纯文本端点。
     * 百炼新增的多模态模型（qwen3.8-flash / qwen3.8-max / qwen3.7-plus 等）名字里没有这两个特征，
     * 走原生协议时会直接报 {@code url error, please check url！}。</p>
     */
    private Model buildNativeDashScopeModel(AgentScopeProperties.Llm cfg, String modelName) {

        DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                .apiKey(cfg.getApiKey().trim())
                .modelName(modelName)
                .stream(cfg.isStream())
                .enableSearch(cfg.isEnableSearch());

        if (cfg.getEnableThinking() != null) {
            builder.enableThinking(cfg.getEnableThinking());
        }
        if (StringUtils.hasText(cfg.getBaseUrl())) {
            builder.baseUrl(cfg.getBaseUrl().trim());
        }

        boolean suspectedMultimodal = !modelName.startsWith("qvq") && !modelName.contains("-vl");
        log.info("[commons] 语言模型已装配（百炼原生协议）: model={}, stream={}, search={}",
                modelName, cfg.isStream(), cfg.isEnableSearch());

        if (suspectedMultimodal) {
            log.info("""
                    [commons] 提示：原生协议下会按模型名判断端点。模型 {} 会被送到纯文本端点；
                    如果它其实是多模态模型（qwen3.8-* / qwen3.7-plus 等），会报 url error。
                    解决办法：把 app.agentscope.llm.provider 设为 openai-compatible。""", modelName);
        }

        return builder.build();
    }
}

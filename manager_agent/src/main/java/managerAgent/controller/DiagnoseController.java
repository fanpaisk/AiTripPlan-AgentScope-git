package managerAgent.controller;

import config.AgentScopeProperties;
import config.CommonsAutoConfiguration;
import io.agentscope.core.model.Model;
import managerAgent.agents.AgentCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utils.AgentUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * author: Imooc
 * description: 配置自检接口 —— 把「实际生效的配置」直接暴露出来，用于快速定位启动/调用问题
 * date: 2026
 *
 * <pre>
 * GET /api/diagnose        只看配置（不发请求，零成本）
 * GET /api/diagnose?deep=true   额外真实调用一次大模型，验证 Key / 模型名 / 端点是否匹配
 * </pre>
 *
 * <p>为什么需要它：参数走的是「.env → 环境变量 → application.yml 默认值」多层覆盖，
 * 出问题时很难确认最终到底用了哪个值。把生效值打出来，比反复猜快得多。</p>
 */
@RestController
@RequestMapping("/api")
public class DiagnoseController {

    private static final Logger log = LoggerFactory.getLogger(DiagnoseController.class);

    /** 与 AgentScope 内部 DashScopeHttpClient.selectEndpoint 的判断规则保持一致 */
    private static final String ENDPOINT_TEXT = "/api/v1/services/aigc/text-generation/generation";
    private static final String ENDPOINT_MULTIMODAL = "/api/v1/services/aigc/multimodal-generation/generation";

    private final Model model;
    private final AgentScopeProperties properties;
    private final AgentCatalogService agentCatalogService;

    public DiagnoseController(Model model,
                              AgentScopeProperties properties,
                              AgentCatalogService agentCatalogService) {
        this.model = model;
        this.properties = properties;
        this.agentCatalogService = agentCatalogService;
    }

    @GetMapping("/diagnose")
    public Map<String, Object> diagnose(@RequestParam(defaultValue = "false") boolean deep) {

        AgentScopeProperties.Llm llm = properties.getLlm();

        // ★ 关键：model.getModelName() 才是【真正传给大模型】的名字，
        //   它可能被环境变量覆盖，和 .env / application.yml 里写的并不一致。
        String effectiveModel = model.getModelName();
        boolean compatible = llm.getProvider() == AgentScopeProperties.Provider.OPENAI_COMPATIBLE;
        String resolvedBaseUrl = StringUtils.hasText(llm.getBaseUrl())
                ? llm.getBaseUrl().trim()
                : CommonsAutoConfiguration.DEFAULT_OPENAI_COMPATIBLE_BASE_URL;
        // 端点提示：兼容模式下所有厂商共用一个路径；只有百炼原生协议才有 text/multimodal 之分
        String effectiveEndpoint = compatible
                ? resolvedBaseUrl + "/chat/completions"
                : expectedEndpoint(effectiveModel);

        Map<String, Object> llmView = new LinkedHashMap<>();
        llmView.put("provider", llm.getProvider());
        llmView.put("effectiveModelName", effectiveModel);
        llmView.put("effectiveEndpoint", effectiveEndpoint);
        llmView.put("baseUrl", StringUtils.hasText(llm.getBaseUrl())
                ? llm.getBaseUrl()
                : "(未设置，使用默认 " + CommonsAutoConfiguration.DEFAULT_OPENAI_COMPATIBLE_BASE_URL + ")");
        llmView.put("apiKeyMasked", mask(llm.getApiKey()));
        llmView.put("stream", llm.isStream());
        llmView.put("enableSearch", llm.isEnableSearch());
        llmView.put("enableThinking", llm.getEnableThinking());

        Map<String, Object> nacosView = new LinkedHashMap<>();
        nacosView.put("serverAddr", properties.getNacos().getServerAddr());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("llm", llmView);
        result.put("nacos", nacosView);
        result.put("maxIters", properties.getMaxIters());
        result.put("remoteCallTimeout", String.valueOf(properties.getRemoteCallTimeout()));
        result.put("toolExecutionTimeout", String.valueOf(properties.getToolExecutionTimeout()));
        result.put("runTimeout", String.valueOf(properties.getRunTimeout()));
        result.put("remoteAgents", agentCatalogService.list());

        // 把常见坑直接做成结论，省得再对着文档推
        List<String> hints = new ArrayList<>();
        if (!StringUtils.hasText(llm.getApiKey())) {
            hints.add("没有读到 API Key：检查 commons/src/main/resources/.env 的 LLM_API_KEY");
        }
        if (effectiveModel != null && !effectiveModel.equals(effectiveModel.trim())) {
            hints.add("模型名两端有空白字符，会导致大模型返回 url error / Model not exist");
        }
        if (llm.getProvider() == AgentScopeProperties.Provider.DASHSCOPE) {
            hints.add("当前用百炼原生协议，换厂商/换多模态模型会报 url error —— "
                    + "把 provider 改成 openai-compatible 即可");
        }
        long registered = agentCatalogService.list().stream()
                .filter(AgentCatalogService.RemoteAgentView::registered).count();
        if (registered == 0) {
            hints.add("没有任何子 Agent 注册到 Nacos：确认子 Agent 已启动、Nacos 版本 >= 3.0");
        }
        result.put("hints", hints);

        if (deep) {
            result.put("deepCheck", deepCheck());
        }
        return result;
    }

    /**
     * 真实调用一次大模型（最短输入），把原始错误原样带回来。
     */
    private Map<String, Object> deepCheck() {
        Map<String, Object> check = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            model.stream(List.of(AgentUtils.userMsg("hi")), List.of(), null)
                    .blockLast(Duration.ofSeconds(60));
            check.put("ok", true);
        } catch (Exception e) {
            check.put("ok", false);
            check.put("error", rootMessage(e));
            log.warn("[Diagnose] 大模型连通性自检失败：{}", rootMessage(e));
        }
        check.put("elapsedMillis", System.currentTimeMillis() - start);
        return check;
    }

    private static String expectedEndpoint(String modelName) {
        if (modelName == null) {
            return ENDPOINT_TEXT;
        }
        if (modelName.startsWith("qvq") || modelName.contains("-vl")) {
            return ENDPOINT_MULTIMODAL;
        }
        return ENDPOINT_TEXT;
    }

    private static String mask(String secret) {
        if (!StringUtils.hasText(secret)) {
            return "(空)";
        }
        String trimmed = secret.trim();
        if (trimmed.length() <= 10) {
            return "****";
        }
        return trimmed.substring(0, 6) + "****" + trimmed.substring(trimmed.length() - 4)
                + " (长度 " + trimmed.length() + ")";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}

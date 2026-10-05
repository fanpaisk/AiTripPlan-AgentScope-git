package config;

import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.tracing.telemetry.TelemetryTracer;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * author: Imooc
 * description: 把 AgentScope 的 tracing 接到 Langfuse（OTLP/HTTP）
 * date: 2026
 *
 * <p>AgentScope 1.0.8 自带 {@code TelemetryTracer}，它会在 LLM 调用 / 工具调用 /
 * Agent 调用三个位置打 OpenTelemetry span（GenAI 语义约定）。这里负责把它接上。</p>
 *
 * <p><b>为什么不直接用 {@code TelemetryTracer.builder().enabled(true).endpoint(...)}？</b>
 * 看了 {@code TelemetryTracer$Builder.build()} 的字节码，它有三条分支：</p>
 * <ol>
 *   <li>{@code enabled=false} → 返回一个内部持有 noop tracer 的实例（什么也不发）；</li>
 *   <li>{@code enabled=true 且传了 tracer} → <b>用外部传进来的 tracer</b>；</li>
 *   <li>{@code enabled=true 且没传 tracer} → 自己 new 一个
 *       {@code OtlpHttpSpanExporter + BatchSpanProcessor + SdkTracerProvider}，
 *       并且<b>不把 provider 暴露出来</b>。</li>
 * </ol>
 *
 * <p>走第 3 条会有两个真实问题，所以这里刻意走第 2 条、自己持有 provider：</p>
 * <ul>
 *   <li><b>退出时 span 会丢</b>：{@code BatchSpanProcessor} 默认攒够一批或等 5 秒才发。
 *       拿不到 provider 就没法在停机前 {@code forceFlush()}，
 *       而 {@code stop-all.ps1} 是直接把进程杀掉的 —— 最后几秒的 trace 全部丢失，
 *       表现为「跑完了但 Langfuse 里没有记录」，非常难查。</li>
 *   <li><b>三个服务分不开</b>：framework 自建的 provider 用默认 Resource，
 *       {@code service.name} 会是 {@code unknown_service:java}，
 *       主管 / 路线 / 行程三个服务的 trace 在 Langfuse 里混在一起无法区分。</li>
 * </ul>
 *
 * <p><b>隐私</b>：上报内容包含 prompt、工具参数与模型输出（Langfuse Cloud 在境外），
 * 用 {@code app.agentscope.tracing.enabled=false} 可随时关闭。</p>
 */
public class AgentScopeTracing implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeTracing.class);

    /**
     * 与框架自建 provider 时用的 instrumentation 名保持一致，方便在 Langfuse 里对照。
     * 见 {@code TelemetryTracer$Builder} 里那两个 ldc 常量。
     */
    private static final String INSTRUMENTATION_NAME = "agentscope-java";
    private static final String INSTRUMENTATION_VERSION = "1.0.8";

    private final AgentScopeProperties.Tracing cfg;
    private final String fallbackServiceName;

    /** 我们自己持有的 provider —— 只有拿着它才能在停机前把缓冲的 span 刷出去 */
    private SdkTracerProvider tracerProvider;

    public AgentScopeTracing(AgentScopeProperties.Tracing cfg, String fallbackServiceName) {
        this.cfg = cfg;
        this.fallbackServiceName = fallbackServiceName;
    }

    /**
     * author: Imooc
     * description: 装配并注册 tracer；配置不全时只告警不抛异常
     * @return true 表示上报已开启
     */
    public boolean start() {

        if (!cfg.isEnabled()) {
            log.info("[tracing] 已按配置关闭 trace 上报（app.agentscope.tracing.enabled=false）");
            return false;
        }

        if (!StringUtils.hasText(cfg.getPublicKey()) || !StringUtils.hasText(cfg.getSecretKey())) {
            log.warn("""

                    ============================================================
                    [tracing] 没有读到 Langfuse 的 Key，跳过 trace 上报（不影响业务）。
                      配置方式：在 commons/src/main/resources/.env 里填
                        LANGFUSE_PUBLIC_KEY=pk-lf-xxxxxxxx
                        LANGFUSE_SECRET_KEY=sk-lf-xxxxxxxx
                      Key 获取：Langfuse Cloud → 项目 → Settings → API Keys
                      填完必须重新 mvn package 并重启（.env 会被打进 jar）
                    ============================================================
                    """);
            return false;
        }

        String endpoint = cfg.getEndpoint() == null ? "" : cfg.getEndpoint().trim();
        if (!StringUtils.hasText(endpoint)) {
            log.warn("[tracing] endpoint 为空，跳过 trace 上报");
            return false;
        }

        String serviceName = StringUtils.hasText(cfg.getServiceName())
                ? cfg.getServiceName().trim()
                : (StringUtils.hasText(fallbackServiceName) ? fallbackServiceName : "aitripplan-unknown");

        try {
            // 1) 告诉 Langfuse 这条 trace 属于哪个服务：Langfuse 靠 service.name 分组
            Resource resource = Resource.getDefault().merge(Resource.create(
                    Attributes.builder()
                            .put("service.name", serviceName)
                            .put("service.version", "1.0.0")
                            .put("deployment.environment", "local")
                            .build()));

            // 2) OTLP/HTTP 导出器：endpoint 必须是【完整】的 traces 地址，
            //    OtlpHttpSpanExporter 不会自己补 /v1/traces。
            //    Langfuse 用 Basic 认证（用户名=Public Key，密码=Secret Key）。
            String auth = Base64.getEncoder().encodeToString(
                    (cfg.getPublicKey().trim() + ":" + cfg.getSecretKey().trim())
                            .getBytes(StandardCharsets.UTF_8));

            // Langfuse v4：直连 OTLP 上报必须带 x-langfuse-ingestion-version: 4，
            // 否则数据会按旧数据模型摄取，表现为「跑完了但 Tracing 列表最多要等 10 分钟才出现」，
            // 极易被误判成 Key 或端点有问题。见 https://langfuse.com/integrations/native/opentelemetry
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .addHeader("Authorization", "Basic " + auth)
                    .addHeader("x-langfuse-ingestion-version", "4")
                    .build();

            // 3) 自己建 provider，留着引用以便停机时 flush
            this.tracerProvider = SdkTracerProvider.builder()
                    .setResource(resource)
                    .setSampler(Sampler.alwaysOn())
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                    .build();

            Tracer tracer = tracerProvider.get(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION);

            // 4) enabled=true + 传入 tracer → 走 Builder 的第 2 条分支，用我们的 tracer
            TelemetryTracer telemetryTracer = TelemetryTracer.builder()
                    .enabled(true)
                    .tracer(tracer)
                    .build();

            TracerRegistry.register(telemetryTracer);
            TracerRegistry.enableTracingHook();

            log.info("""

                    [tracing] trace 上报已开启（AgentScope → Langfuse）
                      service  = {}
                      endpoint = {}
                      key      = {}（已脱敏）
                    """, serviceName, endpoint, mask(cfg.getPublicKey()));
            return true;

        } catch (Exception | LinkageError e) {
            // 依赖缺失（NoClassDefFoundError）/ 网络不通都不该拖垮业务
            log.error("[tracing] 初始化失败，已跳过 trace 上报：{}", e.toString());
            return false;
        }
    }

    /** 只露头尾，避免 Key 进日志 */
    private static String mask(String key) {
        if (key == null || key.length() < 8) {
            return "****";
        }
        return key.substring(0, 6) + "..." + key.substring(key.length() - 4);
    }

    /**
     * 停机前把缓冲的 span 刷出去。
     *
     * <p>顺序很重要：先 {@code forceFlush()}（带超时，不能拖住停机），再 {@code shutdown()}。
     * 少了这一步，最后几秒的 trace 会因为批量缓冲还没到发送时机而永久丢失。</p>
     */
    @Override
    public void destroy() {

        TracerRegistry.disableTracingHook();

        if (tracerProvider == null) {
            return;
        }

        Duration timeout = cfg.getFlushTimeout() == null ? Duration.ofSeconds(15) : cfg.getFlushTimeout();
        long timeoutMillis = timeout.toMillis();
        try {
            log.info("[tracing] 正在上报剩余 span（最多等 {}）...", timeout);
            // forceFlush() 返回的是 CompletableResultCode（不是 boolean），
            // join 只是"等它结束"，成功与否要另外看 isSuccess()。
            CompletableResultCode flushResult = tracerProvider.forceFlush();
            flushResult.join(timeoutMillis, TimeUnit.MILLISECONDS);
            log.info("[tracing] 剩余 span 上报{}", flushResult.isSuccess() ? "完成" : "未在超时内完成（已放弃）");
        } catch (Exception e) {
            log.warn("[tracing] 上报剩余 span 失败（忽略，不阻塞停机）：{}", e.toString());
        } finally {
            try {
                CompletableResultCode shutdownResult = tracerProvider.shutdown();
                shutdownResult.join(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("[tracing] 关闭 OTel provider 异常（忽略）：{}", e.toString());
            }
        }
    }
}

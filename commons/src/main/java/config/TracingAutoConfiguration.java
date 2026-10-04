package config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * author: Imooc
 * description: 观测链路的自动装配入口（AgentScope tracing → Langfuse）
 * date: 2026
 *
 * <p>只要 {@code app.agentscope.tracing.enabled} 不为 false 就会装配；
 * 没配 Key 时 {@link AgentScopeTracing#start()} 只打警告并跳过，业务照常。
 * 因此"填完 Key 重启就生效"，不需要再动开关。</p>
 *
 * <p>用 {@code @ConditionalOnProperty(matchIfMissing = true)} 而不是把开关写在三个模块的
 * yml 里硬编码：默认行为（没配就跳过）已经足够安全，少一个必须记得改的开关就少一个坑。</p>
 */
@AutoConfiguration(after = CommonsAutoConfiguration.class)
@EnableConfigurationProperties(AgentScopeProperties.class)
public class TracingAutoConfiguration {

    /**
     * author: Imooc
     * description: 装配并立刻启动 trace 上报
     * @param properties: app.agentscope.* 配置
     * @param applicationName: spring.application.name，作为 Langfuse 里的 service.name
     * @return config.AgentScopeTracing（实现 DisposableBean，停机时会 flush 剩余 span）
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "app.agentscope.tracing",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public AgentScopeTracing agentScopeTracing(
            AgentScopeProperties properties,
            @Value("${spring.application.name:aitripplan-unknown}") String applicationName) {

        AgentScopeTracing tracing = new AgentScopeTracing(properties.getTracing(), applicationName);
        tracing.start();
        return tracing;
    }
}

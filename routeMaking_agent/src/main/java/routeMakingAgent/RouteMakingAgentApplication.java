package routeMakingAgent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import utils.NacosUtil;

/**
 * author: Imooc
 * description: 路线制定Agent 启动类
 * date: 2026
 *
 * <p>启动后会自动：1) 挂载百度地图 MCP 工具；2) 把 AgentCard 注册到 Nacos（A2A 协议）。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RouteMakingAgentApplication {

    private static final Logger log = LoggerFactory.getLogger(RouteMakingAgentApplication.class);

    public static void main(String[] args) {
        // 必须在 Nacos 客户端类初始化前调用，避免它往 ${user.home}/logs/nacos 再写一套日志
        NacosUtil.disableNacosClientFileLogging();
        ConfigurableApplicationContext context = SpringApplication.run(RouteMakingAgentApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8082");
        String nacos = env.getProperty("agentscope.a2a.nacos.server-addr", "127.0.0.1:8848");
        log.info("""

                ================ RouteMakingAgent(路线制定) 已启动 ================
                  A2A 服务地址 : http://127.0.0.1:{}
                  AgentCard    : http://127.0.0.1:{}/.well-known/agent-card.json
                  Nacos 注册中心: {}
                ==================================================================
                """, port, port, nacos);
    }
}

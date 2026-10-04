package managerAgent;

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
 * description: 主管 Agent 启动类（用户提交 Prompt 的统一入口）
 * date: 2026
 *
 * <p>课程原版把 {@code @SpringBootApplication} 注释掉了，main 方法里直接 new 一个 Agent
 * 跑死数据，导致 AppController 永远不会被 Spring 托管。
 * 这里恢复成标准的 Spring Boot 应用。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ManagerAgentApplication {

    private static final Logger log = LoggerFactory.getLogger(ManagerAgentApplication.class);

    public static void main(String[] args) {
        // 必须在 Nacos 客户端类初始化前调用，避免它往 ${user.home}/logs/nacos 再写一套日志
        NacosUtil.disableNacosClientFileLogging();
        ConfigurableApplicationContext context = SpringApplication.run(ManagerAgentApplication.class, args);
        printBanner(context.getEnvironment());
    }

    private static void printBanner(Environment env) {
        String port = env.getProperty("server.port", "8081");
        String base = "http://127.0.0.1:" + port;
        log.info("""

                ==================== ManagerAgent(主管智能体) 已启动 ====================
                  同步调用   POST  {}/app
                  流式调用   POST  {}/app/stream   (SSE)
                  子 Agent   GET   {}/api/agents
                  运行轨迹   GET   {}/api/runs
                  健康检查   GET   {}/api/health
                =========================================================================
                """, base, base, base, base, base);
    }
}

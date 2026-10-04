package utils;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import org.springframework.util.StringUtils;

import java.util.Properties;

/**
 * author: Imooc
 * description: Nacos 客户端工具类（A2A 注册中心 / 发现中心）
 * date: 2026
 *
 * <p>课程原版把地址写死成 localhost:8848；这里改成从环境变量 / 参数读取，
 * 同时保留 {@link #getNacosClient()} 无参方法，方便在 main 方法或测试里直接用。</p>
 */
public final class NacosUtil {

    /** 默认地址：docker 单机模式 NACOS 的 RPC 端口 */
    public static final String DEFAULT_SERVER_ADDR = "127.0.0.1:8848";

    /**
     * Nacos 客户端默认会加载它自带的 logback 配置，在 {@code ${user.home}/logs/nacos/}
     * 下额外写一份日志文件（config.log / naming.log / ai.log ...），和本项目的日志体系重复。
     * <p>调用本方法关闭该默认配置，让 Nacos 客户端日志统一走项目的 logback.xml。</p>
     * <p><b>必须在 Nacos 客户端类初始化之前调用</b>（Nacos 在静态代码块里读这个属性），
     * 所以放在各个 Application 的 main 方法第一行。</p>
     */
    public static void disableNacosClientFileLogging() {
        System.setProperty("nacos.logging.default.config.enabled", "false");
    }

    private NacosUtil() {
    }

    /**
     * author: Imooc
     * description: 使用环境变量 NACOS_SERVER_ADDR（缺省 127.0.0.1:8848）创建 Nacos AiService
     * @return com.alibaba.nacos.api.ai.AiService
     */
    public static AiService getNacosClient() throws NacosException {
        String addr = System.getenv("NACOS_SERVER_ADDR");
        return getNacosClient(StringUtils.hasText(addr) ? addr : DEFAULT_SERVER_ADDR);
    }

    /**
     * author: Imooc
     * description: 按指定地址创建 Nacos AiService
     * @param serverAddr: 形如 127.0.0.1:8848
     * @return com.alibaba.nacos.api.ai.AiService
     */
    public static AiService getNacosClient(String serverAddr) throws NacosException {
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR,
                StringUtils.hasText(serverAddr) ? serverAddr : DEFAULT_SERVER_ADDR);
        return AiFactory.createAiService(properties);
    }

    /**
     * author: Imooc
     * description: 按完整参数创建 Nacos AiService（支持命名空间 / 鉴权）
     */
    public static AiService getNacosClient(String serverAddr,
                                           String namespace,
                                           String username,
                                           String password) throws NacosException {
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR,
                StringUtils.hasText(serverAddr) ? serverAddr : DEFAULT_SERVER_ADDR);
        if (StringUtils.hasText(namespace)) {
            properties.put(PropertyKeyConst.NAMESPACE, namespace);
        }
        if (StringUtils.hasText(username)) {
            properties.put(PropertyKeyConst.USERNAME, username);
        }
        if (StringUtils.hasText(password)) {
            properties.put(PropertyKeyConst.PASSWORD, password);
        }
        return AiFactory.createAiService(properties);
    }

    /**
     * author: Imooc
     * description: 创建通用 Nacos 客户端（需要 ConfigService / NamingService 时使用）
     */
    public static Object getConfigService(String serverAddr) throws NacosException {
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR,
                StringUtils.hasText(serverAddr) ? serverAddr : DEFAULT_SERVER_ADDR);
        return NacosFactory.createConfigService(properties);
    }
}

package managerAgent.agents;

import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.a2a.spec.AgentCard;
import managerAgent.config.ManagerAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * author: Imooc
 * description: Agent 目录服务：查询配置中声明的远程 Agent 在 Nacos 上的注册情况
 * date: 2026
 *
 * <p>提供 GET /api/agents，一眼就能看出"哪个子 Agent 没起来"，
 * 调试多 Agent 编排时非常省时间。</p>
 */
@Service
public class AgentCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AgentCatalogService.class);

    private final ManagerAgentProperties properties;
    private final ObjectProvider<AgentCardResolver> resolverProvider;

    public AgentCatalogService(ManagerAgentProperties properties,
                               ObjectProvider<AgentCardResolver> resolverProvider) {
        this.properties = properties;
        this.resolverProvider = resolverProvider;
    }

    /**
     * 远程 Agent 视图
     */
    public record RemoteAgentView(String name,
                                  String toolName,
                                  String description,
                                  boolean registered,
                                  String url,
                                  String version,
                                  String error) {
    }

    public List<RemoteAgentView> list() {
        List<RemoteAgentView> views = new ArrayList<>();
        AgentCardResolver resolver = resolverProvider.getIfAvailable();

        for (ManagerAgentProperties.RemoteAgent spec : properties.getRemoteAgents()) {
            if (spec.getName() == null || spec.getName().isBlank()) {
                continue;
            }
            if (resolver == null) {
                views.add(new RemoteAgentView(spec.getName(), spec.resolvedToolName(),
                        spec.getDescription(), false, null, null, "AgentCardResolver 不可用"));
                continue;
            }
            try {
                AgentCard card = resolver.getAgentCard(spec.getName());
                if (card == null) {
                    views.add(new RemoteAgentView(spec.getName(), spec.resolvedToolName(),
                            spec.getDescription(), false, null, null, "Nacos 中未找到该 Agent 卡片"));
                } else {
                    views.add(new RemoteAgentView(spec.getName(), spec.resolvedToolName(),
                            card.description() == null ? spec.getDescription() : card.description(),
                            true, card.url(), card.version(), null));
                }
            } catch (Exception e) {
                log.warn("[AgentCatalog] 查询远程 Agent {} 失败：{}", spec.getName(), e.getMessage());
                views.add(new RemoteAgentView(spec.getName(), spec.resolvedToolName(),
                        spec.getDescription(), false, null, null, e.getMessage()));
            }
        }
        return views;
    }
}

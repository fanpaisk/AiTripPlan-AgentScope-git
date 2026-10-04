package managerAgent.controller;

import managerAgent.agents.AgentCatalogService;
import managerAgent.trace.RunTrace;
import managerAgent.trace.RunTraceRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * author: Imooc
 * description: 运维 / 可观测接口
 * date: 2026
 *
 * <p>GET /api/agents        —— 远程子 Agent 在 Nacos 上的注册情况
 * GET /api/runs            —— 最近 N 次运行的轨迹列表
 * GET /api/runs/{runId}    —— 某次运行的完整轨迹（推理步骤 + 工具调用 + 最终回答）
 * GET /api/health          —— 健康检查</p>
 */
@RestController
@RequestMapping("/api")
public class OpsController {

    private final AgentCatalogService agentCatalogService;
    private final RunTraceRegistry runTraceRegistry;

    public OpsController(AgentCatalogService agentCatalogService,
                         RunTraceRegistry runTraceRegistry) {
        this.agentCatalogService = agentCatalogService;
        this.runTraceRegistry = runTraceRegistry;
    }

    @GetMapping("/agents")
    public List<AgentCatalogService.RemoteAgentView> agents() {
        return agentCatalogService.list();
    }

    @GetMapping("/runs")
    public List<RunTrace> runs(@RequestParam(defaultValue = "20") int limit) {
        return runTraceRegistry.latest(Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<RunTrace> run(@PathVariable String runId) {
        RunTrace trace = runTraceRegistry.get(runId);
        return trace == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(trace);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "manager-agent",
                "registeredAgents", agentCatalogService.list().stream()
                        .filter(AgentCatalogService.RemoteAgentView::registered)
                        .count()
        );
    }
}

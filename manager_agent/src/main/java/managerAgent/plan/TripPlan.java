package managerAgent.plan;

import io.agentscope.core.plan.PlanNotebook;
import managerAgent.config.ManagerAgentProperties;

/**
 * author: Imooc
 * description: 自定义 Agent 自主分解旅游规划任务
 * date: 2026
 *
 * <p>PlanNotebook 是主管 Agent "自主决策"的核心：
 * 复杂任务分解 → 生成执行步骤 → 状态跟踪 → 动态调整 → 任务完成。</p>
 */
public class TripPlan {

    private final ManagerAgentProperties properties;

    public TripPlan(ManagerAgentProperties properties) {
        this.properties = properties;
    }

    /**
     * author: Imooc
     * description: 自定义 PlanNotebook 实例
     * @return io.agentscope.core.plan.PlanNotebook
     */
    public PlanNotebook getPlan() {
        return PlanNotebook.builder()
                //计划步骤是否需要用户确认（HTTP 场景必须 false，否则会停下来等人输入）
                .needUserConfirm(properties.isNeedUserConfirm())
                //分解出来的子任务数量限制
                .maxSubtasks(properties.getMaxSubtasks())
                .build();
    }
}

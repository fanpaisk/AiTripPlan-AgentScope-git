package managerAgent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * author: Imooc
 * description: 主管 Agent 配置（app.manager.*）
 * date: 2026
 *
 * <p>核心设计：<b>远程子 Agent 列表由配置驱动</b>。
 * 想让主管 Agent 多调度一个能力，只要在 application.yml 里加一条 remote-agents 配置，
 * 并把对应 Agent 启动起来注册到 Nacos 即可，不需要改 Java 代码。</p>
 */
@ConfigurationProperties(prefix = "app.manager")
public class ManagerAgentProperties {

    /** 主管 Agent 名称（同时用于日志与可观测） */
    private String name = "ManagerAgent";

    /** 能力描述：会作为 AgentCard / 日志中的说明 */
    private String description = "主管Agent：把用户的旅行需求拆解为子任务，并调度远程专业Agent完成";

    /** 系统提示词，留空使用内置默认值 */
    private String sysPrompt;

    /** 计划步骤是否需要用户确认。HTTP 场景下必须为 false，否则 Agent 会停下来等人输入 */
    private boolean needUserConfirm = false;

    /**
     * 运行模式（EXP-001 对照实验的开关）。
     *
     * <ul>
     *   <li>{@code multi}（默认）：现状行为 —— 主管 Agent 用 PlanNotebook 拆任务，
     *       经 A2A 派发给远程子 Agent；</li>
     *   <li>{@code single}：单个 Agent 自己持有全部能力（地图 MCP + Skills + 计算工具 + PlanNotebook），
     *       <b>不做任何派发</b>。</li>
     * </ul>
     *
     * <p>两臂必须能靠请求级覆盖切换（见 {@code ChatRequest#mode}），
     * 不要靠重启服务来切 —— 否则 JIT 预热差异会污染延迟对比。</p>
     */
    private String mode = "multi";

    /** PlanNotebook 允许拆解出的最大子任务数 */
    private int maxSubtasks = 6;

    /** 最大推理轮数 */
    private int maxIters = 25;

    /**
     * 单 Agent 模式的最大推理轮数。
     *
     * <p>比 {@link #maxIters} 给得更宽：单 Agent 要独自完成两个子 Agent 的工作量。
     * <b>目的是让两条臂都不触顶</b> —— 一旦某一臂触顶，比的就是预算而不是架构了。
     * 实验时需记录各轮是否触顶。</p>
     */
    private int singleMaxIters = 40;

    /**
     * 单个子 Agent 返回内容的最大字符数。
     *
     * <p>子 Agent 的完整报告动辄上万字，全部塞进主管 Agent 的上下文会导致：
     * ① 下一轮模型调用请求体过大，容易超时 / 被连接重置；② token 成本爆炸。
     * 超长部分会截断并注明，主管 Agent 仍能拿到结论与关键数据。</p>
     */
    private int remoteResultMaxChars = 8000;

    public int getRemoteResultMaxChars() {
        return remoteResultMaxChars;
    }

    public void setRemoteResultMaxChars(int remoteResultMaxChars) {
        this.remoteResultMaxChars = remoteResultMaxChars;
    }

    /** Nacos 中已注册、允许被调度的远程 Agent 清单 */
    private List<RemoteAgent> remoteAgents = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSysPrompt() {
        return sysPrompt;
    }

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    public boolean isNeedUserConfirm() {
        return needUserConfirm;
    }

    public void setNeedUserConfirm(boolean needUserConfirm) {
        this.needUserConfirm = needUserConfirm;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    /** 配置默认是否走单 Agent 模式 */
    public boolean isSingleMode() {
        return isSingle(mode);
    }

    /** 判定任意 mode 取值是否为单 Agent 模式；null / 空 / 未知值一律按多 Agent 处理（安全默认） */
    public static boolean isSingle(String mode) {
        return "single".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    public int getMaxSubtasks() {
        return maxSubtasks;
    }

    public void setMaxSubtasks(int maxSubtasks) {
        this.maxSubtasks = maxSubtasks;
    }

    public int getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(int maxIters) {
        this.maxIters = maxIters;
    }

    public int getSingleMaxIters() {
        return singleMaxIters;
    }

    public void setSingleMaxIters(int singleMaxIters) {
        this.singleMaxIters = singleMaxIters;
    }

    public List<RemoteAgent> getRemoteAgents() {
        return remoteAgents;
    }

    public void setRemoteAgents(List<RemoteAgent> remoteAgents) {
        this.remoteAgents = remoteAgents;
    }

    /**
     * 一个远程 A2A Agent 的描述。它会被动态包装成一个 LLM 可调用的工具。
     */
    public static class RemoteAgent {

        /** Nacos 中注册的 AgentCard 名称，必须和子 Agent 启动时注册的名字完全一致 */
        private String name;

        /** 暴露给大模型的工具名，留空则自动生成 callXxxAgent */
        private String toolName;

        /** 给大模型看的能力描述，直接决定主管 Agent 会不会把任务派给它 */
        private String description;

        /** 工具入参描述（子任务内容） */
        private String taskDescription = "交给该 Agent 处理的子任务，要求描述清楚出发地、目的地、日期、天数和约束";

        private boolean enabled = true;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getToolName() {
            return toolName;
        }

        public void setToolName(String toolName) {
            this.toolName = toolName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getTaskDescription() {
            return taskDescription;
        }

        public void setTaskDescription(String taskDescription) {
            this.taskDescription = taskDescription;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** 未显式配置工具名时，由 Agent 名推导：RouteMakingAgent -> callRouteMakingAgent */
        public String resolvedToolName() {
            if (toolName != null && !toolName.isBlank()) {
                return toolName.trim();
            }
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("app.manager.remote-agents[].name 不能为空");
            }
            return "call" + name.trim();
        }
    }
}

package managerAgent.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.model.Plan;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import utils.AgentUtils;

/**
 * author: Imooc
 * description: 计划拦截器：把 PlanNotebook 的自主拆解过程和工具调用打到日志里
 * date: 2026
 *
 * <p>与课程原版的差异（很重要）：</p>
 * <p>原版在 PostReasoningEvent 里调用 {@code user.call().block()} 从控制台读用户输入，
 * 这在 HTTP 服务里会<b>永久阻塞</b>整条请求线程，服务直接不可用。
 * 这里改成纯观测（日志 + 轨迹），把"用户确认"下沉为配置项
 * {@code app.manager.need-user-confirm}，默认关闭。</p>
 */
@Slf4j
public class PlanHook implements Hook {

    /** 上一次已经打印过的计划，避免同一份计划每轮推理都刷屏 */
    private volatile String lastPlanFingerprint = "";

    private final PlanNotebook planNotebook;

    public PlanHook(PlanNotebook planNotebook) {
        this.planNotebook = planNotebook;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {

        if (event instanceof PreReasoningEvent e) {
            var messages = e.getInputMessages();
            if (!messages.isEmpty()) {
                Msg last = messages.get(messages.size() - 1);
                log.info("""

                        ============== 用户输入 ==============
                        {}
                        ======================================""", AgentUtils.textOf(last));
            }
        } else if (event instanceof PostReasoningEvent e) {
            Msg reasoning = e.getReasoningMessage();
            log.info("""

                    -------------- 模型思考 --------------
                    {}
                    --------------------------------------""", AgentUtils.textOf(reasoning));

            printPlanIfChanged();
        } else if (event instanceof PostActingEvent e) {
            String toolName = e.getToolUse() == null ? "unknown" : e.getToolUse().getName();
            log.info(">>> 工具执行完成：{}", toolName);
        }

        return Mono.just(event);
    }

    /** PlanNotebook 生成的计划只在变化时打印一次 */
    private void printPlanIfChanged() {
        Plan current = planNotebook.getCurrentPlan();
        if (current == null) {
            return;
        }
        String fingerprint = String.valueOf(current);
        if (fingerprint.equals(lastPlanFingerprint)) {
            return;
        }
        lastPlanFingerprint = fingerprint;
        log.info("""

                ========== PlanNotebook 自主拆解出的计划 ==========
                {}
                ==================================================""", fingerprint);
    }
}

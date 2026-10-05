package context;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import utils.AgentUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * author: Imooc
 * description: 工具路由 —— 按需决定挂载哪些工具组（EXP-002 机制 A）
 * date: 2026
 *
 * <p><b>为什么需要它：</b>上下文计量显示，每次 LLM 调用的**固定开销**（25~28 个工具的 JSON Schema）
 * 约占首轮输入的 78%。结果外置治的是"增量"，而 Schema 是"底价" —— 本机制治底价。</p>
 *
 * <p><b>做法：</b>用一次**极小的**模型调用（无工具、几十个 token）判断该需求是否需要真实地图数据，
 * 不需要就不挂载那 10 个地图工具。这一小调用的成本远低于它在后续每一次调用里省下的 Schema。</p>
 *
 * <p><b>失败时的默认值：宁多挂、不少挂</b> —— 分类失败/超时/解析不出来时返回 true，
 * 因为"多挂工具"只多花 token，而"少挂工具"会让 Agent 直接做不出真实数据（后者是正确性问题）。</p>
 */
public class ToolRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolRouter.class);

    private static final String ROUTER_PROMPT = """
            判断下面这个旅游规划需求，是否需要查询【真实地图数据】（驾车路线、距离、耗时、路况、地点经纬度）。
            需要就回答 YES，不需要就回答 NO。只回答一个词，不要解释。

            需求：%s
            """;

    private final Model model;
    private final Duration timeout;

    public ToolRouter(Model model, Duration timeout) {
        this.model = model;
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }

    /**
     * author: Imooc
     * description: 判断该需求是否需要地图工具组
     * @param prompt: 用户需求
     * @return true = 需要挂载地图工具（也是失败时的兜底值）
     */
    public boolean needsMapTools(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return true;
        }
        try {
            Msg msg = AgentUtils.userMsg(ROUTER_PROMPT.formatted(prompt));
            AtomicReference<String> text = new AtomicReference<>("");
            Flux<ChatResponse> flux = model.stream(List.of(msg), List.of(), GenerateOptions.builder().build());
            flux.doOnNext(resp -> {
                        if (resp == null || resp.getContent() == null) {
                            return;
                        }
                        StringBuilder sb = new StringBuilder();
                        for (var block : resp.getContent()) {
                            if (block instanceof TextBlock tb && tb.getText() != null) {
                                sb.append(tb.getText());
                            }
                        }
                        if (sb.length() > 0) {
                            text.set(sb.toString());
                        }
                    })
                    .blockLast(timeout);

            String answer = text.get().trim().toUpperCase();
            boolean needs = !answer.contains("NO");
            log.info("[ToolRouter] 工具路由：需求「{}」→ {}（原始回答「{}」）",
                    abbreviate(prompt), needs ? "挂载地图工具组" : "跳过地图工具组", answer);
            return needs;

        } catch (Exception e) {
            // 兜底：分类失败就按"需要"处理，宁可多花 token，也不要让 Agent 拿不到真实数据
            log.warn("[ToolRouter] 工具路由失败，按【需要】处理（宁多挂不少挂）：{}", e.toString());
            return true;
        }
    }

    private static String abbreviate(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 24 ? t : t.substring(0, 24) + "...";
    }
}

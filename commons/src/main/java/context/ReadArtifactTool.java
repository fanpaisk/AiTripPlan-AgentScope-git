package context;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.stream.Collectors;

/**
 * author: Imooc
 * description: 按需取回外置结果的工具
 * date: 2026
 *
 * <p>与 {@link ArtifactStore} 配合：大结果被外置后，Agent 若确实需要细节，
 * 用这三个工具**按需**取回，而不是让全部内容常驻上下文。</p>
 *
 * <p><b>设计取舍：</b>{@code read_artifact} 一次最多返回 {@code retrieveChars} 个字符，
 * 取回超长内容等于没外置；需要更多就用 {@code read_artifact_range} 分段取。
 * 注意工具方法的参数名会进 JSON Schema，因此编译必须带 {@code -parameters}（父 pom 已配）。</p>
 */
public class ReadArtifactTool {

    private final ArtifactStore store;
    private final int retrieveChars;

    public ReadArtifactTool(ArtifactStore store, int retrieveChars) {
        this.store = store;
        this.retrieveChars = Math.max(200, retrieveChars);
    }

    @Tool(description = "列出本次运行中已被外置的工具结果（id、来源工具、总字符数），用来判断是否需要取回细节")
    public String list_artifacts() {
        if (store.size() == 0) {
            return "本次运行没有外置任何结果（说明没有出现超长工具返回）。";
        }
        return store.list().stream()
                .map(a -> "- %s  来源=%s  共%d字符".formatted(a.id(), a.toolName(), a.length()))
                .collect(Collectors.joining("\n", "已外置的结果：\n", ""));
    }

    @Tool(description = "取回某个外置结果的开头部分（一次最多取回固定长度；需要更多请用 read_artifact_range 分段取）")
    public String read_artifact(@ToolParam(name = "id", description = "外置结果的 id，例如 art-1") String id) {
        ArtifactStore.Artifact a = store.get(id);
        if (a == null) {
            return "找不到该 id：" + id + "。请先用 list_artifacts 查看可用 id。";
        }
        String content = a.content();
        if (content.length() <= retrieveChars) {
            return "[%s 全文，%d 字符]\n%s".formatted(a.id(), content.length(), content);
        }
        String head = store.slice(id, 0, retrieveChars);
        return """
                [%s 共 %d 字符，此处只给前 %d 字符；需要更多请调用 read_artifact_range(id, offset, length)]
                %s""".formatted(a.id(), content.length(), retrieveChars, head);
    }

    @Tool(description = "按区间取回外置结果的指定片段，用于只看其中一段、避免把整段拉回上下文")
    public String read_artifact_range(@ToolParam(name = "id", description = "外置结果的 id，例如 art-1")
                                     String id,
                                     @ToolParam(name = "offset", description = "起始字符位置，从 0 开始")
                                     int offset,
                                     @ToolParam(name = "length", description = "取多少字符，建议不超过 4000")
                                     int length) {
        ArtifactStore.Artifact a = store.get(id);
        if (a == null) {
            return "找不到该 id：" + id + "。请先用 list_artifacts 查看可用 id。";
        }
        int len = Math.max(1, Math.min(length, retrieveChars));
        String part = store.slice(id, offset, len);
        return "[%s 的 [%d, %d) 区间，共 %d 字符]\n%s"
                .formatted(a.id(), Math.max(0, offset), Math.max(0, offset) + len, part.length(), part);
    }
}

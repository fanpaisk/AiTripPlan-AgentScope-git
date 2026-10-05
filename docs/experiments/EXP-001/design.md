# EXP-001：单 Agent vs 多 Agent 对照实验

> **状态**：设计已定稿，**尚未开始实现**。
> **本文件是实验的设计与执行规范**；当前进度与"下一步"以 `docs/STATUS.md` 第五节为唯一权威。

最后更新：2026-10-05

---

## 0. 要回答的问题

> **把一个「全能单 Agent」和现在这套「主管 + 2 个专业子 Agent」放在同一批需求上跑，token、延迟、质量、失败率各差多少？差在哪一层？**

假设（**先写死，避免跑完再"解读"**）：

| # | 假设 | 已知依据 |
|---|---|---|
| H1 | 多 Agent **token 明显更高**，差额主要来自**编排层**（主管自己的 LLM 调用 + 子 Agent 重复接收上下文） | 一次 E2E 实测共 21 次 LLM 调用，其中主管 trace 占 8 次 |
| H2 | 延迟方向**不明**：多 Agent 多一层 A2A 与工具等待，单 Agent 上下文更长、单次调用更慢 | 实测 215s 中 186s 是工具等待，模型只占 29.5s |
| H3 | 多 Agent 在**需求覆盖度/结构**上更好；单 Agent 在**一致性与不重复**上可能更好 | 待测 |
| H4 | 单 Agent **失败率更低**（少一次跨进程调用），但更易触 `maxTokens` / 超时 | 待测 |

**H2 与 H3 是主要价值所在**：若单 Agent 又便宜又不差，则多 Agent 必须用「可扩展性」而非「质量」来辩护 —— 这个结论无论朝哪个方向都能写进简历。

---

## 1. 实验设计

| 臂 | 组成 | 备注 |
|---|---|---|
| **A1 多 Agent（现状）** | 主管 + PlanNotebook + A2A → RouteMaking / TripPlanner | 基线，不改行为 |
| **A2 单 Agent** | `manager_agent` 内一个 ReActAgent，**直接持有地图 MCP + Skills**，无 A2A | 主要开发量 |
| **A3 无编排（可选）** | 不要主管：并行直调 8082 + 8085，拼接结果 | 近零新代码，用于隔离「编排层值不值」 |

主实验为 **A1 + A2**；A3 视预算追加。

### 1.1 必须锁死的控制变量

- **同一个构建**：两臂跑同一个 commit。绝不 A1 用旧包、A2 用新包。
- **同一 JVM / 同一次启动**：靠**请求级 `mode` 覆盖**切臂，而非重启服务（避免 JIT 预热差异污染延迟）。
- 冻结并记录：`model` / `maxTokens` / `stream` / `tool-parallel` / 三个超时 / `maxIters` / `remoteResultMaxChars` / `LLM_STREAM`。
- **三个子 Agent 服务全程保持运行**（即使 A2 不调用它们）—— 保证两臂的内存与 CPU 环境一致。
- 两臂 `maxIters` 均设到**都不会触顶**的水平；**记录是否触顶，触顶则该次数据作废**（否则比的是预算而不是架构）。

### 1.2 公平性：真正的污染源是提示词，不是"二次汇总"

原先担心「多 Agent 多一层汇总」会污染结论。但两臂产出的都是**各自的一次最终作答**，而那层汇总本身就是多 Agent 架构的一部分，不算污染。

**真正的污染源**：子 Agent 的 system prompt 经过精心撰写（路线 Agent 规定了 Markdown 表格、1500 字上限、鉴权失败不许重试），若单 Agent 随意写一句"帮我规划行程"，测的就是**提示词质量**而非架构。

> **控制手段**：A2 的 system prompt 按「子 Agent 提示词的并集」撰写 —— 同样的字数纪律、同样的 ①-⑥ 输出结构、同样要求标注估算值；且**两臂完整提示词都进交付物**接受审视。

另有一项诚实的架构性差异需要**声明而非消除**：A1 的最终答案建立在子 Agent 报告**被截断到 `remoteResultMaxChars`** 之后，A2 没有这一层截断。这属于架构固有差异，写进局限性即可。

---

## 2. 实现落点（文件级，已核实存在）

| 改动 | 文件 | 说明 |
|---|---|---|
| 模式开关 | `manager_agent/src/main/java/managerAgent/config/ManagerAgentProperties.java` | 加 `mode`（`multi` \| `single`，默认 `multi`） |
| 请求级覆盖 | `manager_agent/src/main/java/managerAgent/dto/ChatRequest.java` + `controller/AppController.java` | 加 `mode` 字段并透传（实验用；默认值不变） |
| 分叉装配 | `manager_agent/src/main/java/managerAgent/agents/ManagerAgent.java` 的 `newAgent(RunTrace)` | 按 mode 决定挂「远程 Agent 工具」还是「一套本地能力」 |
| 单 Agent 系统提示词 | 新增常量（与 `DEFAULT_SYS_PROMPT` 同级） | **必须声明与 A1 相同的 ①-⑥ 输出结构** |
| **能力对齐（关键）** | 把 `routeMaking_agent/.../mcp/BaiduMapMCP.java` 与 `routeMaking_agent/.../config/BaiduMapProperties.java` 提到 `commons` | 见 2.1 |
| manager 侧地图配置 | `manager_agent/src/main/resources/application.yml` | 加 `app.baidu-map.mcp-sse-url: ${BAIDU_MAP_MCP_SSE_URL:}` 与两个超时 |
| 批量跑批脚本 | 新增 `tools/experiment-run.ps1` | 见第 4 节；Apifox 集合同步加 `mode` 字段作为人工验收入口 |

### 2.1 搬迁 `BaiduMapMCP` 到 commons（已核实方案）

- 两个类都自包含：`BaiduMapMCP` 只依赖 AgentScope 核心（`McpClientBuilder` / `McpClientWrapper`）、`jakarta.annotation.PreDestroy`、Spring `StringUtils`、lombok；`BaiduMapProperties` 是纯 `@ConfigurationProperties`（`app.baidu-map.*`，前缀不变）。
- commons 对外提供 Bean 的既有模式（见 `commons/.../config/CommonsAutoConfiguration.java` + `commons/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`）：
  1. `BaiduMapProperties` 移入 commons 的 `config` 包，并加入 `CommonsAutoConfiguration` 的 `@EnableConfigurationProperties({AgentScopeProperties.class, BaiduMapProperties.class})`；
  2. `BaiduMapMCP` 移入 commons 的 `mcp` 包，**去掉 `@Component`**（commons 的包不在三个启动类的基础包下，扫不到），改为在 `CommonsAutoConfiguration` 里声明 `@Bean @ConditionalOnMissingBean`；
  3. 更新 `RouteMakingAgentConfiguration` 的 import。
- **行为不变**：`BaiduMapMCP` 是懒加载，未调用 `getBaiduMapMCP()` 就不会建连接，因此 manager / tripPlanner 多出这个 Bean 不产生副作用。
- **风险**：动到了**已验收的服务**。搬迁后必须完整重跑 `AGENTS.md` 验收表，确认 `routeMaking_agent` 的 10 个 `map_*` 仍正常挂载。

---

## 3. 指标与测量口径

每次运行记录（CSV + 原始 JSON）：

| 指标 | 来源 | 口径要点 |
|---|---|---|
| `token_total` | Langfuse **Metrics API v2**（`measure=totalTokens`） | **主指标**；跨服务，只能用 Langfuse |
| `token_by_layer` | 同上，按 trace 拆分 | 主管 trace vs 各子 Agent trace → **直接量化编排层开销** |
| `llm_calls` | Langfuse（`GENERATION` 条数） | 已实测：多 Agent 一次 21 次 |
| `duration_ms` | 响应体 `durationMillis` + 墙钟 | 主指标 |
| `status` / `error` | 响应体 | 失败率 |
| `tool_calls` / 工具名 | `RunTrace.steps` 或 Langfuse `TOOL` span | 判断依赖是否真被调用 |
| `speedup` / `verdict` | `RunTrace.toolBatches` | 并行是否生效 |
| `answer_chars` | 响应体 | **只记录，不计入质量分**（避免长度偏置） |
| `map_tools_mounted` | grep `logs\routeMaking_agent.out.log` 的 `已挂载的工具` | **D1 门禁** |

### 3.1 口径难点：一次运行的 token 怎么圈出来

一次用户请求会产生**多条 trace**（A2A 不传递 trace 上下文），所以不能用 traceId 直接聚合。对策：

1. 运行**严格串行**，两次之间留 ≥20s 间隔；
2. 用 `RunTrace` 的起止毫秒圈定时间窗口，前后留小缓冲；**用窗口内 observation 条数交叉校验**，与预期不符则告警复查；
3. **（可选增强，值得单独做）** 把 `runId` 通过 A2A 消息元数据贯穿到子 Agent，落到 `langfuse.session.id` —— 归属变精确，且这本身是一个可写进简历的可观测性改进。

> ⚠️ 查询口径见 `DECISIONS.md` D-018：**必须用 v2 API，旧 `/api/public/traces` 已 410**。不要因为查不到就怀疑链路。

#### ★ 已踩实的坑：摄取延迟会造成 2 倍以上的低估

冒烟轮实测：multi 臂在**运行后 25 秒**读到 `176,298 tokens / 24 obs`，同一窗口**沉淀后重查**是 `401,962 tokens / 42 obs` —— **低估 2.3 倍**。

原因：子 Agent 是**独立 JVM**，跨服务的 span 摄取明显慢于主管自身；只等 25 秒拿到的是不完整账目。**这个坑如果没发现，整组数据会系统性偏低，而且偏低幅度两条臂还不一样**（multi 涉及 2 个子 Agent，single 只涉及 1 个），结论会被直接做反。

**因此定为纪律**：

- 逐轮即时查询的结果只是**临时值**，仅用于跑批中途观察；
- **权威值一律以 `tools\experiment-run.ps1 -Recollect` 的事后重采为准**（不烧 token，只查 Langfuse）；
- `-Recollect` 会把每个 `raw/*.json` 按记录的起止时间重查、原地更新，并重建 `results.csv`；
- 报告里引用的所有 token 数字都必须来自 `-Recollect` 之后的 `results.csv`。

---

## 4. 执行协议

### 4.1 Prompt 集（5 条，固定不变）

| ID | 类型 | 用途 |
|---|---|---|
| P1 | 课程主用例：深圳→惠州 3 日游（路线 + 行程） | 与历史数据可比 |
| P2 | 强地图依赖：纯自驾路线，要里程/耗时/主要高速 | 逼出地图能力差异 |
| P3 | 强行程依赖：景点 + 住宿 + 美食 + 预算 | 逼出 Skills 差异 |
| P4 | 简单：单城一日游 | 看**小任务下多 Agent 的开销是否反而更亏** |
| P5 | 欠约束："帮我安排个周末出行" | 鲁棒性 / 澄清能力 |

### 4.2 重复数与顺序

- 每臂每 prompt **n=3**（下限）起，n=5 更稳。
- **交错执行**（P1-A1, P1-A2, P2-A1, …），并采用 ABBA 交替抵消顺序效应；**不要**"全跑完 A1 再跑 A2"（时间漂移会污染）。
- 每臂先各跑 **1 次预热**（丢弃不记）。

### 4.3 每次运行前的门禁

1. 探一次百度 MCP 地址（HTTP 200 才放行）；
2. `GET /api/health` 与 `GET /api/agents` 正常；
3. 任一不通过 → **暂停并记录**，不让坏数据混进两组。

---

## 5. 质量评分（5 维 rubric，各 1–5 分）

1. **事实可用性**（有无编造里程 / 景点 / 价格）
2. **需求覆盖度**（①路线 ②每日行程 ③餐饮 ④住宿 ⑤天气 ⑥预算 是否齐）
3. **可执行性**（时间与路线自洽、能照着走）
4. **结构清晰度**
5. **如实性**（失败与估算有无如实标注 —— 对应已知缺陷 D2）

**盲评**：去掉臂标签、随机顺序、同一份 rubric 交评审模型，**跑 3 次取中位数**。必须声明的局限：① 评审模型与被评 Agent 是同一个模型（自我偏好风险，当前只有 DeepSeek）；② 人工复核 20% 样本，与模型评分不一致的单独列出。

**不做显著性宣称**（n 太小）；只报告方向、效应量与原始数据。

---

## 6. 预算（先冒烟校准，勿直接跑满）

| | 单次 | 30 次（n=3 全量） |
|---|---|---|
| token | A1 ≈ 85k（实测）；A2 未知，估 40–60k | **约 1.9M – 2.2M** |
| 时长 | A1 ≈ 90–215s | **约 60–80 分钟纯运行** |

**执行顺序**：先跑**冒烟轮**（P1 各 1 次）→ 用 A2 的真实 token 回来校准本预算 → 再决定 n 取 3 还是 5。**先校准，再放量。**

---

## 7. 产物

- `docs/experiments/EXP-001/`：`design.md`（本文件）、`prompts.md`（两臂完整提示词）、`raw/*.json`、`results.csv`、`report.md`（结论 + 图表 + 局限性）
- `docs/DECISIONS.md`：新增 EXP-001 的设计决策（请求级开关、交错执行等）与被否方案
- `README.md`：新增一节面向读者的实验摘要（简历贴这一节）
- ⚠️ 原始回答入库前检查是否含百度 MCP 完整地址（已知缺陷 **D7**）

---

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| **D1 地图地址失效** → 两臂处于不同依赖状态，结论作废 | 每次运行前门禁探活；失效即暂停；记录每 run 的 `map_tools_mounted`，异常轮次单独标注或剔除 |
| D2 Agent 编造失败原因 | 已进 rubric 第 5 维 |
| 额度耗尽 | 冒烟轮先校准；脚本遇 `401/403/配额` 立即停，不要烧完才发现 |
| 机器负载波动 | 交错 + ABBA；运行期不并行做重活 |
| 人工点 30 次必出错 | 用 `tools/experiment-run.ps1` 批量执行并自动采集 |

---

## 9. 已拍板的决策（2026-10-05）

| 决策点 | 选择 | 理由 |
|---|---|---|
| A2 的能力边界 | **真同能力**：把 `BaiduMapMCP` 提到 commons 复用 | 否则质量差异里混进「没工具」而非「没分工」，结论经不起追问 |
| 样本量 | **先冒烟再定** | 先拿 A2 真实 token 校准预算，避免盲跑 30 次 |
| 批量执行方式 | **脚本跑批 + Apifox 做验收** | 硬约束 1「接口测试用 Apifox」的意图是保持集合可复用；Apifox 集合同步加 `mode` 字段作为人工验收入口，批量实验用脚本并入库 |

---

## 10. 待办（按依赖顺序）

1. 搬迁 `BaiduMapMCP` + `BaiduMapProperties` 到 commons；**重跑验收确认 routeMaking 的 10 个 map 工具未受影响**
2. 加 `app.manager.mode` + 请求级 `mode` 覆盖；manager 侧加 `app.baidu-map.*` 配置
3. 撰写 A2 的 system prompt（对齐 A1 的输出结构与字数纪律）
4. 写 `tools/experiment-run.ps1`（门禁 + 跑批 + 采集 + 断点续跑）
5. **冒烟轮**（P1 两臂各 1 次）→ 校准预算 → 定 n
6. 全量跑批 → 盲评 → `report.md`
7. 补 `DECISIONS.md` 与 `README.md` 摘要

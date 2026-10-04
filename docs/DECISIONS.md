# 决策记录（Design Decisions）

> 这份文档记录本项目在补全与优化过程中做过的**关键技术决策**，重点是「为什么这么选、否决了什么、踩了什么坑」。
> 采用**追加式**更新：推翻旧决定时新增一条并注明取代了哪条，不改写历史 —— 因为「当初为什么这么想」本身就是有价值的信息。
>
> 每条都尽量给出**可复现的证据**（字节码、日志、实测数值），而不是「我觉得这样更好」。

---

## D-001 放弃 `--enable-preview`，统一 Java 17 普通编译

- **背景**：课程原 `manager_agent/pom.xml` 用 `--enable-preview` + `source/target 17`。
- **决定**：去掉 preview，父 pom 统一 `<release>17</release>`。
- **理由**：① JDK 21 下 `--enable-preview` + release 17 **直接编译失败**（`源发行版 17 与 --enable-preview 一起使用时无效`）；② preview 编译出的 class 运行时也必须带 preview 参数，IDEA / `mvn spring-boot:run` / `java -jar` 三种方式都要额外配 VM 参数，极易踩坑；③ 代码并未真正使用任何预览特性。
- **被否**：保留 preview（需要额外装 JDK 17 且到处配 VM 参数）；升到 Java 21（没必要，17 已足够）。
- **结果**：仍然「使用 Java 17」，同时额外获得 JDK 21 / 25 的编译与运行兼容性。

## D-002 模型层做成厂商中立（`app.agentscope.llm.*`）

- **背景**：原配置命名是 `app.agentscope.dashscope.*`，且只支持阿里云百炼。
- **决定**：改名 `app.agentscope.llm.*`，默认走 **OpenAI 兼容协议**（`provider=openai-compatible`），支持任意 OpenAI 兼容服务。
- **理由**：① 百炼原生协议下 AgentScope 需按**模型名**猜端点（硬编码只认 `qvq*` 前缀和 `-vl` 后缀），百炼后加的 `qwen3.8-flash` / `qwen3.8-max` / `qwen3.7-plus` 都不带这两个特征，会被送进纯文本端点并报 `url error, please check url！`；兼容模式单端点通吃，从根本上消除这类问题；② 学习 / 迭代项目里换模型、换厂商是高频需求，不该每次都改代码。
- **被否**：硬编码模型名白名单（会随厂商迭代腐烂，等于重复框架自己的错误）。
- **验证**：`GET /api/diagnose` 把**实际生效**的 provider / 模型名 / 端点原样打出来；用旧厂商的 Key 打新厂商端点会返回新厂商的 `authentication_error`，证明请求确实换了目标。

## D-003 选用 DeepSeek 官方 `deepseek-v4-flash`

- **背景**：先后试过 `qwen3.8-flash`、`qwen3.8-max`、`qwen3-max`。
- **决定**：默认配置用 DeepSeek 官方 API + `deepseek-v4-flash`。
- **理由**：百炼账号若开着「仅使用免费额度」，`qwen3.8-flash` / `qwen3.8-max` 的免费额度很快耗尽（一次完整 3-Agent 流程约 **30~50 次**模型调用），之后报 `AllocationQuota.FreeTierOnly`（1 token 探针能过、真实请求就 403，很难一眼看出是配额问题）。换官方 API 直接绕开该限制。
- **被否**：继续在同一平台上换模型（额度不够）；升级为付费（非本项目目标）。
- **前提已验证**：`deepseek-v4-flash` **支持 function calling**（9 轮推理中 `create_plan` / `callRouteMakingAgent` / `callTripPlannerAgent` / `finish_plan` 全部成功执行）—— 这是换模型前唯一的真风险点，**换任何模型前都应先验这一条**。

## D-004 A2A 客户端改用非流式 `message/send`，弃用 `A2aAgent`

- **背景**：主管 Agent 调用子 Agent 时频繁报 `EOF reached while reading`。
- **决定**：`RemoteAgentTool` 不再使用框架的 `A2aAgent`（走 SSE 流式），改为**自己发 JSON-RPC `message/send` 非流式请求**。
- **理由**：服务端日志实锤 —— `IllegalStateException: The following item cannot be propagated because there is no demand and the overflow buffer is full: SendStreamingMessageResponse`，即 **Reactor 背压溢出**。Agent 事件产生速度超过客户端消费速度时缓冲打满，连接被直接掐断；客户端只能看到 EOF，**子 Agent 的真实错误（配额耗尽 / 模型报错）全被吞掉**。任务越复杂越容易触发（短任务成功、长任务必挂）。非流式由服务端累积完整结果后一次性返回，没有背压问题，且 JSON-RPC 的 `error` 字段能把真实原因带回给上游。
- **被否**：调大缓冲区 / 加心跳（治标）；减少事件（不可控，取决于模型输出）。
- **附带收益**：工具耗时由 `RemoteAgentTool` 自己测量并上报（Hook 拿不到单个工具的耗时，见 D-006）。

## D-005 工具并行：显式打开 `ToolkitConfig.parallel`

- **背景**：需要优化端到端耗时。
- **决定**：`ToolUtils.createToolkit(parallel)` 显式构造 `Toolkit`，配置项 `app.agentscope.tool-parallel`（默认 `true`）。
- **理由**：`ToolkitConfig.Builder` 的 `parallel` **默认是 `false`**（字节码里是 `iconst_0`），`new Toolkit()` 用的就是这个默认；而 `ToolExecutor.executeAll` 里是 `parallel ? Flux.mergeSequential : Flux.concat` —— 也就是说**模型虽然会在一轮里发多个工具调用（parallel function calling），框架却串行执行**。这是「框架默认值与你直觉相反」的典型例子。
- **被否**：依赖框架默认（就是串行）。
- **效果**：实测 2 路并发加速比 1.57x ~ 1.99x；3-Agent 端到端 **156s → 63s**。
- **前提**：并行的工具之间**不能有数据依赖**（如"先查天气 → 再据天气排行程"就不能并行）。已在主管 Agent 的 system prompt 中写明规则，由模型自行判断。

## D-006 修正「并行度」的可观测方式

- **背景**：一开始用「Hook 事件时间戳是否相同」判断并行，得出了**错误结论**（说"已经是并行了"，实际是串行）。
- **决定**：新增 `RunTrace.ToolBatch`，记录**批次墙钟耗时**与**批内各工具自身耗时**，用 `sum / wall` 算加速比；工具耗时由工具自己上报。
- **理由**：AgentScope 的 Hook 是**批量通知**的 —— PreActing 对一批工具一次性触发（在执行之前），PostActing 等整批跑完再一次性触发。因此串行和并行的时间戳**看起来完全一样**，根本无法区分。
- **教训**：**可观测性必须能测到你想要优化的那个指标**；否则数据看着漂亮，结论是错的。这也是「先加指标、再谈优化」的实践依据。

## D-007 `maxTokens` 必须显式设置

- **背景**：一次端到端跑出 `status=SUCCESS` 但 `answer` 只有 **253 字**，且断在半句话上（`…不走回头路 —— 深圳市`）。
- **决定**：新增 `app.agentscope.llm.max-tokens`（默认 8192）并传入 `GenerateOptions`。
- **理由**：不设置 `maxTokens` 时用厂商默认值，OpenAI 兼容路径下会**静默截断**长回答，且不报错、`status` 仍是 SUCCESS —— 属最难查的一类问题。证据：Hook 捕获的文本与响应体完全一致（排除采集逻辑问题）；显式设置后同样 prompt 的回答从 253 字变成 **3251 字**且完整收尾。
- **被否**：只在 prompt 里要求"简洁"（治标，不解决截断）。

## D-008 可选观测组件选 Langfuse

- **决定**：观测链路接 Langfuse（自托管用 Docker Compose，或直接用 Cloud 免费版）。
- **理由**：核心功能开源、**无需 license**（只有 RBAC / 审计日志 / 数据脱敏等企业功能才要）；同时支持 OTLP 摄取，能直接吃 AgentScope 产出的 OpenTelemetry span。
- **被否**：自己撸一套 trace 存储（重复造轮子）；从源码运行 Langfuse（要 Node + PG + ClickHouse + Redis + S3，太重）。
- **实践要点**：
  - 官方 compose 用 `docker.langfuse.com/langfuse/langfuse:4`，在部分国内网络下该域名会 **302 到 `registry-1.docker.io`** 而失败；改用 `docker.io/langfuse/langfuse:4` 可走镜像加速。
  - 自托管可用 compose 支持的 `LANGFUSE_INIT_*` 环境变量**无 UI 预建**组织 / 项目 / API Key / 首个用户，省去手工点界面。
  - 自托管 v4 的数据落在 ClickHouse 的 `events_core` / `events_full` 表，**`/api/public/traces` 读取接口不一定可用**（实测返回 404），排查时要直接查库，别以为"没数据"。

## D-009 观测链路的分工：应用内 RunTrace 与 Langfuse 并存

- **决定**：保留项目自建的 `RunTrace` / `GET /api/runs`，**同时**接入 Langfuse。
- **理由**：两者提供的维度不同 —— `RunTrace` 提供**项目特有**的信息（PlanNotebook 计划、`toolBatches` 并行度、A2A 派发细节），Langfuse 提供跨实验的**通用观测口径**（trace / token / 成本 / 对比视图）。做对照实验需要后者，日常调试更依赖前者。
- **被否**：只用 Langfuse（丢失项目特有维度）；只用 RunTrace（做对照实验时没有统一口径与 token 账目）。

## D-010 观测目标用 Langfuse Cloud 免费版（部分取代 D-008 的自托管建议）

- **背景**：本地自托管跑起来后内存吃紧（6 个容器 + Nacos + 3 个 JVM 同机），Langfuse 的 web / worker 进程被 OOM Killer 干掉（容器状态 `Exited (137)`）—— 而观测组件本身不该成为实验的前置成本。
- **决定**：观测改用 **Langfuse Cloud 免费版**；本地那套执行 `docker compose stop`（**容器停、数据卷保留**，`up -d` 可原地恢复，不删数据）。
- **理由**：① 内存是硬约束；② Cloud 免费版对「个人学习 + 对照实验」的用量完全够；③ 省掉维护 6 个容器这类与目标无关的坑。
- **被否**：
  - *继续自托管 + 加内存*（受限于机器）；
  - *只靠应用内 `RunTrace`，不上 Langfuse* —— 注意**这是退路**：若 Cloud 接入不顺就退到这里，耗时 / `toolBatches` / 步数 / 状态 `RunTrace` 都能给，只是缺统一口径与 token 账目；
  - *只停容器、不保留卷*（无必要地丢掉已验证的落库证据）。
- **代价**：**数据出网** —— prompt 与工具参数会上传到 Cloud，与 D-008 自托管「数据不出机器」相反。因此实现里给了总开关，见 D-013。

## D-011 三个 JVM 必须显式限堆（`-Xmx`），并写进启动脚本

- **背景**：与 D-010 同源的内存问题。
- **决定**：`run-all.ps1` 增加 `-HeapMb` 参数（默认 512），启动参数固定带 `-Xmx${HeapMb}m` 与 `-XX:+ExitOnOutOfMemoryError`。
- **理由**：不加 `-Xmx` 时 JVM 默认取**物理内存的 1/4** 作上限（16 GB 机器 ≈ 单进程 4 GB），三个服务叠加再碰上容器就会把整机吃光；而实测 **384 MB 就足够跑完整条链路**（含 5000+ 字回答 + 8000 字工具结果），说明默认值纯属浪费。
  `-XX:+ExitOnOutOfMemoryError` 把 OOM 时的行为从「半死不活、连接超时、看不出原因」变成「进程直接退出 + 日志里有 `OutOfMemoryError`」，**让故障可判** —— 这比省内存本身更重要。
- **证据**：限堆后三个 JVM 工作集约 225 / 246 / 209 MB。
- **被否**：调大整机内存 / 关掉容器平台（治本但换个机器又会复现）；只在文档里写「记得加 -Xmx」（人一定会忘，**必须写进脚本默认值**）。

## D-012 启动脚本的构建参数要自动适配，而不是硬编码

- **背景**：`run-all.ps1` 里的构建命令写成 `mvn -q clean package -DskipTests`，与文档要求的「必须带 `-s build-settings.xml`」不一致 —— **文档与自动化不一致是最容易骗过人的一类坑**（照文档手动敲是对的，用脚本就错）。
- **决定**：脚本自动探测同目录的 `build-settings.xml`，**存在就带上 `-s`，不存在就用默认配置**。
- **理由**：`build-settings.xml` 里写的是各人机器上的本地仓库绝对路径，因人而异，所以它被 `.gitignore` 排除；用「存在才带」的方式既能让需要的人生效，也不给其他人添麻烦。
- **顺带修的两处**：① 改为优先用 `$env:JAVA_HOME\bin\java.exe`（PATH 里可能是版本不对的 JDK）；② 密钥检查从只认旧变量名改为新的厂商中立变量名（模型层早已改名，检查逻辑没跟上 —— 典型的"改了 A 忘了 B"）。

---

## D-013 观测链路的实现方式：自己持有 OTel provider，并补齐 Reactor 插桩

- **背景**：把 AgentScope 的 tracing 接到 Langfuse 时做了一串技术选择，其中两个是**实测踩出来的**，不记下来下一个人一定会再踩。
- **决定**：
  1. **不用** `TelemetryTracer.builder().enabled(true).endpoint(...)` 的「一行式」写法，而是自己构建 `SdkTracerProvider`，再 `.tracer(...)` 传进去。
  2. Maven 依赖除 `opentelemetry-sdk` + `opentelemetry-exporter-otlp` 外，**必须再加 `io.opentelemetry.instrumentation:opentelemetry-reactor-3.1`**。
  3. 插桩版本锁定 **`2.21.0-alpha`**（与 `opentelemetry-api 1.55.0` 精确对齐）。
  4. Key 缺失时**只告警不抛异常**、静默跳过上报（默认 `enabled=true`），这样「填完 Key 重启就生效」，不需要再去改开关。

- **理由 —— 决定 1（自己持有 provider）**：
  读 `TelemetryTracer$Builder.build()` 的字节码，它有三条分支：`enabled=false` → noop tracer；`enabled=true 且传了 tracer` → **用外部传入的**；`enabled=true 且没传` → 自己 new `OtlpHttpSpanExporter + BatchSpanProcessor + SdkTracerProvider`，但**不把 provider 暴露出来**。走第三条有两个真实代价：
  ① **停机丢数据**：`BatchSpanProcessor` 默认攒批 / 最多等 5 秒才发，拿不到 provider 就没法在停机前 `forceFlush()`，而启动脚本是直接杀进程的 → 最后几秒的 trace 永久丢失，表现为「跑完了但 Langfuse 里没有记录」，极难排查；
  ② **三个服务分不开**：它用默认 Resource，`service.name` 会是 `unknown_service:java`，主管 / 路线 / 行程三个服务的 trace 在 Langfuse 里混成一团。
  自己持有 provider 后两者都解决：`DisposableBean.destroy()` 里先 `forceFlush()`（带超时，不拖住停机）再 `shutdown()`；Resource 里显式写 `service.name`。
  - **被否**：一行式写法 + 只靠「进程正常退出」（丢数据且不可见）；引入 OTel Java Agent 自动插桩（对单个 Spring Boot 应用过重，且 AgentScope 已经自带 span 生成，只需接上导出）。

- **理由 —— 决定 2（必须补 Reactor 插桩），这条是实测踩出来的**：
  只加 `opentelemetry-sdk` + `opentelemetry-exporter-otlp` 时，**服务能正常启动**（日志显示 `[tracing] trace 上报已开启`），但**第一次 `POST /app` 就返回 500**，日志是
  `NoClassDefFoundError: io/opentelemetry/instrumentation/reactor/v3_1/ContextPropagationOperator`。
  原因：AgentScope 是**全响应式**（Mono/Flux）的，`TelemetryTracer` 需要跨响应式链传递 span 上下文，用的是该类的两个**静态**方法 `storeOpenTelemetryContext` / `getOpenTelemetryContextFromContextView`（从 `TelemetryTracer` 字节码常量池读出，不是猜的）。
  **为什么这个坑值得写下来**：它的表象（请求 500 + 一个关于 OpenTelemetry 的报错）**极容易被误判成「Langfuse Key 填错了」**，把两类问题混在一起查。而且 AgentScope 的 pom 里连 `opentelemetry-api` 都没声明 —— 它的 tracing 是**可选能力，整条 OTel 栈要使用方自己带**，pom 不会给你任何提示。

- **理由 —— 决定 3（版本对齐）**：
  Spring Boot 4.0.2 只管理 `opentelemetry-bom`（=1.55.0），**不管** `io.opentelemetry.instrumentation:*` 这一套（独立发版、带 `-alpha` 后缀），所以版本必须自己写。逐个拉候选版本的 pom 比对出的对应关系：
  `2.21.0-alpha → api 1.55.0`（**精确命中**）、`2.20.0-alpha → 1.54.0`、`2.22.0-alpha → 1.56.0` …… `2.32.0-alpha → 1.66.0`。
  选最新（2.32.0-alpha）会要求 api 1.66.0，与受 Boot 管控的 1.55.0 差 11 个小版本，运行期可能 `NoSuchMethodError`；而且它的 jar 在部分镜像仓库上**根本没同步**（pom 有、jar 没有），连构建都过不去。
  **规则：升 `opentelemetry-bom` 时必须同步升插桩版本。**

- **验证方式（值得复用）**：接入时 Cloud 的 Key 还没拿到，但「到底有没有真的发 span」必须**当时就能验**，否则等接 Cloud 时会把「密钥问题」和「代码问题」混在一起查。于是写了 `tools/otlp-sink.ps1`（用 `TcpListener` 实现的极简 OTLP 接收器，冒充服务端入口），**当场收到 7 次 `POST /api/public/otel/v1/traces`、`application/x-protobuf`、最大 488 KB、带 Authorization 头** → 证明 span 真的产生了并导出成功。之后又把 Key 清空重启，验证降级路径（三个服务各打一条警告，`/api/health` 仍 UP）。
  - 用 `TcpListener` 而非 `HttpListener`：后者监听非特权端口需要 URL ACL 或管理员权限。

## D-014 本地自托管降级为「备用」

（本条是 D-010 的实施补充，不改变 D-010 的结论。）

- **决定**：本地 Langfuse 容器 `docker compose stop`，**数据卷保留**。
- **理由**：与 D-010 一致（内存）；保留卷是因为它已证明过「OTLP 能落库」，将来若要对比 / 回归可以直接 `up -d` 恢复，不必重装。
- **代价**：ClickHouse `events_core` 那套读取方式不再是主路径，相关记录仅作历史证据保留。

## D-015 本仓库作为**唯一开发主线**，另一份无版本控制的代码目录降级为历史快照

- **背景**：补全工作原本在一个**没有 `.git`** 的目录里进行 —— 也就是说「代码改动」和「踩坑记录」都没有版本保护。代码可以重写，**理由和踩过的坑不能**。而这份公开仓库最初只是「代码的副本」。
- **决定**：**本仓库（有版本控制）成为唯一开发主线**；另一个代码目录保留为只读历史快照，不再作为开发目标。
- **理由**：① 两份可写副本必然分叉，最终会退化成手工同步，而手工同步一定会漏；② 有版本控制才谈得上「哪次改动引入了问题」；③ 交接文档（`AGENTS.md` / `docs/STATUS.md` / 本文件）放在有版本控制的目录里，才不会随一次误删消失。
- **被否**：
  - *继续在无版本控制的目录开发，定期手工复制过来*（手工同步必漏，且「何时同步过」本身无记录）；
  - *把两份目录做 junction / 软链接合并*（掩盖问题而非解决；一旦有人 `git clean` 会连带删掉另一份）。
- **代价（已知并接受）**：历史验收的原始输出（`e2e.json`、启动日志、OTLP 接收器日志）留在那份快照的兄弟目录里，不在本仓库内。**缓解**：`STATUS.md` 第一节把关键数值直接抄录下来了；需要原始文件时回开发机取。

## D-016 开源化处理：只发布「经过脱敏」的文档，密钥与本地配置永不入库

- **背景**：本仓库要公开（简历展示用），而代码里有真实密钥、有各人机器相关的绝对路径、有偏会话交接口吻的内部记录。
- **决定**：
  1. `commons/src/main/resources/.env`（真实密钥）与 `build-settings.xml`（本地仓库绝对路径）**一律不入库**，`.gitignore` 覆盖，并用 `git check-ignore` **逐条验证**而不是「看起来应该没问题」。
  2. `LICENSE` 用 **MIT**，且**文件内只放纯 MIT 文本** —— 出处说明写进 `README.md`，因为往 LICENSE 里追加任何额外文字都会让 GitHub 的许可证识别失败（被判为 Other）。
  3. `README.md` 显著位置声明**代码来源**：初始骨架来自慕课网课程、源文件保留的 `author: Imooc` 即课程原始标注、**课程原始代码著作权归课程作者**，MIT 仅覆盖二次开发部分。
  4. 公开 `docs/DECISIONS.md`（脱敏版），但**不公开**偏会话交接性质的记录 —— 后者如公开，需先按同样标准脱敏。
- **理由**：密钥一旦进过 git，**删文件是没用的**（历史里还在），所以正确做法是**从一开始就不让它进去**；而课程代码的著作权是真实风险，必须在最显眼的位置讲清楚，而不是等对方来问。
- **被否**：把 LICENSE 和说明合成一个文件（会破坏许可证识别）；把出处说明只写在源码注释里（没人会翻 36 个文件才发现）。
- **验证入口**：`git ls-files | Select-String -Pattern '(^|/)\.env$|build-settings\.xml'` 应无输出；远端核对可用 GitHub API 取 `git/trees/main?recursive=1` 检查文件清单。

---

## 待验证 / 路线图

- **观测链路的目标状态**：跑一次 `POST /app` 后，Langfuse 的 Tracing 列表里出现对应 trace，并能看到 LLM 调用与 token。
  - 已完成的子验证：① OTLP 摄取通路可用（自托管时查 ClickHouse `events_core` 确认）；② **应用侧确实在导出 span**（`tools/otlp-sink.ps1` 收到 7 次 protobuf 请求）；③ Key 缺失时优雅降级（三个服务各打一条警告，`/api/health` 仍 UP）。
  - **尚未验证**：AgentScope 的 OTel span 能否被 Langfuse **正确渲染**成 trace 与 token 账目 —— 这是接上真 Key 后的第一件要确认的事。**这一档的准确表述是「代码就绪但未实测」，不是「已验收」。**
- **单 Agent vs 多 Agent 对照实验**：测量口径与通过标准待定。候选维度：完成质量（人工/LLM 打分）、端到端耗时、`toolBatches` 并行度、模型调用次数与 token、失败率。
  - ⚠️ **公平性提醒**：多 Agent 的答案由主管 Agent **二次汇总**过，单 Agent 没有这一层。不控制这个差异（例如让单 Agent 也走一次复述整理），结论会被「多一次汇总」污染。
  - ⚠️ **成本提醒**：一次完整 3-Agent 流程约 30~50 次模型调用，跑两组 × 多轮 prompt 前先估额度。

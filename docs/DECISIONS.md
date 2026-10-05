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

## D-017 直连 OTLP 上报必须带 `x-langfuse-ingestion-version: 4`

- **背景**：T1 接入 Langfuse Cloud 后核对官方 OTLP 文档，发现一条我们**没做**的事。
- **决定**：`AgentScopeTracing` 的 `OtlpHttpSpanExporter` 增加 `x-langfuse-ingestion-version: 4` 请求头。
- **理由**：官方原文是「直接摄入的 OpenTelemetry 数据**可能延迟最多 15 分钟**」——直连 OTLP 而不带这个头时，数据走旧数据模型，新数据模型的实时通路不生效。后果极其隐蔽：**跑完 `POST /app` 立刻去看 Tracing 列表，可能什么都没有**，于是误判成「T1 失败 / Key 不对」，而其实数据 15 分钟后才到。这类「假失败」比真失败更贵，因为它会把人引向错误的排查方向。
- **被否**：不加（省掉一个头，换来一个必然踩到的误判陷阱）。
- **验证**：编译产物里可查到该字符串（`findstr /C:"x-langfuse-ingestion-version" commons\target\classes\config\AgentScopeTracing.class`）；端到端上传后 observation 在一分钟内即可查询到。

---

## D-018 Langfuse 的读取与验收口径：用 v2 API；并用 Metrics API 查 token

- **背景**：T1 首次验收时，`GET /api/public/v2/observations` 返回的 18 条 GENERATION 里 **`modelId` / `inputPrice` / `totalPrice` 全为空**，据此我得出「token 账目缺失」的结论，并花了数轮去追根因。
- **结论：那个结论是错的**，错在**查询姿势**，不在链路。真实情况由两条证据共同确认：
  1. 用「保存报文」的采集器抓下应用发出的 OTLP 原始报文，protobuf 里明文出现 `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens`，且按 wire type 判读为 **int_value**（键后字节 `12 02 18 1F` = int 31、`12 02 18 27` = int 39），与 `ChatResponse` 的 `inputTokens:31 / outputTokens:39` 精确吻合；`gen_ai.request.model` = `deepseek-v4-flash`、`gen_ai.operation.name` = `chat`。
  2. 改查 **Metrics API v2** 立刻拿到 token：`{"data":[{"sum_totalTokens":"85063","sum_inputTokens":"72203","sum_outputTokens":"12860","count_count":"56"}]}`，按模型维度归到 `providedModelName=deepseek-v4-flash`。
- **决定**：
  1. **读取口径定为 v2**：trace/span 用 `GET /api/public/v2/observations?fromStartTime=…`，**token / 成本用 `GET /api/public/v2/metrics?query={…}`**（`measure` 取 `totalTokens` / `inputTokens` / `outputTokens` / `count`）。
  2. **旧接口一律不用**：`GET /api/public/traces` 与 `GET /api/public/observations` 在 Cloud 上返回 **410 Gone**（不是 404），`/api/public/v2/traces` 是 404，单条详情接口不存在（`/api/public/v2/observations/{id}` = 404）。
  3. **`modelId` / `inputPrice` 为空不代表没数据**：`modelId` 指向项目里的模型定义记录，未匹配到就为空；价格为空是因为 Langfuse 的定价表里没有 `deepseek-v4-flash`。token 账目与这些字段无关。
- **被否**：
  - *看到列表里没有 usage 字段就判定「链路没打通」*（就是本次踩的坑：把「接口不返回」当成「数据不存在」）；
  - *改用 `/api/public/traces` 之类的旧接口去凑*（已 410，方向错误）。
- **代价 / 教训**：**可观测性问题的第一嫌疑应该是自己的查询口径，而不是被测链路**。判据要选「能直接证明量存在」的那一个（token 总量），而不是「看起来应该带这个字段」的那一个。
- **被证伪的假设（一并记录，避免重复走）**：一度怀疑「AgentScope 流式路径拿不到 usage」，于是把三处 `stream: true` 改成 `${LLM_STREAM:true}` 并在 `.env` 设 `false` 重打包实测 —— **token 账目在 `stream=true` 与 `stream=false` 两种模式下都正常**（`stream=true` 那次 216s 的 E2E 贡献了 85,063 tokens 中的 85,063−140 部分；`stream=false` 窗口只有 140）。因此该改动**已全部回退**，仓库回到 `stream: true`。
  - 附带查明：DeepSeek 的流式响应**最后一个 chunk 本来就带完整 `usage`**（`prompt_tokens`/`completion_tokens`/`total_tokens`），紧跟 `[DONE]`；AgentScope 也确有 `OpenAIStreamOptions`（含 `include_usage`）与 `GenerateOptions.additionalBodyParams` 可用。**这条链路本来就是好的。**

---

## D-019 重建顺序：先停、再打包、后启动（jar 文件锁 + 脚本不会停旧进程）

- **背景**：改完 `.env` 与代码后直接 `mvn clean package`，构建失败：`Failed to delete …\manager_agent\target\manager_agent-1.0-SNAPSHOT.jar`。
- **决定**：把顺序固化为 **`.\stop-all.ps1` → `mvn … clean package` → `.\run-all.ps1 -SkipBuild`**，并写进 `AGENTS.md` 硬约束与常用命令。
- **理由**：① Windows 不允许删除已被 JVM 加载的 jar，服务在跑时 `clean` 必然失败；② 更危险的是**反向的**坑 —— `run-all.ps1` **没有任何"先停旧进程"的逻辑**（它只 `Start-Process` 新 jar），端口被旧进程占用时新进程会启动失败，**而脚本因为"端口有监听"照样打印 `[就绪]`**。于是人以为重启成功，实际对外服务的是旧代码，之后所有验收结论都建立在错误的构建上。
- **被否**：*用 `-SkipBuild` 单独重启*（这会跳过打包，`.env` 与代码改动根本没进 jar —— 正是本项目硬约束 7 要防的事）。
- **附带说明**：`stop-all.ps1` 是按端口 `Stop-Process -Force` 强杀，**不会触发 Spring 的 `DisposableBean.destroy()`**，因此 D-013 里那个「停机前 `forceFlush()`」在这些场景下不会执行。实测无碍（`BatchSpanProcessor` 约 5 秒一批会自然刷出），但**验收 T1 时不要在请求刚结束就杀服务**，否则最后几秒的 span 可能丢失。
- **验证**：按该顺序重建，5 个模块 `BUILD SUCCESS`（见 `STATUS.md` 第一节 #1）。

---

## D-020 排查时用**环境变量覆盖** `.env`，而不是改 `.env`

- **背景**：需要把 OTLP 端点临时指向本地接收器，以抓取 span 的原始属性。项目原有做法（`tools/otlp-sink.ps1` 的注释、`STATUS.md` 旧第五节）是**改 `.env` → 重新打包 → 重启 → 验完再改回并重新打包**，并专门写了「⚠️ 破坏性验证的恢复步骤」。
- **决定**：改用**真实环境变量覆盖**：在启动该服务的会话里设 `$env:LANGFUSE_OTLP_ENDPOINT=…` 再 `java -jar`，`.env` 一个字都不动。
- **理由**：`spring.config.import: optional:classpath:.env[.properties]` 导入的属性源**优先级低于操作系统环境变量**，所以覆盖生效；而好处是全方位的 —— ① 不必重新打包（省一轮构建）；② 不存在"忘记改回来"的残留状态（原做法最大的风险就是中断在中间，服务持续往本地接收器发数据）；③ `.env` 里含密钥，本就该少碰。
- **证据**：以环境变量启动主管服务后，启动日志打印 `endpoint = http://127.0.0.1:4319/api/public/otel/v1/traces`，证明覆盖确实生效。
- **附带产出**：`tools/otlp-capture.ps1`（与 `otlp-sink.ps1` 同构，但把报文落盘）。`otlp-sink.ps1` 只回答「发出去了没有」，**回答不了「带着哪些属性」**；而 D-018 那个坑正是"必须有后者"才能定论。落盘后 protobuf 的字段名是明文 ASCII，直接 grep 即可判读，不需要写解析器。
- **被否**：*继续沿用「改 `.env` + 恢复步骤」*（多一轮构建、且留一个"忘记恢复"的隐患）；*写一个完整的 OTLP protobuf 解析器*（为了看清属性名而引入依赖，成本远高于 grep 明文）。

---

## D-021 观测数据有摄取延迟：token 账目必须事后重采，不能运行刚结束就读

- **背景**：EXP-001 跑批脚本最初在每次运行结束后等 25 秒就查 Langfuse 的 token 账目，用来给每轮打标签。
- **踩到的坑（实测）**：同一个时间窗口，**运行后 25 秒**读到 `176,298 tokens / 24 obs`；**沉淀后重查**是 `401,962 tokens / 42 obs` —— **低估 2.3 倍**。
- **决定**：
  1. 逐轮即时查询的值只当**临时值**；
  2. **权威值一律以 `tools\experiment-run.ps1 -Recollect` 的事后重采为准**（该模式不跑请求、只重查 Langfuse 并原地更新 `raw/*.json` 与 `results.csv`）；
  3. 默认即时等待时间从 25 秒提到 90 秒（只为减少临时值的偏差，不代表够用）；
  4. 报告里引用的 token 数字必须来自重采后的 `results.csv`。
- **理由**：子 Agent 是**独立 JVM**，跨服务的 span 摄取明显慢于主管自身。而**两条臂涉及的服务数量不同**（多 Agent 臂有 2 个子 Agent，单 Agent 臂没有），低估幅度也不一样 —— 若不修，**两条臂会被系统性偏置，结论可能直接被做反**（当时算出来的比值是 1.6×，真值是 2.2×）。
- **被否**：*把等待时间无限加大*（跑批时间不可控，且仍不能保证够）；*改用 traceId 聚合*（A2A 不传递 trace 上下文，一次请求会产生多条 trace，做不到 —— 见 D-018）；*改用 Langfuse 的 session 维度*（需要把 runId 贯穿 A2A 消息元数据，属于更大的改造，列为 design.md 的可选增强）。
- **附带教训**：**"读数时机"本身是测量方法的一部分。** 只要指标是异步汇聚的，就必须区分"临时值"和"权威值"，并显式规定哪个进报告。

---

## D-022 用推理模型当评审：`max_tokens` 给少了会静默返回空正文

- **背景**：EXP-001 的盲评脚本调用 `deepseek-v4-flash` 按 5 维 rubric 打分，要求只输出 JSON。
- **踩到的坑（实测）**：最初设 `max_tokens = 900`，结果 `finish_reason = length`、`completion_tokens_details.reasoning_tokens = 900`、**`content` 为空**（推理内容 2990 字把预算吃光）。脚本把"解析不到 JSON"当成缺项跳过，于是 6 组里有 4 组被**静默记成 0 分**，汇总出来的"两臂均分 7.58 / 6.5"完全是废数据。
- **决定**：
  1. 评审调用 `max_tokens` 给到 **4000**（实测一次完整评审：prompt 4,398 + reasoning 1,067 + JSON 202，`finish_reason=stop`）；
  2. 脚本**遇到空 `content` 必须抛错**，并在异常里带上 `finish_reason` 与 `reasoning_tokens`；
  3. 每组统计**成功评分次数**，低于重复数要显式告警，全部失败要标红 —— 不允许把"没有分数"混同于"分数为 0"。
- **理由**：**推理模型把 `max_tokens` 分成"推理预算 + 正文预算"两部分**，这与非推理模型的行为完全不同。少给预算的表现不是报错，而是"正文为空"——一个看起来像"模型没按要求输出"的假象。
- **被否**：*换非推理模型*（手上只有这一个模型，且这是已知局限要如实声明）；*不加 JSON 模式、靠正则从推理内容里抠分数*（评分不可靠，且会奖励"推理里写得好看"的答案）。
- **附带教训**：**"缺数据"和"数据是 0"必须在代码里区分开。** 静默默认值是聚合类脚本最危险的行为 —— 它会产出一份格式完全正常、结论完全错误的报告。

---

## D-023 上下文预算：把「工具结果外置 + 按需取回 + 计量」做成产品能力

- **背景**：EXP-001 实测单 Agent 平均每次 LLM 调用携带约 19 万输入 token，端到端是单 Agent 贵 2.2 倍。补上计量器后（见下），逐次曲线给出了根因的**具体形态**：
  ```
  call#1–#4   输入  5,041 → 8,133
  call#5      输入  167,976   ← 一条地图返回（约 16 万 token）进入上下文
  call#6–#9   输入  ~21.5 万/次 ← 之后每一次调用都要重发它
  ```
  也就是说：**不是"工具太多"，也不是"结果均匀累积"，而是少数几条超大结果长期驻留、每次调用重复付费。**
- **决定**（四个可独立开关的机制，本次先做 B+D）：
  1. **B 工具结果外置**：装饰 `McpClientWrapper`，超过阈值（默认 4000 字符）的工具返回存进 **run 级** artifact store，
     上下文里只留摘要 + id；配套三个取回工具（`list_artifacts` / `read_artifact` / `read_artifact_range`）。
  2. **D 上下文计量**：装饰 `Model`，每次调用打印**真实**输入/输出 token（取自厂商 usage）+ 消息字符数 + 工具 schema 字符数 + 估算构成。
  3. 两者都做成**配置 + 请求级开关**（`app.agentscope.context.*` / 请求体 `contextBudget`），
     这样"开着"与"关着"能在**同一个 JVM 内交错运行** —— 否则两次运行之间要改配置重启，时间漂移会与机制效果混在一起。
  4. 默认开启，`enabled=false` 一键回到引入前行为。
- **理由 —— 为什么用装饰器**：MCP 工具是运行时动态注册的（`Toolkit.registerMcpClient`），逐个包装工具会与框架内部实现耦合；
  而 `McpClientWrapper` 的 `callTool` / `listTools` / `initialize` / `close` 都是公开抽象方法，代理它只依赖稳定接口，就能拦到**所有** MCP 返回。
  `Model` 更简单，只有 `stream` / `getModelName` 两个方法。
- **理由 —— 为什么是 run 级 store**：跨运行共享会让上一次请求的地图数据污染下一次决策；run 级天然有界、请求结束即失效。
- **理由 —— 为什么"外置"而不是"截断"**：截断是**不可恢复**的信息丢失，Agent 只能猜；外置让信息留在手边、按需取回，
  代价是一次额外的工具往返。这也让"省 token"与"保信息"不再是对立选项。
- **理由 —— 为什么计量先行**：没有"能测到你正要优化的那个指标"的观测，优化无法验证真伪（这正是本项目 D-006 的教训）。
  实测该计量器立刻改掉了我一个错误判断：我原以为历史是均匀累积的，曲线显示是单条巨物。
- **被否**：
  - *直接把超长结果截断*（不可恢复的信息丢失，会以质量下降为代价换 token —— 而实测不截断也能做到 −81%）；
  - *只换更大上下文的模型*（窗口已有 **1M**，问题从来不是"装不下"，而是**每次重发都要付费**）；
  - *上 RAG / 向量检索*（它解决"读不到"，而我们的问题是"写到爆"，属写侧驱逐/外置问题）；
  - *用历史压缩（机制 C）替代外置*（压缩有损且对所有内容一视同仁，而问题只出在少数几条巨物上；C 排到 A/B 之后再评估）；
  - *靠人工肉眼核对结果*（几十次运行不可能靠眼睛，必须自动化）。
- **效果与代价（筛选轮 n=1，见 EXP-002 报告）**：token **1,073,065 → 200,209（−81%）**，盲评质量 22 → **24**（未降），
  代价是延迟 **+26%**（84s → 106s）与 LLM 调用 **+2 次**（`read_artifact` 取回）。
  **保留：n=1 只能当强信号**，确认轮未做。
- **计量口径（重要，别误读其输出）**：`[ContextMeter]` 里的**真实 token 来自厂商 usage，是准的**；
  但"schema 占比 / 历史占比"是由字符数估算的（中文约 1 token/字符、英文 JSON 约 4 字符/token），**只用于横向对比**。
  要精确归因，用"外置开 / 关两条曲线的差值"，不要用那个百分比。

---

## D-024 跑批工具链的四个坑：都属于「看起来正常的错误数据」

- **背景**：EXP-002 开发期间，跑批脚本与采集链路连续出现四个问题，**没有一个表现为"明显的失败"** ——
  它们要么静默产出错误数字，要么把好环境误判成坏环境。
- **四坑与修法**：

  | # | 坑 | 症状 | 修法 |
  |---|---|---|---|
  | 1 | 门禁探活用 `curl --max-time 8` | SSE 端点握手偶发超 8 秒 → 返回 `000` → **把好地址误判为失效**、整批被门禁拦下（幸运的是 0 token 消耗） | 提到 20 秒 + 重试一次；并记录"curl 读 SSE 会一直挂到 max-time 属正常" |
  | 2 | **PowerShell 数组对 `-eq` 是过滤语义**（返回数组而非布尔） | `ConvertTo-Json` 把它序列化成 `"contextBudget":[]` → 服务端 Jackson 报 `Cannot deserialize Boolean from Array value` → **HTTP 400**，且**不烧 token**，极难察觉 | 显式取首元素 + 显式 `[bool]` 转换：`[bool]([string]@($x)[0] -eq 'on')` |
  | 3 | 坑 2 曾把 `$budget` 数组格式化进文件名 → 产生 `single_bSystem.String[]_P1_r1.json` | **PowerShell 把路径里的 `[ ]` 当通配符**，`Get-Content -Raw` 参数绑定直接失败 → 整个重采循环被中断 | 读写文件一律用 `-LiteralPath`；解析失败只跳过并告警，不中断整批 |
  | 4 | 请求异常被 `catch` 静默吞掉 | 一轮变成"没有数据"，与"数据为 0"无法区分（与 D-022 同一类错误） | catch 里**显式打印**错误与耗时；记录里保留 `requestBody` 便于事后定位 |

- **决定（写成纪律）**：
  1. **禁止静默默认值**：脚本里任何"取值失败就当成 0 / 当成空"的写法都要改成显式报错或显式跳过+告警；
  2. **区分"缺数据"与"数据为 0"**：聚合前先校验字段存在性，输出里标注 n（有效样本数）；
  3. **文件路径一律 `-LiteralPath`**（本项目的实验产物文件名由变量拼装，出现方括号并非不可能）；
  4. **门禁探活要给足超时并重试**，且探活失败只暂停、不修改任何状态 —— 它拦住一次运行的成本，远低于放坏数据进组的成本。
- **理由**：这四条的共同点是 —— **"看起来正常但内部错误"的数据比"明显报错"危险得多**。
  明显报错会立刻停止并迫使你处理；而一个 `[]` 或一个 0 会安静地流进汇总表，产出一份格式完全正确、结论完全错误的报告。
  本项目已经在这同一类问题上栽过三次（D-022 的"0 分"、D-021 的低估、本条的 400）。
- **被否**：*人工肉眼核对*（几十次运行不可行，且人正是最容易被"正常格式"骗过的环节）；
  *把脚本写得更简单以少踩坑*（这些坑来自 PowerShell 与 HTTP 语义本身，不是复杂度带来的，简化不解决）。

---

## 待验证 / 路线图

- **观测链路的目标状态 → 已达成（2026-10-05）**：跑一次 `POST /app` 后，Langfuse 里出现对应 trace，且能看到 LLM 调用与 token 账目。实测数据见 `STATUS.md` 第一节 #8 / #9（56 条 observation、85,063 tokens）；查询口径见 D-018。本条取代原先「尚未验证」的表述。
  - 历史子验证（保留作分段排查入口）：① OTLP 摄取通路可用（自托管时查 ClickHouse `events_core` 确认）；② 应用侧确实在导出 span（`tools/otlp-sink.ps1` 收到 7 次 protobuf 请求）；③ Key 缺失时优雅降级（三个服务各打一条警告，`/api/health` 仍 UP）。
  - 留下的教训：**「渲染不出来 / 没有账目」时先怀疑自己的查询口径**（D-018 就是这么绕了几轮）。
- **单 Agent vs 多 Agent 对照实验**：测量口径与通过标准待定。候选维度：完成质量（人工/LLM 打分）、端到端耗时、`toolBatches` 并行度、模型调用次数与 token、失败率。
  - ⚠️ **公平性提醒**：多 Agent 的答案由主管 Agent **二次汇总**过，单 Agent 没有这一层。不控制这个差异（例如让单 Agent 也走一次复述整理），结论会被「多一次汇总」污染。
  - ⚠️ **成本提醒**：一次完整 3-Agent 流程约 30~50 次模型调用，跑两组 × 多轮 prompt 前先估额度。

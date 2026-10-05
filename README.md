# AiTripPlan —— 多 Agent + Skills + AgentScope(A2A/Nacos) 自主决策旅游规划智能体

> 基于慕课网课程《多 Agent+Skills+SpringAI 构建自主决策智能体》的**实战项目补全版**。
> 课程原代码骨架完整但细节缺失、无法直接运行；本版本已经补齐到「三个服务起来就能跑通端到端」的水准，
> 并在课程基础上做了工程化增强（REST/SSE 接口、运行轨迹可观测、配置驱动的 Agent 编排）。

## ⚖️ 代码来源与许可（请先读这一节）

- **初始骨架**来自上述课程，源文件中保留的 `author: Imooc` 注释即为课程原始标注。
  **课程原始代码的著作权归课程作者所有。**
- 本仓库的 [MIT 许可](LICENSE) **仅覆盖由 fanpaisk 补全与新增的部分**，包括但不限于：
  可运行的工程化改造、`commons` 公共模块、REST/SSE 接口、`RunTrace` 观测体系、
  模型层厂商中立抽象、工具并行、OpenTelemetry → Langfuse 观测链路，以及各处缺陷修复
  （完整清单见[第八节](#八与课程原版代码的差异补全清单)）。
- 若你是课程作者并认为本仓库的公开方式不妥，请开 Issue 联系，我会立即调整。
- 仓库内**不含任何真实密钥**：密钥只写在 `commons/src/main/resources/.env`（已被 `.gitignore` 忽略），
  模板见 [`.env.example`](commons/src/main/resources/.env.example)。

## 📌 项目约定

| 约定 | 说明 |
|---|---|
| **接口测试一律用 Apifox** | 不使用 curl / Postman。接口集合见 `docs/apifox/`，操作手册见 [`docs/接口测试-Apifox指南.md`](docs/接口测试-Apifox指南.md) |
| **接口文档即代码** | 改了接口要同步更新 `docs/apifox/*.openapi.json`，再在 Apifox 里「智能合并」重导一次 |
| **设计决策看 `docs/DECISIONS.md`** | 关键技术选型「为什么这么选、否决了什么、踩了什么坑」都记在那里，含可复现证据。改架构前先读 |
| 密钥不进仓库 | 只写在 `commons/src/main/resources/.env`（已被 `.gitignore` 忽略），或用同名环境变量覆盖 |
| Java 版本 | 17（不用 `--enable-preview`），JDK 17 / 21 均可编译运行 |

---

## 🆕 最近更新（2026-10-05）

这一轮在**课程内容之外**自己做了三件事（数据、原始回答与复现步骤见第九节）：

1. **观测链路闭环（T1）**：AgentScope → OTLP → Langfuse Cloud(EU) 打通，trace / LLM 调用 / **token 账目**均可查。
   期间修掉一个很隐蔽的问题：直连 OTLP 上报**必须带 `x-langfuse-ingestion-version: 4`**，
   否则数据最多延迟 15 分钟 —— 表现为"跑完了但 Langfuse 里什么都没有"，极易误判成密钥问题。
2. **对照实验 EXP-001（单 Agent vs 多 Agent）**：新增单/多 Agent 可切换（`app.manager.mode` 或请求体 `mode`），
   并把地图 MCP、Skills、计算工具提到 `commons`，使两条臂**能力对等**（否则测出来的是"有没有工具"而不是"有没有分工"）。
   结论：**质量打平（21.33 vs 21.17 / 25），但单 Agent 贵 2.2 倍**，且方差达 21 倍；单 Agent 快 23%。
3. **上下文优化 EXP-002（上下文预算）**：新增「工具结果外置 + 按需取回 + 每次调用的上下文计量」
   （`app.agentscope.context.*`）。定位到的根因是**单条超大工具结果长期驻留、每次调用都重发**。
   实测单 Agent 的 token **降低约 84%**（均值 121 万 → 19.4 万）且**质量未降**，代价是延迟约 +23%。

> 课程原代码的 17 项缺陷修复清单仍在第八节；"接下来可以怎么魔改"在第十节。

---

## 一、这是个什么东西

一句话：**用户说一句"帮我规划深圳到惠州元旦3日自驾游"，系统自动拆任务、派给不同专业的 Agent、汇总成一份完整行程。**

### 补全后的验证状态

| 验证项 | 状态 |
|---|---|
| 5 个 Maven 模块编译（JDK 17，无 preview） | ✅ BUILD SUCCESS |
| TripPlannerAgent 启动 + Skills 载入 | ✅ `[Make-A-Table, Suggest-Sights]` |
| RouteMakingAgent 启动 + MCP 缺失降级 | ✅ 只告警不崩 |
| A2A AgentCard 注册到 Nacos 3.1.0 | ✅ `Register agent card xxx to Nacos successfully` |
| `/.well-known/agent-card.json` | ✅ |
| `GET /api/health` / `/api/agents` | ✅ `registeredAgents:2`，两个子 Agent 均 `registered:true` |
| 动态把远程 Agent 挂成工具 | ✅ `已挂载 2 个远程 Agent 工具：[callTripPlannerAgent, callRouteMakingAgent]` |
| `POST /app` 同步接口 + RunTrace | ✅ 返回 runId / status / answer / steps |
| `POST /app/stream` SSE | ✅ `text/event-stream`，`event:chunk/done/error` 分帧正确 |
| `.env` 从 classpath 加载 | ✅ 不带任何启动参数即可读到密钥 |
| TripPlannerAgent 内部组装（Skill + 子Agent + 工具） | ✅ `已创建，技能 2 个，工具 4 个` / `已挂载工具：[call_suggestsightagent]` |
| A2A 客户端到子 Agent 的真实调用（含 JSON-RPC 报文） | ✅ 任务 SUBMITTED → 完成；报文结构已按 SDK 校准（`message.kind` 必填） |
| **真实大模型回答** | ✅ `POST /app` → `SUCCESS`，63s，**5798 字**完整收尾（DeepSeek `deepseek-v4-flash`） |
| **百度地图 MCP 真实算路** | ✅ 挂载 **10 个** `map_*` 工具；回答里带真实数据（如「福田市民中心 → 天河体育中心 127.6 km / 约 132 分钟」） |
| **工具并行执行** | ✅ `PARALLEL（完全并行，2 个并发，加速比 1.59x）`；3-Agent 端到端 156s → 63s |
| **AgentScope → Langfuse 观测链路** | ✅ 代码完成并验证到"确实在导出 span"（用 `tools/otlp-sink.ps1` 收到 7 次 `application/x-protobuf`，最大 488 KB）；**在 Langfuse Cloud 里的渲染效果待填入你自己的 Key 后确认** |
| 内存占用 | ✅ 三个 JVM 限堆 `-Xmx512m`，工作集合计约 680 MB（不限堆时会把整机吃光） |

> 上面「TripPlannerAgent 内部组装」和「A2A 真实调用」两项是补做验证时**新发现并修掉的两个真 bug**，
> 详见第八节表格第 16、17 条 —— 这两个问题在只看启动日志时完全看不出来，只有真正发起一次 A2A 调用才会暴露。


```
                        ┌──────────────────────────────────────────────┐
   用户 POST /app       │           ManagerAgent  (8081)              │
  ─────────────────────▶│  主管智能体 = ReActAgent + PlanNotebook      │
                        │  职责：理解需求 → 拆子任务 → 决策派发 → 汇总  │
                        └───────────────┬──────────────────────────────┘
                                        │  工具调用（A2A 协议）
                                        │  AgentCard 从 Nacos 查询
                        ┌───────────────┴───────────────┐
                        ▼                               ▼
        ┌───────────────────────────┐   ┌───────────────────────────────┐
        │  RouteMakingAgent (8082)  │   │   TripPlannerAgent (8085)     │
        │  路线制定                  │   │   行程规划                     │
        │  └─ 百度地图 MCP (SSE)     │   │   └─ SubAgentTool             │
        │     · 地理编码/驾车路线     │   │      └─ SuggestSightAgent     │
        │     · 距离耗时/路况/POI     │   │         ├─ Skills (SKILL.md)  │
        └───────────────────────────┘   │         │  · Suggest-Sights   │
                        │               │         │  · Make-A-Table     │
                        │               │         └─ 工具 Calculate      │
                        └───────┬───────┴───────────────────────────────┘
                                ▼
                  ┌─────────────────────────────┐
                  │   Nacos 3.x (8848)          │
                  │   A2A AgentCard 注册与发现   │
                  └─────────────────────────────┘
```

**用到的关键技术**（简历可写）：

| 能力 | 实现 |
|---|---|
| 自主决策 | AgentScope `PlanNotebook`（任务分解 → 步骤生成 → 状态跟踪 → 动态调整） |
| 多 Agent 编排 | Nacos 3.x A2A AgentCard 注册中心 + **非流式 `message/send`** 远程调用（不用流式 `A2aAgent`，见第八节） |
| Agent 即工具 | 把远程 A2A Agent 动态封装成 LLM function-calling 工具（`AgentTool`） |
| Skills | `classpath:skills/<Name>/SKILL.md` + `SkillBox` 按需加载 |
| MCP | 百度地图 MCP Server（SSE 传输）接入为 Agent 工具 |
| 过程可观测 | ① `Hook` 拦截 PreReasoning/PostReasoning/PreActing/PostActing，落成 `RunTrace`；② AgentScope `TelemetryTracer` → OpenTelemetry OTLP → **Langfuse** |
| 工具并行 | 显式打开 `ToolkitConfig.parallel`，同批工具走 `Flux.mergeSequential` |
| 工程化 | 多模块 Maven、自动装配 starter、配置驱动编排、SSE 流式接口、**厂商中立的模型层** |

---

## 二、环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **17**（推荐）或 21 | 项目按 Java 17 编译；未使用预览特性，**不需要 `--enable-preview`** |
| Maven | 3.9+ | |
| Nacos | **3.x（必须 ≥ 3.0）** | ⚠️ Nacos 2.x **不支持** A2A Agent 注册中心，会报 `Request Nacos server version is too low` |
| 大模型 | **任何 OpenAI 兼容服务** | 任选其一：DeepSeek 官方（本项目当前用的，`deepseek-v4-flash`）/ 阿里云百炼（`qwen3-max`）/ Kimi / 智谱 / SiliconFlow / 本地 vLLM、Ollama。**换厂商只改 `.env` 里 3 行** |
| 百度地图 MCP | 魔塔社区托管 SSE 地址 | 见下文获取方式。⚠️ 该地址**约 1 天过期** |
| 观测（可选） | Langfuse（Cloud 免费版，或自托管） | 不配也能跑，服务只打一条警告并跳过上报 |

---

## 三、准备外部资源（3 步）

### 1. 大模型 API Key

本项目**不绑定任何厂商**，`app.agentscope.llm.provider` 默认走 OpenAI 兼容协议，任选一家即可：

| 厂商 | `LLM_BASE_URL` | `LLM_MODEL` 示例 | Key 申请 |
|---|---|---|---|
| **DeepSeek 官方**（本项目当前用的） | `https://api.deepseek.com` | `deepseek-v4-flash` | <https://platform.deepseek.com/> |
| 阿里云百炼（兼容模式） | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen3-max` | <https://bailian.console.aliyun.com/> → 「API-KEY」 |

> ⚠️ 注意：百炼账号若开着「仅使用免费额度」，`qwen3.8-flash` / `qwen3.8-max` 这类模型的免费额度
> 很快会耗尽（一次完整 3-Agent 流程约 30~50 次模型调用），之后会报
> `AllocationQuota.FreeTierOnly`。本项目因此改用 DeepSeek 官方。
> 另外所选模型**必须支持 function calling**，否则工具调用整条链路都不工作。

### 2. 百度地图 MCP Server（SSE 地址）

1. 打开魔塔社区 MCP 广场 <https://modelscope.cn/mcp>
2. 搜索 **百度地图**，进入后配置你的百度地图 AK
   （AK 申请：<https://lbs.baidu.com/apiconsole/center> → 应用类型「服务端」→ 校验方式「IP白名单」→ 白名单 `0.0.0.0/0`）
3. 复制生成的 **SSE 地址**，形如 `https://mcp.api-inference.modelscope.net/xxxxxxxx/sse`

### 3. Nacos 3.x（Docker 一键起）

```bash
docker pull nacos/nacos-server:v3.1.0

docker run -d --name nacos \
  -e MODE=standalone \
  -e NACOS_AUTH_ENABLE=false \
  -e NACOS_AUTH_TOKEN=MjM1ZmU4NjAxMzU1NTQyYWU0MTEyYWU4ZDg3YTZiNGUK \
  -e NACOS_AUTH_IDENTITY_KEY=nacos \
  -e NACOS_AUTH_IDENTITY_VALUE=nacos \
  -p 8848:8848 -p 9848:9848 -p 8088:8080 \
  nacos/nacos-server:v3.1.0
```

- 控制台：<http://127.0.0.1:8088>，账号密码都是 `nacos`
- **8848 是 RPC 端口，代码连的是这个**；8088 才是控制台页面

> 验证 Nacos 是否可用：端口能连通 + 启动业务服务后日志出现
> `Register agent card xxx to Nacos successfully.` 即为正常。
> （Nacos 3.x 已经移除了 `/nacos/v1/console/server/state` 这个旧接口，访问它会返回 410，这是正常的。）

**方案 B：不用 Docker，直接用官方 zip（Windows 实测可用）**

从官网下载并解压 Nacos 3.1.0：
<https://github.com/alibaba/nacos/releases/tag/3.1.0>（下载 `nacos-server-3.1.0.zip`）

```powershell
$env:JAVA_HOME = "C:\path\to\jdk17"   # Nacos 3.x 需要 JDK 17
cd C:\path\to\nacos\bin
.\startup.cmd -m standalone
# 停止：.\shutdown.cmd
```

> ⚠️ **zip 方式必须手动补 3 个配置**，否则 Nacos 3.x 会直接启动失败并报
> `Empty identity, Please set nacos.core.auth.server.identity.key and ...`。
> Docker 方式由 `-e NACOS_AUTH_IDENTITY_KEY/VALUE` 等环境变量自动注入，zip 方式要自己写进
> `conf/application.properties`：
>
> ```properties
> nacos.core.auth.enabled=false
> nacos.core.auth.server.identity.key=nacos
> nacos.core.auth.server.identity.value=nacos
> nacos.core.auth.plugin.nacos.token.secret.key=<自建一个 ≥32 字节的 base64 串，或用 Docker 方式>
> ```
>
> 另外 zip 方式下 `nacos.home` 不总是生效（实测直接跑会尝试往用户目录写），
> **不想折腾就用方案 A 的 Docker**。

### 4. 填入密钥

编辑 `commons/src/main/resources/.env`：

```properties
# ---- 大模型（厂商中立，换厂商只改这 3 行）----
LLM_PROVIDER=openai-compatible
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-v4-flash
LLM_API_KEY=sk-你的Key

# ---- 外部依赖 ----
BAIDU_MAP_MCP_SSE_URL=https://mcp.api-inference.modelscope.net/你的实例/sse
NACOS_SERVER_ADDR=127.0.0.1:8848

# ---- 观测（可选，留空即跳过上报，不影响业务）----
LANGFUSE_TRACING_ENABLED=true
LANGFUSE_OTLP_ENDPOINT=https://cloud.langfuse.com/api/public/otel/v1/traces
LANGFUSE_PUBLIC_KEY=
LANGFUSE_SECRET_KEY=
```

> 也可以直接设置**同名系统环境变量**，优先级高于 `.env`，线上部署用这种方式。
> 该文件已在 `.gitignore` 中忽略，不会被提交。
> ⚠️ **`.env` 会被打进 jar**：改完必须重新 `mvn package` 并重启，否则读到的还是旧值。

---

## 四、启动

### 方式 A：IDEA（推荐）

1. `File → Project Structure → Project SDK` 选 **JDK 17**，`Language level` 选 **17 - Sealed types, always-strict floating-point semantics**（**不需要** 选 Preview）
2. 依次启动三个启动类（顺序建议：先子 Agent，后主管）：

   | 模块 | 启动类 | 端口 |
   |---|---|---|
   | `tripPlanner_agent` | `tripPlannerAgent.TripPlannerAgentApplication` | 8085 |
   | `routeMaking_agent` | `routeMakingAgent.RouteMakingAgentApplication` | 8082 |
   | `manager_agent` | `managerAgent.ManagerAgentApplication` | 8081 |

3. 启动日志里应该能看到：
   - `[SuggestSightAgent] 从 classpath:skills 载入 2 个 Skill：[Make-A-Table, Suggest-Sights]`
   - `[tracing] 没有读到 Langfuse 的 Key，跳过 trace 上报（不影响业务）`（配了 Key 则显示 `trace 上报已开启`）
   - `Start to auto register agent TripPlannerAgent into Registries.` + `Auto register agent ... into Registry Nacos`（**没有 failed**）

> ⚠️ **看不到百度地图 MCP 的工具列表是正常的**：`ReActAgent` 是 prototype Bean，框架按 A2A
> **会话**懒创建它，所以 `[BaiduMapMCP] 客户端创建成功` 与 `[RouteMakingAgent] 已挂载的工具：[map_geocode, ...]`
> 出现在**第一次 `POST /app` 之后**，不在启动时。要确认地图工具是否可用，用
> `GET /api/health` 之后跑一次请求，再去 `logs\routeMaking_agent.out.log` 搜 `已挂载的工具`。

### 方式 B：命令行

```bash
# 在 AiTripPlan-AgentScope 目录下
mvn clean install -DskipTests

# 分别开三个终端
mvn -pl tripPlanner_agent spring-boot:run
mvn -pl routeMaking_agent  spring-boot:run
mvn -pl manager_agent      spring-boot:run
```

Windows 下也可以直接用项目自带的脚本（在 `AiTripPlan-AgentScope` 目录执行）：

```powershell
.\run-all.ps1        # 打包 + 启动三个服务（后台运行，日志写到 logs\）
.\stop-all.ps1       # 一键停掉
```

> 脚本会自动做三件事：优先用 `$env:JAVA_HOME\bin\java.exe`、给每个 JVM 加 `-Xmx512m`
> （见下面「为什么必须限堆」）、以及**如果同目录存在 `build-settings.xml` 就自动带上 `-s`**。
> 可选参数：`-SkipBuild`（跳过打包只重启）、`-Only manager|route|planner`（只起一个）、
> `-HeapMb 768`（调大堆上限）。
>
> **为什么必须限堆**：不加 `-Xmx` 时 JVM 默认取物理内存的 1/4 作上限（16 GB 机器单进程可涨到 4 GB），
> 三个服务叠加会把整机内存吃光。实测 512 MB 足够跑完整链路。

> **关于 `build-settings.xml`（可选）**
> 本仓库**不包含**这个文件（已在 `.gitignore` 中忽略），因为它里面写的是各人机器上的本地仓库绝对路径。
> 如果你需要把 Maven 本地仓库指到别处（比如离线构建、或 C 盘紧张），自己建一个：
>
> ```xml
> <settings>
>   <localRepository>D:/maven-repo</localRepository>
> </settings>
> ```
>
> 然后用 `mvn -s build-settings.xml clean package` 即可，`run-all.ps1` 也会自动识别。

---

## 五、接口测试（Apifox）

> ### 📌 团队约定
> **本项目所有接口测试一律使用 Apifox**，不使用 curl / Postman。
> 接口文档就是代码的一部分，代码改了要同步更新 `docs/apifox/` 下的 OpenAPI 文件。
> 完整操作手册见 **[`docs/接口测试-Apifox指南.md`](docs/接口测试-Apifox指南.md)**（含断言、测试场景、排查表）。

### 一次性准备：导入接口集合

`docs/apifox/` 下有三个 OpenAPI 3.0 文件，**各服务一个**（三个服务端口不同，分开导入才不会搞混前置 URL）：

| 文件 | 服务 | 端口 |
|---|---|---|
| `01-manager-agent-8081.openapi.json` | 主管 Agent（用户入口） | 8081 |
| `02-tripPlanner-agent-8085.openapi.json` | 行程规划 Agent | 8085 |
| `03-routeMaking-agent-8082.openapi.json` | 路线制定 Agent | 8082 |

**导入**：Apifox →「项目设置」→「导入数据」→ 选 **OpenAPI / Swagger** → 把 `docs/apifox` 整个文件夹拖进去 → 导入模式选 **「智能合并」**（后续文档更新重导不会产生重复接口）。

接口文件里已经写好了 `servers`（`http://127.0.0.1:8081` 等），**不需要再配置环境的前置 URL**，导入即可直接发请求。

### ⚠️ 导入后必改一项：请求超时时间

Agent 跑一次要调大模型 + 多个子 Agent，**可能耗时 1~5 分钟**。

> Apifox → 设置 → 请求 → **请求超时时间改为 300000 ms（300 秒）以上**

不改的话最典型的现象是：请求几十秒就报超时，但服务端日志显示其实已经跑成功了。

### 接口清单

**主管 Agent（8081）**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/app` | **同步**：等整条链路跑完，返回完整方案 + 轨迹 |
| POST | `/app/stream` | **SSE 流式**：边推理边推，前端可做打字机效果（Apifox 里请求类型选 SSE） |
| GET | `/api/health` | 健康检查，看重 `registeredAgents` 是否为 2 |
| GET | `/api/agents` | 远程子 Agent 注册情况（**排查第一现场**） |
| GET | `/api/runs` | 最近若干次运行的轨迹列表 |
| GET | `/api/runs/{runId}` | 某次运行的完整轨迹（推理步骤 + 工具调用 + 最终回答） |

> **请求体**：除 `prompt`（必填）外还有两个**可选**字段，日常使用留空即可，它们是对照实验（第九节）的开关：
> - `"mode": "multi" | "single"` —— `multi` = 主管 + 远程子 Agent（默认）；`single` = 单个 Agent 自己做完（自带地图 MCP + Skills + 计算工具，不做派发）
> - `"contextBudget": true | false` —— 是否启用「工具结果外置」，默认取配置 `app.agentscope.context.enabled`
>
> 响应里的 `mode` / `contextBudget` 会**回显**本次实际生效的值，可用于校验没有串档。

**子 Agent（8085 / 8082）**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/.well-known/agent-card.json` | A2A 标准发现接口，返回能力声明 |
| POST | `/` | A2A 调用（JSON-RPC `message/send`），用于**单点调试子 Agent** |

> A2A 请求体里 `message.kind` 必须是 `"message"`，漏了会返回 `-32602 Invalid parameters`（OpenAPI 示例里已经写好）。

### 推荐测试顺序（自检清单）

按这个顺序测，出问题能立刻定位到是哪一层：

1. `GET /api/health`（8081）→ 期望 `registeredAgents: 2`
2. `GET /api/agents`（8081）→ 期望两个 Agent 都是 `registered: true`
3. `GET /.well-known/agent-card.json`（8085、8082）→ 确认 `name` 与主管配置一致
4. `POST /`（8085）→ **单测行程规划 Agent**，不经过主管，最快验证
5. `POST /`（8082）→ **单测路线 Agent**，返回里有具体里程/耗时 = 百度地图 MCP 正常
6. `POST /app`（8081）→ 端到端，期望 `status: SUCCESS` + 完整 Markdown 行程
7. `POST /app/stream`（8081）→ 事件逐条到达，最后 `event:done`
8. `GET /api/runs/{runId}`（8081）→ 用第 6 步的 runId 回溯完整决策轨迹

> 建议把 1~6 步存成 Apifox「测试场景」并加上断言（`registeredAgents == 2`、`status == SUCCESS`），
> 以后每次魔改完点一下就能确认没把主链路改坏。

### 关键响应结构

**`POST /app` 成功**

```json
{
  "runId": "a1b2c3d4e5f60000",
  "status": "SUCCESS",
  "answer": "## 行程总览\n...",
  "durationMillis": 87321,
  "steps": [
    { "type": "HINT",        "name": "PreReasoning",         "detail": "帮我制定2026年元旦……" },
    { "type": "REASONING",   "name": "ManagerAgent",         "detail": "我先拆解任务……" },
    { "type": "TOOL_CALL",   "name": "callRouteMakingAgent", "detail": "{task=制定深圳到惠州3日自驾路线……}" },
    { "type": "TOOL_RESULT", "name": "callRouteMakingAgent", "detail": "由 RouteMakingAgent 完成：深圳→惠州约 90 公里……" }
  ]
}
```

**`POST /app` 业务失败**（HTTP 仍是 200，请以 `status` 判断）

```json
{
  "runId": "a1b2c3d4e5f60002",
  "status": "FAILED",
  "answer": "",
  "error": "HTTP request failed with status 401 | {\"code\":\"InvalidApiKey\",...}",
  "durationMillis": 12108,
  "steps": []
}
```

**`GET /api/agents`**

```json
[
  {"name":"RouteMakingAgent","toolName":"callRouteMakingAgent","registered":true,
   "url":"http://127.0.0.1:8082","version":"1.0.0"},
  {"name":"TripPlannerAgent","toolName":"callTripPlannerAgent","registered":true,
   "url":"http://127.0.0.1:8085","version":"1.0.0"}
]
```

`registered=false` 时 `error` 字段会写明原因（未启动 / 未注册 / Nacos 连不上）。

**`POST /app/stream` 事件流**

```
event:chunk
data:{"runId":"...","type":"REASONING","text":"我先调用路线Agent...","last":false}

event:chunk
data:{"runId":"...","type":"TOOL_RESULT","text":"由 RouteMakingAgent 完成...","last":false}

event:done
data:{"runId":"...","type":"DONE","text":"<完整Markdown方案>","last":true,"durationMillis":91234}
```

---

## 六、可调参数速查

### `app.agentscope.llm.*`（commons，三个模块通用）—— ★ 厂商中立，换模型只改这里

| 配置 | 默认 | 说明 |
|---|---|---|
| `llm.api-key` | 空 | 必填，`.env` 的 `LLM_API_KEY`（兜底兼容 `ALIBABA_DASHCOPE_KEY`） |
| `llm.provider` | `openai-compatible` | **协议模式**。`openai-compatible`＝单端点、任意厂商通用（推荐）；`dashscope`＝百炼原生协议（换多模态模型会报 url error） |
| `llm.model` | `qwen3-max` | 模型名，如 `qwen3-max` / `deepseek-v4-flash` |
| `llm.base-url` | 空 | 服务地址。留空＝百炼兼容模式默认地址；DeepSeek 填 `https://api.deepseek.com` |
| `llm.stream` | true | 流式解析 |
| `llm.enable-search` | false | 模型侧联网搜索（额外计费，仅百炼原生协议支持） |

> **换厂商 = 改 3 行，代码零改动**。支持任何 OpenAI 兼容服务：
> 百炼兼容模式 / DeepSeek 官方 / Kimi / 智谱 / SiliconFlow / 本地 vLLM、Ollama。
> 也可以改用系统环境变量覆盖：`APP_AGENTSCOPE_LLM_BASE_URL`、`APP_AGENTSCOPE_LLM_MODEL`、`APP_AGENTSCOPE_LLM_API_KEY`。

| 其它配置 | 默认 | 说明 |
|---|---|---|
| `nacos.server-addr` | `127.0.0.1:8848` | Nacos RPC 地址 |
| `remote-call-timeout` | `15m` | 主管 Agent 放弃单个子 Agent 的时间 |
| `tool-execution-timeout` | `17m` | 框架层工具超时兜底，**必须大于上一项** |
| `run-timeout` | `45m` | 整次请求总超时 |
| `max-iters` | 20 | ReAct 最大推理轮数，防止烧 token |
| **`tool-parallel`** | **true** | ★ 同一轮里的多个工具调用是否**并行**执行。框架默认 `false`（`Flux.concat` 串行），开启后走 `Flux.mergeSequential`（同时订阅，总耗时 = 最慢那个） |

### ★ 工具并行（`app.agentscope.tool-parallel`）

**一句话**：模型可以在一次回复里同时请求多个工具（parallel function calling），
但 AgentScope **默认串行执行**它们，必须显式打开并行。

```yaml
app:
  agentscope:
    tool-parallel: true
```

**原理**（AgentScope 1.0.8）：

```java
// Toolkit.callTools()
executor.executeAll(blocks, toolkitConfig.isParallel(), ...)

// ToolExecutor.executeAll()
if (parallel) return Flux.mergeSequential(monos)...  // 同时订阅所有工具
else          return Flux.concat(monos)...           // 前一个完成才订阅下一个

// ToolkitConfig.Builder —— parallel 默认 false，new Toolkit() 用的就是它
```

**使用条件**：并行的工具之间**不能有数据依赖**。
- ✅ 可以并行：路线 Agent ‖ 景点 Agent（互不依赖）
- ❌ 不能并行：先查天气 → 再据天气排行程（有先后依赖）

主管 Agent 的 system prompt 里已经写明了这条规则，由模型自行判断。

**怎么验证并行生效**：调 `POST /app`，看响应里的两个字段：

```json
"parallelismSummary": "并发 4 个工具，墙钟 35822ms，各工具耗时之和 71174ms → PARALLEL（完全并行，2 个并发，加速比 1.99x）",
"toolBatches": [
  { "size": 4, "wallMillis": 35822, "sumMillis": 71174,
    "toolMillis": [35362, 35812], "speedup": 1.99, "verdict": "PARALLEL（...）" }
]
```

判据：**批内各工具耗时之和 ÷ 批次墙钟耗时 = 加速比**。≈1 是串行，≈N 是 N 路并行。

> ⚠️ 不要用「事件时间戳是否相同」来判断并行 —— AgentScope 的 Hook 是**批量通知**的
> （PreActing 一批一次性触发、PostActing 整批跑完再一次性触发），
> 串行和并行的时间戳看起来一模一样。这也是新增 `toolBatches` 的原因：
> Hook 拿不到单个工具的耗时，只能由工具自己上报（见 `RemoteAgentTool#reportDuration`）。

**实测效果**：3-Agent 端到端从 **156 秒 → 69 秒**（约 2.3 倍）。

### `app.agentscope.tracing.*` —— ★ 观测：把 AgentScope 的调用链路报到 Langfuse

**一句话**：给每次 LLM 调用 / 工具调用 / Agent 调用打 OpenTelemetry span，上报到 Langfuse，
用于做「单 Agent vs 多 Agent」这类对照实验（耗时、token、成本、失败率）。

```yaml
app:
  agentscope:
    tracing:
      enabled: ${LANGFUSE_TRACING_ENABLED:true}                     # 总开关
      endpoint: ${LANGFUSE_OTLP_ENDPOINT:https://cloud.langfuse.com/api/public/otel/v1/traces}
      public-key: ${LANGFUSE_PUBLIC_KEY:}                           # 放 .env，别写进 yml
      secret-key: ${LANGFUSE_SECRET_KEY:}
```

| Key 配置 | 现象 |
|---|---|
| 两个 Key 都为空 | 只打一条警告 `[tracing] 没有读到 Langfuse 的 Key，跳过 trace 上报`，**业务与 `/api/health` 完全正常** |
| 填好了 | 日志出现 `[tracing] trace 上报已开启（AgentScope → Langfuse）`，包含 `service = manager-agent` 等 |

> ★ `endpoint` 必须是**完整**的 traces 地址，`OtlpHttpSpanExporter` 不会自己补 `/v1/traces`。
> Key 获取：Langfuse Cloud → 项目 → Settings → API Keys。改完 `.env` **必须重新打包**。

**实现上的两个坑（踩过，见 `docs/DECISIONS.md` D-013）**：

1. **必须依赖 `io.opentelemetry.instrumentation:opentelemetry-reactor-3.1`。**
   AgentScope 是全响应式的（Mono/Flux），`TelemetryTracer` 要用它的 `ContextPropagationOperator`
   跨响应式链传递上下文。**缺它的表现极具误导性**：服务能正常启动，但**第一次请求就 500**，
   日志是 `NoClassDefFoundError` —— 非常容易被误判成「Langfuse Key 不对」。
   AgentScope 的 pom 连 `opentelemetry-api` 都不声明，tracing 是可选能力，**整条 OTel 栈要自己带**。
2. **插桩版本要与 api 对齐：`2.21.0-alpha` ↔ `opentelemetry-api 1.55.0`**（正好等于 Spring Boot 4.0.2 管理的版本）。
   Spring Boot 只管理 `opentelemetry-bom`，**不管** instrumentation 这一套，所以版本得自己写。
   升 `opentelemetry-bom` 时必须同步升插桩版本，否则 `NoSuchMethodError`。

**为什么没用 `TelemetryTracer.builder().enabled(true).endpoint(...)` 的一行式写法**：
它自建的 `SdkTracerProvider` 不暴露引用，于是 ①停机时拿不到 provider 调 `forceFlush()`，
而 `stop-all.ps1` 是直接杀进程的 → 批量缓冲里最后几秒的 trace 全丢（表现为"跑完了但 Langfuse 里没有"）；
② 它用默认 Resource，`service.name` 会是 `unknown_service:java`，三个服务的 trace 混在一起分不开。
本项目改成**自己持有 provider**（见 `config/AgentScopeTracing.java`）：`DisposableBean.destroy()` 里
先 `forceFlush()`（带超时，不拖住停机）再 `shutdown()`，并在 Resource 里显式写 `service.name`。

**排查「Langfuse 里看不到 trace」—— 先分段定位，别一上来就怀疑 Key**：

```powershell
# 1) 起一个假的 OTLP 接收器（冒充 Langfuse 入口）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\otlp-sink.ps1

# 2) 把 .env 临时指向它（Key 随便填非空值），重新打包 + 重启，跑一次 POST /app
#    LANGFUSE_OTLP_ENDPOINT=http://127.0.0.1:4318/api/public/otel/v1/traces

# 3) 看 tools\otlp-sink.log.txt
#    收不到 bytes>0  → 应用侧就没产生 span（查依赖，见上面第 1 条）
#    收得到          → 应用侧没问题，问题在端点 / Key / 网络
```

> ⚠️ **隐私**：上报内容含 prompt、工具参数与模型输出（实测单次请求载荷可达 488 KB），
> Langfuse Cloud 在境外 —— 不想外传就把 `LANGFUSE_TRACING_ENABLED` 设为 `false`。
> 另外 Langfuse 拿不到本项目特有的信息（如 `toolBatches` 并行度），那部分仍以 `GET /api/runs` 为准。

### `app.agentscope.context.*` —— ★ 上下文预算：工具结果外置 + 上下文计量

```yaml
app:
  agentscope:
    context:
      enabled: true                  # 总开关；false = 一键回到引入本功能之前的行为（用于消融对照）
      offload-threshold-chars: 4000  # 超过该字符数的工具返回才外置（小结果外置不划算）
      preview-chars: 800             # 外置后留在上下文里的预览长度
      retrieve-chars: 4000           # read_artifact 单次最多取回多少字符
      meter-enabled: true            # 每次 LLM 调用打印 [ContextMeter] 行（真实 token + 构成）
```

**为什么需要它**：EXP-001 实测单 Agent 每次 LLM 调用平均携带约 19 万输入 token，
主因是**单条超大工具结果长期驻留**（之后每次调用都重发一遍）。开启外置后实测 **token 降低 81%**、质量持平。
机制详解与实测数据见 [`README` 第九节](#九对照实验多-agent-到底值不值exp-001--exp-002)。

请求体加 `"contextBudget": false` 可**逐次**关闭（用于同一 JVM 内做对照），见下节"接口清单"。

### `app.manager.remote-agents[*]`（★ 扩展点）

```yaml
app:
  manager:
    remote-agents:
      - name: RouteMakingAgent          # 必须和子 Agent 注册到 Nacos 的 card.name 一致
        tool-name: callRouteMakingAgent  # 暴露给大模型的工具名（可省略，按 name 推导）
        description: 擅长自驾游路线制定   # ★ 这句话直接决定主管会不会把任务派给它
        enabled: true
```

**加一个新 Agent = 加一段配置 + 把新服务启动起来，不用改一行 Java 代码。**

### A2A 服务端（子 Agent 模块）

```yaml
agentscope:
  a2a:
    server:
      enabled: true
      # ★ 必须打开：否则 A2A 任务完成后不带回答文本，主管 Agent 永远拿到空字符串
      complete-with-message: true
      agent-completion-timeout-seconds: 600
      card:
        name: TripPlannerAgent       # 注册到 Nacos 的名称
        description: ...             # 主管 Agent 看到的能力描述
        version: 1.0.0
    nacos:
      server-addr: 127.0.0.1:8848
      registry:
        enabled: true                # 注册自己
        register-as-latest: true
      discovery:
        enabled: false               # 自己不需要去发现别人
```

> `complete-with-message` 是子 Agent 侧最容易漏、又最难排查的一项：
> 漏了之后服务照常启动、Nacos 也照常注册成功，只有真正发起一次 A2A 调用才会发现返回是空的。

---

## 七、常见问题排查

| 现象 | 原因 / 解决 |
|---|---|
| 启动报 `Request Nacos server version is too low` | Nacos 版本 < 3.0，换成 `nacos/nacos-server:v3.1.0` |
| 启动报 `没有读到阿里云百炼 API Key` | `.env` 没填 / 没生效。检查 `commons/src/main/resources/.env`，或设置环境变量 |
| 启动报 `Connection refused: 127.0.0.1:8848` | Nacos 没起来，或 `NACOS_SERVER_ADDR` 写错（注意是 **8848** 不是 8088） |
| 主管 Agent 说"调用远程 Agent 失败" | 先在 Apifox 里调 `GET /api/agents`（8081），看 `registered` 和 `error` 字段 |
| 子 Agent 起来了但 Nacos 里看不到 | 检查子 Agent 的 `agentscope.a2a.nacos.registry.enabled=true`，且 `card.name` 非空 |
| 路线 Agent 说"没有地图工具" | `BAIDU_MAP_MCP_SSE_URL` 没配，或 SSE 地址失效（魔塔实例会过期，重新复制一个） |
| `/.well-known/agent-card.json` 打不开 | 子 Agent 模块缺 `agentscope-a2a-spring-boot-starter` 依赖，或没有 `ReActAgent` 类型的 Bean |
| IDEA 里中文日志乱码 | `Help → Edit Custom VM Options` 加 `-Dfile.encoding=UTF-8`，并设置 `Run/Debug Configurations → VM options` 同样参数 |
| 提示 `--enable-preview` 相关错误 | 本项目**不需要** preview，把 IDEA 的 Language Level 从 `17 (Preview)` 改回 `17` |
| 改了 `.env` 但没生效（`java -jar` 方式） | `.env` 会被打进 `commons.jar`，改完必须重新 `mvn package`。用 IDEA 运行时改 `commons/src/main/resources/.env` 重启即可 |
| 不想把密钥写进文件 | 直接设置同名**环境变量**（`ALIBABA_DASHCOPE_KEY` / `BAIDU_MAP_MCP_SSE_URL` / `NACOS_SERVER_ADDR`），优先级高于 `.env` |
| 端口 8848 被占用 / Nacos 起不来 | 检查是否已有另一个 Nacos（Docker 的和 zip 的不能同时起），先停掉其中一个 |
| Apifox 里请求几十秒就报超时，服务端却显示跑成功了 | Apifox 请求超时没调，改成 300 秒以上（见第五节） |
| Apifox 导入后接口报 404 | 三个 OpenAPI 文件要**分开导入**，不要合并成一个（端口不同） |
| A2A 接口返回 `-32602 Invalid parameters` | 请求体里 `message.kind` 漏了 `"message"`（必填） |
| `/app` 返回 `status: FAILED` 且 error 提示子 Agent 未返回内容 | 检查子 Agent 是否配了 `agentscope.a2a.server.complete-with-message: true`（默认 false 会导致 A2A 不回传文本） |
| error 是 `url error, please check url！` | **模型名与接口端点不匹配**（百炼官方定义）。根因是 AgentScope 原生协议只会对 `qvq*` / 含 `-vl` 的模型名走多模态端点，百炼新增的 `qwen3.8-flash`、`qwen3.8-max`、`qwen3.7-plus` 都不带这两个特征。**解决：把 `DASHSCOPE_PROVIDER` 设为 `openai-compatible`**（本项目默认已开启）。调 `GET /api/diagnose` 可看生效值 |
| 路线 Agent 启动日志出现「BAIDU_MAP_MCP_SSE_URL 格式不对」 | 填的不是 URL。必须是 `https://` 开头的完整 SSE 地址，不能只填令牌字符串（见 `docs/接口测试-Apifox指南.md` 第五节） |
| 日志出现 `Invalid SSE response. Status code: 410` / `Url is expired` | **魔塔社区的 MCP 实例地址已过期**（这类托管地址有有效期）。回 <https://modelscope.cn/mcp> 的「百度地图」页面重新复制一个新的 SSE 地址填进 `.env`，然后重新打包 + 重启 |
| 日志出现 `APP IP校验失败` | 百度地图 AK 的 IP 白名单没放开。托管版 MCP 是从魔塔服务器发起请求的，你无法知道它的出口 IP，所以白名单必须填 `0.0.0.0/0`。改完要等几分钟生效 |
| 路线 Agent 说「没有地图工具 / 数据为估算值」 | 上面两种情况之一。**这是设计好的优雅降级**：即使地图工具不可用，Agent 仍会启动、注册到 Nacos、并如实标注哪些是估算值，而不是崩掉或编造精确数字 |

---

## 八、与课程原版代码的差异（补全清单）

课程原代码能看出骨架，但有 15 处「一跑就挂」的问题，本版本全部修掉；
另外在补做端到端验证时又发现 2 个隐藏更深的坑（第 16、17 条），一共 **17 项**：

| # | 原代码问题 | 影响 | 本版本修改 |
|---|---|---|---|
| 1 | `manager_agent/pom.xml` 用 `--enable-preview` + `source/target 17` | JDK 21 下**直接编译失败**；JDK 17 下编译出的 class 运行时也必须带 preview 参数 | 去掉 preview，统一 `<release>17</release>`，JDK 17/21 均可编译运行 |
| 2 | `ManagerAgentApplication` 的 `@SpringBootApplication` 被注释，main 里写死 prompt | Spring 容器根本没起，`AppController` 形同虚设 | 恢复标准 Spring Boot 应用 + 启动横幅 |
| 3 | `AppController` 写成 `@RequestMapping(name = "/app")` | `name` 是映射名不是路径，**接口根本访问不到** | 改为 `@RequestMapping("/app")` + `@PostMapping`，补请求/响应体 |
| 4 | `AgentUtils` 里 `.apiKey("你的API_KEY")` | 跑不通，且密钥硬编码 | 配置化：`app.agentscope.dashscope.api-key` ← `.env` / 环境变量 |
| 5 | `BaiduMapMCP` 里 `.sseTransport("你的MCP地址")` | 跑不通 | 配置化 `app.baidu-map.mcp-sse-url`，缺失时降级告警而不是崩溃 |
| 6 | `RemoteAgentTool` 里 `agent.call().block()` **不带任何消息**且方法返回 `void` | 远程 Agent 收到空输入，且主管 Agent **拿不到任何结果**，A2A 链路空转 | 把子任务作为消息发出，返回远程回答作为工具结果；网络 IO 挪到 boundedElastic |
| 7 | `planHook` 在 `PostReasoningEvent` 里 `user.call().block()` 读控制台 | **HTTP 场景永久阻塞**，请求挂死 | 改为纯观测（日志 + 轨迹），用户确认下沉为配置项，默认关闭 |
| 8 | `Calculate.sum()` 是无参无返回值的空方法 | 注册进去也调用不了 | 补齐为「求和 / 总预算 / 日均预算 / 油费」四个真实工具 |
| 9 | `skillBox.registration().tool(new Calculate())` **没有 `.apply()`** | AgentScope 的 Registration 是 builder 模式，不 apply 什么都不注册 | 补 `.apply()` |
| 10 | `@Component` 类里写 `@Bean` 方法（lite mode） | 语义不严谨 | 改成标准 `@Configuration(proxyBeanMethods = false)` |
| 11 | `.env` / `application.yml` 放在 commons 里被 jar 依赖 | 多份同名 `application.yml` 在 classpath 上，加载顺序不可控 | 配置下沉到各模块，commons 只保留 `.env` 与自动装配入口 |
| 12 | `commons` 的公共类在 `utils`/`config` 包，不在启动类扫描路径下 | `@Component` 不会被扫描到 | 用 `@AutoConfiguration` + `AutoConfiguration.imports` 官方方式对外提供 Bean |
| 13 | 没有配置 `agentscope.a2a.nacos.registry/discovery.enabled` | 依赖默认值，容易漏配 | 三个模块显式声明，并在 README 说明 |
| 14 | `NacosUtil` 地址写死 `localhost:8848` | 无法换环境 | 从配置/环境变量读取，保留无参兼容方法 |
| 15 | 编译未开启 `-parameters` | `@Tool` 方法参数名变成 `arg0`，**大模型无法正确调用工具** | 父 pom 统一加 `-parameters` |

**补做端到端验证时新发现的两个真 bug（只看启动日志看不出来，必须真正发起一次 A2A 调用才会暴露）：**

| # | 问题 | 影响 | 本版本修改 |
|---|---|---|---|
| 16 | `skillBox.registration().tool(...)` 的用法本身就是错的：AgentScope 的 `SkillBox.Registration.apply()` **要求必须先调 `.skill(xxx)`**（它是用来把工具绑定到某个具体技能的）。课程原代码漏了 `.apply()` 所以静默无效，只补 `.apply()` 则会直接抛 `IllegalStateException: Must call skill() before apply()` | TripPlannerAgent **每次被调用都失败**，主管 Agent 永远拿不到它的结果 | 通用工具改为 `toolkit.registration().tool(new Calculate()).apply()`（全局可用）；代码里写明了想绑定到技能时的正确写法 |
| 17 | A2A 服务端 `agentscope.a2a.server.complete-with-message` **默认为 false**（已在框架字节码中确认 `doOnComplete()` 会因此传 `null` 给 `taskUpdater.complete()`） | A2A 任务只以"完成"状态结束、**不带任何回答文本**，`RemoteAgentTool` 永远拿到空字符串（表现成"子 Agent 没返回内容"） | 两个子 Agent 的 `application.yml` 显式打开 `complete-with-message: true`，并加了 `agent-completion-timeout-seconds: 600` |

> 另外还校验了 A2A JSON-RPC 的报文结构：`message.kind` **是必填字段**，漏掉会直接返回 `-32602 Invalid parameters`。
> 这个坑已经写进 `docs/apifox/` 的接口示例里。

### 本版本新增的能力（课程没有的）

- **REST + SSE 双入口**：同步拿结果 / 流式打字机效果
- **Agent 编排配置化**：`app.manager.remote-agents` 动态把 A2A Agent 包装成工具，新增 Agent 零代码
- **RunTrace 运行轨迹**：Hook 采集推理 + 工具调用全过程，`/api/runs` 可查，为前端可视化和后续评估打基础
- **`/api/agents` 自检接口**：一眼看出哪个子 Agent 没注册上
- **优雅降级**：百度 MCP 不可用时 Agent 仍能启动并注册，调用时返回明确错误提示而不是崩服务
- **prototype 作用域 Agent**：每个 A2A 会话拿到独立实例，避免 Memory 串话与并发报错
- **一键启动脚本**：`run-all.ps1` / `stop-all.ps1`
- **观测链路（T1）**：AgentScope `TelemetryTracer` → OTLP → Langfuse Cloud，trace / LLM 调用 / token 账目均已实测可见
- **单 Agent ↔ 多 Agent 可切换**：`app.manager.mode` 或请求体 `mode`，两条臂可在同一 JVM 内交错运行（EXP-001 对照实验的产物，也是"架构可替换"的演示）
- **上下文预算**：工具结果外置 + 按需取回 + 每次调用的上下文计量（EXP-002，实测 token **−81%**）

> 注：为符合 Java 命名规范，课程里的 `planHook` 类已重命名为 `PlanHook`。

---

## 九、对照实验：多 Agent 到底值不值？（EXP-001 / EXP-002）

> 📌 完整数据、原始回答与复现步骤见 [`docs/experiments/EXP-001/`](docs/experiments/EXP-001/) 与 [`docs/experiments/EXP-002/`](docs/experiments/EXP-002/)。
> 这两个实验是**课程内容之外、我自己发起的**：先量化"多 Agent 架构的代价"，再定位根因并做优化。

### 9.1 设计与公平性（EXP-001）

| 项 | 做法 |
|---|---|
| 对照臂 | **A1** 主管 + 2 个远程子 Agent（现状）；**A2** 单个 Agent 直接持有全部能力（地图 MCP + Skills + 计算 + 计划工具），不做任何派发 |
| 能力对齐 | 把 `BaiduMapMCP`、`skills/`、`Calculate` 从子 Agent 模块**提到 commons 复用**，两臂能力对等 —— 否则测出来的是"有没有工具"而不是"有没有分工" |
| 提示词公平 | 两臂 system prompt 逐字固化在 `prompts.md`，输出结构 / 真实数据纪律 / 并行纪律 / 失败如实性四条逐项对齐，接受审视 |
| 同一 JVM | 请求级 `mode` 开关，两臂在**同一次启动内交错运行**，避免 JIT 预热差异污染延迟对比 |
| 样本 | 3 个 prompt × 2 臂 × n=2 = 12 次运行 |
| 指标 | token（Langfuse Metrics v2）、端到端耗时、LLM 调用数、盲评质量（5 维 rubric、去标签随机顺序、3 次取中位数） |

### 9.2 结论一：质量打平，但单 Agent **贵 2.2 倍**

| 指标 | A1 多 Agent | A2 单 Agent |
|---|---|---|
| 质量（盲评 /25） | 21.33 | 21.17 |
| token 均值 | **258,435** | **569,371** |
| token 区间 | 155k ~ 417k（2.7×） | 92k ~ **1,925k**（**21×**） |
| 耗时均值 | 57s | **44s**（快 23%） |

逐题胜负 3 平 2 胜 1 负，**无一方占优**。

> **关键判断：多 Agent 省的不是"分工"，是"上下文隔离"。**
> 每个子任务的重上下文只活在对应子 Agent 的进程里，主管只拿被截断的结论（`remote-result-max-chars: 8000`）。
> 另外质量维度上出现了一个交换：多 Agent 的**如实性**更好（4.33 vs 3.33，二次汇总起了复核作用），
> 单 Agent 的**可执行性**更好（4.17 vs 3.33，少一层缝合就没有"路线表与每日行程对不上"的矛盾）。

### 9.3 定位根因：**单条巨大工具结果长期驻留**

用自己写的上下文计量器逐次打印输入 token：

| 第几次 LLM 调用 | 输入 token | 说明 |
|---|---|---|
| #1 ~ #4 | 5,041 → 8,133 | 正常起步 |
| **#5** | **167,976** | ★ 一条地图返回（约 16 万 token）进入上下文 |
| #6 ~ #9 | ~21.5 万 / 次 | **之后每一次调用都要重发它** |

> 这修正了最初"累积了大量工具结果"的模糊判断 —— 真实形态是**少数几条超大结果长期驻留**，
> 所以优化要打的是"单条巨物"，不是"总量"。

### 9.4 优化（EXP-002）：工具结果外置 + 按需取回

- **机制 B**：装饰 AgentScope 的 `McpClientWrapper`，超长工具返回存进 run 级 artifact store，
  上下文里只留摘要 + id；Agent 需要细节时用 `read_artifact` / `read_artifact_range` **按需取回**
- **机制 D**：装饰 `Model`，打印每次调用的真实 token 与构成（固定 schema 占比 vs 历史占比）——
  先让优化可被验证，再谈优化

| 档 | token | 耗时 | LLM 调用 | 盲评 |
|---|---|---|---|---|
| 基线（不外置） | 1,073,065 | 84s | 9 | 22 |
| **外置开启** | **200,209（−81%）** | 106s | 11 | **24** |

> ⚠️ **保留**：**n=1，是强信号而非结论**（EXP-001 已显示单 Agent 方差可达 21 倍）。
> 确认轮与下一个机制（**schema 按需挂载** —— 计量器显示固定开销占首轮输入 **78%**）见 [`docs/STATUS.md`](docs/STATUS.md)。

### 9.5 继续优化：工具按需挂载（机制 A）与历史压缩（机制 C）

| 机制 | 做法 | 实测结果 |
|---|---|---|
| **A 工具按需挂载** | 用一次**143 token** 的极小模型调用判断该需求是否需要真实地图数据，不需要就不挂那 10 个地图工具（分类失败按"需要"兜底）；未挂地图时系统提示词同步追加"标注估算值、不要调用不存在的工具" | 非地图需求：工具数 **25–28 → 15**，首轮输入 **5,311 → 3,025（−43%）**；P1 这类确实要算路的需求判定为"需要"，**没有误杀** |
| **C 历史压缩** | 消息总字符超阈值时压缩较早的 `TOOL` 消息；**只改发给模型的内容、不动 Agent Memory**，只压 `TOOL` 角色，`metadata` 只保留小字段（保住工具调用配对信息） | **当前负载下不触发** —— 历史字符峰值 8,530 < 阈值 30,000。其前提（历史文本膨胀）已被 B 覆盖 |

> 机制 C 这条本身就是个结论：**优化要针对实测到的膨胀形态，而不是"看起来应该会膨胀的地方"。**
> 另外还留了一个诚实的缺口：计量器报告的单轮输入（27,157 token）比可计量内容多约 1.7 万 token，
> 最可能是**工具 schema 的真实 JSON 体积远大于估算** —— 已记录为下一步第一件要验证的事。

### 9.6 把预算补到多 Agent 臂后，**结论被改写**（这是整段工作最关键的一步）

机制 B 最初只挂在单 Agent 臂上，而**多 Agent 臂里唯一的 MCP 使用者是路线子 Agent** —— 它持有同样的地图工具，
会拿到同样的"单条巨物"。补上之后，子 Agent 侧的实测病灶暴露得非常直白：

```
map_directions 原长  79,539 字符 → 摘要 991
map_directions 原长 104,209 字符 → 摘要 992      ← 单条 10 万字符
（共 7 次外置，合计约 47 万字符）
```

**同题 P1 的最终同台对比**（权威值，MCP 健康）：

| 配置 | token | 耗时 |
|---|---|---|
| multi 无预算 | 409,253（n=2） | 53s |
| **multi + 结果外置** | **136,788（n=2）** | 162s（117 / 208） |
| single + B | 194,086（n=2） | 105s |
| single + A+B+C | 186,494（n=1） | 67s |

**token 排序：`multi+预算 137k` ＜ `single+预算 186k` ＜ `single+B 194k` ≪ `multi 无预算 409k`**

> ### ★ 真正的结论
> 1. **没有上下文管理时**，单 Agent 因为"工具并集 + 巨物驻留"而贵 2.2~3.1 倍，看起来像"架构劣势"；
> 2. **两臂都装上预算后，多 Agent 重新成为最省的**（低约 29%）—— 它的**上下文隔离是真实优势**：
>    每种子任务的重上下文只活在对应子 Agent 的进程里；
> 3. 所以正确的结论不是"哪种架构更好"，而是：
>    **架构差异在缺乏上下文管理时会被成本假象掩盖；把两边都治好，才能看清各架构的真实成本。**
>
> 进度上这比"单 Agent 更省"值钱得多：它同时否掉了自己的前一个结论，并给出了原因。

### 9.7 这段经历能讲什么

1. **会设计对照实验**：控制变量（同构建 / 同 JVM / 交错）、公平性（能力对齐 + 提示词固化）、盲评、主动声明局限
2. **会做可观测性**：先补上"能测到你要优化的那个指标"的计量器 —— 这条本身就是项目里踩出来的教训
3. **会定位根因而不是拍脑袋**：从"贵 2.2 倍"一路追到"单条 16 万 token 的结果驻留"，
   中途还推翻过自己两次判断（一次是拿降级状态的数据当基线，一次是查错接口误判"token 账目缺失"）
4. **会把优化做成产品能力并验证**：−81% 且质量不降，**代价（延迟 +26%）也如实写出来**

---

## 十、后续可以怎么魔改 / 迭代（写简历用）

> 📌 **本节是「可以做什么」的灵感清单，不是已排期的计划。**
> **当前状态、下一步要做的事、已知缺陷**一律以 [`docs/STATUS.md`](docs/STATUS.md) 为准 —— 两者冲突时以那份为准。

按投入产出比排序：

1. **多轮对话 + 会话记忆**
   目前每次请求都是独立运行。可引入 `sessionId` → `InMemoryMemory`/持久化 Memory，让用户能追问"把第二天改成温泉"。

2. **前端页面**
   已经有 SSE 接口，接一个 Vue/React 页面，把 `RunTrace` 的步骤渲染成"思考中 → 调用 RouteMakingAgent → 完成"的时间线，展示效果直接拉满。

3. **人机确认（Human-in-the-loop）**
   把 `need-user-confirm` 打开，配合 AgentScope 的 `ToolResultBlock.suspended` / 中断恢复能力，
   做成"Agent 生成计划 → 用户点确认/修改 → 继续执行"。

4. **更多专业 Agent**
   照着 `tripPlanner_agent` 复制一个 `hotel_agent`（酒店比价）、`food_agent`（美食）、`budget_agent`（预算），
   只在 `app.manager.remote-agents` 加配置即可接入。展示"系统的可扩展性"。

5. **MCP 工具超市**
   再接 1~2 个 MCP Server（高德地图、12306、天气），做多 MCP 并存与工具冲突处理。

6. **持久化与评估**
   `RunTrace` 落 MySQL/Elasticsearch，统计成功率、平均轮数、平均耗时、工具调用分布，
   做一版"Agent 运行质量看板"。

7. **可靠性**
   远程 Agent 调用加**重试 + 熔断**（Resilience4j），Nacos 拉不到卡片时降级为配置直连地址。

8. **安全**
   Manager 入口加鉴权；`.env` 换成 Nacos 配置中心 / KMS 托管密钥。

---

## 十一、目录结构

```
AiTripPlan-AgentScope/
├── pom.xml                                  父 pom：依赖管理 + 统一编译配置
├── run-all.ps1 / stop-all.ps1               一键启停脚本
├── README.md
├── docs/
│   ├── apifox/                              ★ 可直接导入 Apifox 的 OpenAPI 集合（三个服务各一份）
│   │   ├── 01-manager-agent-8081.openapi.json
│   │   ├── 02-tripPlanner-agent-8085.openapi.json
│   │   └── 03-routeMaking-agent-8082.openapi.json
│   └── 接口测试-Apifox指南.md                ★ 导入步骤 / 测试顺序 / 断言 / 排查表
├── commons/                                 公共模块
│   └── src/main/
│       ├── java/config/                     @ConfigurationProperties + @AutoConfiguration
│       ├── java/utils/                      AgentUtils / NacosUtil / ToolUtils
│       └── resources/.env                   密钥（已 gitignore）
├── manager_agent/                           主管 Agent（8081）
│   └── src/main/java/managerAgent/
│       ├── ManagerAgentApplication.java
│       ├── agents/                          ManagerAgent / AgentCatalogService
│       ├── config/                          ManagerAgentProperties
│       ├── controller/                      AppController(/app,/app/stream) + OpsController(/api)
│       ├── hook/                            PlanHook + TraceHook
│       ├── plan/                            TripPlan（PlanNotebook 自定义）
│       ├── tool/                            RemoteAgentTool（A2A Agent → LLM 工具）
│       └── trace/                           RunTrace / RunTraceRegistry
├── routeMaking_agent/                       路线制定 Agent（8082）
│   └── src/main/java/routeMakingAgent/
│       ├── config/                          RouteMakingAgentConfiguration + BaiduMapProperties
│       └── mcp/                             BaiduMapMCP（SSE 接入）
└── tripPlanner_agent/                       行程规划 Agent（8085）
    └── src/main/
        ├── java/tripPlannerAgent/
        │   ├── config/                      TripPlannerAgentConfiguration
        │   ├── agents/                      SuggestSightAgent（Skills + 子 Agent）
        │   └── tool/                        Calculate
        └── resources/skills/                Suggest-Sights / Make-A-Table（SKILL.md + scripts）
```

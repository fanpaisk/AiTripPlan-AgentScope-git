# 状态（STATUS）

> 本文件是**当前快照**，覆盖式更新。历史与理由见 `DECISIONS.md`；开工先读 `AGENTS.md`。

最后更新：2026-10-05
最后验收：2026-10-05（**在本仓库目录当场重跑，输出见第一节**）

---

## 一句话

**端到端可用；观测链路（T1）已完全闭环 —— trace、LLM 调用、token 账目三档全部实测可见。**
三个服务由本仓库构建并运行，百度地图 MCP 当前有效（10 个工具，直接调 8082 拿到真实里程），工具并行生效。
**对照实验 EXP-001 已完成**：单 Agent 与多 Agent 质量打平，但单 Agent 贵 2.2 倍（详见 `docs/experiments/EXP-001/report.md`）。
**上下文优化 EXP-002 第一轮完成**：工具结果外置使单 Agent token **降低 81%**（1,073,065 → 200,209）且质量未降，
**但 n=1，属强信号而非结论**（详见 `docs/experiments/EXP-002/report.md`）。

---

## 一、本次验收（当场重跑，非记忆）

构建与运行环境：`JAVA_HOME=G:\jdk-21`（父 pom `<release>17</release>`）· Nacos 3.1.0（Docker，standalone，Up）· 三个 JVM 各 `-Xmx512m`

| # | 验收项 | 命令 | 当场实测输出 |
|---|---|---|---|
| 1 | 构建 | `mvn -B -s build-settings.xml clean package -DskipTests` | **BUILD SUCCESS**；`commons / manager_agent / routeMaking_agent / tripPlanner_agent` 全 SUCCESS |
| 2 | 启动 | `.\run-all.ps1 -SkipBuild` | 三个服务全部 `[就绪]`（8081 / 8082 / 8085） |
| 3 | 健康检查 | `GET /api/health` | `{"service":"manager-agent","registeredAgents":2,"status":"UP"}` |
| 4 | 配置自检 | `GET /api/diagnose` | `provider=OPENAI_COMPATIBLE`、`model=deepseek-v4-flash`、`baseUrl=https://api.deepseek.com`、`stream=true`、`hints=[]`、超时 `PT15M/PT17M/PT45M` |
| 5 | 子 Agent 注册 | `GET /api/agents` | `RouteMakingAgent registered=true http://127.0.0.1:8082`；`TripPlannerAgent registered=true http://127.0.0.1:8085` |
| 6 | **端到端** | `POST /app`，body `{"prompt":"帮我规划深圳到惠州3日游，涵盖路线和行程"}` | **HTTP 200 / SUCCESS**、**216s**、`answer` **3220 字**且完整收尾、`runId=be15d924e36a4f2a`；`parallelismSummary = 并发 4 个工具，墙钟 62399ms，各工具耗时之和 89678ms → PARALLEL（完全并行，2 个并发，加速比 1.44x）` |
| 7 | **地图工具挂载** | `POST 127.0.0.1:8082/`（A2A 集合里的「验证地图工具」用例） | **HTTP 200、12s**，返回**真实数据**：`88981 m ≈ 89.0 km`、`5386 s ≈ 1h30m`、坐标 `114.4047, 23.0957`；日志 `已挂载的工具` = **10 个 `map_*`** |
| 8 | **观测 T1 · trace** | `GET https://cloud.langfuse.com/api/public/v2/observations?fromStartTime=…` | **56 条** observation（SPAN 16 / GENERATION 16 / TOOL 18 / AGENT 6）、**6 个 trace**；每条带 `version=1.0.0` 与 `environment=local` —— 这两个值由代码里的 `Resource` 显式写入，可确认数据来自本应用 |
| 9 | **观测 T1 · token 账目** | `GET https://cloud.langfuse.com/api/public/v2/metrics?query={"view":"observations","metrics":[{"measure":"totalTokens","aggregation":"sum"}],…}` | **85,063 tokens**（输入 72,203 / 输出 12,860）；按模型维度归到 `providedModelName=deepseek-v4-flash` |
| 10 | 观测开启（反例：降级路径） | 三个服务日志 | `[tracing] trace 上报已开启（AgentScope → Langfuse）`，且 `endpoint = https://cloud.langfuse.com/api/public/otel/v1/traces`（Key 缺失时才是「跳过 trace 上报」） |

> 第 6 项那次 E2E 里**路线子任务失败了**：当时百度 MCP 返回 `410 {"error":{"message":"Url is expired"}}`，回答正文也写了"路线失败"。到第 7 项复测时该地址已恢复（见第四节 D1）。第 6 项的原始响应存于 `logs\t1-e2e.json`（`logs\` 不入版本控制）。

---

## 二、已完成（已验收）

- **代码补全**：课程原代码 17 项缺陷全部修复，逐条见 `README.md` 第八节。
- **模型层厂商中立**：`app.agentscope.llm.*` 三行配置换厂商（现用 DeepSeek 官方，OpenAI 兼容协议）。
- **工具并行**：`tool-parallel: true` 已确认生效（日志 `[ToolUtils] 创建 Toolkit: parallel=true`），实测加速比 1.44x ~ 1.99x。
- **应用内可观测**：`GET /api/runs/{runId}` 返回逐步轨迹与 `toolBatches` 并行度报告（工具耗时由 `RemoteAgentTool` 自报）。
- **自检接口**：`/api/health`、`/api/diagnose`（`?deep=true` 会真打一次模型，约 1s，返回 `deepCheck:{ok:true,elapsedMillis}`）、`/api/agents`。
- **端到端限堆**：`run-all.ps1` 默认 `-Xmx512m` + `-XX:+ExitOnOutOfMemoryError`。
- **Apifox 接口集合**：`docs/apifox/*.openapi.json` 三份（8081 / 8082 / 8085），导入即用。
- **开源化**：MIT LICENSE、`.gitattributes`、密钥与本地路径全部不进版本控制（`git check-ignore` 逐条验证）。
- **观测链路 T1（2026-10-05 闭环）**：`TelemetryTracer` → OTLP → Langfuse Cloud(EU)，trace + LLM 调用 + token 账目三档全部实测可见。实现要点与两个坑见 `DECISIONS.md` D-013 / D-017 / D-018。
- **对照实验 EXP-001（2026-10-05 完成）**：3 prompt × 2 臂 × n=2 = 12 次运行，全部 SUCCESS。结论：**质量打平（21.33 vs 21.17 / 25）**，但**单 Agent 贵 2.2 倍**（569,371 vs 258,435 token）且方差达 21 倍，快 23%。完整报告 `docs/experiments/EXP-001/report.md`。
  - 为此新增的能力（可复用，不属于实验一次性代码）：`commons` 的 `mcp.BaiduMapMCP` / `config.BaiduMapProperties` / `tools.Calculate` / `utils.SkillUtils` 与 `resources/skills` 全部提到公共模块；`manager_agent` 支持 `mode=multi|single`（配置默认 + 请求级覆盖）。
- **上下文预算 EXP-002 机制 B+D（2026-10-05 完成并验证）**：工具结果外置（装饰 `McpClientWrapper`）+ 按需取回（`read_artifact`）+ 每次调用的上下文计量（装饰 `Model`）。筛选轮实测 token **−81%**、盲评质量 **+2**、延迟 **+26%**。报告 `docs/experiments/EXP-002/report.md`。
  - 一键回到引入前行为：`app.agentscope.context.enabled=false`；请求级用 `"contextBudget": false`。

## 三、半成品 / 未开始

| 项 | 状态 | 说明 |
|---|---|---|
| **EXP-002 确认轮**（n=2~3 复跑 off/on） | ⚠️ **未做（下一步第一件）** | 筛选轮 n=1 得到 −81%，但 EXP-001 已证明单 Agent 方差可达 21 倍，**必须复跑才能当结论** |
| **机制 A：工具 schema 按需挂载** | ❌ 未开始 | 靶子已由计量器量出：固定开销（25–28 个工具的 schema）约占首轮输入的 **78%** |
| 机制 C：历史压缩 | ❌ 未开始 | 排在 A/B 之后 —— 前两条已把曲线压平，其边际收益需重新评估 |
| 前端页面 | ❌ 未开始 | SSE 接口 `POST /app/stream` 已就绪 |
| 人机确认（Human-in-the-loop） | ❌ 未开始 | `need-user-confirm` 已有配置项，HTTP 场景需另行设计 |

---

## 四、已知缺陷与未决问题

| # | 问题 | 影响 / 验证入口 |
|---|---|---|
| D1 | **百度地图 MCP 地址会失效**（实测 `410 {"error":{"message":"Url is expired"}}`）。**当前有效**：2026-10-05 09:14 那次 E2E 失效 → 09:34 复测 HTTP 200 并拿到真实里程。说明它**会恢复**（或随实例续期），**不能凭一次失败定性**，也不要照抄旧结论说它"约 1 天必过期" | 失效时路线分支失败（里程/耗时退化），但服务照常启动、注册照常成功。验证：`POST 127.0.0.1:8082/`（A2A）后搜 `logs\routeMaking_agent.out.log` 的 `已挂载的工具`，正常应有 10 个 `map_*` |
| D2 | **Agent 会编造失败原因** | 没挂载工具时它把原因说成「APP IP 校验失败」，实际是 URL 过期。对比 D1 的日志与回答正文即可看出 |
| ~~D3~~ | ~~观测链路在 Langfuse 里的渲染未验证~~ → **已解决（2026-10-05）** | 见第一节 #8 / #9：56 条 observation 与 85,063 tokens 实测可见。历史条目保留在 `DECISIONS.md` 的「待验证」记录里 |
| D4 | `GET /api/agents` 用 Windows PowerShell 5.1 读会乱码 | **不是服务缺陷**：响应头 `application/json` 无 charset，PS 5.1 按 ISO-8859-1 解码；字节是合法 UTF-8，Apifox / 浏览器 / Postman 正常。**另一条本机限制**：本机 `Invoke-WebRequest` 是 PS 5.1，**不支持 `-SkipHttpErrorCheck`** —— 要读 4xx 的响应体得用 `$_.Exception.Response.GetResponseStream()`（排查 Langfuse 410 时踩到） |
| D5 | **trace 上报会外传业务内容** | 单次请求 OTLP 载荷可达 488 KB，含 prompt、工具参数、模型输出，且发往境外（EU 爱尔兰）。不想外传把 `LANGFUSE_TRACING_ENABLED` 设为 `false` |
| D6 | **Langfuse 旧读取接口在 Cloud 上同样已 410**（原记录只提到自托管 v4 与 v3 读取 API 不一致） | `GET /api/public/traces`、`GET /api/public/observations` 返回 **410 Gone**；`/api/public/v2/traces` 是 404。必须改用 **v2**：`/api/public/v2/observations`（trace/span）与 `/api/public/v2/metrics?query=…`（token）。**别把 410 当成「没有数据」** |
| D7 | **【新】应用把百度 MCP 的完整地址（含实例 ID）明文写进 INFO 日志** | `logs\routeMaking_agent.out.log` 里那行 `正在连接百度地图 MCP Server(SSE)：https://mcp.api-inference.modelscope.net/<实例ID>/sse`。分享日志 = 泄露该能力 URL。`logs\` 已在 `.gitignore` 内，但转发日志前要留意 |
| D8 | **【新 · 未定性】直接向 8082 发 A2A 时，返回文本混入模型自身的中间规划文字** | 第 7 项验收的回答开头是 `The data returned… Give output structure. Keep under 1500 words… Let me quickly check real-ti`。仅一次观测，**未确认**属于缺陷还是该子 Agent 的输出风格；重验入口同上（`POST 127.0.0.1:8082/`） |

### 路径与证据

- 本仓库是**独立副本**（D-015），与开发机上另一个无版本控制的代码目录**不联动**。
- 历史验收的原始输出（启动日志、OTLP 接收器日志等）存放在**开发机的兄弟目录**里，**不在本仓库内**；第一节已把关键数值与命令抄录下来，通常够用。
- `build-settings.xml`（含各人机器上的本地仓库绝对路径，因人而异）与 `commons/src/main/resources/.env`（含密钥）**不在版本控制内**。新环境从 `.env.example` 复制出 `.env` 再填 Key。
- 本次 E2E 的原始响应：`logs\t1-e2e.json`。

---

## 五、下一步

### T2：单 Agent vs 多 Agent 对照实验 —— ✅ **已完成（2026-10-05）**

报告：`docs/experiments/EXP-001/report.md`（设计 `design.md`、提示词 `prompts.md`、原始数据 `raw/` + `results.csv` + `score.csv`）。

**结论速览**：质量打平（21.33 vs 21.17 / 25，6 对里 3 平 2 胜 1 负）；单 Agent 贵 **2.2 倍**（均值 569,371 vs 258,435 token）且方差 **21 倍**（92,390~1,924,552）；单 Agent **快 23%**（44s vs 57s）。根因是多 Agent 提供了**上下文隔离**，不是"分工"。

**遗留的下一步**：`report.md` §3 的三条上下文优化（裁 schema / 工具结果摘要化 / 历史滑窗），做完值得再跑一轮对比 —— 这是 EXP-002 最自然的选题。

### T2.5：上下文预算 EXP-002 —— ⚠️ **进行中（机制 B+D 已完成，待确认轮）**

报告：`docs/experiments/EXP-002/report.md`（含逐次调用曲线这一关键证据）。

**已做**：机制 B（工具结果外置 + 按需取回）、机制 D（上下文计量）。筛选轮 n=1/档：token 1,073,065 → 200,209（**−81%**），盲评 22 → 24，耗时 84s → 106s。

**下一步第一件（必须）**：**确认轮 n=2~3** 复跑 off/on 两档 —— n=1 不足以对抗单 Agent 的 21 倍方差。
落点：`.\tools\experiment-run.ps1 -Arm single -Budget off,on -Prompts P1 -Reps 2 -OutDir 'docs\experiments\EXP-002'`，跑完**必须**再执行 `-Recollect` 取权威 token（摄取延迟会造成 2 倍以上低估，见 D-021）。

**之后**：机制 A（schema 按需挂载）。靶子已被计量器量出 —— 固定开销占首轮输入 **78%**，这是"增量已治好、该治底价"的阶段。

### T3：D1 的稳定性观察（低成本，可与任何实验并行）

百度 MCP 地址既会失效也会恢复（D1）。建议每次跑完 E2E 顺手搜一次 `已挂载的工具`，把「有效 / 失效」与时间点记进本节或第四节 D1 行，判断它是「固定时长过期」还是「随机抖动」。**在此之前不要在文档里断言它的有效期规律。**

---

## 六、环境速查

| 组件 | 位置 / 端口 | 启停 |
|---|---|---|
| 三个 Spring 服务 | 8081 / 8082 / 8085 | 先 `.\stop-all.ps1`，再 `.\run-all.ps1`（`-SkipBuild` 跳打包，`-HeapMb 768` 调堆，`-Only manager\|route\|planner` 单起） |
| Nacos 3.1.0 | Docker 容器 `nacos`；8848（RPC，代码连这个）/ 8088（控制台，账号密码均 `nacos`） | `docker start nacos` / `docker stop nacos` |
| Langfuse Cloud（观测，现用） | EU：`https://cloud.langfuse.com`；OTLP 摄取 `/api/public/otel/v1/traces`；读取 `/api/public/v2/observations`、`/api/public/v2/metrics` | 无需本地进程 |
| Langfuse 自托管（**备用**，D-014） | 编排文件在**开发机的兄弟目录**（形如 `<兄弟目录>\.langfuse\docker-compose.yml`），**不在本仓库内**；6 个容器，数据卷保留 | 2026-10-05 实测：起容器后约 15 秒内全部退出（web 143 / worker 137）。本机总内存 15.2 GB、**当时可用仅 2.0 GB**，不要在这台机器上指望它 |
| OTLP 接收器（排查用） | `tools\otlp-sink.ps1`（只统计字节数，默认 127.0.0.1:4318）；`tools\otlp-capture.ps1`（**保存报文**供 grep 属性名，默认 127.0.0.1:4319） | 用法与恢复步骤见 `DECISIONS.md` D-020 |

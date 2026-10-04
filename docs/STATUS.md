# 状态（STATUS）

> 本文件是**当前快照**，覆盖式更新。历史与理由见 `DECISIONS.md`；开工先读 `AGENTS.md`。

最后更新：2026-10-04
最后验收：2026-10-04（**在本仓库目录当场重跑，输出见第一节**）

---

## 一句话

**端到端可用，且跑在「公开仓库副本」上。** 三个服务从本仓库构建并运行，百度地图真实算路打通（10 个工具），工具并行生效（加速比 1.7x）。
**唯一未闭环的是观测链路的最后一环**：AgentScope → OTLP 导出已验证，但在 Langfuse 里的**渲染**未验证（缺 Cloud Key）。

---

## 一、本次验收（当场重跑，非记忆）

构建与运行环境：JDK 17 · Nacos 3.1.0（Docker，standalone）· 三个 JVM 各 `-Xmx512m`

| # | 验收项 | 命令 | 当场实测输出 |
|---|---|---|---|
| 1 | 构建 | `mvn -B -s build-settings.xml clean package -DskipTests` | **BUILD SUCCESS**；`commons / manager_agent / routeMaking_agent / tripPlanner_agent` 全 SUCCESS |
| 2 | 启动 | `.\run-all.ps1 -SkipBuild` | 三个服务全部 `[就绪]`（8081 / 8082 / 8085） |
| 3 | 健康检查 | `GET /api/health` | `{"status":"UP","registeredAgents":2,"service":"manager-agent"}` |
| 4 | 配置自检 | `GET /api/diagnose` | `provider=OPENAI_COMPATIBLE`、`model=deepseek-v4-flash`、`baseUrl=https://api.deepseek.com`、`hints=[]` |
| 5 | 子 Agent 注册 | `GET /api/agents` | `RouteMakingAgent registered=true url=http://127.0.0.1:8082`；`TripPlannerAgent registered=true url=http://127.0.0.1:8085` |
| 6 | **端到端** | `POST /app`（body `{"prompt":"帮我规划深圳到惠州3日游，涵盖路线和行程"}`） | **HTTP 200 / SUCCESS**，**66s**，`answer` **4683 字**且完整收尾；`parallelismSummary = 并发 4 个工具，墙钟 39438ms，各工具耗时之和 67173ms → PARALLEL（完全并行，2 个并发，加速比 1.7x）` |
| 7 | **地图工具挂载** | 测试后搜 `logs\routeMaking_agent.out.log` | `已挂载的工具：[map_geocode, map_weather, map_search_places, map_ip_location, map_poi_extract, map_place_details, map_directions_matrix, map_directions, map_road_traffic, map_reverse_geocode]` —— **10 个** |
| 8 | 观测降级路径 | 同上日志 | `[tracing] 没有读到 Langfuse 的 Key，跳过 trace 上报（不影响业务）`，服务正常 |

> 备份此 JSON 到 `.probe\t1-e2e.json` 与 `.probe\baidu-verify\e2e.json`（在**开发机**的兄弟目录，不在本仓库内 —— 见第四节「路径与证据」）。

---

## 二、已完成（已验收）

- **代码补全**：课程原代码 17 项缺陷全部修复，逐条见 `README.md` 第八节。
- **模型层厂商中立**：`app.agentscope.llm.*` 三行配置换厂商（现用 DeepSeek 官方，OpenAI 兼容协议）。
- **工具并行**：`tool-parallel: true` 已确认生效（`[ToolUtils] 创建 Toolkit: parallel=true`），实测加速比 1.59x ~ 1.99x，3-Agent 端到端 156s → 63~70s。
- **应用内可观测**：`GET /api/runs/{runId}` 返回逐步轨迹与 `toolBatches` 并行度报告（工具耗时由 `RemoteAgentTool` 自报，Hook 拿不到单工具耗时）。
- **自检接口**：`/api/health`、`/api/diagnose`（`?deep=true` 会真打一次模型）、`/api/agents`。
- **端到端限堆**：`run-all.ps1` 默认 `-Xmx512m` + `-XX:+ExitOnOutOfMemoryError`，三个 JVM 工作集合计约 700 MB。
- **Apifox 接口集合**：`docs/apifox/*.openapi.json` 三份（8081 / 8082 / 8085），导入即用。
- **开源化**：MIT LICENSE、`.gitattributes`、密钥与本地路径全部不进版本控制（已用 `git check-ignore` 逐条验证）。

## 三、半成品 / 未开始

| 项 | 状态 | 说明 |
|---|---|---|
| **观测链路最后一环** | ⚠️ **代码就绪，渲染未验证** | 见第五节 T1 |
| 单 Agent vs 多 Agent 对照实验 | ❌ 未开始 | **不被 T1 阻塞**（核心指标 `RunTrace` 已能给），见第五节 T2 |
| 前端页面 | ❌ 未开始 | SSE 接口 `POST /app/stream` 已就绪 |
| 人机确认（Human-in-the-loop） | ❌ 未开始 | `need-user-confirm` 已有配置项，HTTP 场景需另行设计 |

---

## 四、已知缺陷与未决问题

| # | 问题 | 影响 / 验证入口 |
|---|---|---|
| D1 | **百度地图 MCP 地址约 1 天过期**（过期报 `410 / Url is expired`） | 路线 Agent 会**没有地图工具**，里程/耗时退回估算值，但服务照常启动、注册照常成功。验证：跑一次 `POST /app` 后搜 `logs\routeMaking_agent.out.log` 的 `已挂载的工具`，正常应有 10 个 `map_*` |
| D2 | **Agent 会编造失败原因** | 没挂载工具时它把原因说成「APP IP 校验失败」，实际是「Url is expired」。对比 D1 日志与回答正文即可看出 |
| D3 | **观测链路在 Langfuse 里的渲染未验证** | 见 T1。已知的是「应用侧确实在导出 span」（`tools/otlp-sink.ps1` 收到 7 次 `application/x-protobuf`，最大 488 KB） |
| D4 | `GET /api/agents` 用 Windows PowerShell 5.1 读会乱码 | **不是服务缺陷**：响应头 `application/json` 无 charset，PS 5.1 按 ISO-8859-1 解码。字节是合法 UTF-8，**Apifox / 浏览器 / Postman 正常**。复核：取 `RawContentStream` 字节后按 UTF-8 解码 |
| D5 | **trace 上报会外传业务内容** | 单次请求 OTLP 载荷可达 488 KB，含 prompt、工具参数、模型输出，且发往境外 Cloud。不想外传把 `LANGFUSE_TRACING_ENABLED` 设为 `false` |
| D6 | 本地自托管的 Langfuse 读取 API 与 v4 存储不一致 | v4 数据落在 ClickHouse `events_core` / `events_full`，`/api/public/traces` 返回 404。用自托管时直接查库，别以为「没数据」 |

### 路径与证据

- 本仓库是**独立副本**，与开发机上的另一个代码目录**不联动**（那个目录没有版本控制）。
- 历史验收的原始输出（`e2e.json`、启动日志、OTLP 接收器日志）存放在**开发机的兄弟目录**里，**不在本仓库内**。若需要它们，去开发机上取；本文件第一节已把关键数值抄录下来，通常够用。
- `build-settings.xml` 与 `commons/src/main/resources/.env` **不在版本控制内**（前者含各人本地仓库绝对路径，后者含密钥）。新环境从 `.env.example` 复制出 `.env` 再填 Key。

---

## 五、下一步

### T1：补上观测链路的最后一步（需要 Langfuse Cloud 的 Key）

**落点**：`commons/src/main/resources/.env` 的 C 段。

```properties
LANGFUSE_PUBLIC_KEY=pk-lf-...
LANGFUSE_SECRET_KEY=sk-lf-...
```

**步骤**：填 Key → `mvn -B -s build-settings.xml clean package -DskipTests`（`.env` 会被打进 jar，**必须重新打包**）→ `.\run-all.ps1 -SkipBuild` → 跑一次 `POST /app` → 去 `https://cloud.langfuse.com` 的 Tracing 列表确认。

**验收标准**：Langfuse 里出现该 trace，且能看到 LLM 调用与 token 账目。
**已知可用的中间环节**：应用侧确实在导出 span（见 D3 的验证方式）。
**仍未验证**：span 能否被 Langfuse **正确渲染**（这一档是「代码就绪但未实测」，不是「已验收」）。

> `UNKNOWN：AgentScope 导出的 OTel span 在 Langfuse 里能否被正确渲染成 trace 与 token 账目 —— 需要先拿到 Langfuse Cloud 项目的 Public/Secret Key 才能验证（Key 由项目所有者提供，不入库）`

**若渲染不出来怎么排查**：先用 `tools/otlp-sink.ps1` 分段定位，别一上来就怀疑 Key ——
起接收器（`powershell -File tools\otlp-sink.ps1`），把 `.env` 的 `LANGFUSE_OTLP_ENDPOINT` 临时改成
`http://127.0.0.1:4318/api/public/otel/v1/traces`（Key 填任意非空值），重新打包 + 重启 + 跑一次 `POST /app`：
收不到 `bytes>0` → 应用侧没产生 span（查 `commons/pom.xml` 的 OTel 依赖，见 `AGENTS.md` 硬约束 11）；
收得到 → 应用侧没问题，问题在端点 / Key / 网络。
**⚠️ 破坏性验证的恢复步骤**：验完必须把 `LANGFUSE_OTLP_ENDPOINT` 改回 `https://cloud.langfuse.com/api/public/otel/v1/traces`，并**重新打包 + 重启**，否则会一直往本地接收器发。

### T2：单 Agent vs 多 Agent 对照实验

**前提**：不依赖 T1（耗时 / `toolBatches` / 步数 / 状态 `RunTrace` 都能给）。
**落点**：`manager_agent` 加配置开关（建议 `app.manager.mode=single|multi`），复用现有 `AppController` 与 `RunTrace`，这样能用同一套 Apifox 用例跑两组。
**测量维度（待确认）**：完成质量（人工/LLM 打分）、端到端耗时、`toolBatches` 并行度、模型调用次数与 token、失败率。

**两条必须注意的**：
- ⚠️ **公平性**：多 Agent 的答案由主管 Agent **二次汇总**过，单 Agent 没有这一层。不控制这个差异（例如让单 Agent 也走一次复述整理），结论会被「多一次汇总」污染。
- ⚠️ **成本**：一次完整 3-Agent 流程约 30~50 次模型调用，跑两组 × 多轮 prompt 前先估额度。

---

## 六、环境速查

| 组件 | 位置 / 端口 | 启停 |
|---|---|---|
| 三个 Spring 服务 | 8081 / 8082 / 8085 | 仓库根 `.\run-all.ps1`（`-SkipBuild` 跳打包，`-HeapMb 768` 调堆，`-Only manager\|route\|planner` 单起）/ `.\stop-all.ps1` |
| Nacos 3.1.0 | Docker 容器 `nacos`；8848（RPC，代码连这个）/ 8088（控制台，账号密码均 `nacos`） | `docker start nacos` / `docker stop nacos`；首次起法见 `README.md` 第三节 |
| Langfuse（观测，可选） | Cloud：`https://cloud.langfuse.com`（美国区 `us.cloud.langfuse.com`）；OTLP 端点 `/api/public/otel/v1/traces` | 无需本地进程。也可自托管，见 `DECISIONS.md` D-008 / D-014 |
| OTLP 接收器（排查用） | `tools\otlp-sink.ps1`，默认 127.0.0.1:4318 | `powershell -File tools\otlp-sink.ps1`；日志 `tools\otlp-sink.log.txt`（已 gitignore） |

<!-- SESSION_HANDOFF:START -->
# AiTripPlan-AgentScope —— 开工必读

> **动手前先读：`docs/STATUS.md`（当前状态 / 下一步 / 已知缺陷）、`docs/DECISIONS.md`（每条技术决策的理由与被否方案）。**
> 本文件是「宪法」：只放定位、硬约束、命令、验收。细节在那两份里。

## 定位

多 Agent 自主决策旅游规划：一句话 → 主管 Agent 用 PlanNotebook 拆任务 → 经 Nacos(A2A) 调度远程子 Agent（路线 / 行程）→ 调百度地图 MCP + Skills → 汇总成完整行程。
基于慕课网课程项目补全（课程原代码 17 项缺陷已修，清单见 `README.md` 第八节），并做了工程化增强。

| 项 | 值 |
|---|---|
| 栈 | AgentScope 1.0.8 · Spring Boot 4.0.2 · Java 17（**不用 preview**）· Nacos 3.1.0 |
| 模块 | `commons`（无端口）/ `manager_agent`(8081) / `routeMaking_agent`(8082) / `tripPlanner_agent`(8085) |
| 模型 | 厂商中立 `app.agentscope.llm.*`，当前 DeepSeek `deepseek-v4-flash`（OpenAI 兼容协议） |
| 观测 | 可选：AgentScope `TelemetryTracer` → OTLP → Langfuse（未配 Key 时自动跳过，不影响业务） |

## 硬约束（踩过坑才总结出来的，别绕过）

1. **接口测试一律用 Apifox**（项目约定，保持）。集合在 `docs/apifox/`，指南 `docs/接口测试-Apifox指南.md`。不要给 curl。
2. **`ToolkitConfig.parallel` 默认 `false`** —— 必须显式 `ToolUtils.createToolkit(parallel)`，否则同批工具串行（`Flux.concat`）。
3. **子 Agent 也必须配 `.toolExecutionConfig(...)`** —— 否则内部调 MCP / 子 Agent 会被框架默认 5 分钟从中间掐断。
4. **A2A 客户端禁用流式 `A2aAgent`** —— 长任务会 Reactor 背压溢出断连，且真实错误被吞成 `EOF reached while reading`。必须走 `message/send` 非流式（见 `RemoteAgentTool`）。
5. **`maxTokens` 必须显式设置**（`llm.max-tokens`，默认 8192）—— 不设会按厂商默认值**静默截断**在半句话上，且 `status` 仍是 SUCCESS。
6. **超时层次必须递增**：`remote-call-timeout`(15m) < `tool-execution-timeout`(17m) < `run-timeout`(45m)；子 Agent 的 `agent-completion-timeout-seconds` 要更大。
7. **改 `.env` 必须重新打包 + 重启** —— `.env` 会被打进 jar，否则读到的还是旧值。
8. **密钥只写在 `commons/src/main/resources/.env`**（已 gitignore），绝不进任何文档 / README / 聊天。
9. **Nacos 必须 ≥ 3.0** —— 2.x 不支持 A2A Agent 注册，报 `version is too low`。
10. **三个 JVM 必须限堆**（脚本默认 `-Xmx512m`）—— 不限堆时 JVM 默认取物理内存 1/4，三个服务叠加会把整机吃光，容器会被 OOM Killer 干掉（`Exited (137)`）。
11. **OTel 依赖必须含 `opentelemetry-reactor-3.1`，版本锁 `2.21.0-alpha`** —— AgentScope 全响应式，缺它**第一次请求就 500**（`NoClassDefFoundError`），极易误判成「Key 不对」。插桩版本必须与 `opentelemetry-bom`(1.55.0) 对齐，升 BOM 要同步升它。
12. **`ReActAgent` 是 prototype Bean，按 A2A 会话懒创建** —— 地图工具日志（`已挂载的工具`）出现在**第一次请求之后**，不在启动时。别刚启动就搜日志判断 MCP 挂了。

## 常用命令

```powershell
# 构建（本目录若有 build-settings.xml 会自动带上 -s；无则用默认仓库）
mvn -B -s build-settings.xml clean package -DskipTests

.\run-all.ps1              # 打包 + 起 8081/8082/8085，日志写 logs\
.\run-all.ps1 -SkipBuild   # 只重启不打包（改了 .env 就别用这个）
.\run-all.ps1 -HeapMb 768  # 临时调大堆上限
.\stop-all.ps1
```

## 验收（每次改动后至少跑这几条）

| 命令 | 期望 |
|---|---|
| `mvn -s build-settings.xml clean package -DskipTests` | `BUILD SUCCESS`，5 个模块全 SUCCESS |
| `GET http://127.0.0.1:8081/api/health` | `status=UP, registeredAgents=2` |
| `GET http://127.0.0.1:8081/api/diagnose` | `provider=OPENAI_COMPATIBLE`、`baseUrl=https://api.deepseek.com`、`hints=[]` |
| `GET http://127.0.0.1:8081/api/agents` | 两个子 Agent `registered=true` |
| `POST http://127.0.0.1:8081/app`（Apifox，body 字段是 **`prompt`**，超时设 300s+） | `status=SUCCESS`，`answer` 2000+ 字且**完整收尾** |
| 地图工具：**先跑一次 `POST /app`**，再搜 `logs\routeMaking_agent.out.log` 的 `已挂载的工具` | 有 **10 个** `map_*`；为空 = 百度 MCP 地址过期（约 1 天有效期） |

> `parallelismSummary` 出现 `SINGLE` 是**正常的模型调度行为**，不是并行失效。并行能力看日志里的 `[ToolUtils] 创建 Toolkit: parallel=true`。

**观测链路验收（T1）**：填好 `.env` 的 `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` → **重新打包 + 重启** → 跑一次 `POST /app` → 在 `https://cloud.langfuse.com` 的 Tracing 列表应出现该 trace 与 token 账目。若没出现，**先用 `tools\otlp-sink.ps1` 分段定位**（完整步骤与恢复步骤见 `docs/STATUS.md` 第五节），不要一上来就怀疑 Key。
<!-- SESSION_HANDOFF:END -->

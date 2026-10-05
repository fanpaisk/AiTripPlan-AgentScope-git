<!-- SESSION_HANDOFF:START -->
# AiTripPlan-AgentScope —— 开工必读

> **动手前先读：`docs/STATUS.md`（状态 / 下一步 / 缺陷）、`docs/DECISIONS.md`（每条决策的理由与被否方案）。**
> 本文件是「宪法」：只放定位、硬约束、命令、验收。

## 定位

多 Agent 自主决策旅游规划：一句话 → 主管 Agent 用 PlanNotebook 拆任务 → 经 Nacos(A2A) 调度远程子 Agent（路线 / 行程）→ 调百度地图 MCP + Skills → 汇总成完整行程。
基于慕课网课程项目补全（课程原代码 17 项缺陷已修，清单见 `README.md` 第八节），并做了工程化增强。

栈：AgentScope 1.0.8 · Spring Boot 4.0.2 · Java 17（**不用 preview**）· Nacos 3.1.0
模块：`commons`（无端口）/ `manager_agent`(8081) / `routeMaking_agent`(8082) / `tripPlanner_agent`(8085)
模型：厂商中立 `app.agentscope.llm.*`，当前 DeepSeek `deepseek-v4-flash`（OpenAI 兼容协议）
观测：`TelemetryTracer` → OTLP → Langfuse Cloud（EU）。**T1 已验收：trace 与 token 账目均可查**
上下文预算：单 Agent 模式默认启用「工具结果外置 + 按需取回」（`app.agentscope.context.*`）；`enabled=false` 即回到引入前行为

## 硬约束（踩过坑才总结出来的，别绕过）

1. **接口测试一律用 Apifox**（项目约定，保持）。集合在 `docs/apifox/`，指南 `docs/接口测试-Apifox指南.md`。不要给 curl。
2. **`ToolkitConfig.parallel` 默认 `false`** —— 必须显式 `ToolUtils.createToolkit(parallel)`，否则同批工具串行（`Flux.concat`）。
3. **子 Agent 也必须配 `.toolExecutionConfig(...)`** —— 否则内部调 MCP / 子 Agent 会被框架默认 5 分钟从中间掐断。
4. **A2A 客户端禁用流式 `A2aAgent`** —— 长任务会 Reactor 背压溢出断连，真实错误被吞成 `EOF reached while reading`；必须走 `message/send` 非流式（见 `RemoteAgentTool`）。
5. **`maxTokens` 必须显式设置**（`llm.max-tokens`，默认 8192）—— 不设会**静默截断**在半句话上，且 `status` 仍是 SUCCESS。
6. **超时层次必须递增**：`remote-call-timeout`(15m) < `tool-execution-timeout`(17m) < `run-timeout`(45m)；子 Agent 的 `agent-completion-timeout-seconds` 要更大。
7. **改 `.env` 必须重新打包 + 重启** —— `.env` 会被打进 jar。
8. **密钥只写在 `commons/src/main/resources/.env`**（已 gitignore），绝不进任何文档 / README / 聊天。
9. **Nacos 必须 ≥ 3.0** —— 2.x 不支持 A2A Agent 注册。
10. **三个 JVM 必须限堆**（脚本默认 `-Xmx512m`）—— 不限堆会被 OOM Killer 干掉（`Exited (137)`）。
11. **OTel 依赖必须含 `opentelemetry-reactor-3.1`，版本锁 `2.21.0-alpha`** —— 缺它第一次请求就 500（`NoClassDefFoundError`），极易误判成「Key 不对」。
12. **OTLP 直连上报必须带 `x-langfuse-ingestion-version: 4`** —— 少了它数据最多延迟 15 分钟，表现为「跑完了但 Langfuse 里没有记录」。
13. **重建顺序：先停、再打包、后启动**（`.\stop-all.ps1` → `mvn clean package` → `.\run-all.ps1 -SkipBuild`）。顺序反了 `clean` 会因 jar 被 JVM 锁住而失败；且 `run-all.ps1` **不会先停旧进程** —— 端口被占时它照样报 `[就绪]`，实际跑的是旧代码。
14. **`ReActAgent` 是 prototype Bean，按 A2A 会话懒创建** —— `已挂载的工具` 日志出现在**第一次请求之后**，别刚启动就搜日志判断 MCP 挂了。

## 常用命令

```powershell
.\stop-all.ps1                                            # 必须先停：旧 jar 被 JVM 锁住会让 clean 失败
mvn -B -s build-settings.xml clean package -DskipTests    # 改了 .env 必须重打（.env 进 jar）
.\run-all.ps1 -SkipBuild                                  # 起 8081/8082/8085，日志写 logs\
```

## 验收（每次改动后至少跑这几条）

| 命令 | 期望 |
|---|---|
| `mvn -s build-settings.xml clean package -DskipTests` | `BUILD SUCCESS`，5 个模块全 SUCCESS |
| `GET 127.0.0.1:8081/api/health` | `status=UP, registeredAgents=2` |
| `GET 127.0.0.1:8081/api/diagnose` | `provider=OPENAI_COMPATIBLE`、`baseUrl=https://api.deepseek.com`、`hints=[]`、超时 `PT15M/PT17M/PT45M` |
| `GET 127.0.0.1:8081/api/agents` | 两个子 Agent `registered=true` |
| `POST 127.0.0.1:8081/app`（Apifox，body 字段是 **`prompt`**，超时设 300s+） | `status=SUCCESS`，`answer` 2000+ 字且完整收尾 |
| 地图工具：`POST 127.0.0.1:8082/`（A2A，用例见 `docs/apifox/03-*.json`）后搜 `logs\routeMaking_agent.out.log` 的 `已挂载的工具` | 10 个 `map_*`；为空 = 百度 MCP 地址失效 |
| 观测（T1）：`GET .../api/public/v2/observations?fromStartTime=…`；token 用 `.../api/public/v2/metrics?query={"view":"observations","metrics":[{"measure":"totalTokens","aggregation":"sum"}],…}`（Basic Auth：用户名=Public Key、密码=Secret Key） | 能看到 trace 与 token 账目；**旧 `/api/public/traces` 已 410，不要再用** |
| 需要看 span 的**原始属性**时：`tools\otlp-capture.ps1` 收报文，再 grep protobuf 里的属性名 | 做法与实例见 `DECISIONS.md` D-018 / D-020 |
| 上下文预算：`POST /app` 带 `{"mode":"single"}`，再搜 `logs\manager_agent.out.log` | 有 `[ContextMeter]` 行（真实 token + schema/历史构成）；超长地图返回出现「第 N 次外置」 |

> `parallelismSummary` 出现 `SINGLE` 是**正常的模型调度行为**，不是并行失效。并行能力看日志里的 `[ToolUtils] 创建 Toolkit: parallel=true`。
<!-- SESSION_HANDOFF:END -->

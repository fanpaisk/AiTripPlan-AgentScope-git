# AiTripPlan-AgentScope

多 Agent 自主决策的旅游规划系统：**一句话需求** → 主管 Agent 拆解任务 → 经 Nacos(A2A) 调度远程子 Agent（路线 / 行程）→ 调百度地图 MCP + Skills → 汇总成完整行程。

| | |
|---|---|
| 栈 | AgentScope 1.0.8 · Spring Boot 4.0.2 · Java 17 · Nacos 3.1.0 |
| 模块 | `commons`（公共能力）· `manager_agent`(8081) · `routeMaking_agent`(8082) · `tripPlanner_agent`(8085) |
| 模型 | 厂商中立（`app.agentscope.llm.*`），当前 DeepSeek `deepseek-v4-flash`（OpenAI 兼容协议） |
| 观测 | 可选：AgentScope `TelemetryTracer` → OTLP → Langfuse（trace / token 账目） |

**三个有实测数据的亮点**（报告见 [`docs/experiments/`](docs/experiments/)）：

- **工具并行**：显式开启后 3-Agent 端到端 **156s → 63s**（加速比 1.4x ~ 2.0x）
- **上下文预算**：工具结果外置 + 按需取回，单 Agent 的 token **降低约 84%**（均值 121 万 → 19 万），质量盲评未降
- **对照实验**：单 Agent 与多 Agent **质量打平**；把两条臂都做上下文治理后，多 Agent 因**上下文隔离**成为最省的

> ### ⚖️ 代码来源与许可
> - 初始骨架来自**慕课网课程项目**；源文件中保留的 `author: Imooc` 即课程原始标注。
> - **课程原始代码的著作权归课程作者**；本项目在其基础上补全了 17 项缺陷并做了工程化增强。
> - `LICENSE` 采用 **MIT**，仅覆盖**二次开发部分**。详见 [`LICENSE`](LICENSE)。

---

## 一、怎么接入

### 1. 前置依赖

| 依赖 | 说明 |
|---|---|
| JDK 17 | 项目 `<release>17</release>`，不使用 preview 特性；JDK 21/25 也可编译运行 |
| Nacos **≥ 3.0** | 2.x 不支持 A2A Agent 注册。一键起：`docker run -d --name nacos -p 8848:8848 -p 8088:8080 -e MODE=standalone nacos/nacos-server:v3.1.0` |
| 大模型 API Key | DeepSeek / 阿里百炼 / Kimi / 智谱 / 本地 Ollama 均可（走 OpenAI 兼容协议） |
| 百度地图 MCP 地址 | 到 <https://modelscope.cn/mcp> 搜「百度地图」→ 填自己的 AK → 复制生成的 **SSE 地址**。⚠️ 该地址**会过期**（约 1 天），失效时路线 Agent 会没有地图工具 |

### 2. 填密钥（唯一需要改的文件）

```bash
cp commons/src/main/resources/.env.example commons/src/main/resources/.env
```

填 `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`、`BAIDU_MAP_MCP_SSE_URL`。
`.env` 与 `build-settings.xml` 已 gitignore，**密钥不入库**。

### 3. 构建与启动

```powershell
.\stop-all.ps1                                            # 必须先停：运行中的 jar 被 JVM 锁住会让 clean 失败
mvn -B -s build-settings.xml clean package -DskipTests    # 改了 .env 必须重新打包（.env 进 jar）
.\run-all.ps1 -SkipBuild                                  # 起 8081 / 8082 / 8085，日志写 logs\
```

### 4. 验证（自检清单）

| 命令 | 期望 |
|---|---|
| `GET 127.0.0.1:8081/api/health` | `status=UP`、`registeredAgents=2` |
| `GET 127.0.0.1:8081/api/diagnose` | 打印**实际生效**的 provider / 模型 / 端点，`hints` 为空 |
| `GET 127.0.0.1:8081/api/agents` | 两个子 Agent `registered=true` |
| `POST 127.0.0.1:8082/`（A2A 用例见 [`docs/apifox/`](docs/apifox/)） | 返回里有**具体里程/耗时** = 百度地图 MCP 正常 |
| `POST 127.0.0.1:8081/app` | `status=SUCCESS`，`answer` 2000+ 字且完整收尾 |

### 5. 调用接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/app` | 同步：返回完整方案 + 运行轨迹（`runId` / `steps` / `toolBatches`） |
| POST | `/app/stream` | SSE 流式：边推理边推 |
| GET | `/api/runs/{runId}` | 某次运行的完整决策轨迹 |

```json
POST /app
{ "prompt": "帮我规划深圳到惠州3日游，涵盖路线和行程" }
```

请求体还有两个**可选**字段（默认即可，主要用于对照实验）：
`"mode": "multi" | "single"`（多 Agent / 单 Agent）、`"contextBudget": true | false`（是否启用上下文预算）。

> 接口测试按项目约定用 **Apifox**，集合在 [`docs/apifox/`](docs/apifox/)，指南见 [`docs/接口测试-Apifox指南.md`](docs/接口测试-Apifox指南.md)。

---

## 二、大致架构

```
用户 ──POST /app──► manager_agent (8081)
                      · 主管 Agent：PlanNotebook 拆任务 + 汇总最终方案
                      │
                      │ A2A over Nacos（JSON-RPC message/send，非流式）
                      ├──► routeMaking_agent (8082)   百度地图 MCP（10 个工具）
                      └──► tripPlanner_agent (8085)   行程/景点/住宿/预算
                                └─ 进程内子 Agent：SuggestSightAgent + Skills

commons（无端口）：模型工厂 · 工具并行 · 百度地图 MCP 客户端 · Skills 载入
                   观测（OTLP→Langfuse）· 上下文预算（结果外置 / 计量 / 按需取回 / 工具路由）
```

**几个关键设计点**（每条都踩过坑，理由与被否方案见 [`docs/DECISIONS.md`](docs/DECISIONS.md)）：

1. **A2A 走非流式 `message/send`** —— 流式会遇到 Reactor 背压溢出，且真实错误被吞成 `EOF reached while reading`
2. **工具并行必须显式开启** —— 框架默认是串行（`Flux.concat`），不显式设置就白白慢一倍
3. **`maxTokens` 必须显式设置** —— 不设会静默截断在半句话上，而 `status` 仍然是 SUCCESS
4. **超时层次必须递增**：`remote-call-timeout`(15m) < `tool-execution-timeout`(17m) < `run-timeout`(45m)
5. **上下文预算**：默认开启。超长工具结果外置成"摘要 + id"，需要细节时用 `read_artifact` 取回 —— 这是实测把 token 降 84% 的关键
6. **模型厂商中立 + 编排可替换**：换厂商只改 `.env` 三行；`app.manager.mode` 或请求体 `mode` 可在"多 Agent / 单 Agent"之间切换

---

## 三、更新记录

### 2026-10-05

- **观测链路闭环**：AgentScope → OTLP → Langfuse，trace / LLM 调用 / **token 账目**均实测可见
- **新增上下文预算**（4 个可独立开关的机制）：工具结果外置 + 按需取回、上下文计量、工具按需挂载、历史压缩
  - 实测：单 Agent token **−84%**；多 Agent 臂补上后 **409k → 137k（−67%）**
- **两个自查对照实验**：单 Agent vs 多 Agent（质量打平、成本差异被定位到"单条巨大工具结果长期驻留"）+ 上下文优化
  报告与原始数据：`docs/experiments/EXP-001/`、`docs/experiments/EXP-002/`
- **能力下沉 commons**：百度地图 MCP 客户端、Skills、计算工具提到公共模块，使单/多 Agent 两条臂能力对等
- **文档整改**：README 精简为开源门面，详细手册移至 [`docs/手册-完整版.md`](docs/手册-完整版.md)

### 2026-10-04

- 交接文档落地：[`AGENTS.md`](AGENTS.md)（开工必读的硬约束与验收）、[`docs/STATUS.md`](docs/STATUS.md)、[`docs/DECISIONS.md`](docs/DECISIONS.md)

### 初始提交

- 补全慕课网课程项目原代码的 **17 项缺陷**（清单见详细手册"与课程原版代码的差异"一节），并做工程化增强

---

## 四、文档索引

| 文档 | 内容 |
|---|---|
| [`docs/手册-完整版.md`](docs/手册-完整版.md) | 环境准备 · 配置速查 · 接口测试 · 故障排查 · 课程差异清单 · 实验全过程 |
| [`docs/STATUS.md`](docs/STATUS.md) | **当前状态 / 下一步 / 已知缺陷**（讲"下一步"以这份为唯一权威） |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | 每条技术决策的理由**与被否方案** |
| [`AGENTS.md`](AGENTS.md) | 开工必读：定位 · 硬约束 · 常用命令 · 验收表 |
| [`docs/apifox/`](docs/apifox/) | 三个服务的接口集合（导入即用） |
| [`docs/experiments/`](docs/experiments/) | EXP-001 / EXP-002：设计 · 提示词 · 原始数据 · 报告 |

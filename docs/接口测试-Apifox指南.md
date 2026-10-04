# AiTripPlan 接口测试指南（Apifox）

> ## 📌 团队约定
> **本项目所有接口测试一律使用 Apifox，不使用 curl / Postman 等其他工具。**
> 接口文档变更时，同步更新 `docs/apifox/` 下的 OpenAPI 文件，保证「代码 = 文档 = 测试集合」三者一致。

---

## 一、一次性准备：导入接口集合

`docs/apifox/` 下有三个 OpenAPI 3.0 文件，**各服务一个**（因为三个服务端口不同，分开导入才不会搞混前置 URL）：

| 文件 | 服务 | 端口 |
|---|---|---|
| `01-manager-agent-8081.openapi.json` | 主管 Agent（用户入口） | 8081 |
| `02-tripPlanner-agent-8085.openapi.json` | 行程规划 Agent | 8085 |
| `03-routeMaking-agent-8082.openapi.json` | 路线制定 Agent | 8082 |

**导入步骤：**

1. 打开 Apifox → 新建/进入项目（例如 `AiTripPlan`）
2. 左侧「项目设置」→「导入数据」→ 选择 **OpenAPI / Swagger**
3. 先把 `docs/apifox` 整个文件夹拖进去（或分三次选择文件），文件类型选 **JSON**
4. 导入模式选 **「智能合并」**（后续文档更新后重新导入不会产生重复接口）
5. 导入完成后，左侧目录树会出现三组接口，按 Tag 分组：
   - `01. 业务入口` / `02. 可观测`（8081）
   - `01. Agent 卡片` / `02. A2A 调用`（8085）
   - `01. Agent 卡片` / `02. A2A 调用`（8082）

> OpenAPI 文件里已经写好了 `servers`（`http://127.0.0.1:8081` 等），所以**不需要再配置环境的前置 URL**，导入即可直接发请求。

---

## 二、必须调整的两项设置 ⚠️

### 1. 请求超时时间

Agent 跑一次要调大模型 + 多个子 Agent，**可能耗时 1~5 分钟**。

> Apifox → 右上角头像 →「设置」→「请求」→ **请求超时时间** 改为 **300000 ms（300 秒）** 以上。

不改的话，最典型的现象就是：请求发出后几十秒报超时，但服务端日志显示它其实跑成功了。

### 2. SSE 流的显示

`POST /app/stream` 是 SSE 接口。在 Apifox 里：

> 新建请求时请求类型选择 **SSE**（或保留 POST，在 Header 手动加 `Accept: text/event-stream`），
> 发送后事件会**逐条实时出现**在响应区，而不是等全部结束才显示。

---

## 三、推荐测试顺序（自检清单）

按这个顺序测，出问题时能立刻定位到是哪一层：

### 第 0 步：前置检查（不通过就别往下测）

| 检查项 | 怎么看 |
|---|---|
| Nacos 3.x 在跑 | `http://127.0.0.1:8848` 端口可连通（**必须是 3.x，2.x 不支持 A2A Agent 注册**） |
| `.env` 已填 | `commons/src/main/resources/.env` 里 `ALIBABA_DASHCOPE_KEY` 不为空 |
| 三个服务都起来了 | 8081 / 8082 / 8085 端口都能连通 |

### 第 1 步：主管 Agent 自检 —— `GET /api/health`（8081）

期望：`registeredAgents` 为 **2**。

```json
{ "status": "UP", "service": "manager-agent", "registeredAgents": 2 }
```

- 返回 `0` 或接口直接 500 → Nacos 没起来 / 子 Agent 没注册，跳到第 2 步细查

### 第 1.5 步：配置自检 —— `GET /api/diagnose`（8081）【报错时第一个调】

**任何 `url error` / `InvalidApiKey` / `Model not exist` 之类的报错，先调这个接口。**

它会把**实际生效**的配置打出来（配置是 `.env` → 环境变量 → `application.yml` 多层覆盖的，光看文件猜不出最终值）：

- `dashscope.effectiveModelName` —— **真正传给大模型的模型名**（可能被环境变量覆盖，和文件里写的不一样）
- `dashscope.effectiveEndpoint` —— 会走哪个接口端点
- `dashscope.apiKeyMasked` —— Key 是否读到、长度对不对（不会泄露完整 Key）
- `hints` —— 直接给出结论，例如「模型名看起来是多模态模型，但会走到纯文本端点」

需要验证 Key / 模型名到底能不能用时，加参数 `deep=true`，它会真实调用一次大模型并把**原始报错**带回来：

```
GET /api/diagnose?deep=true
```

> `deep=true` 会消耗少量 token，平时用不带参数的版本即可。

### 第 2 步：子 Agent 注册详情 —— `GET /api/agents`（8081）

期望：两个 Agent 都是 `registered: true`，`url` 分别是 8082 / 8085。

```json
[
  { "name": "RouteMakingAgent", "toolName": "callRouteMakingAgent", "registered": true, "url": "http://127.0.0.1:8082", "version": "1.0.0" },
  { "name": "TripPlannerAgent", "toolName": "callTripPlannerAgent", "registered": true, "url": "http://127.0.0.1:8085", "version": "1.0.0" }
]
```

**这是排查问题的第一现场**：哪个 `registered: false`，看它的 `error` 字段就知道原因（未启动 / 未注册 / Nacos 连不上）。

### 第 3 步：子 Agent 卡片 —— `GET /.well-known/agent-card.json`

分别在 **8085** 和 **8082** 上调用，确认 `name` 与主管 Agent 配置一致：

- 期望：8085 返回 `"name": "TripPlannerAgent"`
- 期望：8082 返回 `"name": "RouteMakingAgent"`

打不开 → 该模块缺 A2A starter 依赖，或容器里没有 `ReActAgent` Bean。

### 第 4 步：单测子 Agent —— `POST /`（8085 / 8082，A2A 调用）

**这一步最划算**：不经过主管 Agent，直接验证子 Agent 单独是否正常。

先测 8085（不依赖百度地图）：

- 用示例「推荐景点」发一次
- `result.parts[0].text` 里应该有正常的景点推荐内容

再测 8082（验证百度地图 MCP）：

- 用示例「深圳到惠州自驾路线」发一次
- 返回里有**具体里程/耗时** → MCP 正常
- 返回里出现「该数据未能获取」→ 检查 `.env` 的 `BAIDU_MAP_MCP_SSE_URL`

> ⚠️ 这两个请求的 body 里 `message.kind` 必须是 `"message"`，漏了会返回 `-32602 Invalid parameters`。

### 第 5 步：端到端 —— `POST /app`（8081）

用最长的那个示例「深圳到惠州三日游」发一次。

期望响应：

- `status` = `SUCCESS`
- `answer` 是一份完整的 Markdown 行程（含交通、每日安排、餐饮、住宿、天气、预算）
- `steps` 里能看到 `TOOL_CALL`：`callRouteMakingAgent`、`callTripPlannerAgent`
- `durationMillis` 通常在 60000~300000 之间

### 第 6 步：流式 —— `POST /app/stream`（8081）

期望：事件逐条到达，`event:chunk` … 最后一条 `event:done`，`done.text` 是完整方案。

### 第 7 步：回溯 —— `GET /api/runs/{runId}`（8081）

把第 5 步响应里的 `runId` 填进去，检查完整轨迹：
模型思考 → 调用工具 → 工具返回，全过程无遗漏。

> 这个接口在演示/面试时非常好用：不用翻服务端日志就能把「Agent 是怎么决策的」讲清楚。

---

## 四、把常用请求存成 Apifox「测试场景」

建议把第 1~6 步存成一个 **测试场景（Test Scenario）**，以后每次改完代码点一下就能跑完整回归：

1. Apifox 左侧「自动化测试」→ 新建场景 `AiTripPlan 冒烟`
2. 依次加入：`/api/health` → `/api/agents` → 8085 卡片 → 8085 调用 → 8082 卡片 → `/app`
3. 给关键步骤加**断言**：
   - `/api/health`：`$.registeredAgents` 等于 `2`
   - `/api/agents`：`$[0].registered` 等于 `true` 且 `$[1].registered` 等于 `true`
   - `/app`：`$.status` 等于 `SUCCESS`
4. 场景里把 `/app` 的超时单独设为 300 秒

这样每次迭代（魔改）后，一条命令就能确认「没把主链路改坏」。

---

## 五、常见问题

| 现象 | 原因 / 解决 |
|---|---|
| 请求几十秒就报超时，但服务端日志显示跑成功了 | Apifox 超时没调，改成 300 秒以上 |
| `/app` 返回 `status: FAILED`，error 里是 `url error, please check url！` | **模型名与接口端点不匹配**（百炼官方定义）。根因：AgentScope 原生协议只对 `qvq*` / 含 `-vl` 的模型名走多模态端点，而 `qwen3.8-flash`、`qwen3.8-max`、`qwen3.7-plus` 都不带这两个特征 → 被送进纯文本端点。**解决：把 `DASHSCOPE_PROVIDER` 设为 `openai-compatible`**（兼容模式单端点，所有模型通用）。调 `GET /api/diagnose` 可见 `provider` 与生效值 |
| `/app` 返回 `status: FAILED`，error 里是 `InvalidApiKey` | 先看 `/api/diagnose` 的 `apiKeyMasked` 是否为 `(空)`：是则 `.env` 没被读到；不是则 Key 本身无效、或与 Base URL 地域不匹配 |
| `/app` 返回 `status: FAILED`，error 里是 `Model not exist` | 模型名拼错，或该模型未在百炼控制台「模型市场」开通 |
| `/app` 返回 `status: FAILED`，error 里有 `调用远程 Agent「xxx」失败` | 先调 `/api/agents` 看 `registered` 与 `error` |
| `/api/health` 返回 500 | Nacos 没启动，或 `NACOS_SERVER_ADDR` 配错（是 **8848**，不是控制台的 8088） |
| A2A 调用返回 `-32602 Invalid parameters` | body 里 `message.kind` 漏了 `"message"` |
| 子 Agent 返回文本是「该数据未能获取」 | 百度地图 MCP SSE 地址没配 / 魔塔实例已过期，重新复制一个填进 `.env` |
| `/app/stream` 一次性返回全部内容，看不出流式 | 请求类型没选 SSE，或 Apifox 版本不支持流式显示 |
| 导入 OpenAPI 后接口报 404 | 三个文件要**分开导入**，不要合并成一个（端口不同） |

---

## 六、维护约定

改了接口之后，请同步更新 `docs/apifox/*.openapi.json`，然后在 Apifox 里用**「智能合并」**重新导入一次。

新增接口的模板（抄现有格式即可）：

```json
"/你的路径": {
  "get": {
    "tags": ["02. 可观测"],
    "summary": "一句话说明",
    "description": "详细说明，含排查提示",
    "operationId": "唯一英文标识",
    "responses": {
      "200": {
        "description": "说明",
        "content": {
          "application/json": {
            "schema": { "type": "object" },
            "examples": { "示例": { "value": { } } }
          }
        }
      }
    }
  }
}
```

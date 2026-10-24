# 工具检索与执行（5 个端点）

前缀 `{PLATFORM_BASE_URL}/api/oauth2/tool`，请求头 `Authorization: Bearer <access_token>`（`oa-` 令牌）。

**本模块的 scope**：`tool:read`（端点 1–2：检索与 schema。机器对机器可用）、`tool:exec`（端点 3–5：执行。机器对机器可用）。

★响应一律**平台信封** `{code, displayCode, message, data, tid}`：HTTP 恒 200，外层成败看 `code == "0000"`。

★**执行类端点（3–5）判定要两层**：外层 `code=="0000"` 只表示「接口调用成功」，**工具本身执行成没执行成看 `data.success`**，失败原因在 `data.error`（插件）/ `data.message`（工作流）。

公共前提：

- 插件 / 工作流必须**已发布**，草稿直接拒。
- 发布范围校验：租户级放行；空间级须属于其中之一；发布空间列表为空则放行。无权时外层 `4030`（message「无权限」）。★**检索与 schema 是同一套判据**，所以「schema 查得到」就等于「执行得动」，反之亦然。
- ★**不接受任何 `apiKey` 参数**，鉴权只认 `Authorization` 头里的 `oa-` 令牌。
- 可用工具 = **平台内已发布的插件与工作流**。知识库、MCP、技能、智能体不在本模块范围内（平台没有对外暴露它们的执行入口）。

**推荐调用顺序**：端点 1 拿到 `targetType` + `targetId` → 端点 2 拿它的入参定义 → 端点 3/4/5 按定义执行。

---

## 1. 检索已发布的工具 —— `GET /api/oauth2/tool/list`

scope：`tool:read`。

**请求参数**

| 位置 | 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| query | `type` | 字符串 | 否 | `plugin` / `workflow`；**省略 = 两类都查**（见下方「坑」）。★非法值报 `2006`，不会静默回落 |
| query | `spaceId` | 数字 | 否 | 只看某个空间；省略 = 我能看到的全部。★**须是你所属的空间**，否则 `4030` |
| query | `kw` | 字符串 | 否 | 名称 / 描述关键字，**数据库全文匹配**（不是模糊 Like，空格分词、短词可能不命中） |
| query | `page` | 数字 | 否 | 页码，从 1 开始，缺省 1 |
| query | `pageSize` | 数字 | 否 | 每页条数，缺省 20、**上限 100**（超出按 100 收窄） |

**请求示例**

```
GET /api/oauth2/tool/list?type=plugin&kw=天气&page=1&pageSize=20
Authorization: Bearer oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

**响应示例**（`data` = [工具检索结果页](#数据结构工具检索结果页)）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": {
    "total": 2,
    "page": 1,
    "pageSize": 20,
    "items": [
      {
        "targetType": "plugin",
        "targetId": 88,
        "name": "天气查询",
        "description": "按城市与天数查询天气预报",
        "icon": "https://platform.example.com/api/f/icon-plugin-001?ak=xxxx",
        "spaceId": 57,
        "scope": "Space",
        "publishDate": "2026-09-30 14:22:05"
      },
      {
        "targetType": "plugin",
        "targetId": 91,
        "name": "汇率换算",
        "description": "实时汇率换算",
        "icon": "https://platform.example.com/api/f/icon-plugin-002?ak=yyyy",
        "spaceId": 57,
        "scope": "Tenant",
        "publishDate": "2026-09-28 09:10:00"
      }
    ]
  },
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- ★**`type` 省略时页码对两类各自生效**：返回的是「插件第 page 页 + 工作流第 page 页」拼起来的，`items` 最多 `pageSize × 2` 条，`total` 是两类之和。想按页稳定拿到 N 条就**显式带 `type`**。
- ★`description` 对**未发布时**的名字 / 描述修改不会同步——取的是发布记录上的快照。
- 列表**不含工具的参数定义**（插件/工作流的配置体积大，列表不返回）。要参数定义就对上 `targetType`+`targetId` 调端点 2。
- `scope` 是**发布范围**（`Tenant` 全租户可用 / `Space` 仅发布空间可用），不是 OAuth2 的 scope，别混。

---

## 2. 查询工具 schema —— `GET /api/oauth2/tool/schema`

scope：`tool:read`。

**请求参数**

| 位置 | 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| query | `type` | 字符串 | **是** | `plugin` / `workflow`。★**不设默认**——同一个 id 在两类里是两条不同记录 |
| query | `id` | 数字 | **是** | 工具 ID（即端点 1 的 `targetId`） |

**请求示例**

```
GET /api/oauth2/tool/schema?type=plugin&id=88
Authorization: Bearer oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

**响应示例**（`data` = [工具 schema 对象](#数据结构工具-schema-对象)）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": {
    "targetType": "plugin",
    "targetId": 88,
    "name": "天气查询",
    "toolName": "query_weather",
    "description": "按城市与天数查询天气预报",
    "schema": {
      "method": "POST",
      "url": "{PLATFORM_BASE_URL}/api/oauth2/tool/plugin/execute?pluginId=88",
      "authorization": "Bearer <access_token>",
      "scope": "tool:exec",
      "contentType": "application/json",
      "requestBody": {
        "type": "object",
        "properties": {
          "city": { "type": "string", "description": "城市名" },
          "days": { "type": "integer", "description": "预报天数" }
        },
        "required": ["city"]
      },
      "response": {
        "type": "object",
        "description": "平台信封，业务结果在 data 里",
        "properties": {
          "code": { "type": "string", "description": "\"0000\" 表示接口调用成功；工具本身成没成看 data.success" },
          "message": { "type": "string", "description": "调用失败时的原因" },
          "data": {
            "type": "object",
            "description": "插件执行结果",
            "properties": {
              "success": { "type": "boolean", "description": "插件是否执行成功；false 时外层信封 code 仍是 \"0000\"" },
              "result": {
                "type": "object",
                "description": "插件输出，结构由插件自身定义",
                "properties": {
                  "forecast": { "type": "array", "description": "逐日预报" }
                }
              },
              "error": { "type": "string", "description": "失败原因，成功时为 null" },
              "requestId": { "type": "string", "description": "链路 ID（Trace-Id 头回显）" },
              "costTime": { "type": "integer", "description": "耗时（毫秒）" }
            }
          }
        }
      }
    }
  },
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

工作流（`type=workflow`）的 `schema` 多一段 `streaming`（指向 SSE 端点），响应示例节选：

```json
{
  "method": "POST",
  "url": "{PLATFORM_BASE_URL}/api/oauth2/tool/workflow/execute?workflowId=12",
  "authorization": "Bearer <access_token>",
  "scope": "tool:exec",
  "contentType": "application/json",
  "requestBody": { "type": "object", "properties": { "keyword": { "type": "string" } } },
  "response": {
    "type": "object",
    "description": "平台信封，业务结果在 data 里",
    "properties": {
      "code": { "type": "string" },
      "data": {
        "type": "object",
        "description": "工作流执行结果",
        "properties": {
          "success": { "type": "boolean", "description": "工作流执行成败" },
          "data": { "type": "object", "description": "工作流输出", "properties": { "reportUrl": { "type": "string" } } },
          "code": { "type": "string", "description": "工作流自己的业务码：\"0000\" 成功 / \"0001\" 失败" },
          "message": { "type": "string" },
          "requestId": { "type": "string" },
          "costTime": { "type": "integer" }
        }
      }
    }
  },
  "streaming": {
    "url": "{PLATFORM_BASE_URL}/api/oauth2/tool/workflow/sse-execute?workflowId=12",
    "contentType": "text/event-stream",
    "requestBody": "同本接口",
    "stopWhen": "isComplete == true",
    "frame": { "type": "object", "description": "每一帧的 data 段（JSON，前缀一个空格）" }
  }
}
```

**坑**

- ★`schema.url` 里的 `{PLATFORM_BASE_URL}` **原样就是占位符**，调用方要换成自己的平台地址再用——门面不知道你从哪个域名接进来的。
- ★`authorization` 写的是 `Bearer <access_token>`：把你手头这把 `oa-` 令牌填进去即可。**注意执行端点的 scope 是 `tool:exec`**，只申请了 `tool:read` 的令牌调执行端点会 `4030`。
- ★`requestBody` 是**参数 JSON Schema**，可以直接当 function calling 的 `parameters` 用；但它描述的是**执行端点的整个请求体**（参数不额外包一层）。
- ★入参**只含启用中的参数**，插件 / 工作流的内部绑定字段（`bindValue` 之类）不会出现。未启用的参数不会出现在 `required` 里，也不要在调用时传。
- 出参是**工具自己声明的**结构，平台只做转述。插件 / 工作流作者没定义出参时，`properties` 会是空对象（不代表没有输出，只代表没声明）。
- `toolName` 缺失时平台会回落成 `plugin{id}` / `workflow{id}`——模型侧凭它认工具，不会为空。

---

## 3. 执行插件（同步） —— `POST /api/oauth2/tool/plugin/execute`

scope：`tool:exec`。

**请求参数**

| 位置 | 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| query | `pluginId` | 数字 | **是** | 插件 ID |
| header | `Trace-Id` | 字符串 | 否 | 链路 ID，回显在 `requestId` |
| body | **参数就是整个请求体** | 对象 | 否 | ★**不额外包一层**——不要写成 `{"params": {...}}`；无参传 `{}` |

**请求示例**

```
POST /api/oauth2/tool/plugin/execute?pluginId=88
Authorization: Bearer oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
Content-Type: application/json
```

```json
{
  "city": "成都",
  "days": 3
}
```

**响应示例**（`data` = [插件结果对象](#数据结构插件结果对象)）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": {
    "success": true,
    "result": {
      "forecast": [
        { "date": "2026-10-02", "weather": "多云", "tempLow": 16, "tempHigh": 24 }
      ]
    },
    "error": null,
    "requestId": "trace-20261002-0001",
    "costTime": 1250
  },
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- ★**工具失败时外层信封仍是 `code:"0000"`**，真正的失败在 `data.success=false` + `data.error` ⇒ 判定逻辑必须两层都看。
- `result` 是插件自定义结构，没有统一 schema，按各插件自己的说明解析。

---

## 4. 执行工作流（同步） —— `POST /api/oauth2/tool/workflow/execute`

scope：`tool:exec`。

**请求参数**

| 位置 | 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| query | `workflowId` | 数字 | **是** | 工作流 ID |
| header | `Trace-Id` | 字符串 | 否 | 链路 ID |
| body | **参数就是整个请求体** | 对象 | 否 | 同端点 3，不包一层 |

**请求示例**

```
POST /api/oauth2/tool/workflow/execute?workflowId=12
Authorization: Bearer oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
Content-Type: application/json
```

```json
{
  "keyword": "季度报告"
}
```

**响应示例**（`data` = [工作流结果对象](#数据结构工作流结果对象)）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": {
    "success": true,
    "data": {
      "reportUrl": "/api/f/wf-report-001"
    },
    "code": "0000",
    "message": null,
    "requestId": "trace-20261002-0002",
    "costTime": 48213
  },
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- ★**没有超时限制**——长任务会一直挂着连接，请改用 SSE 端点（下）。
- `data` 内层的 `code` 是工作流自己的业务码（`0000` 成功 / `0001` 失败），与外层信封的 `code` 同名不同物，别拿混。

---

## 5. 执行工作流（SSE） —— `POST /api/oauth2/tool/workflow/sse-execute`

scope：`tool:exec`。请求参数同端点 4；请求头加 `Accept: text/event-stream`。

**响应**：`text/event-stream` 帧流。每帧 `data:` 后**前缀一个空格**，内容是 [工作流执行帧对象](#数据结构工作流执行帧对象) 的 JSON。

**帧示例**（执行中 → 完成，共 2 帧节选）

```
data: {"success":true,"data":null,"code":"0000","message":null,"requestId":"trace-20261002-0003","nodeExecuteResultMap":{"node-1":{"status":"RUNNING"}},"costTime":1200,"isComplete":false}

data: {"success":true,"data":{"reportUrl":"/api/f/wf-report-001"},"code":"0000","message":null,"requestId":"trace-20261002-0003","nodeExecuteResultMap":{"node-1":{"status":"SUCCESS","output":"ok"}},"costTime":52300,"isComplete":true}
```

**错误帧示例**（工作流不存在 / 未发布 / 无权时）

```
data: {"success":false,"data":null,"code":"0001","message":"workflow not found or not published","requestId":null,"nodeExecuteResultMap":null,"costTime":0,"isComplete":true}
```

**坑**

- ★**失败不抛异常**：工作流不存在 / 未发布 / 你无权时，HTTP 连接照常建立、正常结束，只在流内推一个 `success=false, isComplete=true` 的错误帧 ⇒ **连接建立成功 ≠ 执行成功**，必须解析帧内容。
- 收流判据：`isComplete == true`。
- ★`nodeExecuteResultMap` 与 `isComplete` **只在 SSE 帧里有**，同步端点（2）的返回体里没有这两个字段。

---

## 数据结构

### 工具检索结果页

`data`，对应端点 1：

| 字段 | 类型 | 说明 |
|---|---|---|
| `total` | 数字 | 命中条数。★`type` 省略时是两类之和 |
| `page` | 数字 | 页码（回显请求值，缺省 1） |
| `pageSize` | 数字 | 每页条数（回显生效值，已按上限 100 收窄） |
| `items` | 数组 | [工具条目](#工具条目)数组；★`type` 省略时是「两类各自的第一页」拼起来的，最多 `pageSize × 2` 条 |

### 工具条目

`items` 数组元素，对应端点 1：

| 字段 | 类型 | 说明 |
|---|---|---|
| `targetType` | 字符串 | `plugin` / `workflow`，与请求参数 `type` 同形 |
| `targetId` | 数字 | 工具 ID。★把 `targetType` + `targetId` 一起交给端点 2 与执行端点 |
| `name` | 字符串 | 展示名 |
| `description` | 字符串 | 描述 |
| `icon` | 字符串 | 图标地址（缺省图标已补成可直接访问的地址） |
| `spaceId` | 数字 | 所属空间。租户级发布的工具，此字段来自发布记录，未必是你所在的空间 |
| `scope` | 字符串 | **发布范围**：`Tenant`（全租户可用）/ `Space`（仅发布空间可用）。★不是 OAuth2 的 scope |
| `publishDate` | 字符串 | 最后修改时间 |

### 工具 schema 对象

`data`，对应端点 2：

| 字段 | 类型 | 说明 |
|---|---|---|
| `targetType` | 字符串 | `plugin` / `workflow` |
| `targetId` | 数字 | 工具 ID |
| `name` | 字符串 | 展示名 |
| `toolName` | 字符串 | 工具名（发布配置里的 `functionName`，缺失时回落成 `plugin{id}` / `workflow{id}`） |
| `description` | 字符串 | 描述 |
| `schema` | 对象 | 自描述文档，见下。★是**对象**不是字符串，不必再解析一次 |

`schema` 的字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `method` | 字符串 | 恒为 `POST` |
| `url` | 字符串 | 执行地址（含 `?pluginId=` / `?workflowId=`）。★`{PLATFORM_BASE_URL}` 是占位符，要换成你的平台地址 |
| `authorization` | 字符串 | `Bearer <access_token>`，填你手头的 `oa-` 令牌 |
| `scope` | 字符串 | 执行该工具所需的 scope：`tool:exec` |
| `contentType` | 字符串 | `application/json` |
| `requestBody` | 对象 | **参数 JSON Schema**（`type` / `properties` / `required`），可直接当 function calling 的 `parameters` |
| `response` | 对象 | 响应结构：外层平台信封 + `data` 里的业务字段（插件为 `success` / `result` / `error` / `requestId` / `costTime`） |
| `streaming` | 对象 | **仅工作流有**：SSE 端点地址、`contentType`、`stopWhen`（`isComplete == true`）与帧结构 |

### 插件结果对象

`data`（原 `PluginResultVo`），对应端点 3：

| 字段 | 类型 | 说明 |
|---|---|---|
| `success` | 布尔 | ★**false 时外层信封仍是 `0000`，必须看本字段** |
| `result` | 对象 | 插件自定义结构，成功时的输出 |
| `error` | 字符串 | 失败原因，成功时为 null |
| `requestId` | 字符串 | 链路 ID（`Trace-Id` 头回显） |
| `costTime` | 数字 | 耗时（毫秒） |

### 工作流结果对象

`data`（原 `WorkflowResultVo`），对应端点 4：

| 字段 | 类型 | 说明 |
|---|---|---|
| `success` | 布尔 | 执行成败（两层判定的内层） |
| `data` | 对象 | 工作流输出 |
| `code` | 字符串 | 工作流业务码：`0000` 成功 / `0001` 失败 |
| `message` | 字符串 | 失败原因 |
| `requestId` | 字符串 | 链路 ID |
| `costTime` | 数字 | 耗时（毫秒） |

### 工作流执行帧对象

SSE 每帧的 JSON（原 `WorkflowExecutingDto`），对应端点 5：

| 字段 | 类型 | 说明 |
|---|---|---|
| `success` | 布尔 | 本帧时的执行状态 |
| `data` | 对象 | 输出（完成帧才有） |
| `code` / `message` | 字符串 | 工作流业务码 / 失败原因 |
| `requestId` | 字符串 | 链路 ID |
| `nodeExecuteResultMap` | 对象 | ★各节点的执行进度与输出，**仅 SSE 帧有** |
| `costTime` | 数字 | 耗时（毫秒） |
| `isComplete` | 布尔 | ★true = 流结束（成功或失败都靠它收流），**仅 SSE 帧有** |

# 任务会话（11 个端点）

前缀 `{PLATFORM_BASE_URL}/api/oauth2/chat`，请求头 `Authorization: Bearer <access_token>`（`oa-` 令牌）。

**本模块的 scope**：`chat:read`（端点 1–4）、`chat:write`（端点 5–11，★敏感 scope）。两者机器对机器（client_credentials）都可用。

★响应一律**平台信封** `{code, displayCode, message, data, tid}`：成败看 `code == "0000"`，业务结果在 `data` 里，HTTP 恒 200（SSE 两个端点除外，直接回帧流）。

**本模块专属错误口径**

- 会话 id 非法 / 会话不存在 / 会话不属于当前用户 → **三种情况合并**成 `0001` `Invalid conversationId`，排障时自己核对会话归属。
- 建会话时智能体未发布 → `4000` 「智能体不存在或已下架」。
- 发布范围权限不足 → `0001` `Permission denied`。

---

## 1. 会话列表 —— `GET /api/oauth2/chat/conversation/list`

scope：`chat:read`。

**请求参数**（query）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `agentId` | 数字 | **是** | 只列这个智能体下的会话 |
| `lastId` | 数字 | 否 | 游标：上一页最后一条的 `id` |
| `limit` | 数字 | 否 | 默认 10 |
| `topic` | 字符串 | 否 | 主题模糊过滤 |

**请求示例**

```
GET /api/oauth2/chat/conversation/list?agentId=1596&limit=10
Authorization: Bearer oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

**响应示例**（`data` = [会话对象](#数据结构会话对象)数组；★**不含消息列表**）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": [
    {
      "id": 90001,
      "tenantId": 1,
      "userId": 21,
      "uid": "conv-8f3a2b1c",
      "agentId": 1596,
      "topic": "季度报告整理",
      "summary": null,
      "icon": null,
      "variables": null,
      "modified": "2026-10-01 18:30:00",
      "created": "2026-10-01 18:00:00",
      "agent": null,
      "messageList": null,
      "type": "Chat",
      "taskId": null,
      "taskStatus": null,
      "taskCron": null,
      "taskCronDesc": null,
      "devMode": 0,
      "topicUpdated": 1,
      "pinned": false,
      "archived": false,
      "collected": false,
      "sharedUris": [],
      "defaultTopic": false
    }
  ],
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- ★**列表接口不返回 `messageList`**，要消息得单独调端点 3。

---

## 2. 会话详情 —— `GET /api/oauth2/chat/conversation/detail`

scope：`chat:read`。

**请求参数**（query）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `conversationId` | 数字 | **是** | 会话 ID |

**响应示例**：`data` = 单个[会话对象](#数据结构会话对象)。主题为空时回填为智能体名相关文案；`agent` 字段带[智能体详情对象](#数据结构智能体详情对象)。

---

## 3. 消息列表 —— `GET /api/oauth2/chat/message/list`

scope：`chat:read`。

**请求参数**（query）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `conversationId` | 数字 | **是** | 会话 ID |
| `index` | 数字 | 否 | 游标：上一页最后一条的 `index` |
| `limit` | 数字 | 否 | 默认 10 |

**响应示例**（`data` = [消息对象](#数据结构消息对象)数组）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": [
    {
      "index": 12,
      "tenantId": 1,
      "senderType": "USER",
      "senderId": "21",
      "userId": 21,
      "agentId": 1596,
      "id": "msg-0001",
      "role": "USER",
      "type": "CHAT",
      "text": "帮我整理季度报告",
      "time": "2026-10-01 18:01:00",
      "attachments": [],
      "think": null,
      "quotedText": null,
      "finished": true,
      "finishReason": null,
      "componentExecutedList": [],
      "messageType": "USER",
      "metadata": null
    },
    {
      "index": 13,
      "tenantId": 1,
      "senderType": "AGENT",
      "senderId": "1596",
      "userId": 21,
      "agentId": 1596,
      "id": "msg-0002",
      "role": "ASSISTANT",
      "type": "CHAT",
      "text": "已整理完成，共 3 个部分……",
      "time": "2026-10-01 18:02:30",
      "attachments": [],
      "think": "先列出报告结构……",
      "quotedText": null,
      "finished": true,
      "finishReason": "stop",
      "componentExecutedList": [],
      "messageType": "AGENT",
      "metadata": null
    }
  ],
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

---

## 4. 订阅实时输出（SSE） —— `GET /api/oauth2/chat/message/subscribe`

scope：`chat:read`。请求头 `Accept: text/event-stream`。

**请求参数**（query）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `conversationId` | 数字 | **是** | 会话 ID（有归属校验：不是你的会话连不上） |

**响应**：SSE 帧流，每帧一个[输出帧对象](#数据结构输出帧对象sse)的 JSON（逐 `eventType` 的 `data` 载荷结构见该节）。

**帧示例**（节选 3 帧，值为示意）

```
data: {"requestId":"req-7f3a","eventType":"PROCESSING","error":null,"data":{"name":"网页搜索","type":"Plugin","status":"EXECUTING","result":null},"completed":false}

data: {"requestId":"req-7f3a","eventType":"MESSAGE","error":null,"data":{"id":"req-7f3a","role":"ASSISTANT","type":"CHAT","text":"已整理完成……","time":"2026-10-01 18:02:30","finished":true},"completed":false}

data: {"requestId":"req-7f3a","eventType":"FINAL_RESULT","error":null,"data":{"success":true,"error":null,"startTime":1759312950000,"endTime":1759312981000,"completionTokens":312,"promptTokens":1840,"totalTokens":2152,"outputText":"已整理完成……"},"completed":true}
```

**客户端逻辑**：`eventType == "ERROR"` 时取外层 `error` 字段；`completed == true` 收流；`HEART_BEAT` 帧忽略但用于保活。

**坑**

- ★订阅时会话必须**正在执行**：不在执行中（`taskStatus != EXECUTING`）首帧直接返回 `ERROR`（`error: "Conversation not executing"`），不会有别的帧。先发起对话（端点 8）再订阅，或直接用端点 8 自己的流。
- 权限校验失败（不是你的会话）也是首帧 `ERROR`，错误信息在外层 `error`。

---

## 5. 创建会话 —— `POST /api/oauth2/chat/conversation/create`

scope：`chat:write`。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `agentId` | 数字 | **是** | 该智能体须**已发布**且当前用户对它有权限 |
| `topic` | 字符串 | 否 | 非空时会话主题按它设置 |
| `variables` | 对象 | 否 | 会话变量（键值对） |
| `devMode` | 布尔 | 否 | ★**传了也无效**（门面不接受开发模式） |

**请求示例**

```json
{
  "agentId": 1596,
  "topic": "季度报告整理",
  "variables": { "quarter": "2026Q3" }
}
```

**响应示例**（`data` = 数字：新会话 id。★创建失败时可能为 `null`）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": 90002,
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

---

## 6. 更新会话主题 —— `POST /api/oauth2/chat/conversation/update`

scope：`chat:write`。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | 数字 | **是** | 会话 ID |
| `topic` | 字符串 | 与 `firstMessage` 二选一 | 直接设主题 |
| `firstMessage` | 字符串 | 与 `topic` 二选一 | 用首条消息内容生成主题 |
| `icon` | 字符串 | 否 | 会话图标 |

**请求示例**

```json
{ "id": 90002, "topic": "2026Q3 季度报告" }
```

**响应示例**：`data` = 更新后的[会话对象](#数据结构会话对象)（信封结构同端点 1 的单条）。

---

## 7. 删除会话 —— `POST /api/oauth2/chat/conversation/delete`

scope：`chat:write`。

**请求参数**

| 位置 | 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| query 或 form | `conversationId` | 数字 | **是** | ★**不是 JSON body**——写成 `{"conversationId":123}` 的 JSON 会报「参数不能为空」 |

**请求示例**

```
POST /api/oauth2/chat/conversation/delete?conversationId=90002
Authorization: Bearer oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

**响应示例**（`data` 为 null）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": null,
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

---

## 8. 发起对话（SSE） —— `POST /api/oauth2/chat/message/send`

scope：`chat:write`。请求头 `Accept: text/event-stream`；响应是[输出帧对象](#数据结构输出帧对象sse)的 SSE 流（同端点 4）。

**请求体**（字段较多，常用的加粗）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| **`conversationId`** | 数字 | **是** | 会话必须属于当前用户 |
| **`message`** | 字符串 | 否 | 用户输入文本 |
| `variableParams` | 对象 | 否 | 变量键值对 |
| `attachments` | 数组 | 否 | 附件条目均字符串：`fileUrl`（文件地址）与 `mimeType`（文件类型）**必填**（官方文档口径），`fileKey` / `fileName` 可选。示例：`[{"fileUrl":"https://…/a.png","mimeType":"image/png","fileName":"a.png"}]` |
| `debug` | 布尔 | 否 | 默认 false |
| `from` | 字符串 | — | ★**服务端强制覆盖为 `"api"`**，传了也无效 |
| `sandboxId` | 数字 | 否 | 罕用 |
| `selectedComponents` | 数组 | 否 | 条目 `{id:数字, type:字符串}`，type 枚举：`Plugin`/`Workflow`/`Trigger`/`Knowledge`/`Variable`/`Database`/`Model`/`Agent`/`Table`/`Mcp`/`Page`/`Event`/`Skill`/`SubAgent`/`Hook` |
| `skillIds` | 数字数组 | 否 | 罕用 |
| `selectedDocs` | 数组 | 否 | 条目 `{slugId, title, pageType}` 均字符串 |
| `filterSensitive` | 布尔 | — | ★**服务端强制覆盖为 null**，传了也无效 |
| `modelId` | 数字 | 否 | 指定模型 |
| `requestId` | 字符串 | 否 | 罕用 |
| `agentMode` | 字符串 | 否 | 罕用 |
| `agentConfig` | 对象 | — | ★**服务端强制覆盖为 null**，传了也无效（字段全部被忽略） |
| `imVars` | 对象 | 否 | 罕用 |

**请求示例**

```json
{
  "conversationId": 90002,
  "message": "帮我整理季度报告",
  "variableParams": { "quarter": "2026Q3" }
}
```

**坑**

- ★**SSE 端点的错误在流内，不抛异常**：连接建立成功不代表任务成功，必须解析帧内容（`eventType`、`error`、`completed`）。

---

## 9. 停止对话 —— `POST /api/oauth2/chat/message/stop`

scope：`chat:write`。

**请求参数**

| 位置 | 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| query 或 form | `conversationId` | **字符串** | **是** | ★不是 JSON body；★本端点是**字符串**类型（与其他端点的数字不同） |

**请求示例**

```
POST /api/oauth2/chat/message/stop?conversationId=90002
Authorization: Bearer oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

**响应**：信封，`data` 为 null。

---

## 10. 清空历史消息 —— `POST /api/oauth2/chat/message/clear`

scope：`chat:write`。

**请求参数**

| 位置 | 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| query 或 form | `conversationId` | 数字 | **是** | ★不是 JSON body |

**请求示例**

```
POST /api/oauth2/chat/message/clear?conversationId=90002
Authorization: Bearer oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

**响应**：信封，`data` 为 null。

---

## 11. 回复权限请求 —— `POST /api/oauth2/chat/message/permission-response`

scope：`chat:write`。用于智能体执行中向你请求授权的场景（例如要执行敏感操作）：你从 SSE 帧里收到权限请求，用户点选后调本端点把选项回传。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `toolId` | 字符串 | 否 | 权限请求里给的工具 ID，**原样回传** |
| `conversationId` | 数字 | **是** | 会话 ID |
| `option.optionId` | 字符串 | 任务型会话时必填 | 用户点选的选项 ID |
| `option.outcome` | 字符串 | 否 | ★**传了也无效**，一律按 `"selected"` 处理 |

**请求示例**

```json
{
  "toolId": "tool-send-email",
  "conversationId": 90002,
  "option": { "optionId": "opt-allow-once" }
}
```

**响应**：信封，`data` 为 null。

**坑**

- ★**非任务型会话调用它会直接返回成功但不产生任何通知**——不要拿它当通用消息发送接口。

---

## 数据结构

### 会话对象

`data`（原 `ConversationDto`），对应端点 1 / 2 / 6：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | 数字 | 会话 ID |
| `tenantId` / `userId` / `agentId` | 数字 | 租户 / 归属用户 / 智能体 |
| `uid` | 字符串 | 会话唯一标识 |
| `topic` | 字符串 | 主题（详情端点里为空时回填智能体名文案） |
| `summary` / `icon` | 字符串 | 摘要 / 图标 |
| `variables` | 对象 | 会话变量 |
| `modified` / `created` | 字符串 | 时间 |
| `agent` | 对象 | [智能体详情对象](#数据结构智能体详情对象)（列表接口通常为 null） |
| `messageList` | 数组 | ★**列表查询不返回**，要消息单独调端点 3 |
| `type` | 字符串 | 枚举：`Chat` / `TempChat` / `TASK` / `TaskCenter` / `Development` / `DevDebug` / `IM` / `Repo` |
| `taskId` | 字符串 | 任务 ID（任务型会话） |
| `taskStatus` | 字符串 | 枚举：`CREATE` / `EXECUTING` / `CANCEL` / `COMPLETE` / `FAILED` |
| `taskCron` / `taskCronDesc` | 字符串 | 定时任务 cron 与描述 |
| `devMode` | 数字 | ★null 会序列化为 0 |
| `topicUpdated` | 数字 | 主题是否被改过 |
| `sandboxServerId` / `sandboxSessionId` | 字符串 | 沙箱信息（罕用） |
| `devSpaceId` | 数字 | 开发空间（罕用） |
| `devTargetType` | 字符串 | `Agent` / `PageApp` / `Skill` / `Plugin`（罕用） |
| `devTargetId` | 字符串 | 注意是**字符串**（罕用） |
| `fileWorkspacePath` / `agentWorkspacePath` | 字符串 | 工作区路径（罕用） |
| `pinned` / `archived` / `collected` | 布尔 | 置顶 / 归档 / 收藏 |
| `sharedUris` | 字符串数组 | 分享链接 |
| `defaultTopic` | 布尔 | 是否仍是默认主题（派生属性，随 JSON 输出） |

### 消息对象

`data` 数组元素（原 `ChatMessageDto`），对应端点 3；SSE `MESSAGE` 帧的 `data` 也是同款结构（流式帧是其子类，另带 `ext` / `executeId` 两个扩展字段）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `index` | 数字 | 游标（翻页用） |
| `tenantId` / `userId` / `agentId` | 数字 | 归属 |
| `senderType` | 字符串 | 枚举：`USER` / `AGENT` / `REMINDER` |
| `senderId` | 字符串 | 发送者 ID（字符串形态） |
| `id` | 字符串 | 消息 ID |
| `role` | 字符串 | 枚举：`USER` / `ASSISTANT` / `SYSTEM` / `FUNCTION` |
| `type` | 字符串 | 枚举：`CHAT` / `THINK` / `PROCESS_OUTPUT` / `QUESTION` / `ANSWER` |
| `text` | 字符串 | 正文 |
| `time` | 字符串 | 时间 |
| `attachments` | 数组 | 附件条目：`fileKey` / `fileUrl` / `fileName` / `mimeType` 均字符串 |
| `think` | 字符串 | 思考过程文本 |
| `quotedText` | 字符串 | 引用文本 |
| `finished` | 布尔 | 是否已完成 |
| `finishReason` | 字符串 | 结束原因 |
| `componentExecutedList` | 数组 | 组件执行记录 |
| `messageType` | 字符串 | 派生自 `role`，会随 JSON 输出 |
| `metadata` | 对象 | ★**恒为 null**（保留位） |

### 输出帧对象（SSE）

每帧的 JSON（原 `AgentOutputDto`），对应端点 4 / 8：

| 字段 | 类型 | 说明 |
|---|---|---|
| `requestId` | 字符串 | 本次请求 ID |
| `eventType` | 字符串 | 枚举：`PROCESSING` / `PROCESSING_MESSAGE` / `MESSAGE` / `STATE` / `FINAL_RESULT` / `HEART_BEAT` / `ERROR` |
| `error` | 字符串 | 仅 `ERROR` 帧有值 |
| `data` | 对象 | 帧载荷，结构随 `eventType`（见下） |
| `completed` | 布尔 | ★true = 收流 |

客户端逻辑：`eventType=="ERROR"` 取外层 `error`；`completed==true` 收流；`HEART_BEAT` 忽略但用于保活。

**各 `eventType` 的 `data` 载荷**（按服务端源码核实）：

| `eventType` | `data` 结构 |
|---|---|
| `PROCESSING` | 组件执行对象（见下方表）——工具/组件调用的开始与结束各发一帧（`status` 区分） |
| `MESSAGE` | [消息对象](#数据结构消息对象)同款结构（流式帧另有 `ext` / `executeId` 两个扩展字段）；正文帧 `role="ASSISTANT"`、`type="CHAT"`，思考帧 `type="THINK"` |
| `FINAL_RESULT` | 执行统计对象（见下方表），本帧 `completed=true`，是流的最后一帧 |
| `STATE` | 仅流程编排版智能体会发：`{currentNodeId, nodeStatus, executionPath, sessionState}`（`sessionState` 是会话状态键值对，服务端已剔除敏感项） |
| `HEART_BEAT` | 恒 `null`，直接忽略（仅保活；约 10 秒一帧） |
| `PROCESSING_MESSAGE` | 预留类型，当前版本服务端不发射；若出现按 `PROCESSING` 同构处理 |
| `ERROR` | 错误信息在**外层** `error` 字段，`data` 为 `null` |

**组件执行对象**（原 `ComponentExecutingDto`，`PROCESSING` 帧的 `data`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | 字符串 | 工具/组件名称 |
| `type` | 字符串 | 组件类型枚举：`Plugin` / `Workflow` / `Mcp` / `Table` / `Knowledge` / `Database` / `Agent` / `Model` / `Variable` / `Page` / `ToolCall` / `Plan` / `Event` / `SandboxStart` / `Control` / `Im` |
| `status` | 字符串 | `EXECUTING`（开始帧）/ `FINISHED`（成功结束帧）/ `FAILED`（失败结束帧） |
| `result` | 对象 | 执行结果对象（见下方表），★结束帧才有值，开始帧为 `null` |
| `executingMessage` | 对象 | 执行中的过程信息（罕用） |
| `originalTitle` / `targetId` | 字符串 / 数字 | 原始标题 / 组件配置 ID（罕用） |
| `subEventType` | 字符串 | 沙箱交互子事件：`OPEN_DESKTOP` / `OPEN_FILE` / `REQUEST_PERMISSION` / `ASK_QUESTION` / `RENDER_UI`（罕用） |
| `cardBindConfig` / `cardData` / `pageArgConfig` | 对象 | 卡片/页面绑定数据（罕用） |

**执行结果对象**（组件执行对象的 `result`，原 `ComponentExecuteResult`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` / `name` / `icon` | 数字 / 字符串 / 字符串 | 组件 ID / 名称 / 图标 |
| `type` | 字符串 | 同组件类型枚举 |
| `success` / `error` | 布尔 / 字符串 | 是否成功 / 失败原因 |
| `data` | 对象 | 组件输出数据（结构随组件而变） |
| `startTime` / `endTime` | 数字 | 毫秒时间戳 |
| `input` | 对象 | 组件入参（罕用） |
| `executeId` / `kind` / `locations` / `innerExecuteInfo` | — | 罕用 |

**执行统计对象**（原 `AgentExecuteResult`，`FINAL_RESULT` 帧的 `data`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `success` | 布尔 | 本次执行是否成功 |
| `error` | 字符串 | 失败信息（成功时为 `null`） |
| `startTime` / `endTime` | 数字 | 开始 / 结束毫秒时间戳 |
| `completionTokens` / `promptTokens` / `totalTokens` | 数字 | 输出 / 输入 / 总 token 数 |
| `outputText` | 字符串 | 汇总正文——★只想要最终回答的话取这个字段，不必自己拼 `MESSAGE` 帧 |
| `componentExecuteResults` | 数组 | 各组件执行记录；★`debug=false`（默认）时**不下发** |

### 智能体详情对象

会话对象的 `agent` 字段（原 `AgentDetailDto`）。字段多，日常集成常用的是前几行，其余标「罕用」：

| 字段 | 类型 | 说明 |
|---|---|---|
| `agentId` / `spaceId` / `creatorId` | 数字 | 智能体 / 空间 / 创建者 ID |
| `uid` | 字符串 | 智能体唯一标识 |
| `name` / `description` / `icon` / `remark` / `category` | 字符串 | 基本信息 |
| `type` / `subType` | 字符串 | 类型（`ChatBot` / `PageApp` / `TaskAgent` 等） |
| `publishDate` | 字符串 | 发布时间 |
| `statistics` | 对象 | [统计对象](#数据结构统计对象) |
| `publishUser` | 对象 | [发布者对象](#数据结构发布者对象) |
| `isCollect` | 布尔 | 当前用户是否收藏 |
| `openSuggest` / `openScheduledTask` | 字符串 | 枚举 `Open` / `Close`：问题建议 / 定时任务开关 |
| `openingChatMsg` | 字符串 | 首次打开自动回复 |
| `openingGuidQuestions` | 字符串数组 | 开场引导问题（旧字段，罕用） |
| `guidQuestionDtos` | 数组 | [引导问题对象](#数据结构引导问题对象)（罕用） |
| `variables` | 数组 | [参数对象](#数据结构参数对象)：智能体变量定义（罕用） |
| `shareLink` | 字符串 | 分享链接 |
| `manualComponents` | 数组 | [手动组件对象](#数据结构手动组件对象)（罕用） |
| `allowCopy` | 数字 | 1 = 允许复制 |
| `conversationId` / `sandboxId` | 数字 | 开发会话 / 沙箱（罕用） |
| `hasPermission` | 布尔 | 当前用户有无权限 |
| `expandPageArea` / `hideChatArea` / `hideDesktop` | 数字 | 1 = 展开扩展页面区 / 隐藏聊天区 / 隐藏远程桌面（罕用） |
| `allowOtherModel` / `allowAtSkill` / `allowPrivateSandbox` / `allowChooseMode` / `enableVersionControl` | 数字 | 均 1 = 允许（罕用） |
| `pageHomeIndex` | 字符串 | 页面首页索引（罕用） |
| `customPageMenus` | 数组 | [自定义页面菜单](#数据结构自定义页面菜单)：服务端由 Page 组件派生（罕用） |
| `eventBindConfig` | 对象 | [事件绑定配置](#数据结构事件绑定配置)（罕用） |
| `paymentRequired` | 布尔 | 是否付费 |
| `trialCount` / `calledTrialCount` | 数字 | 试用次数 / 已试用次数 |
| `subscribed` / `overCallLimit` / `showPublishBtn` | 布尔 | 订阅 / 超调用上限 / 展示发布按钮 |

### 统计对象

`statistics`（原 `StatisticsDto`），各计数字段均为数字：

| 字段 | 说明 |
|---|---|
| `targetId` | 统计目标 ID |
| `userCount` / `convCount` / `collectCount` / `likeCount` / `referenceCount` | 用户数 / 会话数 / 收藏数 / 点赞数 / 引用数 |
| `callCount` / `failCallCount` / `totalCallDuration` | 调用总数 / 失败数 / 总时长 |

### 发布者对象

`publishUser`（原 `PublishUserDto`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `userId` | 数字 | 用户 ID |
| `userName` / `nickName` / `avatar` | 字符串 | 账号名 / 昵称 / 头像 |

### 引导问题对象

`guidQuestionDtos` 元素（原 `GuidQuestionDto`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | 字符串 | 枚举：`Question` / `Page` / `Link` |
| `title` / `info` / `icon` | 字符串 | 标题 / 问题信息 / 图标 |
| `url` | 字符串 | `type=Link` 时有效 |
| `pageId` | 数字 | `type=Page` 时有效 |
| `basePath` / `pageUri` | 字符串 | `type=Page` 的路径信息 |
| `pageUrl` | 字符串 | 完整页面地址（前端拼好；配置更新时不传） |
| `args` | 数组 | [参数对象](#数据结构参数对象) |
| `params` | 对象 | `type=Page` 点击跳转直接使用（配置更新时不传） |

### 参数对象

`args` / `variables` 元素（原 `Arg`），描述一个输入参数的定义：

| 字段 | 类型 | 说明 |
|---|---|---|
| `key` | 字符串 | 唯一标识（后台自动生成） |
| `name` / `displayName` / `description` | 字符串 | 参数名 / 展示名 / 描述 |
| `dataType` | 字符串 | 数据类型：`String` / `Integer` / `Number` / `Boolean` / `Object` / `File` 及 `Array_*` 系列（★未设置时序列化为 `String`） |
| `require` | 布尔 | 是否必填 |
| `enable` | 布尔 | ★未设置时序列化为 `true`；false 时对使用者和大模型不可见 |
| `systemVariable` | 布尔 | 系统内置变量（只读） |
| `bindValueType` | 字符串 | `Input`（输入）/ `Reference`（变量引用） |
| `bindValue` | 字符串 | 引用时为 `节点ID.字段`；输入时为最终值 |
| `subArgs` | 数组 | 下级参数（递归同结构）。★JSON 里会同时出现别名属性 `children`，两者内容一致 |
| `inputType` | 字符串 | 录入形态：`Query` / `Body` / `Header` / `Path` / `Text` / `Paragraph` / `Select` / `MultipleSelect` / `Number` / `AutoRecognition` / `File` / `Radio` / `FixedValue` |
| `selectConfig` | 对象 | 下拉配置：`dataSourceType`（`MANUAL`/`BINDING`）、`targetType`/`targetId`/`targetName`/`targetDescription`/`targetIcon`、`options`（数组，元素 `{label, value, children}`） |
| `updateStrategy` | 字符串 | `REPLACE`（整体覆盖）/ `APPEND`（追加） |
| `loopId` | 数字 | 循环 ID（罕用） |
| `originDataType` | 字符串 | 原始类型（罕用） |

### 手动组件对象

`manualComponents` 元素（原 `AgentManualComponentDto`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | 数字 | 组件配置 ID |
| `name` / `icon` / `description` | 字符串 | 组件名 / 图标 / 描述 |
| `type` | 字符串 | 枚举：`Plugin` / `Workflow` / `Trigger` / `Knowledge` / `Variable` / `Database` / `Model` / `Agent` / `Table` / `Mcp` / `Page` / `Event` / `Skill` / `SubAgent` / `Hook` |
| `defaultSelected` | 数字 | 0 = 否，1 = 是 |

### 事件绑定配置

`eventBindConfig`（原 `EventBindConfigDto`）只有一个字段 `eventConfigs`（数组），元素：

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` / `identification` | 字符串 | 事件名 / 事件标识 |
| `type` | 字符串 | `Link`（外链）/ `Page`（扩展页面） |
| `url` | 字符串 | `type=Link` 时有效 |
| `pageId` | 数字 | `type=Page`；更新时需传 |
| `basePath` / `pageUrl` | 字符串 | `type=Page`；不需传 |
| `pageName` / `pageUri` | 字符串 | `type=Page`；`pageUri` 更新时需传 |
| `args` | 数组 | [参数对象](#数据结构参数对象) |
| `argJsonSchema` | 字符串 | 参数 JSON Schema |

### 自定义页面菜单

`customPageMenus` 元素（服务端由 Page 组件**派生生成**，只读）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` / `icon` / `description` | 字符串 | 菜单名 / 图标 / 描述 |
| `path` | 字符串 | 菜单路径（形如 `/page{basePath}-{agentId}/prod/`） |
| `selected` | 布尔 | 是否为首页菜单 |

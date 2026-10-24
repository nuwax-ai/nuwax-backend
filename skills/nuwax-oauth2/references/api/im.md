# 消息通信 IM（5 个端点）

前缀 `{PLATFORM_BASE_URL}/api/oauth2/im`，请求头 `Authorization: Bearer <access_token>`（`oa-` 令牌）。

**本模块的 scope**：`im:read`（1–4 读接口）、`im:push`（5 推送）。★两个都是**「仅授权码」scope——机器对机器（client_credentials）拿不到**：必须有真人在浏览器里登录授权一次，你的系统要存得住令牌。

★响应一律**平台信封** `{code, displayCode, message, data, tid}`：成败看 `code == "0000"`，业务结果在 `data` 里，HTTP 恒 200。

IM 侧故障的专属错误码：`3612` IM 服务未配置或已停用 / `3613` 暂时不可用（**可带同一 `bizMsgId` 重试**，幂等不会重发）/ `3614` IM 上游拒绝（message 带原因）。

---

## 1. 会话列表 —— `GET /api/oauth2/im/conversation/list`

scope：`im:read`。授权用户的 IM 会话（单聊+群聊混合）。

**请求参数**（query）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `cursor` | 字符串 | 否 | 不透明游标，取上一页响应的 `nextCursor` |
| `limit` | 数字 | 否 | 每页条数 |
| `traceId` | 字符串 | 否 | 链路 ID |

**请求示例**

```
GET /api/oauth2/im/conversation/list?limit=20
Authorization: Bearer oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

**响应示例**（`data` = [会话分页对象](#数据结构会话分页对象)）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": {
    "list": [
      {
        "convId": 10086,
        "convType": 2,
        "name": "研发冲刺组",
        "avatar": null,
        "notice": null,
        "ownerUserId": 21,
        "memberCount": 8,
        "maxMember": 2000,
        "status": 1,
        "spaceId": 57,
        "lastMsgId": 7301234567890123456,
        "lastMsgSeq": 152,
        "lastMsgAt": 1759900000000,
        "lastMsgDigest": "下周投放方案已更新",
        "lastSenderId": 33,
        "lastSenderName": "李四",
        "peerUserId": null,
        "peerName": null,
        "peerAvatar": null,
        "myRole": 1,
        "myNickName": "张三",
        "unreadCount": 3,
        "topFlag": false,
        "topTime": null,
        "dnd": false,
        "visible": true,
        "createTime": 1750000000000,
        "joinTime": 1750000000000
      }
    ],
    "hasMore": true,
    "nextCursor": "eyJzZXEiOjE1Mn0"
  },
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

---

## 2. 群组列表 —— `GET /api/oauth2/im/group/list`

scope：`im:read`。参数与响应结构同「会话列表」，只返回群聊类会话。

★**一页可能少于 `limit`、甚至为空但 `hasMore=true`**（服务端按会话类型过滤后的结果）⇒ **翻页判停只看 `hasMore`**，按「本页条数 < limit 就停」会漏数据。

---

## 3. 联系人列表 —— `GET /api/oauth2/im/contact/list`

scope：`im:read`。

**请求参数**（query）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `traceId` | 字符串 | 否 | 链路 ID |

**响应示例**（`data` = [联系人列表对象](#数据结构联系人列表对象)；三种状态混合返回）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": {
    "items": [
      {
        "userId": 33,
        "subjectType": 1,
        "userName": "lisi",
        "nickName": "李四",
        "avatar": "https://files.example.com/f/abcd?ak=yyyy",
        "remark": null,
        "displayName": "李四",
        "status": 1,
        "source": 2,
        "applyMsg": null,
        "time": 1759900000000
      },
      {
        "userId": 1596,
        "subjectType": 2,
        "userName": null,
        "nickName": "小助手",
        "avatar": null,
        "remark": null,
        "displayName": "小助手",
        "status": 1,
        "source": 1,
        "applyMsg": null,
        "time": 1759800000000
      }
    ],
    "degraded": false,
    "truncated": false
  },
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- **500 条上限**：撞上限时 `truncated=true`；用户资料部分取不到时 `degraded=true`（但列表本身完整）。
- ★智能体的 `userName` 恒为 null，**请用 `displayName` 展示**（服务端已算好：备注 > 昵称 > 账号名 > `用户{id}`）。
- ★`userId` 不透明：真人与智能体同形，别按「id 大小 / 奇偶」猜身份，看 `subjectType`。

---

## 4. 会话消息列表 —— `GET /api/oauth2/im/message/list`

scope：`im:read`。

**请求参数**（query）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `convId` | 数字 | **是** | 会话 ID |
| `startTime` | 数字 | 否 | epoch 毫秒，时间窗起点 |
| `endTime` | 数字 | 否 | epoch 毫秒，时间窗终点 |
| `afterSeq` | 数字 | 否 | 增量拉取：只取 seq 大于它的消息 |
| `limit` | 数字 | 否 | 每页条数 |
| `traceId` | 字符串 | 否 | 链路 ID |

**响应示例**（`data` = [消息分页对象](#数据结构消息分页对象)，`list` **按 seq 升序**）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": {
    "list": [
      {
        "msgId": 7301234567890123456,
        "convId": 10086,
        "seq": 152,
        "senderId": 33,
        "senderType": 1,
        "msgType": "text",
        "body": { "text": "下周投放方案已更新" },
        "digest": "下周投放方案已更新",
        "msgStatus": 1,
        "replyToMsgId": null,
        "mentionUserIds": [],
        "mentionAll": false,
        "sendTime": 1759900000000,
        "revokeTime": null,
        "editTime": null,
        "readCount": null,
        "ext": null
      }
    ],
    "hasMore": false,
    "nextSeq": null
  },
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- ★`msgId` 是雪花 ID，**JavaScript 侧按字符串解析**（超出 Number 安全整数范围）。
- ★`readCount` 为 `null` ≠ 0（表示未统计）；本端点返回原始投影，viewer 相关字段为 null。
- `hasMore=false` 时 `nextSeq` **恒为 null**；分页结果**没有 `total` 字段**，做不了「共 N 条」，只能无限滚动/增量拉取。

---

## 5. 推送消息 —— `POST /api/oauth2/im/message/push`

scope：`im:push`。以**授权用户本人**的身份发消息。

**请求体**（JSON）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `toUserId` | 数字 | **与 `convId` 二选一，必须且只能给一个** | 私聊目标用户。都给 / 都不给 → `4000` `exactly one of toUserId or convId` |
| `convId` | 数字 | 同上 | 群聊或既有会话 |
| `msgType` | 字符串 | **是** | 合法值与对应 `body` 结构见下表。★传了不认识的值 → `3614`（message 带 IM 侧原因） |
| `body` | 对象 | **是** | 结构随 `msgType` 变化（见下表），服务端按类型做结构化校验。★传 JSON `null` 也会被拒 |
| `replyToMsgId` | 数字 | 否（仅群聊） | 引用回复 |
| `mentionUserIds` | 数字数组 | 否（仅群聊） | @ 指定用户 |
| `mentionAll` | 布尔 | 否 | 默认 false，@ 全体 |
| `ext` | 字符串 | 否 | 扩展 JSON 字符串 |
| `bizMsgId` | 字符串 | 否 | ★**幂等键**。不传则服务端生成 UUID（去横线）并在响应回显 |
| `traceId` | 字符串 | 否 | 链路 ID |

**`msgType` 合法值与 `body` 结构**（全集就这 10 个，小写；大小写混写会被归一成小写）

| `msgType` | 含义 | `body` 结构 |
|---|---|---|
| `text` | 文本 | `{"text": "..."}`。★`text` 为空会被拒（`3614`） |
| `rich_text` | 富文本 | `{"text": "<正文>", "attachments": [{"kind": "image/file/video/audio", "url": "...", "fileName": "...", "size": 123}], "segments": [{"type": "text", "text": "...", "color": "#rrggbb", "bg": "#rrggbb"}, {"type": "image", "attachment": 0}]}`。`attachments` / `segments` 可省（纯文字富文本只发 `{"text": "..."}` 也认）；`attachment` 是 `attachments[]` 的下标 |
| `image` / `file` / `audio` / `video` | 单体媒体 | 顶层 `{"url": "<可访问的下载地址>", "fileName": "...", "size": 123}`（`size` 可省）。媒体文件要先经你自己的存储拿到 URL——本端点不做文件上传 |
| `card` | 卡片 | `{"title": "...", "desc": "..."}`（★`subtitle` 是已废弃的别名，`desc` 优先） |
| `system` | 居中系统提示条 | `{"text": "..."}`。通常由服务端事件生成，第三方构造会以你本人的名义发出，慎用 |
| `meeting` | 会议（预留） | 业务未实现，别用 |
| `merge_forward` | 合并转发快照卡 | 只能由 IM 的合并转发能力生成，★不要手工构造 |

**请求示例**（私聊发文本）

```json
{
  "toUserId": 33,
  "msgType": "text",
  "body": { "text": "你好，方案我看过了" },
  "bizMsgId": "order-notify-20261001-0001"
}
```

**响应示例**（`data` = [推送结果对象](#数据结构推送结果对象)）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": {
    "bizMsgId": "order-notify-20261001-0001",
    "message": {
      "msgId": 7301234567890123999,
      "convId": 10010,
      "seq": 7,
      "sendTime": 1759900001000,
      "idempotentHit": false
    }
  },
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- ★**发送者恒为授权用户本人**，不做 AK 身份换算，不能代别人发。
- ★**幂等**：同一个 `bizMsgId` 重复推送不会发出第二条，响应里 `idempotentHit=true`。网络重试请务必带上你自己生成的 `bizMsgId`（`3613` 重试同理）。
- 单聊的 `convId` 由服务端创建/复用后在响应 `message.convId` 回填。
- ★**不开放**「以系统身份发消息」「事件推送」「撤回消息」三类能力。撤回在 IM 侧还会留一条全群可见的「XX 撤回了一条消息」墓碑，本就不适合开放。

---

## 数据结构

IM 的数据对象在服务端均为 record，JSON 只含下列字段。

### 会话分页对象

`data`（原 `ImConversationPageDto`），对应端点 1 / 2：

| 字段 | 类型 | 说明 |
|---|---|---|
| `list` | 数组 | 会话摘要对象（见下） |
| `hasMore` | 布尔 | 翻页判停**只看它** |
| `nextCursor` | 字符串 | ★`hasMore=false` 时恒 null；**无 `total` 字段** |

### 会话摘要对象

`list` 元素（原 `ImConversationSummaryDto`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `convId` | 数字 | 会话 ID |
| `convType` | 数字 | 1 单聊 / 2 群聊 / 3 系统 / 4 团队群 |
| `name` | 字符串 | 会话名（单聊由服务端填对端昵称） |
| `avatar` / `notice` | 字符串 | 头像 / 公告 |
| `ownerUserId` | 数字 | 群主用户 ID |
| `memberCount` | 数字 | 成员数 |
| `maxMember` | 数字 | 成员上限（默认 2000） |
| `status` | 数字 | 1 正常 / 2 解散 / 3 封禁 |
| `spaceId` | 数字 | 归属空间 |
| `lastMsgId` / `lastMsgSeq` / `lastMsgAt` | 数字 | 最后一条消息的 id / 序号 / 时间（`lastMsgAt` 是 epoch ms，**排序键**） |
| `lastMsgDigest` | 字符串 | 最后一条消息摘要 |
| `lastSenderId` / `lastSenderName` | 数字 / 字符串 | 最后发送者 |
| `peerUserId` / `peerName` / `peerAvatar` | 数字 / 字符串 / 字符串 | 单聊对端信息 |
| `myRole` | 数字 | 我在群里的角色：1 普通 / 2 管理员 / 3 群主 |
| `myNickName` | 字符串 | 我的群昵称 |
| `unreadCount` | 数字 | 未读数（快照值） |
| `topFlag` / `topTime` | 布尔 / 数字 | 置顶标记与时间 |
| `dnd` | 布尔 | 免打扰 |
| `visible` | 布尔 | 是否可见 |
| `createTime` / `joinTime` | 数字 | epoch 毫秒 |

### 联系人列表对象

`data`（原 `ImContactListDto`），对应端点 3：

| 字段 | 类型 | 说明 |
|---|---|---|
| `items` | 数组 | 联系人条目（见下），三态混合 |
| `degraded` | 布尔 | true = 用户资料未取到，但列表完整 |
| `truncated` | 布尔 | true = 撞 500 上限被截断 |

### 联系人条目

`items` 元素（原 `ImContactItemDto`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `userId` | 数字 | ★不透明，真人与智能体同形 |
| `subjectType` | 数字 | 1 真人 / 2 智能体 |
| `userName` | 字符串 | ★智能体恒 null |
| `nickName` / `avatar` / `remark` | 字符串 | 昵称 / 头像 / 我的备注 |
| `displayName` | 字符串 | 服务端算好的展示名：备注 > 昵称 > 账号名 > `用户{id}` |
| `status` | 数字 | 1 互为联系人 / 2 对方申请我 / 3 我申请对方 |
| `source` | 数字 | 来源（两侧口径不一致，原样透传） |
| `applyMsg` | 字符串 | 申请附言 |
| `time` | 数字 | epoch 毫秒 |

### 消息分页对象

`data`（原 `ImMessagePageDto`），对应端点 4：

| 字段 | 类型 | 说明 |
|---|---|---|
| `list` | 数组 | 消息明细（见下），**seq 升序** |
| `hasMore` | 布尔 | 翻页判停 |
| `nextSeq` | 数字 | `hasMore=false` 时恒 null |

### 消息明细对象

`list` 元素（原 `ImMessageDetailDto`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `msgId` | 数字 | 雪花 ID，★JS 侧按 string 解析 |
| `convId` / `seq` / `senderId` | 数字 | 会话 / 序号 / 发送者 |
| `senderType` | 数字 | 1 真人 / 2 机器人 / 3 系统 |
| `msgType` | 字符串 | 消息类型，合法值全集见端点 5 的「msgType 合法值与 body 结构」表 |
| `body` | 对象 | 消息体，结构随 `msgType`（同端点 5 的表） |
| `digest` | 字符串 | 摘要 |
| `msgStatus` | 数字 | 1 正常 / 2 撤回 / 3 审核拦截 |
| `replyToMsgId` | 数字 | 引用回复的消息 ID |
| `mentionUserIds` | 数字数组 | @ 的用户 |
| `mentionAll` | 布尔 | 是否 @ 全体 |
| `sendTime` / `revokeTime` / `editTime` | 数字 | epoch 毫秒 |
| `readCount` | 数字 | ★null ≠ 0（未统计） |
| `ext` | 字符串 | 扩展 |

### 推送结果对象

`data`（原 `ImPushResult`），对应端点 5：

| 字段 | 类型 | 说明 |
|---|---|---|
| `bizMsgId` | 字符串 | 幂等键回显（你没传则是服务端生成的） |
| `message` | 对象 | 已发消息的回执（见下） |

`message`（原 `ImMessageDto`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `msgId` | 数字 | 消息 ID |
| `convId` | 数字 | 会话 ID（单聊时服务端创建/复用后回填） |
| `seq` | 数字 | 消息序号（排序用） |
| `sendTime` | 数字 | epoch 毫秒 |
| `idempotentHit` | 布尔 | ★true = 幂等命中（这条没有真的再发一次） |

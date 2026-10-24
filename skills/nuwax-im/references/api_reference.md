# nuwax-im 接口参考

21 个端点全部 **POST**，前缀 `/api/v1/4sandbox/nuwax-im`，鉴权 `Authorization: Bearer $SANDBOX_ACCESS_KEY`。

★可用面：**21 个端点里 3 个仅管理员** —— `3. send-system`、`21. system-notify`、`18. event-push`
（判据 = 登录态 `UserDto.role == Admin`，非管理员返回 `4030` 无权限）；
**2 个仅智能体身份** —— `19. agent-pending-list` / `20. agent-pending-clear`
（拿不到智能体虚拟身份就本地 `4030`，原文「本接口只能以智能体身份调用」）；
其余 **16 个**任何持 AK 的用户都能调。

## 响应信封（所有端点统一）

```json
{ "success": true, "code": "0000", "message": "success", "data": {}, "tid": "..." }
```

- **判成功看 `code`，不是 HTTP 状态码**：业务错误也返回 HTTP 200。
  `code == "0000"` 才是成功。
- `tid` 是链路跟踪 ID，报障时带上它。

平台层错误码（由本 API 层产生）：

| code | 含义 | 可重试 |
|---|---|---|
| `0000` | 成功 | — |
| `3612` | IM 适配层未配置/停用（**请求从未发出**） | ❌ 部署问题，找运维 |
| `3613` | IM 暂时不可用（网络层失败 / 上游暂时性故障） | ⚠️ 带同一个 `bizMsgId` 重试，**但先看原文** —— `IM_90002`（与平台团队空间同步失败：建群失败、或成员变更**没问成**）也会走这个码，那种**永远重试不出结果** |
| `3614` | IM 业务拒绝，`message` 是上游原文 | ❌ 改请求 |
| `4000` | **参数错误**（`ErrorCodeEnum.INVALID_PARAM`）——必填项缺失/越界等，请求已被拒绝，无副作用 | ❌ 改参数，别原样重试 |
| `4010` | 未登录 / 会话过期 | ❌ 换 AK |
| `4030` | 无权限 —— ★**复合码，至少三种情况**：① API Key 无效/缺失；② **调用了管理员端点而调用者不是平台管理员**（`3. send-system` / `18. event-push`）；③ **以非智能体身份调用排队区端点**（`19.` / `20.`） | ❌ 看 `message` 原文分情况：`无权限` ⇒ 换端点或让管理员来（**换 AK 无效**）；`本接口只能以智能体身份调用` ⇒ 这次运行不是 IM 消息触发的，**换 AK 同样无效**；`无效的 API Key` / `缺少 API Key` ⇒ 才去查 AK（租户无需传，服务端按 AK 自行解析） |
| `4040` | 路径不存在 —— 多半是服务端版本不含本 API | ❌ 检查部署 |

> ★`4000` = **参数**问题，`4030` = **身份/权限**问题；`4030` 内部还要再分 ——
> 「无权限」是角色不够（**换 AK 解决不了**），「API Key 无效」才是 AK 的问题。只看 code 会修错方向。

IM 侧错误码（出现在 `3614` 的 `message` 原文里）：

| IM 码 | 含义 |
|---|---|
| `IM_20001` | 消息不存在（`msgId` 不对，或不在这个会话里） |
| `IM_20005` | 幂等命中（**按成功处理**，消息是上一次发出去的） |
| `IM_20103` | 会话不存在（`convId` 不对 / 不归你） |
| `IM_20104` | **这个身份不是该会话的成员** —— ★判的是**平台替你选的那个执行身份**在不在，**不是「你」在不在**；退过群 / 被移出的人也不算（`member_status != 1`）。★**别把它读成「convId 填错了」**（那是 `IM_20103`）。详见「12. `members-list`」 |
| `IM_20106` | 不支持与自己创建单聊。★2026-09-27 起已解除：给自己发消息**以你名下智能体的身份**送达（`senderType=2`）；只在服务端未升级时才会再遇到本码 |
| `IM_90001` | 参数校验失败（如 `不能移除自己`、`msgIds 单次上限 200`、`toUserId 必须为正数`、`memberUserIds 必填且不能为空`） |
| `IM_90002` | 与关联的**平台团队空间**同步失败 —— **建群失败**，或成员变更**没问成**（详见「9. `create-group`」/「11. `members-remove`」）。★**「那个人不在空间里」不属于本码** —— 那是幂等成功，见「11.」；别拿它当"空间不一致"的警报。★被标成 `retryable=true` → 平台映射成 `3613` → 退出码 3，**但它不是暂时故障，重试无用**（尤其「本次不会创建群」那一支） |
| `IM_90004` | 内部调用验签失败（**服务端配置问题，不是客户端能改的**） |

★**配额/上限多半由 IM 侧强制**，报错形态是 `3614` + `IM_90001` **不带** `convId` 之类的上下文，
例如 `msgIds 单次上限 200，得到 201` —— 看到 `IM_90001` 先怀疑「数量/取值越界」。

## 写端点 vs 只读端点

- **写端点**（11 个）的 `data` 是 `{ bizMsgId, data }` —— 外层是幂等键，内层是业务对象。
  （11 = 5 个 `/message/*` 写 + 5 个 `/conversation/*` 写 + `event-push`；剩下 6 个是只读，
  11 + 6 = 17。）
- **只读端点**（6 个：`message-get` / `read-counts` / `message-list` / `members-list` /
  `conversation-list` / `contact-list`）的 `data` 直接就是业务对象，没有这层包装
  （它们没有幂等语义）。★只读端点的 `bizMsgId` 免填，但仍过签名 / nonce / 限流。

### `idempotentHit` 的确切语义（实测）

| 调用 | `idempotentHit` |
|---|---|
| 首次（这个 `bizMsgId` 没见过） | `null` —— **不是 `false`** |
| 重放（同一个 `bizMsgId` 再来一次） | `true` |

判「这次是不是重放」要写 `== true` / `is True`，**写成 `!= false` 会把首次调用也当成重放**
（`null` 在弱类型比较下不等于 `false` 的写法很容易反过来）。重放时 `msgId` / `seq` / `sendTime`
与首次**逐字段相同** —— 这是幂等真的生效、没有产生第二条消息的证据（实测群里只多了一条）。

## 消息类型 `msgType`

| wire 值 | 含义 | `body` 结构 |
|---|---|---|
| `text` | 文本 | `{"text": "..."}` |
| `rich_text` | 富文本 | 按 IM 契约 |
| `image` | 图片 | 按 IM 契约（如 `{"url": ...}`） |
| `file` | 文件 | 按 IM 契约 |
| `audio` | 语音 | 按 IM 契约 |
| `video` | 视频 | 按 IM 契约 |
| `system` | 系统提示 | 服务端生成 |
| `card` | 卡片 | 见 `send-card` |

> 除 `text` 外本技能**不校验** `body` 结构，原样透传给 IM，由 IM 按 `msgType` 结构化校验。
> 结构不对会在最远端报 `IM_90001`，排查成本最高——非文本类型先确认 `body` 结构再发。

★**上表不是所有端点的可用集合** —— `21. system-notify` 有自己的**白名单（★DEV-298 起四型）**：
`text`（缺省，纯文本气泡）/ `system`（居中提示条）/ `card`（通知卡）/ `rich_text`（富文本气泡）。
★`image` / `file` 等**单体媒体型仍然禁止**（富内容由 `rich_text` 的 `attachments` 承载）。
它的防线不在白名单而在别处：发送者恒为「系统通知」合成账号、消息**只落收件人自己的专属单聊**
（请求里**没有** `convId`），冒充不了真人、也塞不进任意两人之间的私聊 ——
要以自己的身份发私聊，用 `send-p2p`。

`senderType`：`1` = 人、`2` = 机器人、`3` = 系统。
`msgStatus`：`1` = 正常、`2` = 已撤回、`3` = 审核未通过。
**群成员 `role`**（★与平台登录角色 `UserDto.role` **同名不同义**，两者毫无关系）：
`1` = 普通成员、`2` = 管理员、`3` = 群主。
**成员 `memberStatus`**：`1` = 在群、`2` = 已退群、`3` = 被移出。

★判群主**只认 `role == 3`**；`1` 是**普通成员**，别按数值大小反推。

---

## messages（8）

> ★IM 内部 RPC 的 `messages/*` 有 8 个，这里 8 个全在；其中 `messages/system`（即 `3. send-system`）
> **仅管理员可用**（见该小节）。批量发系统通知的 `messages/system-notify` 收在末尾取号 `21`，
> 同样是**仅管理员**端点 —— 见「## notify（1）」。

### 1. `send-p2p` — 单聊发消息

`POST /message/send-p2p`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `toUserId` | Long | ✅ | 接收方用户 ID（**IM 不校验该用户是否存在**，发错人不会报错） |
| `msgType` | String | ✅ | 缺省 `text` |
| `body` | Object | ✅ | 消息体 |
| `ext` | String | | 业务扩展，≤2048，服务端不解析 |
| `bizMsgId` | String | | 幂等键 |
| `traceId` | String | | 链路跟踪 |

返回 `data.data` = `ImMessageDto`：

```json
{ "msgId": 228035897340198912, "convId": 228035364898471936,
  "seq": 7, "sendTime": 1790057594629, "idempotentHit": null }
```

★ **IM 不校验 `toUserId` 是否存在**：发给一个不存在的用户不会报错，消息会落进一个孤儿会话。
`send-*` 的 `data.data` 只有这 5 个字段（没有 `digest` / `senderId`）。

> ⛔ **群场景触发时本端点不可用**：若这次运行是「群里 @ 到智能体 / 监听模式唤起」起来的，
> 调 `send-p2p` 一律 `4030`（`由群消息触发时不能发私聊消息，只能在触发本次对话的群里发言`）。
> 私聊场景触发时不受影响。机理见 SKILL.md「触发场景也决定消息能发到哪儿」。

### 2. `send-group` — 群发消息

`POST /message/send-group`　入参：`convId`✅、`msgType`✅、`body`✅、`replyToMsgId`、`mentionUserIds`、`mentionAll`、`ext`、`bizMsgId`、`traceId`

返回同 `ImMessageDto`。

> ★★ **群场景触发时 `convId` 必须就是触发本次对话的那个群**：发往别的群一律 `4030`
> （`只能把消息发回触发本次对话的那个群（本次触发群 convId=…，请求的 convId=…）`）。
> 私聊场景触发时无此限制（以用户本人身份发，能发到哪儿由他自己的群成员资格决定）。
> 机理见 SKILL.md「触发场景也决定消息能发到哪儿」。

### 3. `send-system` — 系统 / 机器人消息（★★仅管理员）

`POST /message/send-system`　入参：`convId` / `toUserId`（**二选一**）、`senderType`、`msgType`✅、`body`✅、`ext`、`bizMsgId`、`traceId`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `convId` | Long | 二选一 | 群会话内发用这个 |
| `toUserId` | Long | 二选一 | 单聊发给某人用这个 |
| `senderType` | Integer | | `2`=机器人，`3`=系统（缺省由 IM 按 `3` 处理） |
| `msgType` | String | ✅ | 缺省 `text` |
| `body` | Object | ✅ | 消息体 |
| `ext` | String | | 业务扩展 |
| `bizMsgId` | String | | 幂等键 |
| `traceId` | String | | 链路跟踪 |

返回同 `ImMessageDto`。

**★★管理员闸门**：调用者的**平台角色必须是管理员**（登录态 `UserDto.role == Admin`），
否则返回 `4030` 无权限 —— 与 AK 是否有效无关，换 AK 也解决不了。

★闸门只收窄「谁能碰到」，**不消除**下面三条（管理员调用时照样成立）：

- `senderType=2|3` **不受**禁言与用户频控约束。
- 收件人看到的是**系统 / 机器人**，不是调用者本人。
- `actor` 是智能体主体时，`senderType=2` 等于**以智能体名义发言**。

### 4. `send-card` — 互动卡片

`POST /message/send-card`　入参：`convId`✅、`card`✅、`ext`、`bizMsgId`、`traceId`

`card` 结构：

```json
{
  "title": "部署完成", "desc": "v1.2.3 已上线", "subtitle": null,
  "iconUrl": null, "actionUrl": null,
  "fields":  [{ "label": "环境", "value": "prod" }],
  "actions": [{ "key": "rollback", "label": "回滚", "style": "danger",
                "actionType": "callback", "payload": {"v": "1.2.2"},
                "state": { "state": "normal", "text": null, "at": null } }]
}
```

`fields` / `actions` 都可省略。返回同 `ImMessageDto`。

### 5. `revoke` — 撤回消息

`POST /message/revoke`　入参：`convId`✅、`msgId`✅、`reason`、`bizMsgId`、`traceId`

返回 `data.data` = `null`。

★ **撤回人不可指定**，由触发场景决定（私聊 = 用户本人；群 = 智能体在该群的身份）——
**能撤的只有「该身份自己发的」消息**，撤别人的会得到 `IM_*` 无权限而不是静默失败。
时限走 `im.revoke.window-sec`（DEV-207 起默认 24 小时，**管理员同样受限**）。
完整「什么能撤、什么撤不掉」见 SKILL.md「★撤回：能撤的只有本会话里自己说的话」。

### 6. `message-get` — 消息详情（只读）

`POST /message/get`　入参：`convId`✅、`msgId`✅、`traceId`

返回 `data` = `ImMessageDetailDto`：

```json
{ "msgId": 228035381038153728, "convId": 228035364898471936, "seq": 1,
  "senderId": 1, "senderType": 1, "msgType": "text",
  "body": { "text": "技能活体验证：第一条群消息" },
  "digest": "冯飞1: 技能活体验证：第一条群消息",
  "msgStatus": 1, "replyToMsgId": null,
  "mentionUserIds": [], "mentionAll": false,
  "sendTime": 1790057471532, "revokeTime": null, "editTime": null,
  "readCount": 0, "ext": null }
```

★ **不存在的那几个字段是空集合/`false`，不是 `null`**：没 @ 人时 `mentionUserIds` 是 `[]`、
`mentionAll` 是 `false`。别用 `is not None` 去判「有没有 @ 人」——它对每条消息都成立
（`isinstance(..., list) and len(...) > 0` 才是「@ 了人」）。

★ `digest` 是**发送者名 + 正文**的展示串（`冯飞1: ...`），**消息被撤回后变成 `null`**。

撤回生效的判据：`msgStatus: 2` + `revokeTime` 有值（实测 `revoke` 后立刻可见）。
`msgId` 不存在时不是返回空对象，而是 `3614` + `IM_20001 消息不存在`（退出码 4）。

### 7. `read-counts` — 批量已读人数（只读）

`POST /message/read-counts`　入参：`convId`✅、`msgIds`✅（**单次上限 200**）、`traceId`

返回 `data` = 数组：

```json
[{ "msgId": 228035381038153728, "seq": 1,
   "readCount": 0, "eligibleCount": null, "readAll": null,
   "level": "L1", "readerListAvailable": true }]
```

字段含义：

| 字段 | 说明 |
|---|---|
| `readCount` | 已读数 |
| `eligibleCount` | 应读人数；**实测可为 `null`**（此时分母不可知，别用它算「已读率」） |
| `readAll` | 是否全部已读；**实测可为 `null`**——语义是「未知/不适用」，不是 `false` |
| `level` | 分档标记（实测 `"L1"`），超大群会升档 |
| `readerListAvailable` | 能否进一步取已读名单（实测 `true`） |

★ **可能返回空数组 `[]`，此时 `code` 仍是 `"0000"`**（实测：同一批 `msgId` 在有些会话上**必返空数组**）。
**别把它读成「已读数 = 0」，更别当失败** —— 同一条消息在 `message-get` 里 `readCount` 可能非 0
（实测为 `2`）。`read-counts` 的明细与 `message-get` 的 `readCount` 是**两条独立的路**，
取不到明细时**以 `message-get` 的 `readCount` 为准**。

★ 超过 200 个不返回 4000，而是 `3614` + `IM_90001 msgIds 单次上限 200，得到 201`（退出码 4）。
要查 200 条以上必须自己分批。

### 8. `message-list` — 按时间范围列举消息（只读）

`POST /message/list`　入参：`convId`✅、`startTime`（毫秒）、`endTime`（毫秒）、`afterSeq`、`limit`、`traceId`

返回 `data` = `ImMessagePageDto`：

```json
{ "list": [ { ...ImMessageDetailDto... } ], "hasMore": false, "nextSeq": null }
```

**翻页只认 `hasMore`**；下一页把 `nextSeq` 原样回填到 `afterSeq`。
★ **`hasMore=false` 时 `nextSeq` 是 `null`** —— 无条件回填会把 `afterSeq=null` 传回去，
虽然不报错但语义不清；先判 `hasMore` 再取 `nextSeq`。

---

## conversations（8）

### 9. `create-group` — 建群

`POST /conversation/create-group`　入参：`name`✅、**`memberUserIds`✅（必填且不能为空）**、
`avatar`、`notice`、`maxMember`（缺省 2000）、`clientGroupId`、`bizMsgId`、`traceId`

返回 `data.data` = `ImConversationDto`：

```json
{ "convId": 228036057097043968, "name": "建群语义验证",
  "ownerUserId": 1, "memberCount": 2, "created": true }
```

★ **`memberUserIds` 不能为空**：不给或给空列表 → `3614` + `IM_90001 memberUserIds 必填且不能为空`
（退出码 4）。本技能已在本地把 `--members` 设成必填，不会再白跑一次请求。

★ **创建者自动入群，且身份是群主（`role=3`）**：实测只传了 1 个成员，`memberCount` 是 **2**
（创建者 + 那 1 个）。所以 `--members` 不是「群成员名单」，而是「除我之外还要拉谁」。

★ **建群会同时创建一个平台「团队空间」**（`space.type = "Group"`，名字与群名相同，
创建者是 `Owner`），IM 群与它**双向绑定**。实测：建了 5 个群，`space` 表里就多出 5 条
`type=Group` 的记录，群名一致。所以：

- **建群不只是建一个 IM 会话**，它会在平台的空间列表里露出来 —— 别用「随便建个群试试」当无害操作。
- **`members-add` / `members-remove` 必须让 IM 群与这个空间保持一致**（上游原文：
  `成员变更必须与它保持一致`）。这是下面 `IM_90002` 那一堆坑的总根源。

⛔ **不要用「不存在的 `userId`」当建群成员 —— 现在整批建群直接失败，而报错看不出是这个原因。**

> ★本条的旧版本说「它会被加进 IM 群、与空间长期不一致、之后 `members-remove` 永久 `IM_90002`」。
> **那个结论已经作废**（它描述的是写穿还不是强一致时的行为）。下面是当前实现。

按当前实现（读码 + 单元测试 `ImConversationSpaceWriteThroughTest` 确认，非实测）：
建群要**先在平台建一个团队空间、再把初始成员逐个写进那个空间**，然后才落 IM 的库；
平台侧「这个人是谁」查的是**平台用户表**，不存在的 ID 必然被拒 ⇒ 写穿失败
⇒ **整个建群失败、space 被补偿删除、群一个字都不落库**（强一致）。报出来的是 `IM_90002`：

```
❌ 创建群组失败：平台拒绝或不可用，请稍后重试（★建群需要在平台侧同步创建一个团队空间
   spaceId=…，它没能与群一起建出来 ⇒ 本次不会创建群）
```

★ **`IM_90002` 在这条路上「重试」永远不会成功** —— 传的就是不存在的 ID，重试一万次还是它。
看到建群报 `IM_90002`：**先回头核对 `--members`**，再怀疑平台抖动（凭据：消息里带了
`spaceId=` 与「本次不会创建群」这两句，见「11. `members-remove`」里对 `IM_90002` 的辨析）。

★ **它躲得过前面的「不可联系人」闸门**：平台查不到这个人时按**放行**处理、只记一条 WARN，
所以一定会走到写穿那一步才失败 —— 别以为「参数检查过了就说明 ID 是对的」。

⚠️ **会真的把人拉进群、对方会收到通知**。要验证链路，用 `--members 1`（只拉自己）
或你自己的测试账号 —— **不是**用不存在的 ID。

★ `clientGroupId` 是**业务侧群唯一键**（**实测确认**）：同一个值重复调用只建出一个群，
第二次返回 `convId` 与首次一致、`created: false`，**且 `idempotentHit` 仍是 `null`** ——
建群的「这是不是重放」要看 **`created`**，不是 `idempotentHit`。需要「建群」这一步可重试时，
一定要给 `clientGroupId`。

### 10. `members-add` — 加群成员

`POST /conversation/members/add`　入参：`convId`✅、`userIds`✅（**上限 50**）、`nickName`、`agentMode`、`bizMsgId`、`traceId`

★ **`agentMode` 只对「本批 `userIds` 里的智能体」生效**（真人成员一律忽略，不给也行）：

| 值 | 含义 | 什么时候用 |
|---|---|---|
| `1`（缺省 / 不给就是它） | **唤起** | 只在有人 @ 它时才把消息发给它 |
| `2` | **监听** | 群里**每条**消息都发给它 —— ★**真实模型费用**，只在确实要它跟进全部消息时才传 |

- ★ 非 `1` / `2` 的值服务端会**报错 `IM_90001`**（**不是**静默当 `1`）—— 别指望它兜底。
- ★ `nickName` 同理**当前被服务端刻意忽略**（群名片只能本人在群里设）。
- ★ 它是**写侧入参，返回里不回显**：本技能只覆盖「**加进去的那一刻**」设这个模式。
  已进群的智能体要改模式，走 IM 的 `agent-mode` 接口（**本技能不含该子命令**）。

返回 `data.data` = `MemberAddResultDto`：

```json
{ "inserted": 2, "revived": 0, "alreadyIn": 1,
  "failedUserIds": [],
  "applied": 0, "appliedUserIds": [],
  "pendingConfirm": 0, "pendingConfirmUserIds": [],
  "skippedUserIds": [],
  "memberCount": 5, "idempotentHit": null }
```

★ **部分成功是正常返回**（退出码仍为 0）：已经在群里的计入 `alreadyIn`。

★ **一次加人有六档结局，其中四档在 `inserted` 上都读作 `0`** —— 只看 `inserted` 的话，
「转成申请」「等对方同意」「本来就在群里」「冷却被跳过」会和「真的加不进去」**长得一模一样**。逐档读：

| 字段 | 非零 / 非空 = | 等谁 | 是失败吗 |
|---|---|---|---|
| `inserted` / `revived` | 真的进群了（新加 / 退群后又被拉回） | — | 成功 |
| `alreadyIn` | 本来就在群里 | — | 幂等命中，**不是失败** |
| `applied` > 0 | 这批人**整体转成了「入群申请单」** | **群主 / 管理员**审批 | 没真加入 |
| `pendingConfirm` > 0 | 已发出邀请卡片 | **被邀请人本人**点同意 | 没真加入 |
| `skippedUserIds` 非空 | 冷却期内被跳过 | 时间 | **暂时** |
| `failedUserIds` 非空 | 明确加不成（账号停用 / 注销这类永久理由） | 改条件 | **永久** |
| **六档全空** | 见下面那条 ⛔ | — | **区分不了** |

★ **两种粒度别混**：`applied` 是**整批**（非零时 `inserted`/`revived` 必然为 0，且与
`pendingConfirm` 互斥）；`pendingConfirm` 是**逐人**（**可以**与 `inserted` 同时非零 ——
同一个团队的人直接进群、不同团队的等他确认）⇒ 判「这次到底发生了什么」不能只看一个字段。

⛔ **六档全空时，不要下结论说「什么都没发生」。** 七项一个值都没有，有两种成因，
**这个返回区分不了**：

1. 你传的 `userIds` 里**确实没人可加**（先核对一遍你传的 ID）；
2. **对面 IM 是旧版本**、不返回 `applied` / `pendingConfirm` / `skippedUserIds` 这三个字段 ——
   那么「转成申请」或「等对方同意」**可能已经发生**，只是没透出来。

★ 所以 **别拿 `failedUserIds` 当「没加上的那些人」的完整清单** —— 它只收「明确失败」的人，
不含转申请 / 等确认 / 冷却跳过。想确认到底进去没有，**用 `members-list` 回读**。

★ **「拿一个不存在的 `userId` 当测试靶子」这条路也不可靠**：本群绑定了平台团队空间时
（绝大多数群），加人要先往空间里写穿，而平台侧查不到这个用户 ⇒ 整批以 `IM_90002` 失败、
**IM 成员表零写入**（与 `create-group` 同一个根因，见上面「9. `create-group`」）。
拿它当「无副作用的假靶子」，得到的是报错而不是一张安静的读数。

### 11. `members-remove` — 移出群成员

`POST /conversation/members/remove`　入参：`convId`✅、`userIds`✅（**上限 50**）、`reason`、`bizMsgId`、`traceId`

返回 `data.data` = `null`。★ **与 `members-add` 相反：任一失败则整体失败**。

★ **空间侧「对不上」不报错 —— 只有「没问成」才报错。** 要移除的人**在 IM 群里、但不在关联的
平台空间里** ⇒ ✅ **成功**，`data: null`、退出码 0、**没有任何提示**（想确认真踢掉了得
`members-list` 回读）。这跟直觉相反（"两侧不一致"听起来像该报错），所以单独拎出来：

**判据是「这次问没问成」，不是「人在不在空间里」。** 人不在空间里 ⇒ 平台明说「他本来就不是成员」
⇒ IM 当成**幂等成功**、照常删本地。这不是容错，是刻意的：不这么做的话，这个"幽灵成员"
就**永远删不掉**了。下面三种都属于「问成了」，**全部成功**：

| 你移除的人 | 结果 |
|---|---|
| **两边都在**（正常成员） | ✅ 成功 |
| **只在 IM 群里、不在空间里** | ✅ **成功**（幂等，无提示） |
| **两边都不在**（干净群 + 幽灵 ID） | ✅ 成功（幂等空操作） |

⛔ **`IM_90002` —— 「可重试」是假的，别被骗去重试**

```
❌ members-remove失败: IM 服务暂时不可用，请稍后重试
   平台错误码: 3613          ← 退出码 3，按 SKILL.md 的规则「可重试」
   → IM 暂时不可用（3613）。可以稍后重试……
```

上游原文才是真相（服务端日志里能看到）：

```
[IM_90002] 移除群成员失败：平台拒绝或不可用，请稍后重试
（★本群绑定了一个平台团队空间 spaceId=1281，成员变更必须与它保持一致）
```

**触发条件：只有「没问成」那三种** —— 都不是"人不在空间里"（那三种见上面第一张表，**全是成功**）：

| 情况 | 平台 4.12 返回 | 结果 |
|---|---|---|
| 平台侧**查不到这个人**（账号已注销 / 被删） | `3001 用户不存在` | ❌ `IM_90002` → `3613` → **退出码 3** |
| 平台侧**查不到这个空间**（`spaceId` 指向的空间已不在） | `3026 空间不存在` | ❌ 同上 |
| 平台**不可用 / 没配 key**（请求没发出去、或没答） | 网络失败 / `4030` | ❌ 同上 |

⇒ 看到 `IM_90002` 时，**别再去核对「这个人还是不是空间成员」** —— 那件事**根本不会**报错
（上面第一张表三行全绿）。要核对的是**平台 / 账号 / 空间本身是否还在**。

★ **同族的 `IM_90002` 还有第二个来源（建群 / 加人那一侧），靠 `message` 文案区分**：

| `message` 里的那句话 | 是哪条路 | 该核对什么 |
|---|---|---|
| 「…★本群绑定了一个平台团队空间 `spaceId=…`，成员变更必须与它保持一致」 | 成员变更写穿失败（**本节**） | 平台 `space_user` 里有没有这个人 |
| 「…★建群需要在平台侧同步创建一个团队空间 `spaceId=…`，它没能与群一起建出来 ⇒ **本次不会创建群**」 | **建群**失败，群根本没建出来（见「9. `create-group`」） | 你传的 `memberUserIds` 里有没有不存在的 ID |

两条**都带 `spaceId=`、都不可重试**，但处置相反 —— 认准「本次不会创建群」这句。

**怎么办**：`3613` + 这条 `spaceId` 原文时，**不要重试** —— 它不会自己好。
按上面那张表对号入座：**这个账号还在不在**（`3001`）、**这个空间还在不在**（`3026`）、
**平台通不通**（网络 / `4030`）。★**不要**再去核对"他在不在空间成员里"——那条路不报错。
用 `--json` 看原始 `message` 是唯一能识破它的办法（默认渲染只说「暂时不可用」）。

★ 不能移除自己 —— `IM_90001`，而且上游原文直接给了正解：
`不能移除自己（请调用 POST /conversations/{convId}/leave 退群）`。
退群接口**不在本技能内**，要退群得让别的调用方去做，或让别人来移你。

### 12. `members-list` — 群成员列表（只读）

`POST /conversation/members/list`　入参：`convId`✅、`limit`（**上限 100**）、`traceId`

返回 `data` = `MemberPageDto`：

```json
{ "totalCount": 1, "degraded": false,
  "members": [{ "userId": 1, "name": "冯飞1",
                "avatar": "https://testagent.xspaceagi.com/api/f/s3/default/20260815/8961...jpg",
                "nickName": null, "role": 3, "memberStatus": 1,
                "joinTime": 1790057468000, "muteEndTime": null }] }
```

★**字段语义**（`role` / `memberStatus` 的唯一定义处；SKILL.md 里出现的那个 `role` 是**平台登录角色**，
同名不同义，别混）：

| 字段 | 取值 | 说明 |
|---|---|---|
| `role` | `1` = **普通成员**、`2` = 管理员、`3` = 群主 | ★判群主**只认 `== 3`**；`1` 是普通成员，别按数值大小反推 |
| `memberStatus` | `1` = 在群、`2` = 已退群、`3` = 被移出 | ★本接口**只会返回 `1`**：服务端强制 `member_status = 1`，退过群 / 被移出的人**不在名单里**（行还在，但不出现） |
| `name` | 用户显示名 | 展示用 |
| `nickName` | 群名片，没设置就是 `null` | ★群里没人设昵称时**恒为 `null`**，别拿它当名字 |
| `totalCount` | 群内**在群**成员数 | 与 `members.length` 不等时看 `degraded` |
| `degraded` | 是否被降级截断 | >500 人的群只返前 100 |

★ **`name` 就是展示用的名字，`nickName` 是群昵称（没设就是 `null`）** —— 实测 `name` = `"冯飞1"`
（即用户显示名），`nickName` = `null`。**要展示名字取 `name`**，别取 `nickName`
（群里没人设昵称时那个恒为空），也别用 `name or nickName` 的顺序去猜。

⛔ **本接口要求调用身份是该会话的活跃成员**，否则 `3614` + `IM_20104`（**不是 `IM_20103`**）。
同一道闸门也挡在 `message-get` / `message-list` / `read-counts` 前面，而平台给这几个端点用的
是**同一个身份助手** —— 所以对**同一个 `convId`、同一次触发场景**，它们要么一起通、
要么一起报 `IM_20104`，不会有的通有的不通（真出现不对称，先确认两次调用的 `convId` 是否真的相同）。

★ `degraded=true` 表示**超过 500 人的群被服务端降级截断**；若 `totalCount > members.length`
（即 `truncated`），**别把这份名单当完整名单**。

### 13. `conversation-p2p` — 取或建单聊会话

`POST /conversation/p2p`　入参：`toUserId`✅、`bizMsgId`、`traceId`

返回 `data.data` = `ImConversationDto`（`created=false` 表示会话已存在，复用）。

★ 给自己发消息（`--to-user-id` 传自己）2026-09-27 起可用：**以你名下智能体的身份**送达（`senderType=2`），落进「你 ↔ 该智能体」的单聊，并给你加 1 条未读。★ IM **不校验 `toUserId` 是否存在**，不存在的用户会落进孤儿会话。

### 14. `digest` — 更新会话摘要

`POST /conversation/digest`　入参：`convId`✅、`digest`、`bizMsgId`、`traceId`

返回 `data.data` = `null`。★ **尽力而为，允许丢失**——不要拿它当可靠通知通道。

### 15. `conversation-list` — 列举我的会话列表（只读）

`POST /conversation/list`　入参：`cursor`、`limit`、`traceId`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `cursor` | String | | 上一页的 `nextCursor`（可空 = 第一页）。★**不透明**，见下方 |
| `limit` | Integer | | 单页条数，缺省 **50**，服务端**夹到 `[1,100]`**（★夹断而不是拒绝：传 0 / 负数 / 超大值都不报错） |
| `traceId` | String | | 链路跟踪 |

返回 `data` = `ImConversationPageDto`：

```json
{ "list": [ { "convId": 228036057097043968, "convType": 2, "name": "发布群",
              "avatar": "https://...", "notice": null, "ownerUserId": 1,
              "memberCount": 2, "maxMember": 2000, "status": 1, "spaceId": 1281,
              "lastMsgId": 228036057097043999, "lastMsgSeq": 5, "lastMsgAt": 1790057594629,
              "lastMsgDigest": "冯飞1: 群建好了", "lastSenderId": 1, "lastSenderName": "冯飞1",
              "peerUserId": null, "peerName": null, "peerAvatar": null,
              "myRole": 3, "myNickName": null, "unreadCount": 0,
              "topFlag": false, "topTime": null, "dnd": false, "visible": true,
              "createTime": 1790057468000, "joinTime": 1790057468000 } ],
  "hasMore": true, "nextCursor": "t1|1790057594629|1790057594629|228036057097043968" }
```

**★`convType` 有 4 种取值**（真机实测，别只按「单聊/群聊」二分）：

| `convType` | 含义 | 真机占比（user 1 / 38 条） |
|---|---|---|
| `1` | 单聊 | 17 |
| `2` | 群聊（IM 自建群） | 15 |
| `3` | 系统会话 | 0（本次未见，但契约里有） |
| **`4`** | **团队群 —— 平台 space 的镜像群** | **6** |

★**`4` 是常态而不是边角**（16%）。判「是不是群」要 `convType ∈ {2, 4}` ——
只判 `== 2` 会把真机上六分之一的会话误判成「不是群」。`4` 的资料跟随平台 space，
所以 IM 自己的群号一类字段对它恒为 null。

**★`status` 也不止「正常/不正常」**（IM 侧 `ImConvStatus`）：

| `status` | 含义 | 能否发言 |
|---|---|---|
| `1` | 正常 | ✅ |
| `2` | **已解散** | ❌ |
| `3` | **被封禁** | ❌ |

真机实测 38 条里有 **1 条 `status=2`（已解散）** —— 列表里**会**出现这种会话，
别假定「列出来的都能发消息」。

**翻页规则（只有两条）**：

1. **判停只看 `hasMore`**。它是服务端真取过 `limit+1` 条得出的，不是估算。
2. **`nextCursor` 原样回填到 `cursor`**。★`hasMore=false` 时它**恒为 `null`**
   （含「最后一页正好取满」与「一个会话都没有」两种情形）—— **不是**「`list` 为空才为 null」。
   照后者写会导致多翻一次拿回一个空页。

**★刻意没有 `total`**：想知道总数只能一直翻到 `hasMore=false` 再累加。
把 `list` 的长度当会话总数，在 `hasMore=true` 时会少算。

**★`list` 的顺序是会话活跃度倒序（置顶优先，再按 `lastMsgAt`），`cursor` 里的排序键与它同源。**
本地重排过的列表再配上游标翻页，会出现**某条记录被跳过**（不报错）。

**★游标是不透明的**（内部是 `t{0|1}|topTime|lastMsgAt|convId` 快照，≤128 字符）：

| 形态 | 服务端行为 |
|---|---|
| 首页不传 / 传空串 | ✅ 正常（空串按「无游标」处理，不是非法游标） |
| 自己拼 / 格式非法 | ❌ `3614` + `IM_40002`（**不猜** —— 猜错会静默返回一份错序列表） |
| **串用别人的游标** | ⚠️ **不报错**：游标里不含用户身份，服务端在结构上无法校验归属，只会静默返回「从那个位置开始」的一页（**通常为空**）⇒ **按用户隔离存储是调用方的责任**（服务端兜不了） |

**★这是「查看者视角」的投影，不是中立数据**：`unreadCount`（**那个人**的未读数，且是快照值）、
`topFlag`/`topTime`（**那个人**的置顶）、`myNickName`（**那个人**的群名片）、`dnd`、`visible`
都只对「当前 AK 那个用户」成立。★`dnd`（免打扰）**仍然计未读数**，只是不计入总未读、不弹通知——
渲染成「有数字没红点」，不是「没有未读」。

字段裁剪是刻意的（IM 侧 `ConversationSummaryDto` 共 **43** 个字段，本投影 **28** 个）。
★真机实测（user 1，38 条会话）报文里实际出现 **37** 个字段，被裁掉的 10 个是：
`readSeq`/`ackSeq`/`clearBeforeSeq`/`joinSeq`（多端同步水位线）、
`muteAll`/`muteEndTime`（禁言）、`allowMemberInvite`/`allowMemberPin`/`joinApprove`/`mentionAllPerm`
（群权限项）、`readReceiptOn`（已读回执开关）。

★另有几个字段**上游有、报文里从不出现** —— 不是没有，是**该取值下为 `null` 被 JSON 省略**：
`groupNo` / `ext` / `created`（列表路径恒 null，填它们要给首屏加 50 次往返）、`draft`（私人草稿）、
`topTime`（★没置顶就没值 —— 真机 38 条 `topFlag` 全 `false`，所以 `topTime` 一个都没出现，
本投影里的 `topTime` 因此**尚未取到过非 null 样本**）。
别把「字段不在报文里」读成「这个群没有群号」。

要判「能不能发言」看 `status`（非 1 = 已解散/封禁，见上表）与 `myRole`。

★ 单聊时 `name` / `avatar` 由**服务端填对端昵称/头像**，调用方不需要按 `convType` 再判断该显示什么；
`peerUserId` 是单聊对端 id（群聊为 `null`），可**直接拿去 `send-p2p`**。

★★ **拿列表里的条目去发消息时，目标字段按 `convType` 二选一**（别一律用 `convId` —— 单聊条目也有 `convId`）：

| 条目 `convType` | 目标字段 | 命令 |
|---|---|---|
| `1` 单聊 | `peerUserId` | `send-p2p --to-user-id <peerUserId>` |
| `2`/`4` 群聊 / 团队群 | `convId` | `send-group --conv-id <convId>` |

★注意 `convId` 并非只对群有用 —— `message-list` / `read-counts` / `revoke` / `digest`
这些**按会话操作**的命令对单聊条目照样用 `convId`；**只有「发一条新消息」这一步分两种字段**。
（这是 `send-p2p` 的入参结构决定的：它只有必填的 `--to-user-id`，没有 `convId` 这一项。）
完整规则与示例见 `SKILL.md` 的「会话列表 → 发消息：用哪个字段」。

★**响应尾部会多出 4 个 `true/false` 派生字段**（平台侧 DTO 的判定方法被序列化出来了，
**不是** IM 下发的，也不在报文里）：`p2p`、`group`、`spaceMirror`、`imOwnedGroup`。
它们是上面 `convType` 的便捷封装，**可以直接用**：
`group` ≡ `convType ∈ {2,4}`、`spaceMirror` ≡ `convType == 4`、`imOwnedGroup` ≡ `convType == 2`。

### 16. `conversation-detail` — 会话详情（只读）

`POST /conversation/detail`　入参：`convId`✅、`traceId`

★**只有一个业务字段，且没有批量形态** —— 要查 N 个会话就调 N 次。
（「我有哪些会话」用 `15. conversation-list` 一次拿全；详情是**定点**查询。）

返回 `data` = 会话详情（平台侧 `ImConversationDetailDto`，30 个字段）：

```json
{ "convId": 228036057097043968, "convType": 2, "name": "发布群", "avatar": "https://...",
  "notice": "公告内容", "ownerUserId": 1, "memberCount": 2, "maxMember": 2000, "status": 1,
  "spaceId": 1281, "groupNo": "228036057097043969",
  "lastMsgId": 228036057097044001, "lastMsgSeq": 5, "lastMsgAt": 1790057594629,
  "lastMsgDigest": "冯飞1: 群建好了", "lastSenderId": 1, "lastSenderName": "冯飞1",
  "peerUserId": null, "peerName": null, "peerAvatar": null,
  "myRole": 3, "myNickName": null, "unreadCount": 0,
  "topFlag": false, "topTime": null, "dnd": false, "visible": true,
  "createTime": 1790057468000, "joinTime": 1790057468000, "ext": "{\"k\":1}" }
```

**★★与 `15. conversation-list` 的关系：重叠 28 个字段，增量只有两个 —— `groupNo` 与 `ext`**

`groupNo`（群号）**只有详情路径会填**，列表路径**恒为 `null`**（给首屏逐条查群号要加几十次往返）；
`ext` 列表接口**根本不返回**。⇒ **要群号就只能走这里，翻列表翻不出来**。
反过来也别走另一个极端：拿详情当「列会话」用是 N 次往返，列表一次就够。

字段含义与 `15.` **同名同义**（`convType` 4 种取值、`status` 三态、`myRole` 三角色、
`unreadCount` 是快照值、`dnd` 仍计未读、单聊 `name`/`avatar` 由服务端填对端），不重复。
只列详情**独有**的这两个：

| 字段 | 说明 |
|---|---|
| `groupNo` | ★**群号**。类型是 **String 而不是 Long**（19 位雪花超 `2^53-1`，经 JS 浮点会静默改值）⇒ 原样透传、不要做数值运算。单聊 / 系统会话恒 `null`；**团队群（`convType=4`）也为 `null`**（它的资料跟随平台 space）；**老群**（群号概念之前建的）同样是 `null` ⇒ 前端遇到 `null` 应**整行不显示**，别渲染空白或 `"null"` |
| `ext` | 扩展字段（JSON 文本，原样透传）。★★**不可**对它做字符串相等比较：MySQL 的 `json` 列会重新序列化（键按长度/字典序重排、补空格），两次读回的同一个对象可能**不是逐字相同**的字符串 ⇒ 要比就解析成结构再比，别比字符串 |

**★★查一个自己不在里面的会话，是允许的**

这是它与其余「按 `convId` 定位」的端点（`message-get` / `message-list` / `members-list` /
`revoke` / `read-counts`）**最大的行为差异**：那几个会以 `IM_20104` 拒绝非成员，
而**本端点正常返回**（服务端对内部 RPC 豁免成员闸门）。这恰好有用 ——
「用户丢了个 `convId` 过来，先看看这是个什么会话」在还没入群时也做得了。此时：

| 字段 | 非成员时 |
|---|---|
| `myRole` / `myNickName` / `topFlag` / `topTime` / `dnd` / `joinTime` 等**视角字段** | 整块 `null` |
| **`unreadCount`** | ★★**是 `0`，不是 `null`** |
| 单聊的 `peerUserId` / `peerName` / `peerAvatar` | **也为 `null`**（拿不到对端资料） |
| **中立字段** —— `convId` / `convType` / `name` / `avatar` / `notice` / `ownerUserId` / `memberCount` / `maxMember` / `status` / `spaceId` / `groupNo` / 最后一条摘要 / `createTime` / `ext` | ★**照常返回**（它们来自会话本行，与「谁在看」无关） |

⛔ **不要用 `unreadCount == 0` 判断「这个人已经读完」** —— 非成员时它同样是 `0`，
会把「看不见」读成「没有未读」。
✅ **判「在不在这个会话里」的唯一可靠判据是 `myRole != null`**。响应里另有一个平台侧算好的
派生字段 **`viewerIsMember`**（`true`/`false`，与它等价），可以直接用
（这些判定方法是平台侧 DTO 被序列化出来的，不在 IM 报文里，同 `15.` 结尾说的派生字段）。

★★**这条判据的主语是「本次行使身份」，不是「AK 归属人」** —— 与请求里的 `convId` 也无关：
行使身份由平台按**触发场景**替你定（私聊 / 手工 AK ⇒ 本人；群场景 ⇒ **智能体在那个群里的虚拟身份**，
见 `## 身份`）。⇒ AK 绑着 A 群、却拿它去查 B 群时，**即便你本人在 B 群里**，`myRole` 照样是 `null`
（那个虚拟身份不在 B 群）。下结论前先想清楚「问的是哪个身份在里面」。

★**豁免不是无条件的** —— 下列情况**照常报错**，别当成「详情接口不会失败」：

| 情况 | 结果 |
|---|---|
| `convId` 非法（非正数）或会话不存在 | `IM_40001` / `IM_40400` |
| 团队群（`convType=4`）对应的平台 space 已删（会话已解散） | 拒绝，与其它端点一致 |

★与列表相同的三条纪律（照抄 `15.`）：这是**以本次行使身份为观察者**的投影
（★不是「AK 归属人」—— 群场景下行使身份是智能体在那个群里的虚拟身份），不是中立数据；
`spaceId != null` **不能**用来判断「是不是空间镜像群」—— 判据永远是 `convType == 4`。

---

## contacts（1）

### 17. `contact-list` — 列举我的联系人（只读）

`POST /contact/list`　入参：`traceId`（★**没有**任何业务字段，也**没有**分页/过滤参数）

返回 `data` = `ImContactListDto`：

```json
{ "items": [ { "userId": 33, "subjectType": 1, "userName": "zhangsan",
               "nickName": "张三", "avatar": "https://...", "remark": "老张",
               "displayName": "老张", "status": 1, "source": 1,
               "applyMsg": null, "time": 1790057468000 } ],
  "degraded": false, "truncated": false }
```

★★ **返回的是「三态混合」列表，不是「只有好友」**：

| `status` | 含义 | 该怎么办 |
|---|---|---|
| `1` | 已互为联系人 | 当正常联系人用（可 `send-p2p`） |
| `2` | **对方申请我**（待我同意） | ★别渲染成好友、更别直接发消息；只说「有 N 条待处理申请」 |
| `3` | **我申请对方**（待对方同意） | 同上 |

★服务端**不**替调用方筛 —— 这样「平台查到的集合」始终是「界面上看到的集合」的**超集**，
不会出现「界面里有、平台查不到」的缺口。要「只要好友」自己按 `status==1` 筛。
`status` 未下发时按**不是好友**处理。

★★ **两个「缩水」标志语义完全不同，混了就会错报**：

| 标志 | 含义 | 结果集 | 该怎么办 |
|---|---|---|---|
| `degraded=true` | 平台资料（昵称/头像）没取到，`displayName` 回落成 `用户{id}` | ★**完整** | 照常用，最多提一句「头像暂时加载不出来」，**别整单报错** |
| `truncated=true` | 撞到**单用户 500 上限**被截断 | ★**缩水了** | **必须**告诉用户「仅显示前 500 位联系人」，否则会被当成丢数据 |

★注意 `degraded` 在 `/search/contacts`（用户态搜索接口）里**不是这个意思** —— 那条的
`degraded=true` 表示「只搜了本地会话成员」，是**结果集缩水**。同名不同义，别互推。

★★ **`userId` 是不透明标识，`subjectType` 才是类型**：`1`=人、`2`=智能体。★**人和智能体在这里
长得一样**（智能体拿到的也是普通十位数 userId），**别按 id 段位猜**。可直接拿 `userId` 去
`send-p2p` / `conversation-p2p`。
智能体的 `userName` **恒为 `null`**（智能体只有一个名字，放在 `nickName` 里）。

★**展示名用服务端算好的 `displayName`**（优先级：备注 > 昵称 > 账号名 > `用户{id}`），
**不要自己重算** —— 重算结果与界面不一致时，用户会以为「平台查错了人」。
`remark`（我给对方起的备注）**只存在于 IM**，平台侧没有备注概念。

★**为什么没有分页参数**：用户态 `GET /contacts` 本身就没有分页（内部硬上限 500，
超出用 `truncated=true` 表达，**不是错误码**）。单方面加 `limit`/`cursor` 会长出
**第二份过滤/分页逻辑**，让「平台查到的条数」与「界面上看到的条数」从此可能不一致。

★**`tenantId` 是必填**（与其它端点不同）：平台资料按租户取，缺失时 IM 侧直接报
**`IM_90001`**「`tenantId` 必填」（真机实测，**不是**泛化的 `PARAM_ERROR`）。
本层从 AK 解析后注入，调用方不用管（租户永远不需要传）。

---

## events（1）

### 18. `event-push` — 推送自定义事件（★★仅管理员）

`POST /event/push`　入参：`userIds` / `convId`（**至少给一个**）、`eventType`✅、`payload`、`bizMsgId`、`traceId`

返回 `data.data` = `ImCustomEventPushResultDto`：

```json
{ "targetUsers": 1, "targetDevices": 3, "publishedFrames": 1 }
```

实测（`--user-ids 1` 推给 1 个用户）：`targetUsers=1`、`targetDevices=3`、`publishedFrames=1`。
`targetDevices > 0` 只说明「命中了在线设备」，仍然不等于对方会看到。

★★ **「成功」不等于「送达」**：收件人离线即收不到 —— **不落库、无补偿**。
全都没送到时也返回三个 `0` 而不报错（`targetDevices == 0` 就是一个在线设备都没命中）。
需要可靠送达的链路必须自建对账。

**★★管理员闸门**：调用者的**平台角色必须是管理员**（登录态 `UserDto.role == Admin`），
否则返回 `4030` 无权限 —— 与 AK 是否有效无关，换 AK 也解决不了。

★**为什么单给这个端点加闸门**（2026-09-23 曾整个移除，同日按需求「拿回来，但要判调用者为管理员」恢复）。
它在 IM 侧是设计上的**服务间业务通道**，授权面是这样的（均已读 IM 源码核对）：

- **目标侧零授权**：IM 的 push 链上没有任何成员/关系校验 ——
  `ImEventRpcServiceImpl.pushCustomEvent` 只校验 `userId`/`tenantId` 为正数、收件人二选一、
  `userIds` 为正数且 ≤1000、`payload` ≤16KB；`CustomEventBroadcaster.broadcast` 的 `convId` 模式
  拿 `membersOf(convId)` 当**收件人列表**，**从不校验调用者是不是成员**。
  （对照：消息/会话类端点每个都会走 `requireActiveMember`。）
- **帧体无发送者**：`CustomEventDto` 只有 `{eventId, eventType, payload, ts}` ——
  收件端**无法归属**这条事件是谁推的。
- **内容由调用方定义**：`eventType` 是自由字符串（IM 刻意不做枚举校验），`payload` 原样透传。
- **不落库** ⇒ 没有审计、没有读路径、无法撤回。伪造一条**消息**至少能撤回、能查；
  伪造一条**事件**在收件端**什么都留不下**。

★★**闸门只是收窄了「谁能碰到」，上面四条在管理员调用时照样成立** ——
这是需求变更明确接受的取舍。且危害面由**将来**注册 `eventType` 处理器的人决定：
新增消费方之前，先回来看一眼这段。

---

## agents（2）

「排队区」= 消息进来之后、还没被这一轮处理掉的那批新消息。这一组是**智能体自己消费它们**的通道：
读走 → 并入自己的上下文 → 声明「这批我消费了」。不消费也不会丢，只是这批消息会在本轮结束后
**再触发一次运行**（白跑一趟）。

★★**这一组的身份口径与其余 19 个端点完全不同**：它对外的 `userId` 必须是**智能体主体**，
服务端从 AK 的扩展字段 `AGENT_VIRTUAL_USER_ID` 取虚拟身份，取不到就 **`4030`
（原文「本接口只能以智能体身份调用」）**，**不会退回登录用户**。
⇒ 手工 AK / 登录态直连**稳定失败**，它只在**由 IM 消息触发**的那次运行里有意义。
不按场景（群聊/私聊）分流 —— 两种场景下都必须是智能体本人。

### 19. `agent-pending-list` — 查看本会话排队区（只读）

`POST /agent/pending/list`　入参：`convId`✅、`traceId`

返回 `data` = `ImAgentPendingListDto`：

```json
{
  "items": [
    { "msgId": 227861195531485184, "senderId": 33, "senderName": "张三", "seq": 3,
      "text": "完整正文", "digest": "截断摘要", "enqueueTime": 1700000000000 },
    { "msgId": 227861195531485185, "senderId": 44, "seq": 4, "digest": "[图片]" }
  ],
  "count": 2
}
```

| 字段 | 说明 |
|---|---|
| `items[].msgId` | ★雪花号，**超过 `2^53-1`**，别经手 JS 浮点；原样带给 `20.` 即可 |
| `items[].text` | ★**回查后的完整正文**。非文本消息（图片/文件等）无 `text`，回退看 `digest` |
| `items[].digest` | 摘要 / 列表展示用的短文本，可能是 `[图片]` 这种占位 |
| `items[].seq` | 会话内的消息序号（可空） |
| `items[].enqueueTime` | 入队时刻（毫秒） |
| `count` | = `items` 长度。**刻意没有分页**，服务端上限 **500 行** |

- ★**顺序 = 入队顺序**（服务端按 `create_time, id` 排）。**调用方不要重排** ——
  这是用户连发消息的先后。
- `items` 为空**通常意味着消息已被正常处理**（IM 侧或本轮已消费），不是出错。
- `count == 500` 说明撞到上限 ⇒ **异常堆积**（常态近空），如实上报，别当分页继续拉。
- 已撤回 / 已删除 / 查无的消息**整条被剔除**，不会以空正文出现。

### 20. `agent-pending-clear` — 声明这批已消费

`POST /agent/pending/clear`　入参：`convId`✅、`msgIds`✅（**非空**）、`bizMsgId`（写操作必填）、`traceId`

返回 `data` = `ImAgentPendingClearDto`：

```json
{ "removedCount": 1, "removedMsgIds": [227861195531485184] }
```

★★**语义是「声明我已消费」，不是「删除」**：清掉之后 IM 侧不再把这批算作待处理，
从而**不会再多发起一轮运行**。它不影响消息本身，消息照旧在会话里。

| 纪律 | 为什么 |
|---|---|
| **必须点名 `msgIds`** | 不存在「清空整个会话排队区」的用法。`list` 与 `clear` 之间**可能有新消息入队**，整队清空会**静默丢消息** |
| **先 `list` 读走、确实并入上下文了，再 `clear`** | 先清后读 = 那批消息再也不会被处理 |
| **别清这次没读到的** | 同上，静默丢消息 |
| `msgIds` 为空会被**本地拦下**（`4000`） | 服务端对空列表是「删 0 条」的成功返回，与「确实清干净了」长得一模一样 ⇒ 参数拼错会静默丢消息，故本地拒 |

- ★**`removedCount` 可能小于请求条数，甚至为 0，都不报错**。三种成因：
  ① 行已被 IM 侧取走；② `msgId` 本就不在队里（传错 / 重复清）；③ 幂等重放。
  ⇒ **判据看 `removedMsgIds`（实际删掉的那几条），不要拿「请求条数 == `removedCount`」当成功条件**，
  更别把 0 当成失败去重试。
- 写操作照常走幂等：重发**必须复用同一个 `bizMsgId`**。

**错误码**：

| 码 | 场景 |
|---|---|
| `4030` + `本接口只能以智能体身份调用` | 本次凭据没有智能体虚拟身份（登录态 / 手工 AK 直连）。★**换 AK 无效**，它只在 IM 消息触发的运行里可用 |
| `4000` + `参数'msgIds'不能为空` | 没给或给了空 `msgIds`（本地拦下，请求未发出） |
| `4000` + `参数'convId'不能为空` | 没给 `convId` |

---

## notify（1）

### 21. `system-notify` — 批量发系统通知（★★仅管理员）

`POST /message/system-notify`　入参：`toUserIds`✅、`msgType`、`body`✅、`ext`、`bizMsgId`、`traceId`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `toUserIds` | Long[] | ✅ | 收件人。★**去重后上限 100** —— 超出由 IM **整批拒绝**（不是静默截断成前 100 个） |
| `msgType` | String | | ★DEV-298 起白名单**四型**：`text`（缺省，纯文本气泡）/ `system`（居中提示条）/ `card`（通知卡，`body.title` 必填）/ `rich_text`（富文本气泡，markdown）。★`image`/`file` 等单体媒体型仍禁 |
| `body` | Object | ✅ | `text`/`system` 时形如 `{"text":"..."}`，★**`text` 必须非空**（两型判据相同，只是渲染形态不同）；`card` 时给卡片结构；`rich_text` 三种合法形态**至少其一**（裸 markdown `{"text","attachments"}` / `segments` 段序 / 仅 `attachments`），三者皆空拒收 |
| `ext` | String | | 业务扩展字段（JSON 原文，服务端原样透传给收件端，约定 ≤2048） |
| `bizMsgId` | String | | ★幂等键，**整批共用一个**（不是每人一个） |
| `traceId` | String | | 链路跟踪 |

返回 `data.data` = 一张**对账单**（`ImSystemNotifyResultDto`），**不是「一条消息」**：

```json
{ "targetCount": 3, "sentCount": 2,
  "list":   [ { "toUserId": 7, "convId": 111, "msgId": "2278611...", "seq": 12, "sendTime": 1758600000000 } ],
  "failed": [ { "toUserId": 9, "reason": "IM_20103: 会话不存在" } ] }
```

- `targetCount` = **去重后**的目标数（= `list` + `failed` 条数之和）；`sentCount` = 实际投递条数。
- `list[].toUserId` 是**把结果与请求对上**的唯一依据；`msgId` 是雪花 ID ——
  **消费方是 JS/TS 时按字符串解析**（JS 精度只到 2^53）。
- ★排序用 `list[].seq`（会话内序号），**别用 `sendTime`**。

**★★管理员闸门**：调用者的**平台角色必须是管理员**（登录态 `UserDto.role == Admin`），
否则返回 `4030` 无权限 —— 与 AK 是否有效无关，换 AK 也解决不了。

★**为什么单给它加闸门**（DEV-291 新增端点，2026-09-23 接入）：

- **收件人不必与调用者有往来** —— 它不是「发给我的联系人」，是一条**不经加好友就能触达任意用户**
  的广播口子。对照：`send-p2p` 至少还用你的真实身份、落进你与对方的会话。
- **会写进对方的会话列表** —— 每人各自与一个叫「系统通知」的**合成账号**成一条单聊，
  **带未读红点、进历史、可删除**。⇒ 打扰是**留在对方会话列表里**的，不是一闪而过的提示。
- **一次调用 = 多份库写入**，而幂等键**整批只有一个**（见下方纪律）。

★★**「管理员能用」≠「没有代价」**，闸门只收窄了「谁能碰到」：

- ★**发送身份你连选都选不了**：请求体里**没有** `senderType` 字段 —— 发送身份由 IM 内部
  固定为合成账号，`userId` 只作**审计归属**与租户上下文来源。所以它**天然不会**变成
  「以管理员的名义发」；收件人看到的是「系统通知」。
  （对照 `3. send-system`：那个端点的 `senderType` 由调用方给，性质完全不同。）
- ★**只发不收**：本技能**没有**「读别人收到的系统通知」的端点，也**不会有**。
- ★**`ext` 会原样落到收件端**：别往里塞不该给对方看的东西。

**★★两条相反的重试纪律（最容易搞反，`failed` 落在哪一层决定能不能重试整批）**

| 你看到的 | 含义 | 该怎么办 |
|---|---|---|
| 整体 `code=0000`（**哪怕 `failed` 非空**） | 请求已受理，幂等占位**已写死**（结果缓存在幂等闸门里）。`failed` 里是**逐人**没投出去的 | ★**不能**拿原样重试整批 —— 同一个 `bizMsgId` 只会把这次的结果**原样回放**（`IM_20005`），既不重发也不补发。要补发就**换一个新的 `bizMsgId`**，且 `toUserIds` **只传 `failed` 里那些人**；把已成功的混进去 = **真的给他们再发一条** |
| 整体 `code≠0000`（如 `4030` / `3614` / 参数错） | 整批在 IM 侧**没受理**，幂等占位已**释放** | ★同一个 `bizMsgId` **重试整批是安全的**：已成功的那几条由 IM 侧消息域的唯一键（值由 `bizMsgId + toUserId` 决定、**可复现**）挡住 ⇒ 不重复落库、不重复扇出，只有没发出去的会被补上 |

> 一句话：**看整体 `code`，不看 `failed` 长度**。
> ★这也解释了为什么 `failed` 非空时**整体仍是成功**——个别人不存在是**业务结论**
> （一个离职账号不该让另外 99 个人的通知发不出去），不是这次调用出错。

**错误码**

| 码 | 场景 |
|---|---|
| `4030` + `无权限` | 调用者不是平台管理员（★换 AK 无效） |
| `4000` + `参数'toUserIds'不能为空` | 没给或给了空 `toUserIds`（本地拦下） |
| `4000` + `参数'body'不能为空` | 没给 `body`（本地拦下） |
| `IM_2xxxx`（在 `failed[].reason` 里，**不在信封上**） | 个别收件人没投出去（★**整体仍是 `0000`**）。形如 `IM_20103: 会话不存在` |

★**`msgType` 白名单 DEV-298 起是四型**（`text` 缺省 / `system` / `card` / `rich_text`）——
旧口径「只给 `system`/`card`」已被覆盖：用户拍板「系统通知要能渲染成普通气泡」，
`text` 与 `system` 的 `body` 判据完全相同（`{"text":"..."}` 非空），只是渲染形态不同。
★核心防线一字未动：`image`/`file` 等单体媒体型仍禁、发送者恒为「系统通知」合成账号、
只落收件人自己的专属单聊（没有 `convId` 可给）。要以自己的身份发私聊，仍用 `send-p2p`。

---

## 服务端路径映射（排查 404 时用）

| 技能端点 | 沙箱路径（你调用） | 服务端落地路径 | IM 内部 RPC 路径 |
|---|---|---|---|
| send-p2p | `/message/send-p2p` | `/api/nuwax-im/message/send-p2p` | `/internal/im/rpc/messages/p2p` |
| send-group | `/message/send-group` | `…/message/send-group` | `/internal/im/rpc/messages/group` |
| send-system ★仅管理员 | `/message/send-system` | `…/message/send-system` | `/internal/im/rpc/messages/system` |
| send-card | `/message/send-card` | `…/message/send-card` | `/internal/im/rpc/messages/card` |
| revoke | `/message/revoke` | `…/message/revoke` | `/internal/im/rpc/messages/revoke` |
| message-get | `/message/get` | `…/message/get` | `/internal/im/rpc/messages/get` |
| read-counts | `/message/read-counts` | `…/message/read-counts` | `/internal/im/rpc/messages/read-counts` |
| message-list | `/message/list` | `…/message/list` | `/internal/im/rpc/messages/list` |
| create-group | `/conversation/create-group` | `…/conversation/create-group` | `/internal/im/rpc/conversations/group` |
| members-add | `/conversation/members/add` | `…/conversation/members/add` | `/internal/im/rpc/conversations/members/add` |
| members-remove | `/conversation/members/remove` | `…/conversation/members/remove` | `/internal/im/rpc/conversations/members/remove` |
| members-list | `/conversation/members/list` | `…/conversation/members/list` | `/internal/im/rpc/conversations/members/list` |
| conversation-p2p | `/conversation/p2p` | `…/conversation/p2p` | `/internal/im/rpc/conversations/p2p` |
| digest | `/conversation/digest` | `…/conversation/digest` | `/internal/im/rpc/conversations/digest` |
| conversation-list | `/conversation/list` | `…/conversation/list` | `/internal/im/rpc/conversations/list` |
| conversation-detail | `/conversation/detail` | `…/conversation/detail` | `/internal/im/rpc/conversations/detail` |
| contact-list | `/contact/list` | `…/contact/list` | `/internal/im/rpc/contacts/list` |
| event-push ★仅管理员 | `/event/push` | `…/event/push` | `/internal/im/rpc/events/push` |
| agent-pending-list ★仅智能体 | `/agent/pending/list` | `…/agent/pending/list` | `/internal/im/rpc/agents/pending/list` |
| agent-pending-clear ★仅智能体 | `/agent/pending/clear` | `…/agent/pending/clear` | `/internal/im/rpc/agents/pending/clear` |
| system-notify ★仅管理员 | `/message/system-notify` | `…/message/system-notify` | `/internal/im/rpc/messages/system-notify` |

（★标「仅管理员」的三行，服务端在控制器入口处前置了管理员闸门；非管理员会拿到 `4030`，
**根本走不到**后面的落地路径 —— 排查时别去 IM 侧找日志。
★标「仅智能体」的两行同理：不是智能体身份就本地 `4030`，**请求根本没发到 IM**。）

沙箱路径由平台的重写规则映射到落地路径（白名单 `nuwax-im/**`）。
报 `4040 No static resource api/v1/4sandbox/nuwax-im/...` 说明**服务端版本不含本 API**，
或白名单没放行 —— 是部署问题，不是用法问题。

## 身份

底层 IM 契约的每个请求都要求 `userId`（「以谁的身份执行」），本层**一律用 AK 解析出的
登录态构造**，请求体里没有这个字段。所以：

- 发送者 = `SANDBOX_ACCESS_KEY` 所属用户，**调用方伪造不了**。
- 想换身份，只能换 AK。
- 同理，`tenantId` 也不在请求体里，由平台按 AK 自行解析（租户永远不需要传）。

★**「我是谁」与「我能不能用这个端点」是两条独立的轴**，别混：

| 轴 | 判据 | 影响 |
|---|---|---|
| 身份（19 个端点） | AK 解析出的登录态 `userId` | 消息以**谁**的名义发出 |
| 角色（`send-system` / `system-notify` / `event-push`） | 同一个登录态里的 `UserDto.role == Admin` | **能不能调用**这三个端点；不通过就 `4030` |
| 场景（`send-p2p` / `send-group` 的目标） | 这次运行由**群消息**还是**私聊消息**触发 | 群触发时只能**发回本群**：`send-p2p` 一律 `4030`、`send-group` 到别的群也 `4030` |
| 主体（`agent-pending-*`） | AK 扩展里的 `AGENT_VIRTUAL_USER_ID`（**智能体主体**） | 这一组**只认它**；没有就本地 `4030`，**不回退登录用户** |

⇒ 换 AK 只会换「身份」，**不会**把非管理员变成管理员 —— 管理员端点过不去就是过不去。
反过来：管理员调这三个端点时，**身份仍然是管理员本人**（请求体里没有 `userId` 可以改成别人）——
★但 `system-notify` 是**唯一的例外**：它的**发送身份**由 IM 固定为合成账号「系统通知」，
请求体里**根本没有**发送身份字段，所以那边「身份」这一轴只体现为**审计归属**，收件人看到的是「系统通知」。
（`send-system` 不同：它的 `senderType` 由调用方给，能把消息说成系统/机器人发的。）
⇒ 场景这一轴同理**换 AK 也改不了**：它是这次运行**怎么被触发的**这个事实，不随钥匙变。
⇒ 主体这一轴**也不按场景分流**：群聊/私聊两种场景下，`agent-pending-*` 要的都是智能体本人 ——
拿登录用户去填**稳定失败**，这与上面三轴「以本人身份为主」的默认正好相反。

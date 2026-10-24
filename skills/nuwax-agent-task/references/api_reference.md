# nuwax-agent-task API Reference

端点级契约。使用方式与语义纪律见 [../SKILL.md](../SKILL.md)。

- 前缀：`/api/v1/4sandbox/agent-task`（挂在 `PLATFORM_BASE_URL` 下）
- 方法：**全部 POST**，`Content-Type: application/json`
- 鉴权：`Authorization: Bearer <SANDBOX_ACCESS_KEY>`；AK 必须**锚定着某个智能体**
  （沙箱环境里现成的那把就满足，无需另建）。
  ★租户永远不需要传：服务端按 AK 自行解析租户，无需任何租户参数/请求头。
- 响应信封（所有端点一致）：

```json
{ "code": "0000", "displayCode": "0000", "message": "success", "data": ..., "tid": "..." }
```

`code == "0000"` 才是成功；**业务失败也走 HTTP 200**，判据是 `code`。

---

## 可见范围（所有端点共同前提）

服务端按 AK 解析出：归属用户 `userId`、绑定智能体 `agentId`（含其配置的子智能体）。

- dispatch：`targetAgentId` 必须在上述智能体范围内，否则报「目标智能体不在可派发范围内」；
  续聊时目标任务还须通过归属校验。
- ★`AGENT_ID` 只是**技能 CLI 的取值兜底**（见 §1 字段表），不改变上面的线上契约：
  请求体里 `targetAgentId` 永远有值、且永远按同一套范围校验判 —— 环境变量不构成任何特权，
  也不是身份声明。
- 列表/搜索：只返回该用户 + 该智能体范围内的任务。
- progress / cancel / dispatch（续聊）：目标任务的 `userId` 与 `agentId` 都必须在范围内，
  否则统一报 `code != "0000"`、`message = "任务不存在或无权访问"`
  （★不区分「不存在」与「无权」，防存在性探测）。
- AK 没有锚定任何智能体（用户级 / 租户级等），或其锚定目标解不出合法智能体时，
  所有端点统一报 `message = "仅支持绑定智能体的访问密钥（AK）调用本接口"`。
  ★沙箱环境里现成的 `SANDBOX_ACCESS_KEY` 是锚定智能体的，可直接用。

---

## 1. POST /dispatch —— 派发任务（★触发真实模型执行）

**派发 = 创建（或续聊）任务会话并让目标智能体立即开始真实执行，产生费用与副作用。**
接口是 fire-and-forget：返回即「已派发/已启动」，不等任务完成；结果用 `/progress` 查，停用 `/cancel`。

请求体：

```json
{
  "targetAgentId": 300,
  "inputMessage": "帮我整理季度数据并输出图表",
  "subject": "季度数据整理"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `targetAgentId` | long（JSON 数字） | **是** | 目标智能体：仅允许本 AK 绑定的智能体或其配置的子智能体；续聊时须与任务原执行智能体一致。缺了报 `targetAgentId 不能为空`。★**技能 CLI 里这个参数可省**：不传 `--target-agent-id` 时客户端用环境变量 `AGENT_ID`（= 本沙箱智能体）填好再发出 —— 请求体这一层始终有值 |
| `inputMessage` | string | **是** | 任务指令文本：发给目标智能体的输入 |
| `subject` | string | 否 | 任务主题：**新建**时作为会话 topic，缺省取 `inputMessage`；**续聊时忽略** |
| `conversationId` | long（JSON 数字） | 否 | 已有任务（会话）ID：传入表示**续聊**该任务而非新建；任务须通过归属校验、目标智能体一致、且未在执行中 |

响应 `data`：

```json
{
  "conversationId": 228036057097043968,
  "created": true,
  "topic": "季度数据整理",
  "agentId": 300,
  "agentName": "数据分析子智能体",
  "taskStatus": "EXECUTING",
  "error": null
}
```

| 字段 | 说明 |
|---|---|
| `conversationId` | 任务句柄：后续 progress / cancel / 续聊都用它 |
| `created` | `true` = 本次新建了任务会话；`false` = 续聊了已有任务 |
| `topic` | 任务主题（新建 = subject 或 inputMessage；续聊 = 原主题） |
| `taskStatus` | **返回三态**：`EXECUTING` = 已开始执行；`CREATE` = 启动阶段被拒绝（任务没在跑，原因见 `error`）；`FAILED` = 启动后立即失败 |
| `error` | 启动阶段的拒绝/失败原因（如无执行权限、超出配额）；`null` = 未被拒绝 |

★语义纪律：
- `taskStatus=EXECUTING` 只代表「已开始」，**不代表完成**——别立刻报「任务完成」。
- `taskStatus=CREATE` 是**启动被拒绝**——任务没在跑，⛔ 不得转述为「已开始执行」，报出 `error` 原文。
- 续聊一个 `EXECUTING` 中的任务会被拒（见错误表 ALREADY_RUNNING）；等跑完或先 `/cancel`。
- 目标智能体**未发布 / 已下线 / 绑定模型不可用**时不会创建任务，直接报错（不产生孤儿会话）。

## 2. POST /list-running —— 查执行中的任务

请求体（可整体省略）：

```json
{ "limit": 20 }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `limit` | int | 否 | 返回条数上限；缺省 20，最大 50（超过按 50 截断，非报错） |

响应 `data`：任务条目数组，**只含 `taskStatus == "EXECUTING"` 的任务**，按最近更新排序。

```json
[
  {
    "conversationId": 228036057097043968,
    "topic": "整理季度数据",
    "agentId": 300,
    "agentName": "数据分析子智能体",
    "taskStatus": "EXECUTING",
    "created": "2026-09-26T02:00:00.000+00:00",
    "modified": "2026-09-26T02:05:00.000+00:00"
  }
]
```

空数组 = 当前没有执行中的任务（是成功，不是失败）。

## 3. POST /search —— 按主题搜任务（含已结束）

请求体（可整体省略 = 最近任务列表）：

```json
{ "keyword": "周报", "limit": 20 }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `keyword` | string | 否 | 按任务主题匹配；空白串等价于不传 |
| `limit` | int | 否 | 缺省 20，最大 50 |

响应 `data`：同 `/list-running` 的条目数组，但**不限状态**（每条自带 `taskStatus`），
按最近更新排序。

## 4. POST /progress —— 查任务进度或结果

请求体：

```json
{ "conversationId": 228036057097043968 }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `conversationId` | long（JSON 数字） | **是** | 缺失会被参数校验拒绝 |

响应 `data`：

```json
{
  "conversationId": 228036057097043968,
  "topic": "写周报",
  "agentId": 300,
  "agentName": "写作子智能体",
  "taskStatus": "COMPLETE",
  "running": false,
  "messages": [
    { "role": "USER", "senderType": "USER", "text": "帮我写本周周报", "time": "..." },
    { "role": "ASSISTANT", "senderType": "AGENT", "text": "本周周报如下……", "time": "..." }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `taskStatus` | `CREATE` / `EXECUTING` / `CANCEL` / `COMPLETE` / `FAILED`（任务从未变更过状态时按 `CREATE` 报） |
| `running` | 便捷位：`taskStatus == "EXECUTING"` |
| `messages` | **最多 2 条**（任务输入 + 最新一条），不是全量记录；`role`：`USER`/`ASSISTANT`/`SYSTEM`/`FUNCTION`；`senderType`：`USER`/`AGENT`/`REMINDER` |

★结果语义：`COMPLETE` 时 `messages` 最后一条即任务结果；`EXECUTING` 时没有最终结果；
`FAILED` 时最后一条可能只是中间内容。详见 SKILL.md §4。
⚠️ 例外：被 `/cancel` 停掉的任务收尾后**也报 `COMPLETE`**，其最后一条是**被切断的半截输出**，
⛔ 不能当结果交付（见 §5）。

## 5. POST /cancel —— 受理停止执行中的任务（异步）

请求体：

```json
{ "conversationId": 228036057097043968, "reason": "用户改主意了" }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `conversationId` | long（JSON 数字） | **是** | 缺失会被参数校验拒绝 |
| `reason` | string | 否 | 仅记录日志，不影响行为 |

响应 `data`：

```json
{ "conversationId": 228036057097043968, "cancelled": true, "taskStatus": "CANCEL" }
```

| 字段 | 说明 |
|---|---|
| `cancelled` | `true` = 取消前任务在执行中，**停止请求已被受理**；`false` = 任务本来就没在执行，**未做任何变更** |
| `taskStatus` | **受理时刻**的状态：`cancelled=true` 时为 `CANCEL`（受理值，**非终态**）；`cancelled=false` 时为任务原状态（如 `COMPLETE`） |

★**本接口是异步的**：它只写入停止状态、让执行停下，任务随后自己收尾结束（实测长任务：
受理后约 1.6~3.2 秒才 `running=false`）。要确认停稳，**轮询 `/progress` 到 `running=false`**
（或它从 `/list-running` 消失），别把本接口的返回当成「已停止」。

★停稳后落库状态通常是 `COMPLETE`（`CANCEL` 只是短暂过渡）——停掉的任务与自然跑完的任务在
`/progress` 里状态相同，接口**不保留「是被取消的」这一信息**，唯一的区分线索是调用方刚调过 `cancel`。

幂等：重复取消同一任务不报错，第二次起 `cancelled=false`（此时它已停稳）。
⛔ `cancelled=false` 时不得向使用者转述为「已取消」。
⛔ 取消过的任务不要按 §4 取结果：它的最后一条通常是**被切断的半截输出**。

---

## 错误码

| code | 场景 | 处理 |
|---|---|---|
| `0000` | 成功 | — |
| `4030` | AK 无效或过期 | 检查 `SANDBOX_ACCESS_KEY`（租户无需传，服务端按 AK 自行解析） |
| 非 `0000`，message = `任务不存在或无权访问` | conversationId 错误，或任务不在本 AK 可见范围（progress / cancel / dispatch 续聊） | 先核对 ID，再核对任务归属；★无法区分两种成因 |
| 非 `0000`，message = `仅支持绑定智能体的访问密钥（AK）调用本接口` | AK 没有锚定任何智能体 | 换用锚定了智能体的 AK（沙箱里现成的 `SANDBOX_ACCESS_KEY` 即是） |
| 非 `0000`，message = `目标智能体不在可派发范围内（仅本 AK 智能体或其配置的子智能体）` | dispatch 的 targetAgentId 越界 | 只能派给本 AK 智能体或其配置的子智能体 |
| 非 `0000`，message = `目标智能体不可执行（未发布、已下线或模型不可用）` | dispatch 目标拿不到可执行配置 | 换目标或联系管理员；**不会创建孤儿会话** |
| 非 `0000`，message = `任务正在执行中，不能重复派发（可等待完成或先取消）` | dispatch 续聊一个 EXECUTING 中的任务 | 等它跑完（progress 查），或先 cancel 再续聊 |
| 非 `0000`，message = `任务派发启动失败，请稍后重试或用 progress 查看任务状态` | chat 启动阶段抛出未预期异常（会话已建） | 用 progress 查该会话实际状态，再决定是否重派 |
| 非 `0000`，message = `conversationId 不能为空` / `targetAgentId 不能为空` / `inputMessage 不能为空` 等 | 参数校验失败 | 按 message 补参数 |

脚本退出码：`0` 成功；`1` 本地用法错误或上表任何业务失败；`2` 环境变量缺失（请求未发出）。

## 明确不存在的能力

- 无重跑、无改任务内容（`FAILED` 后只能重新 dispatch，那是新任务、新 conversationId）；
- 无全量聊天记录拉取（`messages` 上限 2 条）；
- 定时任务（cron 类）不在本前缀下，属于另一套接口。

---
name: nuwax-agent-task
description: 当用户要「让某个子智能体去做一件事」或查看/管理已派发的子任务时使用：派发新任务（★触发真实模型执行，产生费用与副作用）、续聊已有任务、现在有哪些任务在跑、按主题搜历史任务（含已结束的）、查某个任务的进度、拿已完成任务的最终结果、取消一个还在执行的任务。用户没指明派给哪个智能体时，目标缺省取环境变量 AGENT_ID（= 本沙箱智能体），即默认派给自己，无需追问目标。可见范围恒为当前凭据（SANDBOX_ACCESS_KEY）绑定的智能体及其子智能体执行、且属于凭据归属用户本人的任务，不能伪造身份、也看不到范围外任务。需环境已配置 PLATFORM_BASE_URL 与 SANDBOX_ACCESS_KEY。
tags: [任务, 子任务, 任务管理, 派发, 创建任务, 进度, 结果, 取消, 执行中, agent-task, sandbox, 4sandbox]
version: "1.2.2"
---

# Nuwax 任务管理（派发 / 查询 / 进度 / 取消）

**派发并管理子智能体任务**：派发（新建或续聊）、查执行中、按主题搜索、查进度或拿结果、取消。

全部能力收在一个入口脚本里，5 个端点对应 5 个子命令：

```bash
python3 scripts/task.py endpoints        # 离线列出全部端点，不发请求
python3 scripts/task.py dispatch --input-message "帮我整理季度数据"        # 没点名 → 缺省派给本沙箱智能体
python3 scripts/task.py dispatch --target-agent-id 300 --input-message "帮我整理季度数据"   # 点名了 → 显式传
python3 scripts/task.py list-running     # 现在有哪些任务在跑（→ conversationId）
python3 scripts/task.py search --keyword 周报   # 按主题搜任务，含已结束的
python3 scripts/task.py progress --conversation-id 228036057097043968   # 进度或结果
python3 scripts/task.py cancel --conversation-id 228036057097043968 --reason "不需要了"
```

**何时使用：** 用户说「让 XX 智能体去整理季度数据」（→ `dispatch`）、「我的任务跑到哪了」「现在有什么在跑」「把那个任务停了 / 取消掉」「上次让 XX 智能体做的事结果呢」「搜一下有没有做过周报的任务」「在那个任务上继续，让它补充图表」（→ `dispatch --conversation-id`）—— 目标对象是**智能体任务（会话）**。
**何时不使用：** 要管**定时任务**（那是另一套接口）；要发 IM 消息（用 nuwax-im 技能）；要直接和用户对话（那是聊天本体，不是任务）。

## Environment Variables

| 变量名 | 说明 |
|--------|------|
| `PLATFORM_BASE_URL` | 平台根地址，如 `https://testagent.xspaceagi.com` |
| `SANDBOX_ACCESS_KEY` | 沙箱 AK，脚本自动拼 `Authorization: Bearer <key>` |
| `AGENT_ID` | **可选兜底**：本沙箱智能体 ID。`dispatch` 没传 `--target-agent-id` 时用它（见 §2） |

★**租户永远不需要传**：服务端按 AK 自行解析租户，没有任何租户参数/环境变量/请求头要配。
★`AGENT_ID` 是**取值兜底**，不是身份参数：它不是「我是谁」的声明，服务端身份与可见范围
  始终只由 `SANDBOX_ACCESS_KEY` 决定。别把它塞进请求体、也别指望用它越权。

缺这两个变量时，**需要联网的子命令**以退出码 `2` 退出，不会发出任何请求。
例外：`endpoints` 是**离线**命令（不发请求），不检查环境变量，没有 AK 也能跑
—— 想知道有哪些能力、或想确认脚本在不在时，先跑它。

## ★五条必须先理解的事

### 1. 身份与可见范围来自 AK，不在参数里

请求体里**没有任何 userId 字段**可传，`dispatch` 的目标也**只能是**
`SANDBOX_ACCESS_KEY` 范围内的智能体。服务端按 AK 判定你是谁、能派/能看哪些任务，范围恒为：

> **AK 绑定的智能体自己 + 它配置的子智能体**所执行的、且属于 **AK 归属用户本人**的任务。

范围外的任务（别人的、别的智能体的）统一报「**任务不存在或无权访问**」——
服务端**不区分**「ID 不存在」和「存在但你无权」，所以拿到这个报错时：
先核对 `conversationId` 抄没抄错，再确认这个任务是不是本 AK 智能体（或其子智能体）执行的。
向范围外智能体派发则报「目标智能体不在可派发范围内」。

`dispatch` 不传目标时缺省用本沙箱智能体，但**缺省值要过同一套范围校验** —— 它只是
「省一次手传」，既不是身份声明、也不带来任何特权；换个 `AGENT_ID` 也派不到范围外去。

**推论**：想查/取消/借道派发别人的任务，做不到，也不该想办法做到。
另：**环境里现成的 `SANDBOX_ACCESS_KEY` 直接就能用**，它本身就锚定着本沙箱的智能体 ——
不需要再去建一把「智能体钥匙」。只有拿一把跟任何智能体都不沾边的钥匙（用户级、租户级等）
调用时，才会统一报「仅支持绑定智能体的访问密钥（AK）调用本接口」。

### 2. `dispatch` 触发【真实模型执行】，且是 fire-and-forget

- **目标智能体从哪来**：用户**明确说了**派给谁 → 显式传 `--target-agent-id <id>`；
  **没说（一个字都没提智能体）→ 不传**，脚本自动取环境变量 `AGENT_ID`（= 本沙箱自己的智能体），
  即「默认派给自己」。所以**不要为了问「派给哪个智能体」去打断用户**。
  例外：上下文里能看出该由某个**子智能体**来做（用户点了它的名字、或这件事明显是它的职责）
  就显式传它，别一律兜给自己。显式传的值**永远优先**，不会被环境变量覆盖。
  （没显式传、环境里也没有 `AGENT_ID` 时，脚本以退出码 `2` 退出并给出两条出路，不会瞎猜一个 ID。）
- **真实执行**：派发成功后目标智能体会真的开始跑模型 —— 产生费用与副作用。
  指令文本就是发给它的输入，**派发前把指令想清楚**，别拿测试语句随手打真靶子。
- **fire-and-forget**：接口返回只代表「已派发/已启动」，**不代表任务完成**。
  返回里 `taskStatus=EXECUTING` = 已开始跑；结果之后用 `progress` 查，要停用 `cancel`。
- **返回三态要如实转述**：

| 返回 | 含义 | 对使用者怎么说 |
|---|---|---|
| `taskStatus=EXECUTING`，`error=null` | 正常启动 | 「已派发，任务在跑」，给 conversationId，之后查进度 |
| `taskStatus=CREATE`，`error` 有值 | **启动被拒绝**（如无执行权限、超配额），任务没在跑 | 报出 `error` 原文，⛔ 别说「已开始执行」 |
| `taskStatus=FAILED` | 启动后立即失败 | 报失败，可用 `progress` 看详情再决定是否重派 |

- **续聊**：传 `--conversation-id` = 在已有任务上追加指令（不新建会话）。前提：
  任务在可见范围内、目标智能体与任务一致、且**任务没在执行中**
  （执行中续聊会被拒：「任务正在执行中，不能重复派发」—— 等跑完或先 `cancel`）。
  ★「目标与任务一致」这条要留神：缺省目标是 `AGENT_ID`（本沙箱智能体），
  所以**续聊自己的任务**可以不传 `--target-agent-id`；**续聊子智能体执行的任务**必须
  显式传它，否则会因目标不一致被拒。
- **新建 vs 续聊**：返回 `created=true` 是新建了任务会话（`subject` 作主题，缺省取指令原文）；
  `created=false` 是续聊（`subject` 被忽略，主题不变）。

### 3. 「任务」= 一次会话，`conversationId` 是它的唯一句柄

每个任务对应一个会话（`dispatch` 新建，或此前主智能体派发时创建）。所有端点都围绕
`conversationId`（雪花 ID，**数字**，脚本参数按整数传，别加引号）。
拿 ID 的途径：`dispatch` 的返回里带；`list-running` / `search` 的输出里每条都带 `[conversationId=...]`。

### 4. 任务状态五档，`progress` 的「结果」语义看状态

| taskStatus | 含义 | progress 返回里的语义 |
|---|---|---|
| `CREATE` | 已创建，还没开始跑 | 没有结果 |
| `EXECUTING` | 执行中（`running=true`） | **没有最终结果**；messages 是中间内容，别当结果转述 |
| `COMPLETE` | 已结束 | **自然跑完时**，`messages` 的最后一条就是任务结果，可直接取用（★见下） |
| `CANCEL` | 停止指令已受理，任务即将结束 | 没有（完整）结果；任务随后异步收尾，落库通常变成 `COMPLETE` |
| `FAILED` | 失败 | 最后一条可能只是失败前的中间内容，别当完整结果用 |

`progress` 返回的 `messages` **最多 2 条**（任务输入 + 最新一条），不是全量聊天记录。

★**`COMPLETE` 不区分「跑完」和「被停掉」**：被 `cancel` 停掉的任务收尾后落库的也是 `COMPLETE`，
在 `progress` 里与自然跑完的任务**长得一模一样**。区别在于**最后一条是被切断的半截输出**——
实测取消一个「从 1 数到 5000」的任务：落库 `COMPLETE`，最后一条只数到 **2288** 就断了。
所以：**自己调用过 `cancel` 的任务，不要按「`COMPLETE` ⇒ 取最后一条当结果」处理**（见 §5）。

### 5. `cancel` 是「受理停止」，任务**异步**结束 —— 看 `cancelled` 字段

`cancel` **不会让任务当场结束**：它只写入停止状态、让执行停下，任务随后自己收尾结束。
实测长任务：受理后约 **1.6~3.2 秒**才 `running=false`。

- `cancelled=true`：受理前任务确实在执行中，**停止请求已被受理**。返回里的
  `taskStatus=CANCEL` 是**受理时刻写入的状态，不是终态**。
- `cancelled=false`：任务本来就没在执行（已完成/已失败/还没开始），**什么都没变**。
  ⛔ 这时**别对使用者说「已取消」**——尤其任务是 `COMPLETE` 时，结果仍然有效，
  说「已取消」会让人误以为结果没了。

**要确认真的停稳，得轮询 `progress` 到 `running=false`**（或看它从 `list-running` 里消失）。
别把 `cancel` 的返回当成「已经停了」。

★**停稳后落库通常是 `COMPLETE`**（`CANCEL` 只是短暂过渡）——这是有意设计：停止被视为
「任务结束」，所以停掉的任务与跑完的任务状态相同，**状态本身不保留「是被取消的」这件事**，
唯一的区分线索是**你自己刚调过 `cancel`**。因此：
- 取消后对使用者说「**已停止**」，⛔ 别因为看到 `COMPLETE` 就说「任务跑完了」；
- 取消过的任务 ⛔ **按 §4 取不到结果**——最后一条是半截输出，不是任务产出。

`cancel` 幂等：对同一个任务重复调用不会出错，第二次起 `cancelled=false`（此时它已停稳）。
`--reason` 可选，仅记录日志，不影响取消行为。

## ★本技能不能做什么

- **不能改任务内容 / 重跑任务**：派发出去的任务改不了输入；`FAILED` 后没有「重跑」按钮，
  只能重新 `dispatch`（那是一个**新任务**，会再次产生真实执行）。
- **不能拉全量聊天记录**：`progress` 的 `messages` 上限 2 条。
- **不能管定时任务**：定时任务（按 cron 周期跑的那种）是另一套接口，不在本技能里。
- **不能向范围外派发**：目标只能是本 AK 智能体或其配置的子智能体（见 §1）。

## 端点速查（5 个，全部 POST，前缀 `/api/v1/4sandbox/agent-task`）

| 子命令 | 端点 | 参数 | 作用 |
|---|---|---|---|
| `dispatch` | `/dispatch` | `[--target-agent-id]`（缺省 = 环境变量 `AGENT_ID`）`--input-message`（必填）`[--subject] [--conversation-id]` | 派发任务（★真实执行）；传 `--conversation-id` 为续聊 |
| `list-running` | `/list-running` | `[--limit]`（默认 20，上限 50） | 只返回 `EXECUTING` 的任务 |
| `search` | `/search` | `[--keyword] [--limit]` | 按主题关键词搜，**含已结束**；不给关键词 = 最近任务列表 |
| `progress` | `/progress` | `--conversation-id`（必填） | 单任务进度/结果（见上方状态语义表） |
| `cancel` | `/cancel` | `--conversation-id`（必填）`[--reason]` | 受理停止执行中任务（**异步**结束，见 §5） |

通用开关：`--json` 打印平台原始响应信封，不做渲染。

## 退出码

| 码 | 含义 |
|---|---|
| `0` | 成功（**空列表也是成功**，输出里有明确提示） |
| `1` | 本地用法错误（请求没发出去），或平台业务拒绝（`code` 非 `0000`，看 message 原文；「任务不存在或无权访问」「目标智能体不在可派发范围内」等都在这一档） |
| `2` | 环境变量缺失（请求没发出去） |

★业务错误也走 HTTP 200 —— 成败判据是信封里的 `code`，脚本已代劳；
自动化时**以退出码为准**，别只 grep stdout。

## 推荐工作流

1. **派发**：先定指令文本，再定目标 —— 用户点名的智能体就用 `--target-agent-id <id>`，
   没点名就别问、直接 `dispatch --input-message "<指令>"`（缺省派给本沙箱智能体，见 §2）
   → 按返回三态转述（见 §2）→ 记下 `conversationId`。
2. **跟进**：过一阵 `progress --conversation-id <id>`；`EXECUTING` 就再等等，
   `COMPLETE` 取最后一条消息作结果，`FAILED` 报失败详情。
3. **找任务**：知道大概主题 → `search --keyword`；只关心在跑的 → `list-running`。
4. **要停就停**：`cancel --conversation-id <id>` → 按 `cancelled` 字段分流
   （true → 停止已受理；false → 说明任务本来就没在跑，报出实际状态）。
   `cancelled=true` 时**轮询 `progress` 到 `running=false`** 再宣告「已停止」；并记住它最终
   大概率显示 `COMPLETE` —— **不要**读成「跑完了」、也不要取结果（见 §5）。
5. **继续加工**：对**已结束**的任务追加指令 → `dispatch --conversation-id <id> --input-message "<补充指令>"`
   （续聊时目标必须与任务一致：任务是自己跑的，缺省目标就对得上，不用带；任务是子智能体跑的，必须带上 `--target-agent-id <同一智能体>`）。

## Anti-patterns

- ❌ `dispatch` 返回后立刻向使用者说「任务完成了」
  ✅ fire-and-forget：返回只是「已派发」，用 `progress` 轮询到 `COMPLETE` 再报结果
- ❌ 用户没说派给哪个智能体，就反问「要派给哪个智能体」把流程卡住
  ✅ 不传 `--target-agent-id`，缺省即本沙箱智能体（环境变量 `AGENT_ID`）；只有上下文能看出该由某个子智能体做时才显式指定
- ❌ 反过来：上下文明显是某个子智能体的活，也一律缺省兜给自己
  ✅ 该显式传就显式传 —— 缺省只是「用户没说」时的兜底，不是「懒得判断」的借口
- ❌ 拿测试/占位文本随手 `dispatch` 试接口
  ✅ 派发即真实模型执行（费用+副作用），确认指令内容后再发
- ❌ `dispatch` 返回 `taskStatus=CREATE` 却说「已开始执行」
  ✅ 这是启动被拒绝，报出 `error` 原文，任务没在跑
- ❌ 对 `EXECUTING` 的任务续聊派发
  ✅ 会被拒（ALREADY_RUNNING）；等跑完或先 `cancel`
- ❌ 任务 `EXECUTING` 时把 `messages` 里的中间内容当「结果」转述
  ✅ 报「还在执行中」，过一阵再 `progress`，或按用户意愿 `cancel`
- ❌ `cancel` 返回 `cancelled=false` 却说「已取消」
  ✅ 报出实际状态（如「任务已经跑完了，结果仍有效」）
- ❌ `cancel` 返回 `cancelled=true` 就当场宣告「已停止」，把返回当成任务已结束
  ✅ 那只是**受理**；轮询 `progress` 到 `running=false` 才算停稳，再据实转述
- ❌ 取消过的任务，看到 `progress` 报 `COMPLETE` 就当「跑完了」，把最后一条消息交付成结果
  ✅ 被停掉的任务也落 `COMPLETE`，最后一条通常是**被切断的半截输出**；要说「已停止」，别当结果
- ❌ 把 `conversationId` / `targetAgentId` 当字符串加引号传，或从别的字段猜 ID
  ✅ ID 按数字传；conversationId 只从 `dispatch`/`list-running`/`search` 输出里取
- ❌ 拿「任务不存在或无权访问」断定任务不存在
  ✅ 该报错不区分不存在/无权，先核对 ID，再核对任务归属
- ❌ 把 `FAILED` 的任务「重跑」成同一个任务
  ✅ 没有重跑能力；重新 `dispatch` 是新任务、新 conversationId

## References

- 端点级请求/响应字段与错误码：[references/api_reference.md](references/api_reference.md)

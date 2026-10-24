# 定时任务技能使用示例

以下命令均在技能根目录下运行。

> cron 一律是 **Quartz 6 字段**（`秒 分 时 日 月 周`，「日」「周」有且只有一个写 `?`），
> **不是** Linux crontab 的 5 字段。照抄下面的例子即可。

## ★`--message` 标准写法（默认带推送）

任务到点时用户**不在对话里**，执行结果只落在会话里 —— 所以 `--message` 要写明
「执行完把结果用 nuwax-im 私聊发给用户」。下面所有例子的 `--message` 都已按这个写法给出，
实际用时换掉「干什么」那半句即可：

```bash
--message '<要做什么>，然后用 nuwax-im 技能私聊发给用户：python3 scripts/im.py send-p2p --to-user-id "$SYS_USER_ID" --text "<结果正文>"'
```

★整段 `--message` 用**单引号**包裹：`$SYS_USER_ID` 是沙箱环境变量（= 本次运行的用户 ID），
要留给任务执行时那次运行去展开，别在创建时被本地 shell 吃掉。

★`scripts/im.py` 是 **nuwax-im 技能**的入口脚本（**不是**本技能的 `scheduler.py`）。

★**高频任务**（每 5 分钟 / 每小时）改成「**只在异常时才推**」，否则用户会被刷屏 —— 见下方「每 5 分钟执行」。

★用户明确说「不用推 / 我自己看」时才省略推送那半句。

★**本次运行是群消息触发的**（`IM_SCENARIO=group`，或存在 `IM_GROUP_CONV_ID`）时，
推送对象换成那个群，写法见下节 —— **不要**沿用这里的私聊写法。

★**没有群上下文时（网页对话等）也不默认私聊**：用户要求「结果发到 XX 群 / 群里同步一下」，
就写 `send-group` + **那个群的会话 ID（数字）**（见下节）；只有要「通知我」而没提群才用上面这行私聊。

★**`IM_SCENARIO` / `IM_GROUP_CONV_ID` 不是必须的**：为空也没关系（**任务到点执行那次可能为空**），
**不代表发不了群** —— 群发靠 `--message` 里写死的群会话 ID 数字，变量有没有值都一样。

## ★群消息触发时：优先发回群里（不是私聊），要通知谁就在群里 @ 他

```bash
python3 scripts/scheduler.py create \
  --task-name "每日群播报" \
  --execute-type Loop \
  --cron "0 0 9 * * ?" \
  --message '查询成都今天的天气，整理成 3 行以内的中文播报，然后发到群里：python3 scripts/im.py send-group --conv-id 227009028956360704 --text "<播报正文>"；若这步报 IM_20104（当前身份不是该群成员），改用私聊通知本人：python3 scripts/im.py send-p2p --to-user-id "$SYS_USER_ID" --text "<播报正文>"，不要静默结束'
```

必须写全这几件事：**发到群里**、**群会话 ID 的具体数字**、用 **`send-group`**、
以及**群发失败时报 `IM_20104` 就回落私聊通知本人**。
**优先发回那个群**，写成私聊群里没人看得到；只有群发报 `IM_20104`（当前身份不是该群成员）
才算例外。**群发失败必须有交代，别静默结束。**

有人在群里说「结果通知我 / 提醒我」时，**照样发回群，在同一条消息里 @ 他**：

```bash
  --message '查询成都今天的天气，整理成 3 行以内的中文播报，然后发到群里并 @ 他：python3 scripts/im.py send-group --conv-id 227009028956360704 --mention-user-ids 366 --text "<播报正文>"；若这步报 IM_20104，改用私聊通知本人：python3 scripts/im.py send-p2p --to-user-id "$SYS_USER_ID" --text "<播报正文>"，不要静默结束'
```

`--mention-user-ids` 收逗号分隔的 userId（@ 全体用 `--mention-all`，只 @ 少数人别用它）。

★群会话 ID 与被 @ 的 userId **都不能写变量**（如 `$IM_GROUP_CONV_ID` / `$IM_SENDER_ID`）：
任务到点那次执行时这些 `IM_*` 变量可能为空，变量会展开成空串 —— 消息没有目标、@ 也会 @ 空。
两个 ID 都只能靠 `--message` 的文字带过去（本回合说话的人的 id 就是 `IM_SENDER_ID` 的值，展开成数字写进去）。

★写成私聊**照样能发出去**，不会有错给你看，只是群里没人看得到；别指望报错提醒，照上面的写法自己写对。
★反过来，**群发失败不能静默**：`send-group` 报 `IM_20104` 时按上面那句退路回落私聊通知本人。

★**没有群上下文、但用户要求结果进某个群**时，写法**跟这节完全一样**（`send-group` + 群会话 ID 数字），
区别只在群会话 ID 的来源：不是环境变量给的，而是**用户指明的那个群**——拿不准就问用户，
不要因为「环境里没有群信息」就把结果改发私聊。

★建完用 `scheduler.py list` 核一眼：该任务的「消息内容」里应能看到 `send-group` 和那串群会话 ID
（要 @ 人还应有 `--mention-user-ids` 和数字）。
详见 [../SKILL.md](../SKILL.md)「★群消息触发时：优先发回该群，要通知谁就在群里 @ 他」。

## 创建一次性任务

```bash
python3 scripts/scheduler.py create \
  --task-name "会议提醒" \
  --execute-type Once \
  --lock-time "2026-09-01 09:00:00" \
  --message '提醒用户 15 分钟后参加重要会议，然后用 nuwax-im 技能私聊发给用户：python3 scripts/im.py send-p2p --to-user-id "$SYS_USER_ID" --text "<提醒正文>"'
```

`--message` 是**必填**（任务到点时发给智能体的内容），`--lock-time` 必须晚于当前时间。

## 创建循环任务

### 每天早上 9 点执行
```bash
python3 scripts/scheduler.py create \
  --task-name "每日检查" \
  --execute-type Loop \
  --cron "0 0 9 * * ?" \
  --message '执行每日系统检查，然后用 nuwax-im 技能把结果私聊发给用户：python3 scripts/im.py send-p2p --to-user-id "$SYS_USER_ID" --text "<检查结果>"'
```

### 每 2 小时执行
```bash
python3 scripts/scheduler.py create \
  --task-name "定期同步" \
  --execute-type Loop \
  --cron "0 0 */2 * * ?" \
  --message '同步数据到远程服务器，然后用 nuwax-im 技能把结果私聊发给用户：python3 scripts/im.py send-p2p --to-user-id "$SYS_USER_ID" --text "<同步结果>"'
```

### 工作日早上 9 点执行
```bash
python3 scripts/scheduler.py create \
  --task-name "工作日报告" \
  --execute-type Loop \
  --cron "0 0 9 ? * 1-5" \
  --message '生成今天的工作日报，然后用 nuwax-im 技能把日报正文私聊发给用户：python3 scripts/im.py send-p2p --to-user-id "$SYS_USER_ID" --text "<日报正文>"'
```

### 每 5 分钟执行
```bash
python3 scripts/scheduler.py create \
  --task-name "频繁监控" \
  --execute-type Loop \
  --cron "0 */5 * * * ?" \
  --message '检查系统状态，只在异常时才用 nuwax-im 技能私聊通知用户：python3 scripts/im.py send-p2p --to-user-id "$SYS_USER_ID" --text "<异常详情>"'
```

### 每月 1 号凌晨执行
```bash
python3 scripts/scheduler.py create \
  --task-name "月度备份" \
  --execute-type Loop \
  --cron "0 0 0 1 * ?" \
  --message '执行月度数据备份，然后用 nuwax-im 技能把结果私聊发给用户：python3 scripts/im.py send-p2p --to-user-id "$SYS_USER_ID" --text "<备份结果>"'
```

## 更新任务

```bash
# 改任务名称（必带 --task-name）
python3 scripts/scheduler.py update \
  --task-id 123 \
  --task-name "更新后的任务名称"

# 改任务名称 + 消息
python3 scripts/scheduler.py update \
  --task-id 123 \
  --task-name "会议提醒" \
  --message "会议时间改到下午两点"
```

`--task-name` 每次都要带（不是「不改就不传」）；`--message` 不传就保持原内容。

★群任务的推送写法有问题时，就是走 `update` 改 `--message`：带上 `send-group` 和
**群会话 ID 的具体数字**，要通知某个人再加 `--mention-user-ids` + **他的数字 ID**
（两个 ID 都别写变量，理由见上）。

### ⛔ 想「改周期」请走重建，不要走 update

平台当前的 `update` **改不动周期**：`--execute-type Loop --cron ...` 会被服务端校验后丢弃，
**接口仍回成功**，但周期原封不动。「把一次性改成每天 9 点」「把每天改成每小时」都属于这一类。

```bash
# ❌ 这样改不会生效（虽然回成功）
python3 scripts/scheduler.py update \
  --task-id 123 --task-name "每小时检查" \
  --execute-type Loop --cron "0 0 * * * ?"

# ✅ 正确做法：取消旧的，按新周期建新的（新任务、新 ID）
python3 scripts/scheduler.py cancel --task-id 123
python3 scripts/scheduler.py create \
  --task-name "每小时检查" \
  --execute-type Loop \
  --cron "0 0 * * * ?" \
  --message '检查系统状态，只在异常时才用 nuwax-im 技能私聊通知用户：python3 scripts/im.py send-p2p --to-user-id "$SYS_USER_ID" --text "<异常详情>"'
```

`--execute-type Once --lock-time ...` 是唯一会写周期的分支，但写进去的是「该时刻的 日/月 粒度」
表达式，**年份会被丢掉**（传 `2028-01-01 10:00:00`，实际落到 `2027-01-01 10:00`）。
一次性任务要换时间，同样建议 cancel + create，行为更好预期。详见
[api_reference.md](api_reference.md) §2。

## 取消任务

```bash
python3 scripts/scheduler.py cancel --task-id 123
```

成功输出：
```
✅ 定时任务取消成功!
🆔 任务ID: 123
📋 跟踪ID: trace-id-456
```

取消后该任务**立刻从列表消失**（列表只列待执行的），这是正常的。
重复取消同一个任务也会回成功——**没有**「cancelled=false」这类「其实没变」的信号。

## 查询任务列表

```bash
# 友好格式
python3 scripts/scheduler.py list

# JSON 格式
python3 scripts/scheduler.py list --json
```

友好格式输出示例：
```
✅ 找到 2 个定时任务

[1] ID: 123
    任务名称: 每日检查
    执行类型: Loop
    执行时间: 2026-09-27T01:00:00.000+00:00
    执行周期: 0 0 9 * * ?
    消息内容: 执行每日系统检查，然后用 nuwax-im 技能把结果私聊发给用户…

[2] ID: 124
    任务名称: 会议提醒
    执行类型: Once
    执行时间: 2026-09-01T01:00:00.000+00:00
    执行周期: 0 0 9 1 9 ?
    消息内容: 提醒用户 15 分钟后参加重要会议，然后用 nuwax-im 技能私聊发给用户…
```

★两个阅读要点：

- **时间字段是 UTC**：`2026-09-27T01:00:00.000+00:00` = 北京时间 `2026-09-27 09:00`。
  回答使用者时先转成当地时间，别把 UTC 当本地时间报。
- **「执行时间」对循环任务也出现**，它是**下一次执行时刻**（服务端算出来的），
  不是「这个任务只跑一次」。反过来，一次性任务也会带「执行周期」（服务端存下来的等价 cron）。
  判断类型只看「执行类型」那一行。

## 完整示例

创建每天早上 8 点执行的健康检查任务：

```bash
# 环境变量由沙箱注入，一般无需手动设置；本地调试时才需要
export PLATFORM_BASE_URL="https://your-platform.example.com"
export CONVERSATION_ID="12345"
export SANDBOX_ACCESS_KEY="your_access_key"

# 创建任务
python3 scripts/scheduler.py create \
  --task-name "每日健康检查" \
  --execute-type Loop \
  --cron "0 0 8 * * ?" \
  --message '检查系统各项指标是否正常，然后用 nuwax-im 技能把结果私聊发给用户：python3 scripts/im.py send-p2p --to-user-id "$SYS_USER_ID" --text "<检查结果>"'
```

成功输出：
```
✅ 定时任务创建成功!
🆔 任务ID: 123
📋 跟踪ID: trace-id-123
```

★**必须看到正的「任务ID」才算建成**：只打印出「创建失败: 服务端未返回任务ID」就是没建
（最常见原因是 cron 不是 Quartz 6 字段），此时退出码为 1。

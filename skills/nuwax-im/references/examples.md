# nuwax-im 示例集

`SKILL.md`「示例」章的完整版 —— 5 个**真机跑通**的命令序列，可直接照抄（把 userId / convId 换成你自己的）。

> ★跑之前先看一眼 `SKILL.md` 的「★必查字段」与「★`IM_20104 不是会话成员`」两节 ——
> 读返回时的 `convType` / `peerUserId` / `status` / `role` / `memberStatus`，以及最容易误判的那个错误码，
> 都在那里。大部分「发错地方 / 报错看不懂」都能靠那两节避免。

## 例 1：给某人发一条消息

```bash
$ python3 scripts/im.py send-p2p --to-user-id 6 --text "构建已经通过了"
✅ send-p2p成功
bizMsgId: 3f2a9c1e8b7d4a5f9e0c1b2a3d4e5f6a
  重发请带 --biz-msg-id 3f2a9c1e... （不带会再发一条）
msgId: 227861195531485184
convId: 227861195531485140
```

## 例 2：建群 → 拉人 → 发消息（一条链路，隐含依赖顺序）

```bash
# ① 建群。clientGroupId 是业务幂等键：脚本重跑不会建出第二个群（实测第二次 created=false，convId 不变）
#   ★--members 必填且不能为空（IM 硬性要求），它【不是】成员名单 —— 我自己会被自动加进去当群主。
#   ⚠️ 换掉 7,8 是你要拉的人：他们会真的进群、收到通知；建群还会顺带在平台建一个同名团队空间。
#   ⛔ 别拿不存在的 userId 顶替：整个建群会以 IM_90002 失败，而文案看着像「平台抖动」，
#      容易让人一直重试（详见 references/api_reference.md §9）。
CONV=$(python3 scripts/im.py create-group --name "发布群" --members 7,8 \
        --client-group-id release-2026-09-22 --json | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["data"]["convId"])')

# ② 补人（部分成功是正常返回）
python3 scripts/im.py members-add --conv-id "$CONV" --user-ids 7,8

# ②' 拉【智能体】进群并让它「监听」：群里每条消息都会发给它（真实模型费用），
#    --agent-mode 只对智能体生效、真人成员忽略；不给就是 1（唤起：有人 @ 才回）。
python3 scripts/im.py members-add --conv-id "$CONV" --user-ids 9 --agent-mode 2

# ③ 发消息
python3 scripts/im.py send-group --conv-id "$CONV" --text "群建好了" --mention-all
```

## 例 3：写一个「失败可安全重试」的脚本

```bash
KEY=$(uuidgen | tr -d -)                       # 自己生成幂等键，一开始就拿在手里
for attempt in 1 2 3; do
  python3 scripts/im.py send-p2p --to-user-id 6 --text "部署完成" --biz-msg-id "$KEY"
  rc=$?
  [ $rc -eq 0 ] && break                        # 成功
  [ $rc -eq 3 ] || break                        # ★只有 3（3613）值得重试；4/5 重试无用
  sleep 2
done
```

## 例 4：非文本消息走 `--body`

`--text` 只是 `{"text": "..."}` 的简写，只对 `msgType=text` 成立。其它类型结构不同，
必须显式给 `--msg-type` + `--body`：

```bash
python3 scripts/im.py send-p2p --to-user-id 6 --msg-type image \
  --body '{"url":"https://example.com/a.png","width":800,"height":600}'

# body 太大就落文件：--body @/tmp/body.json
```

## 例 5：先查会话 / 联系人，再动手（★唯一「不猜 ID」的姿势）

```bash
# ① 用户说「在发布群里说一句」——先找出那个群的 convId，【不要】自己拼一个
$ python3 scripts/im.py conversation-list --limit 50
{
  "list": [
    { "convId": 228036057097043968, "convType": 2, "name": "发布群",
      "memberCount": 2, "lastMsgDigest": "冯飞1: 群建好了", "unreadCount": 0, "status": 1 },
    { "convId": 228036057097043999, "convType": 4, "name": "我的团队007",   # ★4=团队群
      "spaceId": 785, "memberCount": 2, "lastMsgDigest": "…", "status": 1 },
    { "convId": 227861195531485140, "convType": 1, "name": "张三",
      "peerUserId": 33, "lastMsgDigest": "[图片]", "status": 2 } ],      # ★2=已解散
  "hasMore": false, "nextCursor": null          # ★hasMore=false ⇒ 停，别再翻
}

# ② 会话里没有「张三」这个人？查联系人（★三态混合，status==1 才是好友）
$ python3 scripts/im.py contact-list
{ "items": [ { "userId": 33, "subjectType": 1, "displayName": "张三", "status": 1 },
             { "userId": 44, "subjectType": 2, "displayName": "小助手", "status": 2 } ],
  "degraded": false, "truncated": false }
# ★status=2 是「对方申请我，待同意」—— 不能当好友，更不能直接发消息给他

# ③ 发消息 —— ★两个字段分两路，按 convType 选（见 SKILL.md「会话列表 → 发消息：用哪个字段」）

# ③-1 群聊：取①里 convType=2 那条的【convId】走 send-group
$ python3 scripts/im.py send-group --conv-id 228036057097043968 --text "发布群已经建好了"
✅ send-group成功
convId: 228036057097043968

# ③-2 单聊：取①里 convType=1 那条的【peerUserId】走 send-p2p
#      ★是用 peerUserId=33，不是那条的 convId=227861195531485140
#        —— send-p2p 根本没有 convId 这个入参
$ python3 scripts/im.py send-p2p --to-user-id 33 --text "发布群已经建好了"
```

# ④ 要看上表某个会话的【群号】：列表里 groupNo 恒为 null（翻到底也没有），
#    只能定点查详情 —— 一次一个会话，别拿它当「列会话」用
$ python3 scripts/im.py conversation-detail --conv-id 228036057097043968
#    ★这个身份不在会话里，它照样返回（别的按 convId 的端点会报 IM_20104）；
#      此时 myRole=null、unreadCount=0 —— 0 是占位值，不是「没有未读」，判成员只认 myRole
#    ★主语是【本次行使身份】：群场景下那是「智能体在那个群里的虚拟身份」，不是 AK 归属人
#      ⇒ AK 绑 A 群去查 B 群，即便本人在 B 群，myRole 也是 null，别读成「我不在那个群」

★ 上面①的三条返回里，`convType` / `peerUserId` / `status` 这三个字段最容易读错，
判据见 `SKILL.md` 的「★必查字段」。

## 例 6：批量发系统通知（★★仅管理员）

★这个端点的**发送身份由 IM 固定**（一个叫「系统通知」的合成账号）—— 入参里**根本没有**发送身份字段，
所以它天然不会变成「以你的名义发」。★收件人**不必是你的联系人**，每人各自与这个合成账号成一条会话，
**带未读红点**。⇒ 用之前先想清楚收件人会看到什么。

```bash
# ① 一次给一批人发同一条。★去重后上限 100 人（超出由 IM 整批拒绝，不是截断）
#    ★--text 缺省按 msgType=text（纯文本气泡，DEV-298）发出；想要居中提示条加 --msg-type system
python3 scripts/im.py system-notify --to-user-ids 6,7,8 --text "您的存储空间即将用满，请及时清理"
#   → 目标 3 人，成功 3 人，跳过 0 人

# ② 非文本（通知卡）：--msg-type card 配 --body（白名单四型 text/system/card/rich_text，
#    ★image/file 等单体媒体型仍禁；rich_text 需 --body 给 markdown/segments/attachments 结构）
python3 scripts/im.py system-notify --to-user-ids 6,7 --msg-type card \
  --body '{"title":"部署完成","desc":"v1.2.3 已上线"}'

# ③ 想自己控幂等键（★整批共用一个，不是每人一个）
python3 scripts/im.py system-notify --to-user-ids 6,7 --text "续费提醒" --biz-msg-id renewal-20260924
```

### ★回执是「对账单」，`failed` 非空**不等于**调用失败

```bash
python3 scripts/im.py system-notify --to-user-ids 7,9 --text "续费提醒"
#   → 目标 2 人，成功 1 人，跳过 1 人
#   ★下列收件人没投出去（整体调用仍是成功）：
#     9 —— IM_20103: 会话不存在
#     补发：换一个【新的】--biz-msg-id，且 --to-user-ids 只传上面这些人。
#     ★别把已成功的人混进补发批次 —— 那会真的给他们再发一条。
```

**两条相反的重试纪律，判据是整体 `code`，不是 `failed` 的长度**：

```bash
# 情形 A：整体成功（code=0000），failed 里有 9 —— ★不能原样重试整批
#   同一个 --biz-msg-id 再调一次只会把上面那份回执【原样回放】（IM_20005），既不重发也不补发。
python3 scripts/im.py system-notify --to-user-ids 9 --text "续费提醒" --biz-msg-id renewal-20260924-1
#                                                           ↑ 换成【新】键，且只传 failed 里的人

# 情形 B：整体失败（code≠0000，如 4030 / 3614 / 参数错）—— ★原样整批重试是安全的
#   幂等占位已释放；已成功的那几条会被 IM 侧消息域的唯一键挡住（那个值由 bizMsgId + toUserId
#   决定、可复现）⇒ 不重复落库、不重复扇出，只有没发出去的会被补上。
python3 scripts/im.py system-notify --to-user-ids 6,7,8 --text "续费提醒" --biz-msg-id renewal-20260924
#                                                           ↑ 同一个键，整批重来
```

⛔ **别做**：把 `failed` 非空当成「调用失败」去重试**整批** —— 已成功的人**会真的再收到一条**。
⛔ **别做**：把 `--to-user-ids` 当「我的联系人列表」用。它**不校验这层关系**，
把人名当 ID 传进去就是发给一个真实的人（或一个不存在的 ID），**都不报错**。

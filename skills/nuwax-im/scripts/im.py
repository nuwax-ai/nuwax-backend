#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
nuwax-im 技能客户端 —— 以「当前用户」身份操作 Nuwax IM。

二十个端点全部收在本脚本里，形如：

    python3 scripts/im.py send-p2p --to-user-id 6 --text "你好"
    python3 scripts/im.py create-group --name "项目群" --members 6,7
    python3 scripts/im.py conversation-list --limit 20     # 我有哪些会话（拿 convId）
    python3 scripts/im.py conversation-detail --conv-id 228036057097043968   # 某个会话的详情（拿群号）
    python3 scripts/im.py contact-list                     # 我的联系人（拿 toUserId）
    python3 scripts/im.py endpoints            # 离线列出全部端点，不发请求

★身份不在参数里。发送者永远是这个 AK 所属的用户 —— 请求体里根本没有
  userId 字段（服务端的 DTO 就把它删了），所以「冒充别人」在协议层不可能。
  要换身份，只能换 SANDBOX_ACCESS_KEY。

★幂等键 bizMsgId。所有写端点都接受 --biz-msg-id；不给则本地生成一个 UUID
  并回显。响应丢了要重发时，必须带上同一个 --biz-msg-id，否则会发出第二条。

★退出码就是错误分类，别只看 stdout：

    0  成功
    1  参数问题：本地用法错误（请求没发出去），或平台码 4000（服务端拒了参数，无副作用）
    2  环境变量缺失（请求没发出去）
    3  3613 IM 暂时不可用 —— 可重试，带同一个 --biz-msg-id
    4  3614 IM 业务拒绝   —— 不可重试，按 message 原文改请求
    5  3612 IM 未配置/停用 —— 部署问题，重试无意义，找运维

  3 和 4 的区别是「等一会儿再发」还是「改参数再发」，别混。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import uuid
from urllib import request
from urllib.error import HTTPError, URLError

# ---------------------------------------------------------------- 常量

API_PREFIX = "/api/v1/4sandbox/nuwax-im"
TIMEOUT_SECONDS = 30

# 平台侧错误码（服务端 BizExceptionCodeEnum 的取值，稳定，可依赖）
CODE_NOT_CONFIGURED = "3612"
CODE_RETRYABLE = "3613"
CODE_REJECTED = "3614"

EXIT_OK = 0
EXIT_USAGE = 1
EXIT_ENV = 2
EXIT_RETRYABLE = 3
EXIT_REJECTED = 4
EXIT_NOT_CONFIGURED = 5


# ---------------------------------------------------------------- 环境


def require_env():
    """读取沙箱环境变量；缺一个就退出（退出码 2，请求不会发出去）。"""
    base = (os.getenv("PLATFORM_BASE_URL") or "").rstrip("/")
    key = os.getenv("SANDBOX_ACCESS_KEY") or ""
    missing = [n for n, v in (("PLATFORM_BASE_URL", base), ("SANDBOX_ACCESS_KEY", key)) if not v]
    if missing:
        print("❌ 缺少环境变量: " + ", ".join(missing), file=sys.stderr)
        print("   本技能需要沙箱环境变量 PLATFORM_BASE_URL 与 SANDBOX_ACCESS_KEY；", file=sys.stderr)
        print("   不在 4sandbox 里运行时无法使用。", file=sys.stderr)
        sys.exit(EXIT_ENV)
    return base, key


def die(message):
    """本地用法错误 —— 请求没发出去，退出码 1。"""
    print("❌ " + message, file=sys.stderr)
    sys.exit(EXIT_USAGE)


# ---------------------------------------------------------------- 参数类型


def big_int(text):
    """
    ★雪花 ID 必须当整数送。

    msgId / convId 是雪花号（实测 227861195531485184 ≈ 2.3e17），超过 JS 的
    Number.MAX_SAFE_INTEGER。Python 的 int 是任意精度，原样序列化不会失真 ——
    但前提是它作为 **JSON number** 发出去，写成字符串服务端会反序列化失败。
    所以这里强制转 int，不做字符串透传。
    """
    raw = str(text).strip()
    try:
        value = int(raw)
    except ValueError:
        raise argparse.ArgumentTypeError("不是合法整数: %r" % text)
    if value <= 0:
        raise argparse.ArgumentTypeError("必须是正整数: %r" % text)
    return value


def big_int_list(text):
    """逗号分隔的 ID 列表，中英文逗号都认。"""
    if isinstance(text, list):
        return text
    items = [part.strip() for part in str(text).replace("，", ",").split(",")]
    values = [big_int(item) for item in items if item]
    if not values:
        raise argparse.ArgumentTypeError("ID 列表为空: %r" % text)
    return values


def parse_json_arg(text, label):
    """解析 JSON 字符串，或 @文件路径。"""
    if text is None:
        return None
    raw = text.strip()
    if raw.startswith("@"):
        try:
            with open(raw[1:], "r", encoding="utf-8") as handle:
                return json.load(handle)
        except OSError as exc:
            die("%s 读文件失败: %s" % (label, exc))
        except json.JSONDecodeError as exc:
            die("%s 文件不是合法 JSON: %s" % (label, exc))
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        die("%s 不是合法 JSON: %s" % (label, exc))


# ---------------------------------------------------------------- HTTP


def call(path, payload):
    """
    POST 到某个端点，返回响应信封（dict）。

    ★业务错误也走 HTTP 200：code 才是判据，别被状态码骗。
    网络层失败返回一个合成的信封（code=-1），由 classify() 归类。
    """
    base, key = require_env()
    url = base + API_PREFIX + path
    headers = {
        "Content-Type": "application/json",
        "Authorization": "Bearer " + key,
    }
    # 租户永远不需要传：服务端按 AK 自行解析租户，客户端不带任何租户参数/头。
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = request.Request(url, data=body, headers=headers, method="POST")
    try:
        with request.urlopen(req, timeout=TIMEOUT_SECONDS) as resp:
            text = resp.read().decode("utf-8")
            return json.loads(text) if text else {}
    except HTTPError as exc:
        text = exc.read().decode("utf-8", "replace")
        try:
            envelope = json.loads(text)
        except Exception:
            envelope = {"message": text[:800] or "(空响应体)"}
        envelope.setdefault("statusCode", exc.code)
        return envelope
    except URLError as exc:
        # 到不了平台 —— 请求根本没出去，重发是安全的（带上同一个 bizMsgId。）
        return {"code": "-1", "message": "网络错误，请求未送达平台: %s" % exc.reason}


def classify(envelope):
    """把响应信封映射成退出码。"""
    code = str(envelope.get("code", ""))
    if code in ("0000", "0"):
        return EXIT_OK
    if code == CODE_NOT_CONFIGURED:
        return EXIT_NOT_CONFIGURED
    if code == CODE_RETRYABLE:
        return EXIT_RETRYABLE
    if code == CODE_REJECTED:
        return EXIT_REJECTED
    # 未登录 / AK 无效 / 平台侧别的业务错误：都不在 IM 的三分类里，按本地错误处理
    return EXIT_USAGE


# ---------------------------------------------------------------- 输出


def print_member_add(inner):
    """
    加成员结果的**分档**渲染（members/add 专用）。

    ★★为什么不能只打 inserted：一次「加 5 个人」服务端有**六种**结局，
    其中四种的 inserted/revived 都是 0。只打一个数字的话
    「转成入群申请（等管理员审）」「发了邀请卡片（等对方本人同意）」
    「冷却期被跳过」和「真的什么都没发生」在输出里**逐字相同** ——
    使用者会据此说「人已经拉进来了」或「这条命令没用」，而两者都可能不对。
    """
    def ids(key):
        v = inner.get(key) or []
        return "、".join(str(x) for x in v) if v else "-"

    print("本次加成员结果（★一次调用可能同时落多档，逐档看）：")
    print("  ① 真的进群了     : inserted=%s（新增） revived=%s（退群后又被拉回来）"
          % (inner.get("inserted"), inner.get("revived")))
    print("  ② 本来就在群里   : alreadyIn=%s —— 不是失败，但也不该说『已邀请』"
          % inner.get("alreadyIn"))
    if inner.get("applied"):
        print("  ③ ★转成入群申请 : %s 人（%s）—— 等【群主/管理员】审批，人还没进群"
              % (inner.get("applied"), ids("appliedUserIds")))
        print("     ⛔ 别对使用者说『已经拉进群了』—— TA 要等管理员同意才会进来")
    if inner.get("pendingConfirm"):
        print("  ④ ★等对方点同意 : %s 人（%s）—— 已发邀请卡片，等【被邀请人本人】同意，人还没进群"
              % (inner.get("pendingConfirm"), ids("pendingConfirmUserIds")))
        print("     ★与③不是一回事：③等的人是管理员，本档等的人是对方自己")
    if inner.get("skippedUserIds"):
        print("  ⑤ 冷却期被跳过   : %s —— ★过一阵还能再试，【不是】『这个人加不了』"
              % ids("skippedUserIds"))
    if inner.get("failedUserIds"):
        print("  ⑥ 加不进去       : %s —— 永久性理由（账号停用 / 不存在 / 撞群上限）"
              % ids("failedUserIds"))
    # ★memberCount 缺失时【不打】这一行：对面 IM 旧版就不返回它，
    #   打一句「memberCount=None」会让人以为"群人数读成了空"，那是另一回事。
    if inner.get("memberCount") is not None:
        print("  群成员总数 memberCount=%s" % inner.get("memberCount"))

    if not any(inner.get(k) for k in ("inserted", "revived", "alreadyIn", "applied",
                                      "pendingConfirm", "skippedUserIds", "failedUserIds")):
        # ★这一档必须【明确说不知道】，不能替使用者断言"什么都没发生"。
        #   六档全空只可能是两件事，而它们在本输出里无法区分：
        #   ① 请求里的 userIds 本来就没人可加（正常，但调用方该自己核对入参）；
        #   ② 对面 IM 是【旧版本】，不返回 applied/pendingConfirm/skippedUserIds ——
        #      那么"转成申请""等对方同意"这两个真实结局就是被【吞掉】了。
        print("⚠️  六档全空 —— 这次【一个人都没动】。两种成因，本输出区分不了：")
        print("   ① 入参 userIds 里确实没人可加（先核对一遍你传的 ID）；")
        print("   ② 对面 IM 版本旧、不返回 applied/pendingConfirm/skippedUserIds 三个字段")
        print("      ⇒ 『转成申请』或『等对方同意』可能已经发生，只是没透出来。")
        print("   ★所以这里【不能】下结论说『什么都没发生』—— 拿不到字段就是拿不到字段。")


def print_write(data, action):
    """
    写端点的结果渲染。

    写端点的 data 是 {bizMsgId, data:{...}} —— 外层是幂等键，内层才是业务对象。
    ★bizMsgId 一定要打出来：它是重发时唯一的凭据。
    """
    biz_msg_id = data.get("bizMsgId")
    inner = data.get("data")
    print("✅ " + action + "成功")
    if biz_msg_id:
        print("bizMsgId: %s" % biz_msg_id)
        print("  重发请带 --biz-msg-id %s（不带会再发一条）" % biz_msg_id)
    if isinstance(inner, dict):
        if inner.get("idempotentHit") is True:
            print("idempotentHit: true —— ★这次是幂等重放，复用的是上一次的结果，没有新增")
        for label, field in (("msgId", "msgId"), ("convId", "convId")):
            if inner.get(field) is not None:
                print("%s: %s" % (label, inner[field]))
        # ★★加成员：六档结局逐档渲染（见 print_member_add 的注释）。
        #   判据是 inserted 字段在场 —— 只有 members/add 的结果里有它。
        if inner.get("inserted") is not None:
            print_member_add(inner)
        if inner.get("created") is not None:
            print("created: %s（false=会话已存在、直接复用，没有新建）" % inner["created"])
        # ★★system-notify：data 是【对账单】—— 计数 + 成功明细 + 业务性跳过，
        #   判据是 targetCount 在场。★failed 非空 ≠ 调用失败（整体仍是 0000）。
        if inner.get("targetCount") is not None:
            print_system_notify(inner)
        if inner.get("targetUsers") is not None:
            print("targetUsers: %s  targetDevices: %s  publishedFrames: %s"
                  % (inner.get("targetUsers"), inner.get("targetDevices"), inner.get("publishedFrames")))
            if not inner.get("targetDevices"):
                print("⚠️  一个在线设备都没命中 —— 事件不落库、无补偿，收件人离线就是收不到")
        # ★排队区「声明已消费」：判据是 removedMsgIds，不是 removedCount，
        #   更不能拿「请求条数 == removedCount」当成功条件 —— 删少了是正常结局。
        if inner.get("removedMsgIds") is not None:
            removed = inner.get("removedMsgIds") or []
            print("已清除 %d 条" % len(removed))
            if removed:
                print("removedMsgIds: %s" % ", ".join(str(x) for x in removed))
            else:
                print("★一条都没删掉 —— 正常，不是失败。三种成因都会这样：行已被 IM 侧取走、"
                      "id 本就不在队里（传错/重复清）、幂等重放。那批消息由 IM 侧正常处理，不会丢")
    if inner is not None and not isinstance(inner, dict):
        print(json.dumps(inner, ensure_ascii=False, indent=2))


def print_system_notify(inner):
    """
    「系统通知」的回执渲染 —— 它是一张【对账单】，不是「一条消息」。

    ★failed 非空 ≠ 调用失败：整体信封仍是 0000，「某个收件人在平台上查不到」是业务结论。
    ★补发纪律（两条相反，别记混）：
      · code=0000（哪怕 failed 非空）⇒ 本次结果已被幂等闸门缓存，用【同一个】--biz-msg-id
        重试只会回放这个结果（既不重发也不补发）。要补发 failed 里的人，
        必须【换新的 --biz-msg-id 且只传那些人】—— 把已成功的混进去会真的再发一条。
      · 调用本身失败（code≠0000 / 退出码非 0）⇒ 幂等占位已释放，用同一个键重试
        【整批】是安全的：已成功的会被消息层的唯一键挡住。
    """
    total = inner.get("targetCount")
    sent = inner.get("sentCount")
    failed = inner.get("failed") or []
    print("目标 %s 人，成功 %s 人，跳过 %s 人" % (total, sent, len(failed)))
    if not failed:
        return
    print("★下列收件人没投出去（整体调用仍是成功）：")
    for item in failed:
        print("  %s —— %s" % (item.get("toUserId"), item.get("reason")))
    print("  补发：换一个【新的】--biz-msg-id，且 --to-user-ids 只传上面这些人。")
    print("  ★别把已成功的人混进补发批次 —— 那会真的给他们再发一条。")


def print_pending(data):
    """
    排队区 list 的结果渲染。

    ★按列表顺序原样打印、不重排：那个顺序就是用户说话的顺序
    （服务端与 IM 侧合并投喂用的是同一把尺子）。
    """
    items = data.get("items") or []
    print("=== 排队区：%d 条等待处理 ===" % len(items))
    if not items:
        print("（空）—— 通常意味着这些消息已被正常处理，不是出错")
        return
    for it in items:
        who = it.get("senderName") or it.get("senderId")
        print("  msgId=%s  %s: %s" % (it.get("msgId"), who, it.get("text") or it.get("digest") or ""))
    print("→ 确认要消费就把这些 msgId 原样传给 agent-pending-clear（★读与清之间新来的消息不要一起清）")
    if len(items) >= 500:
        print("⚠️  撞到 500 条上限 —— 这是异常堆积，请上报，不要当成正常翻页")


def print_conversation_detail(data):
    """
    会话详情的渲染。

    ★这里必须自己判一次「在不在这个会话里」并把结论打出来：不是成员时响应里的
    `unreadCount` 是 `0` 而**不是** `null`，直接读 JSON 很容易把「看不见」读成「没有未读」。
    唯一可靠的判据是 `myRole` 是不是 `null`（判据在服务端，见 references/api_reference.md §16）。

    ★★这条判据的主语是「**本次行使身份**」，不是「AK 归属人」，也与 `convId` 无关：
    群场景下行使的是**智能体在那个群里的虚拟身份**，私聊 / 手工 AK 才是本人。
    所以 AK 绑 A 群去查 B 群时，即便本人在 B 群，myRole 一样是 null —— 输出里必须点明，
    否则用户会把它读成「我不在那个群」（错误结论，会让人白白去找群主确认）。
    """
    print(json.dumps(data, ensure_ascii=False, indent=2))
    print()
    if data.get("myRole") is None:
        print("★ 本次行使身份【不是】这个会话的成员：上面的 myRole / myNickName / topFlag / joinTime 等")
        print("  视角字段整块为 null，而 unreadCount=0 只是取不到视角时的占位值，")
        print("  【不是】「未读已读完」—— 别拿 0 当结论。")
        print("  ★主语提醒：群场景下这个身份是【智能体在那个群里的虚拟身份】，不是 AK 归属人本人；")
        print("    所以这条结论说的是「那个身份不在里面」，推不出「我本人不在里面」。")
        print("  中立字段（convId / convType / name / memberCount / groupNo / ext / status）照常可信。")
    else:
        print("✅ 本次行使身份是这个会话的成员（myRole=%s）。" % data.get("myRole"))
    if data.get("groupNo"):
        print("群号: %s" % data["groupNo"])


def print_failure(envelope, action):
    code = str(envelope.get("code", ""))
    print("❌ " + action + "失败: " + str(envelope.get("message", "未知错误")), file=sys.stderr)
    for label, field in (("平台错误码", "code"), ("HTTP 状态", "statusCode"), ("跟踪 ID", "tid")):
        if envelope.get(field) is not None:
            print("   %s: %s" % (label, envelope[field]), file=sys.stderr)
    if code == CODE_NOT_CONFIGURED:
        print("   → IM 适配层没配好（3612，请求根本没发出去）。这是部署问题，"
              "重试没用，请让运维检查 IM_RPC_BASE_URL / IM_RPC_SECRET_PLATFORM_IM。", file=sys.stderr)
    elif code == CODE_RETRYABLE:
        print("   → IM 暂时不可用（3613）。可以稍后重试；"
              "重试时务必带上同一个 --biz-msg-id，否则可能发出第二条。", file=sys.stderr)
    elif code == CODE_REJECTED:
        print("   → IM 拒绝了这次操作（3614）。重试无用，按上面的原文改请求。", file=sys.stderr)
    elif code == "-1":
        print("   → 请求没送到平台。检查 PLATFORM_BASE_URL 与网络。", file=sys.stderr)
    elif code == "4000":
        # ★4000 是平台的参数错误（ErrorCodeEnum.INVALID_PARAM），不是身份问题。
        #   曾经把它和 4010/4030 归成一类，实测会把人引到「去查 AK」的死路上。
        print("   → 平台判定请求参数不合法（4000，请求已被拒绝，没有产生任何副作用）。"
              "按上面的原文改参数，别原样重试。", file=sys.stderr)
    elif code in ("4010", "4030"):
        print("   → 身份问题：AK 无效或过期，检查 SANDBOX_ACCESS_KEY（租户无需传，服务端按 AK 自行解析）。", file=sys.stderr)


def emit(envelope, action, json_mode):
    code = classify(envelope)
    if json_mode:
        print(json.dumps(envelope, ensure_ascii=False, indent=2))
        return code
    if code != EXIT_OK:
        print_failure(envelope, action)
        return code
    data = envelope.get("data")
    if data is None:
        print("✅ " + action + "成功")
    elif action == "agent-pending-list" and isinstance(data, dict):
        print_pending(data)
    elif action == "conversation-detail" and isinstance(data, dict):
        print_conversation_detail(data)
    elif isinstance(data, dict) and "bizMsgId" in data:
        print_write(data, action)
    elif action == "read-counts" and isinstance(data, list) and not data:
        # ★空数组不是失败：请求打通了、code=0000，只是这个会话取不到已读明细。
        #   直接打一个裸的 [] 会让人以为「接口坏了」或「没人读」——都不是。
        print("[] （空）")
        print("★ 这是成功返回，不是失败：本次没拿到任何已读明细。")
        print("  也**不等于**「没人读」——同一条消息在 message-get 里 readCount 可能是非 0（实测为 2）。")
        print("  取已读数以 message-get 的 readCount 为准，别拿这个空数组下结论。")
    else:
        print(json.dumps(data, ensure_ascii=False, indent=2))
    return code


# ---------------------------------------------------------------- 请求体组装


def require_renderable_body(msg_type, body):
    """
    文本类 body 的前置校验。

    字段名写错（例如把 text 写成 content）时，服务端是「发送成功、只把摘要变成 [文本]」：
    气泡渲染不出、搜索也搜不到，而调用方收不到任何错误。所以必须在这里拦。
    """
    kind = (msg_type or "").lower()
    if kind in ("text", "system"):
        text = body.get("text") if isinstance(body, dict) else None
        if not isinstance(text, str) or not text.strip():
            hint = "；你给的是 content，正确字段名是 text" \
                if isinstance(body, dict) and "content" in body else ""
            die('msgType=%s 的 --body 缺少非空 text 字段%s，应形如 {"text":"..."}' % (msg_type, hint))
    elif kind == "rich_text":
        data = body if isinstance(body, dict) else {}
        text = data.get("text")
        has_text = isinstance(text, str) and bool(text.strip())
        has_segments = isinstance(data.get("segments"), list) and len(data["segments"]) > 0
        has_atts = isinstance(data.get("attachments"), list) and len(data["attachments"]) > 0
        if not (has_text or has_segments or has_atts):
            die("msgType=rich_text 的 --body 至少要有一个非空的 text / segments / attachments"
                "（三者皆空的富文本前端渲染出来是空气泡）")


def message_body(ns):
    """
    组装消息体。

    --text 是 {"text": "..."} 的简写，只对文本消息成立；
    别的类型（image / file / rich_text …）结构不同，必须用 --body 给原始 JSON。
    """
    text = getattr(ns, "text", None)
    raw = getattr(ns, "body", None)
    if text is not None and raw is not None:
        die("--text 与 --body 只能给一个")
    if raw is not None:
        msg_type = getattr(ns, "msg_type", None)
        if not msg_type:
            die("给了 --body 就必须显式给 --msg-type（只有纯文本才能省略）")
        body = parse_json_arg(raw, "--body")
        require_renderable_body(msg_type, body)
        return body
    if text is not None:
        if not str(text).strip():
            die("--text 不能为空（否则发出去只会显示成 [文本]，调用方却收到成功）")
        return {"text": text}
    # ★两个都没给：本地就拦下。放过去的话 msgType 也是 None，请求会照发，
    #   由服务端回一个 4000「参数'msgType'不能为空」—— 白白多一次往返，
    #   而且错误码是 4000（参数错误）不是本地用法错，定位方向容易被带偏。
    die("必须给 --text（纯文本简写）或 --msg-type + --body（结构化消息体）")


def write_extras(ns):
    """写端点共用的 --biz-msg-id / --trace-id。"""
    return {
        "bizMsgId": getattr(ns, "biz_msg_id", None) or uuid.uuid4().hex,
        "traceId": getattr(ns, "trace_id", None),
    }


# ---------------------------------------------------------------- 端点定义
#
# 每个端点：path（对外路径）、mode（write/read）、summary、args（argparse 参数）、
# payload（namespace -> 请求体）。

ENDPOINTS = {}


def endpoint(name, path, mode, summary, args, payload):
    ENDPOINTS[name] = dict(path=path, mode=mode, summary=summary, args=args, payload=payload)


def arg(*flags, **kwargs):
    return (flags, kwargs)


# ---- messages（9）

endpoint(
    "send-p2p", "/message/send-p2p", "write",
    "单聊发消息（会话不存在时 IM 自动创建）",
    [
        arg("--to-user-id", type=big_int, required=True, help="接收方用户 ID"),
        arg("--msg-type", help="消息类型，缺省 text；可选 text/rich_text/image/file/audio/video/card"),
        arg("--text", help="文本消息简写，等价 --body '{\"text\":\"...\"}'"),
        arg("--body", help="消息体原始 JSON（或 @文件）；非文本类型必填"),
        arg("--ext", help="业务扩展字段（JSON 字符串，服务端原样透传）"),
        arg("--biz-msg-id", help="幂等键；不给则本地生成并回显。重发必须复用同一个值"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(toUserId=ns.to_user_id, msgType=ns.msg_type or ("text" if ns.text is not None else None),
                    body=message_body(ns), ext=ns.ext, **write_extras(ns)),
)

endpoint(
    "send-group", "/message/send-group", "write",
    "群发消息",
    [
        arg("--conv-id", type=big_int, required=True, help="会话（群）ID"),
        arg("--msg-type", help="消息类型，缺省 text"),
        arg("--text", help="文本消息简写"),
        arg("--body", help="消息体原始 JSON（或 @文件）"),
        arg("--reply-to-msg-id", type=big_int, help="引用回复的消息 ID"),
        arg("--mention-user-ids", type=big_int_list, help="@ 的用户 ID，逗号分隔"),
        arg("--mention-all", action="store_true", help="@ 全体成员"),
        arg("--ext", help="业务扩展字段（JSON 字符串）"),
        arg("--biz-msg-id", help="幂等键；重发必须复用同一个值"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(convId=ns.conv_id, msgType=ns.msg_type or ("text" if ns.text is not None else None),
                    body=message_body(ns), replyToMsgId=ns.reply_to_msg_id,
                    mentionUserIds=ns.mention_user_ids, mentionAll=ns.mention_all or None,
                    ext=ns.ext, **write_extras(ns)),
)

endpoint(
    "send-system", "/message/send-system", "write",
    "系统/机器人消息（★★仅管理员：AK 归属人不是平台管理员会 4030 无权限）",
    [
        arg("--conv-id", type=big_int, help="群会话 ID（与 --to-user-id 二选一）"),
        arg("--to-user-id", type=big_int, help="单聊接收方（与 --conv-id 二选一）"),
        arg("--sender-type", type=int, choices=[2, 3], help="2=机器人，3=系统（缺省 3）"),
        arg("--msg-type", help="消息类型，缺省 text"),
        arg("--text", help="文本消息简写"),
        arg("--body", help="消息体原始 JSON（或 @文件）"),
        arg("--ext", help="业务扩展字段（JSON 字符串）"),
        arg("--biz-msg-id", help="幂等键；重发必须复用同一个值"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(convId=ns.conv_id, toUserId=ns.to_user_id, senderType=ns.sender_type,
                    msgType=ns.msg_type or ("text" if ns.text is not None else None),
                    body=message_body(ns), ext=ns.ext, **write_extras(ns)),
)

# ★★send-system / system-notify / event-push 是 21 个端点里【仅有的三个管理员端点】。
#   服务端在它们上面前置了 requireAdmin()（判据 = 登录态 UserDto.role == Admin，
#   与 ApiKeyInterceptor/AuthInterceptor 用的是同一个字段）：
#     · send-system —— senderType=2|3 在 IM 侧【不适用】禁言与用户频控
#       （ImMessageApplicationServiceImpl：if (st.isRateLimited())，isRateLimited() == HUMAN），
#       用它相当于"以系统/机器人名义发言 + 绕过自身禁言与频控"；
#     · system-notify —— 能往【任意】同租户用户的「系统通知」信箱投消息：收件人不必是
#       联系人、不必有会话、不必与调用方有任何关系，且消息落库、带未读红点、进历史。
#       ★它不是 send-system 换了个皮：那个端点调用方给得出 senderType，这个端点
#       调用方【根本给不了发送身份】（IM 固定成合成账号「系统通知」）—— 要防的是不同的事。
#     · event-push —— IM 的 push 链上目标侧零授权、帧体无发送者、内容由调用方定义、不落库
#       ⇒ 一个不可归属、不可追溯的注入原语。
#   ⇒ 闸门只是把"谁能碰到"收窄到管理员，【不消除】上面这些性质。
#   ★非管理员调用会得到 4030（ErrorCodeEnum.PERMISSION_DENIED），不是 3020。
#   ★新增子命令时注意：服务端新端点的默认是"给普通用户"的，
#     要变成管理员端点必须显式加 requireAdmin() —— 见 SKILL.md 的"管理员端点"一节。

endpoint(
    "system-notify", "/message/system-notify", "write",
    "批量发「系统通知」（★★仅管理员；★收件人不必是联系人 —— 每人会多一条带未读红点的系统通知）",
    [
        arg("--to-user-ids", type=big_int_list, required=True,
            help="收件人用户 ID，逗号分隔。★去重后上限 100（超出由 IM 整批拒绝，不静默截断）"),
        arg("--msg-type", help="★白名单四型（DEV-298）：text（缺省，纯文本气泡）/ system（居中提示条）"
                              " / card（通知卡）/ rich_text（富文本气泡）；card/rich_text 需 --body 给结构。"
                              " image/file 等单体媒体型仍禁"),
        arg("--text", help="文本简写，等价 --body '{\"text\":\"...\"}'（msg-type=text/system 通用）"),
        arg("--body", help="消息体原始 JSON（或 @文件）；msg-type=card/rich_text 时必填"),
        arg("--ext", help="业务扩展字段（JSON 字符串，服务端原样透传给收件端）"),
        arg("--biz-msg-id", help="幂等键；不给则本地生成并回显。★整批共用一个键，重发必须复用同一个值"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(toUserIds=ns.to_user_ids,
                    msgType=ns.msg_type or ("text" if ns.text is not None else None),
                    body=message_body(ns), ext=ns.ext, **write_extras(ns)),
)

endpoint(
    "send-card", "/message/send-card", "write",
    "互动卡片消息",
    [
        arg("--conv-id", type=big_int, required=True, help="会话 ID（卡片只能发到群/会话，不能指定个人）"),
        arg("--card", help="卡片原始 JSON（或 @文件），结构见 references/api_reference.md"),
        arg("--title", help="卡片标题简写（与 --card 二选一）"),
        arg("--desc", help="卡片正文简写（与 --title 搭配）"),
        arg("--ext", help="业务扩展字段（JSON 字符串）"),
        arg("--biz-msg-id", help="幂等键；重发必须复用同一个值"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(convId=ns.conv_id, card=card_payload(ns), ext=ns.ext, **write_extras(ns)),
)

endpoint(
    "revoke", "/message/revoke", "write",
    "撤回消息",
    [
        arg("--conv-id", type=big_int, required=True, help="会话 ID"),
        arg("--msg-id", type=big_int, required=True, help="要撤回的消息 ID"),
        arg("--reason", help="撤回原因"),
        arg("--biz-msg-id", help="幂等键；重发必须复用同一个值"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(convId=ns.conv_id, msgId=ns.msg_id, reason=ns.reason, **write_extras(ns)),
)

endpoint(
    "message-get", "/message/get", "read",
    "查单条消息详情（只读）",
    [
        arg("--conv-id", type=big_int, required=True, help="会话 ID"),
        arg("--msg-id", type=big_int, required=True, help="消息 ID"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(convId=ns.conv_id, msgId=ns.msg_id, traceId=ns.trace_id),
)

endpoint(
    "read-counts", "/message/read-counts", "read",
    "批量查已读人数（只读；msgIds 单次上限 200）",
    [
        arg("--conv-id", type=big_int, required=True, help="会话 ID"),
        arg("--msg-ids", type=big_int_list, required=True, help="消息 ID 列表，逗号分隔，上限 200"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(convId=ns.conv_id, msgIds=ns.msg_ids, traceId=ns.trace_id),
)

endpoint(
    "message-list", "/message/list", "read",
    "按时间范围列举会话消息（只读）",
    [
        arg("--conv-id", type=big_int, required=True, help="会话 ID"),
        arg("--start-time", type=int, help="起始时间（毫秒时间戳）"),
        arg("--end-time", type=int, help="结束时间（毫秒时间戳）"),
        arg("--after-seq", type=big_int, help="翻页游标：把上一次响应里的 nextSeq 原样回填"),
        arg("--limit", type=int, help="单页条数"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(convId=ns.conv_id, startTime=ns.start_time, endTime=ns.end_time,
                    afterSeq=ns.after_seq, limit=ns.limit, traceId=ns.trace_id),
)

# ---- conversations（7）

endpoint(
    "create-group", "/conversation/create-group", "write",
    "建群（clientGroupId 是业务幂等键：同值重复调用只建出一个群）",
    [
        arg("--name", required=True, help="群名称"),
        arg("--avatar", help="群头像"),
        arg("--notice", help="群公告"),
        # ★required：IM 侧硬性要求 memberUserIds 非空（实测 msgType 之外的
        #   IM_90001「memberUserIds 必填且不能为空」）。本地拦下省一次往返。
        #   这【不是】成员列表——创建者会被自动加进去，所以给一个 id 就够。
        arg("--members", type=big_int_list, required=True,
            help="初始成员用户 ID，逗号分隔（★必填且不能为空；创建者会自动入群）"),
        arg("--max-member", type=int, help="群人数上限（缺省 2000）"),
        arg("--client-group-id", help="★业务侧群唯一键：同一个值重复调用只会建出一个群"),
        arg("--biz-msg-id", help="幂等键；重发必须复用同一个值"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(name=ns.name, avatar=ns.avatar, notice=ns.notice, memberUserIds=ns.members,
                    maxMember=ns.max_member, clientGroupId=ns.client_group_id, **write_extras(ns)),
)

endpoint(
    "members-add", "/conversation/members/add", "write",
    "加群成员（单次上限 50；部分成功是正常返回）",
    [
        arg("--conv-id", type=big_int, required=True, help="会话 ID"),
        arg("--user-ids", type=big_int_list, required=True, help="用户 ID 列表，逗号分隔，上限 50"),
        arg("--nick-name", help="群内昵称"),
        arg("--agent-mode", type=int,
            help="★本批【智能体】进群后的衔接模式：1=唤起（默认）/ 2=监听。只作用于 --user-ids 里的"
                 "智能体，真人成员忽略；不给即 1。★2=监听 会让群里每条消息都发给它"
                 "（真实模型费用），只在确实要它跟进全部消息时才传"),
        arg("--biz-msg-id", help="幂等键；重发必须复用同一个值"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(convId=ns.conv_id, userIds=ns.user_ids, nickName=ns.nick_name,
                    agentMode=ns.agent_mode, **write_extras(ns)),
)

endpoint(
    "members-remove", "/conversation/members/remove", "write",
    "移出群成员（单次上限 50；★任一失败则整体失败）",
    [
        arg("--conv-id", type=big_int, required=True, help="会话 ID"),
        arg("--user-ids", type=big_int_list, required=True, help="用户 ID 列表，逗号分隔，上限 50"),
        arg("--reason", help="移出原因"),
        arg("--biz-msg-id", help="幂等键；重发必须复用同一个值"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(convId=ns.conv_id, userIds=ns.user_ids, reason=ns.reason, **write_extras(ns)),
)

endpoint(
    "members-list", "/conversation/members/list", "read",
    "群成员列表（只读；limit 上限 100，大群会被服务端降级截断）",
    [
        arg("--conv-id", type=big_int, required=True, help="会话 ID"),
        arg("--limit", type=int, help="单页条数，上限 100"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(convId=ns.conv_id, limit=ns.limit, traceId=ns.trace_id),
)

endpoint(
    "conversation-p2p", "/conversation/p2p", "write",
    "取或建单聊会话（原子操作；★不支持与自己建单聊）",
    [
        arg("--to-user-id", type=big_int, required=True, help="对端用户 ID"),
        arg("--biz-msg-id", help="幂等键；重发必须复用同一个值"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(toUserId=ns.to_user_id, **write_extras(ns)),
)

endpoint(
    "digest", "/conversation/digest", "write",
    "更新会话摘要（★尽力而为，允许丢失；不要拿它当可靠通知）",
    [
        arg("--conv-id", type=big_int, required=True, help="会话 ID"),
        arg("--digest", help="新的会话摘要文本"),
        arg("--biz-msg-id", help="幂等键；重发必须复用同一个值"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(convId=ns.conv_id, digest=ns.digest, **write_extras(ns)),
)

endpoint(
    "conversation-list", "/conversation/list", "read",
    "列举我的会话列表（只读；★游标不透明、翻页只看 hasMore）",
    [
        arg("--cursor", help="翻页游标：首页留空，之后把上一次响应的 nextCursor 原样回填"),
        arg("--limit", type=int, help="单页条数，缺省 50，服务端夹到 1~100（不是拒绝）"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(cursor=ns.cursor, limit=ns.limit, traceId=ns.trace_id),
)

endpoint(
    "conversation-detail", "/conversation/detail", "read",
    "会话详情（只读；★能拿到群号 groupNo 与 ext —— 这两个字段列表里恒为 null）",
    [
        arg("--conv-id", type=big_int, required=True, help="会话 ID"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(convId=ns.conv_id, traceId=ns.trace_id),
)

# ---- contacts（1）

endpoint(
    "contact-list", "/contact/list", "read",
    "列举我的联系人（只读；★三态混合：1=已互为联系人 2=对方申请我 3=我申请对方）",
    [
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(traceId=ns.trace_id),
)

# ---- events（1）

endpoint(
    "event-push", "/event/push", "write",
    "推送自定义事件（★★仅管理员；★成功≠送达：收件人离线即收不到，不落库、无补偿）",
    [
        arg("--event-type", required=True, help="事件类型标识，业务自定义"),
        arg("--user-ids", type=big_int_list, help="目标用户 ID，逗号分隔（与 --conv-id 至少给一个）"),
        arg("--conv-id", type=big_int, help="目标会话 ID"),
        arg("--payload", help="事件负载 JSON（或 @文件）"),
        arg("--biz-msg-id", help="幂等键；重发必须复用同一个值"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(userIds=ns.user_ids, convId=ns.conv_id, eventType=ns.event_type,
                    payload=parse_json_arg(ns.payload, "--payload"), **write_extras(ns)),
)

# ---- agents（2，★DEV-285 排队区自查）
#
# ★★这一组与其余 19 个子命令的身份口径【不同】：其余端点以「AK 归属人（必要时换成本轮的
#   智能体虚拟身份）」行使，而这两个端点【只认智能体主体身份】—— 服务端从 AK 里取
#   AGENT_VIRTUAL_USER_ID，取不到就本地 4030 拒绝（不会退回登录用户）。
#   ⇒ 它们只在「由 IM 消息触发的那次运行」里可用；手工拿一把普通 AK 敲这两个命令，
#     会稳定拿到 4030「本接口只能以智能体身份调用」。那不是 AK 失效，换钥匙也没用。
#
# ★用途：由 IM 消息触发运行时，用户可能连着发了好几条。默认由 IM 侧在本次运行结束后合并
#   再发起下一轮；若你自己在本轮里就把它们读走并声明消费，IM 侧就不会再多发起一轮。
#   三步顺序不能换：agent-pending-list → 并入自己的上下文 → agent-pending-clear。

endpoint(
    "agent-pending-list", "/agent/pending/list", "read",
    "查排队区：本会话里还有哪些新消息在等我处理（只读；★仅智能体身份可用）",
    [
        arg("--conv-id", type=big_int, required=True, help="会话 ID"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(convId=ns.conv_id, traceId=ns.trace_id),
)

endpoint(
    "agent-pending-clear", "/agent/pending/clear", "write",
    "声明排队区中这批消息已消费（★先 list 读走、确实处理过再清；★仅智能体身份可用）",
    [
        arg("--conv-id", type=big_int, required=True, help="会话 ID"),
        arg("--msg-ids", type=big_int_list, required=True,
            help="要清除的消息 ID，逗号分隔（一般是 list 返回的 msgId 原样回传）"),
        arg("--biz-msg-id", help="幂等键；重发必须复用同一个值"),
        arg("--trace-id", help="链路跟踪 ID"),
    ],
    lambda ns: dict(convId=ns.conv_id, msgIds=ns.msg_ids, **write_extras(ns)),
)


def card_payload(ns):
    """卡片：--card 原样用；--title/--desc 是简写。"""
    if ns.card and (ns.title or ns.desc):
        die("--card 与 --title/--desc 只能给一组")
    if ns.card:
        return parse_json_arg(ns.card, "--card")
    if ns.title or ns.desc:
        card = {}
        if ns.title:
            card["title"] = ns.title
        if ns.desc:
            card["desc"] = ns.desc
        return card
    die("必须给 --card，或 --title（可搭配 --desc）")


# ---------------------------------------------------------------- CLI


class Parser(argparse.ArgumentParser):
    """
    ★argparse 默认用退出码 2 表示「参数有错」，而本脚本用 2 表示「环境变量缺失」。
    两者撞在一起，调用方会把「--to-user-id 打错了」读成「没配 PLATFORM_BASE_URL」，
    然后去查错方向。所以这里把 argparse 的错误也归到 1（用法错误）。
    """

    def error(self, message):
        self.print_usage(sys.stderr)
        print("❌ " + message, file=sys.stderr)
        sys.exit(EXIT_USAGE)


def build_parser():
    parser = Parser(
        prog="im.py",
        description="nuwax-im 技能：以当前用户身份操作 Nuwax IM（/api/v1/4sandbox/nuwax-im/**）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="先看 python3 scripts/im.py endpoints 列出全部端点。\n"
               "★发送者身份来自 SANDBOX_ACCESS_KEY，没有参数可以指定为别人。",
    )
    parser.add_argument("--json", action="store_true", help="打印平台原始响应信封，不做渲染")
    sub = parser.add_subparsers(dest="command", metavar="<端点>")

    # ★--json 在子命令后也要能用（`im.py send-p2p ... --json`）。不带 SUPPRESS 的话，
    #   子解析器的 default=False 会【覆盖】掉顶层已经设好的 True，于是
    #   `im.py --json send-p2p` 静默变成不打印原始信封 —— 位置不同、结果不同，最难查的那种坑。
    child_json = dict(action="store_true", default=argparse.SUPPRESS,
                      help="打印平台原始响应信封，不做渲染")

    for name, spec in ENDPOINTS.items():
        child = sub.add_parser(name, help=spec["summary"], description=spec["summary"])
        for flags, kwargs in spec["args"]:
            child.add_argument(*flags, **kwargs)
        child.add_argument("--json", **child_json)
        child.set_defaults(_spec=spec, _name=name)

    sub.add_parser("endpoints", help="列出全部端点（离线，不发请求）"
                   ).add_argument("--json", **child_json)
    return parser


def print_endpoints():
    print("nuwax-im 端点（全部 POST，前缀 %s）" % API_PREFIX)
    print()
    # 18 → 21：最长的子命令名有 19 个字符（agent-pending-clear / conversation-detail），
    # % -18s 会让它们顶掉后面的「类型」列。
    print("%-20s %-6s %s" % ("端点", "类型", "说明"))
    print("-" * 88)
    for name, spec in ENDPOINTS.items():
        print("%-20s %-6s %s" % (name, "写" if spec["mode"] == "write" else "只读", spec["summary"]))
    print()
    print("用法: python3 scripts/im.py <端点> --help")
    print("身份固定取自 SANDBOX_ACCESS_KEY —— 不可伪造、也没有参数能改。")
    return EXIT_OK


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)

    if not args.command:
        parser.print_help()
        return EXIT_USAGE
    if args.command == "endpoints":
        return print_endpoints()

    spec = args._spec
    payload = spec["payload"](args)
    action = args._name
    result = call(spec["path"], payload)
    return emit(result, action, args.json)


if __name__ == "__main__":
    sys.exit(main())

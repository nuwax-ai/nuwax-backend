#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
nuwax-agent-task 技能客户端 —— 派发并管理「主智能体派发给子智能体的任务」。

五个端点全部收在本脚本里，形如：

    python3 scripts/task.py dispatch --target-agent-id 300 --input-message "帮我整理季度数据"
    python3 scripts/task.py list-running                 # 现在有哪些任务在跑
    python3 scripts/task.py search --keyword 周报        # 按主题搜任务（含已结束的）
    python3 scripts/task.py progress --conversation-id 228036057097043968
    python3 scripts/task.py cancel --conversation-id 228036057097043968 --reason "不需要了"
    python3 scripts/task.py endpoints                    # 离线列出全部端点，不发请求

★身份不在参数里。能派发/能看/能取消的任务范围永远是「这把 AK 绑定的智能体自己
  + 它配置的子智能体」，且只限 AK 归属用户本人 —— 由服务端按
  SANDBOX_ACCESS_KEY 判定，请求体里没有任何 userId/agentId 可伪造。
  范围外的任务统一报「任务不存在或无权访问」（不区分不存在和无权）。

★dispatch 会触发【真实模型执行】，产生费用与副作用，调用前想清楚。
  它是 fire-and-forget：返回即「已派发」，不代表任务已完成 ——
  结果用 progress 查，要停用 cancel。只能派给本 AK 智能体或其配置的子智能体；
  传 --conversation-id 则续聊已有任务（须在可见范围内且未在执行中）。

★dispatch 的目标智能体可以省略：不传 --target-agent-id 时，取环境变量 AGENT_ID
  （= 本沙箱自己的智能体），即「默认派给自己」。要派给子智能体必须显式传
  --target-agent-id；显式传的值永远优先，不会被环境变量覆盖。

★退出码就是错误分类，别只看 stdout：

    0  成功
    1  参数问题：本地用法错误（请求没发出去），或平台业务拒绝（code 非 0000，
       看 message 原文；「任务不存在或无权访问」也在这一档）
    2  环境变量缺失（请求没发出去）
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from urllib import request as urlrequest
from urllib.error import HTTPError, URLError

API_PREFIX = "/api/v1/4sandbox/agent-task"
TIMEOUT_SECONDS = 30

EXIT_OK = 0
EXIT_USAGE = 1
EXIT_ENV = 2


# ---------------------------------------------------------------- 基础件

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


def big_int(text):
    """
    雪花 ID 参数类型：必须按 JSON number 传。
    服务端把 conversationId 收成 Long —— 传字符串会在反序列化时被拒。
    """
    try:
        return int(str(text).strip())
    except (TypeError, ValueError):
        raise argparse.ArgumentTypeError("不是合法整数: %r" % text)


def positive_int(text):
    try:
        value = int(str(text).strip())
    except (TypeError, ValueError):
        raise argparse.ArgumentTypeError("不是合法整数: %r" % text)
    if value <= 0:
        raise argparse.ArgumentTypeError("必须是正整数: %r" % text)
    return value


def agent_id_or_env(explicit):
    """
    目标智能体 ID 的取值：显式传参 > 环境变量 AGENT_ID（本沙箱智能体）。

    ★显式传参永远优先 —— 传了就用传的，绝不用环境变量覆盖使用者的意图。
    AGENT_ID 只是「使用者没指明目标时，默认派给本沙箱自己」这个兜底；
    它同时被服务端的 AK 范围校验兜着，越界目标仍会被拒。
    """
    if explicit is not None:
        return explicit
    raw = (os.getenv("AGENT_ID") or "").strip()
    if not raw:
        print("❌ 没法确定目标智能体：没传 --target-agent-id，环境里也没有 AGENT_ID。", file=sys.stderr)
        print("   两种做法任选：", file=sys.stderr)
        print("    ① 显式指定：dispatch --target-agent-id <id> --input-message \"...\"", file=sys.stderr)
        print("    ② 在 4sandbox 里运行（沙箱会注入 AGENT_ID = 本智能体 ID）。", file=sys.stderr)
        sys.exit(EXIT_ENV)
    try:
        return int(raw)
    except (TypeError, ValueError):
        print("❌ 环境变量 AGENT_ID 不是合法整数: %r" % raw, file=sys.stderr)
        print("   请改用 --target-agent-id 显式指定目标智能体。", file=sys.stderr)
        sys.exit(EXIT_ENV)


def call(path, payload):
    """
    发一个 POST 请求，返回平台响应信封（dict）。

    ★业务错误也走 HTTP 200 —— 成败判据是信封里的 code（"0000" 成功），
      不是 HTTP 状态码。HTTP 层出错时把错误体尽力解析成信封返回，
      让上层统一按 code/message 渲染。
    """
    base, key = require_env()
    url = base + API_PREFIX + path
    headers = {
        "Content-Type": "application/json",
        "Authorization": "Bearer " + key,
    }
    # 租户永远不需要传：服务端按 AK 自己解析租户，客户端不带任何租户参数/头。
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urlrequest.Request(url, data=body, headers=headers, method="POST")
    try:
        with urlrequest.urlopen(req, timeout=TIMEOUT_SECONDS) as resp:
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
        # 到不了平台 —— 请求根本没出去。
        return {"code": "-1", "message": "网络错误，请求未送达平台: %s" % exc.reason}


def classify(envelope):
    """把响应信封映射成退出码：0000 成功，其余都是 1。"""
    code = str(envelope.get("code", ""))
    if code in ("0000", "0"):
        return EXIT_OK
    return EXIT_USAGE


# ---------------------------------------------------------------- 渲染

STATUS_HINT = {
    "CREATE": "已创建（还没开始跑）",
    "EXECUTING": "执行中",
    "CANCEL": "已取消",
    "COMPLETE": "已完成",
    "FAILED": "失败",
}


def print_items(items, empty_hint):
    if not items:
        print("（空）")
        print("★ 这是成功返回，不是失败：" + empty_hint)
        return
    print("共 %d 条：" % len(items))
    for item in items:
        status = item.get("taskStatus") or "?"
        hint = STATUS_HINT.get(status, "")
        print("- 「%s」 [%s%s] [conversationId=%s] [agentId=%s%s]" % (
            item.get("topic") or "(无主题)",
            status,
            " " + hint if hint else "",
            item.get("conversationId"),
            item.get("agentId"),
            " " + item.get("agentName") if item.get("agentName") else "",
        ))
        if item.get("modified"):
            print("  最近更新: %s" % item["modified"])


def print_progress(data):
    status = data.get("taskStatus") or "?"
    hint = STATUS_HINT.get(status, "")
    print("任务「%s」 %s%s" % (data.get("topic") or "(无主题)", status, "（" + hint + "）" if hint else ""))
    print("conversationId: %s  agentId: %s%s" % (
        data.get("conversationId"),
        data.get("agentId"),
        "  agentName: " + data["agentName"] if data.get("agentName") else "",
    ))
    messages = data.get("messages") or []
    if data.get("running"):
        print("★ 还在执行中 —— 现在没有最终结果，别把中间消息当结果转述。")
        print("  过一阵再用 progress 查；等不及可以 cancel。")
    if not messages:
        print("（没有可展示的消息）")
        return
    print("最近消息（最多 2 条）：")
    for msg in messages:
        who = msg.get("senderType") or msg.get("role") or "?"
        print("  [%s] %s" % (who, msg.get("text") or "(空)"))
    if status == "COMPLETE":
        print("★ 任务已完成：最后一条消息就是任务结果，可直接取用/转述。")
    elif status == "FAILED":
        print("★ 任务失败了：最后一条消息可能只有失败前的中间内容，别当完整结果用。")


def print_dispatch(data):
    created = data.get("created")
    status = data.get("taskStatus") or "?"
    # ✅ 只给真正启动了的（EXECUTING）；CREATE=启动被拒 / FAILED=启动即败，首行就用 ⚠️，
    # 避免「✅ 已派发」和「任务没在跑」并排出现误导使用者。
    mark = "✅" if status == "EXECUTING" else "⚠️"
    print("%s %s conversationId: %s  智能体: %s%s" % (
        mark,
        "已派发任务（新建任务会话）" if created else "已追加指令（续聊已有任务）",
        data.get("conversationId"),
        data.get("agentId"),
        "  " + data.get("agentName") if data.get("agentName") else "",
    ))
    print("主题: %s" % (data.get("topic") or "(无)"))
    error = data.get("error")
    if status == "EXECUTING":
        print("任务状态: EXECUTING —— 真实模型执行已启动（会产生费用与副作用）。")
        print("★ fire-and-forget：派发返回 ≠ 任务完成。")
        print("  稍后用 progress 取结果；要停用 cancel。别现在就向使用者报「已完成」。")
    elif status == "CREATE":
        print("⚠️ 任务状态: CREATE —— 启动阶段被拒绝，任务【没有在跑】。")
        if error:
            print("拒绝原因: %s" % error)
        print("★ 别向使用者说「已开始执行」：先按拒绝原因处理（如权限/配额），再决定是否重试。")
    elif status == "FAILED":
        print("⚠️ 任务状态: FAILED —— 启动后立即失败。")
        if error:
            print("失败信息: %s" % error)
        print("★ 用 progress 查看详情，再决定是否重新派发。")
    else:
        print("任务状态: %s" % status)
        if error:
            print("附加信息: %s" % error)


def print_cancel(data):
    print("conversationId: %s" % data.get("conversationId"))
    if data.get("cancelled"):
        print("✅ 已取消（取消前任务在执行中，现在状态 %s）" % data.get("taskStatus"))
    else:
        print("⚠️ 本次【没有取消任何执行】—— 任务当前状态 %s，本来就不在执行中。" % data.get("taskStatus"))
        if data.get("taskStatus") == "COMPLETE":
            print("   ★别对使用者说「已取消」：任务是【跑完之后】才被调的 cancel，结果仍然有效。")
        else:
            print("   ★别对使用者说「已取消」：该任务此前已结束/未开始，状态未变化。")


def print_failure(envelope, action):
    code = envelope.get("code")
    message = envelope.get("message") or "(无 message)"
    print("❌ " + action + " 失败")
    if code is not None:
        print("code: %s" % code)
    if envelope.get("statusCode"):
        print("httpStatus: %s" % envelope["statusCode"])
    print("message: %s" % message)
    if str(code) == "4030":
        print("★ 4030 = AK 无效或过期：检查 SANDBOX_ACCESS_KEY（租户无需传，服务端按 AK 自行解析）。")
    if "任务不存在或无权访问" in str(message):
        print("★ 服务端不区分「不存在」和「无权」：conversationId 可能错了，"
              "也可能这个任务不在本 AK 的可见范围内（别人/别的智能体的任务）。")
    if "目标智能体不在可派发范围内" in str(message):
        print("★ dispatch 只能派给本 AK 绑定的智能体或其配置的子智能体，换个目标。")
    if "目标智能体不可执行" in str(message):
        print("★ 目标智能体未发布 / 已下线 / 绑定模型不可用，换目标或联系管理员。")
    if "任务正在执行中" in str(message):
        print("★ 同一任务不能并发派发：等它跑完（progress 查），或先 cancel 再续聊。")


def emit(envelope, action, json_mode):
    code = classify(envelope)
    if json_mode:
        print(json.dumps(envelope, ensure_ascii=False, indent=2))
        return code
    if code != EXIT_OK:
        print_failure(envelope, action)
        return code
    data = envelope.get("data")
    if action in ("list-running", "search"):
        empty = ("当前没有执行中的任务。" if action == "list-running"
                 else "没搜到匹配的任务（换个关键词，或不带 --keyword 看最近任务）。")
        print_items(data or [], empty)
    elif action == "dispatch" and isinstance(data, dict):
        print_dispatch(data)
    elif action == "progress" and isinstance(data, dict):
        print_progress(data)
    elif action == "cancel" and isinstance(data, dict):
        print_cancel(data)
    elif data is None:
        print("✅ " + action + " 成功")
    else:
        print(json.dumps(data, ensure_ascii=False, indent=2))
    return code


# ---------------------------------------------------------------- 端点定义

ENDPOINTS = {}


def endpoint(name, path, mode, summary, args, payload):
    ENDPOINTS[name] = dict(path=path, mode=mode, summary=summary, args=args, payload=payload)


def arg(*flags, **kwargs):
    return (flags, kwargs)


endpoint(
    "dispatch", "/dispatch", "write",
    "派发任务给目标智能体（★触发真实模型执行，产生费用；传 --conversation-id 为续聊）",
    [
        arg("--target-agent-id", type=big_int,
            help="目标智能体 ID：仅允许本 AK 绑定的智能体或其配置的子智能体。"
                 "★不传 = 用环境变量 AGENT_ID（本沙箱智能体）；要派给子智能体必须显式传"),
        arg("--input-message", required=True,
            help="任务指令文本：发给目标智能体的输入（触发真实执行）"),
        arg("--subject",
            help="任务主题：新建时作为会话 topic，缺省取 --input-message；续聊时忽略"),
        arg("--conversation-id", type=big_int,
            help="已有任务（会话）ID：传入表示续聊该任务而非新建（须在可见范围内且未在执行中）"),
    ],
    lambda ns: dict(targetAgentId=agent_id_or_env(ns.target_agent_id),
                    inputMessage=ns.input_message,
                    subject=ns.subject, conversationId=ns.conversation_id),
)

endpoint(
    "list-running", "/list-running", "read",
    "查执行中的任务（默认最多 20 条，--limit 上限 50）",
    [
        arg("--limit", type=positive_int, help="返回条数上限，默认 20，最大 50"),
    ],
    lambda ns: dict(limit=ns.limit),
)

endpoint(
    "search", "/search", "read",
    "按主题关键词搜任务，含已结束的；不给关键词 = 最近任务列表",
    [
        arg("--keyword", help="任务主题关键词（可空）"),
        arg("--limit", type=positive_int, help="返回条数上限，默认 20，最大 50"),
    ],
    lambda ns: dict(keyword=ns.keyword, limit=ns.limit),
)

endpoint(
    "progress", "/progress", "read",
    "查单个任务的进度或结果（COMPLETE 时最后一条消息即结果）",
    [
        arg("--conversation-id", type=big_int, required=True, help="任务对应的会话 ID"),
    ],
    lambda ns: dict(conversationId=ns.conversation_id),
)

endpoint(
    "cancel", "/cancel", "write",
    "取消执行中的任务（已结束的任务调用它不会有任何变更，看返回的 cancelled）",
    [
        arg("--conversation-id", type=big_int, required=True, help="任务对应的会话 ID"),
        arg("--reason", help="取消原因（可选，仅记录日志）"),
    ],
    lambda ns: dict(conversationId=ns.conversation_id, reason=ns.reason),
)


# ---------------------------------------------------------------- CLI 骨架

class Parser(argparse.ArgumentParser):
    """
    ★argparse 默认用退出码 2 表示「参数有错」，而本脚本用 2 表示「环境变量缺失」。
    两者混在一起时，使用者会把「参数写错」误判成「环境没配好」，
    然后去查错方向。所以这里把 argparse 的错误也归到 1（用法错误）。
    """

    def error(self, message):
        self.print_usage(sys.stderr)
        die(message)


def build_parser():
    parser = Parser(
        prog="task.py",
        description="nuwax-agent-task：派发并管理子智能体任务（派发 / 查执行中 / 搜索 / 查进度或结果 / 取消）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="先看 python3 scripts/task.py endpoints 列出全部端点。\n"
               "身份与可见范围固定取自 SANDBOX_ACCESS_KEY —— 不可伪造、也没有参数能改。",
    )
    parser.add_argument("--json", action="store_true", help="打印平台原始响应信封，不做渲染")
    sub = parser.add_subparsers(dest="command")
    child_json = dict(action="store_true", default=argparse.SUPPRESS,
                      help="打印平台原始响应信封，不做渲染")
    for name, spec in ENDPOINTS.items():
        child = sub.add_parser(name, help=spec["summary"], description=spec["summary"])
        for flags, kwargs in spec["args"]:
            child.add_argument(*flags, **kwargs)
        child.add_argument("--json", **child_json)
    sub.add_parser("endpoints", help="列出全部端点（离线，不发请求）"
                   ).add_argument("--json", **child_json)
    return parser


def print_endpoints():
    print("nuwax-agent-task 端点（全部 POST，前缀 %s）" % API_PREFIX)
    print()
    print("%-14s %-6s %s" % ("端点", "类型", "说明"))
    print("-" * 88)
    for name, spec in ENDPOINTS.items():
        print("%-14s %-6s %s" % (name, "写" if spec["mode"] == "write" else "只读", spec["summary"]))
    print()
    print("用法: python3 scripts/task.py <端点> --help")
    print("身份与可见范围固定取自 SANDBOX_ACCESS_KEY —— 不可伪造、也没有参数能改。")
    print("★dispatch 的 --target-agent-id 可省略：缺省取环境变量 AGENT_ID（本沙箱智能体），")
    print("  即「默认派给自己」；要派给子智能体必须显式传。")
    print("★dispatch 触发【真实模型执行】（产生费用与副作用），且是 fire-and-forget：")
    print("  返回即「已派发」而非「已完成」，结果用 progress 查，要停用 cancel。")
    return EXIT_OK


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)

    if not args.command:
        parser.print_help()
        return EXIT_USAGE
    if args.command == "endpoints":
        return print_endpoints()

    spec = ENDPOINTS[args.command]
    payload = spec["payload"](args)
    result = call(spec["path"], payload)
    return emit(result, args.command, args.json)


if __name__ == "__main__":
    sys.exit(main())

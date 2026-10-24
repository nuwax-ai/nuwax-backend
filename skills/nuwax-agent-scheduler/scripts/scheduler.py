#!/usr/bin/env python3
"""
定时任务技能 CLI

四个子命令：create / update / cancel / list。
公共逻辑（环境变量、时间格式化、HTTP 请求、cron 校验）内联在本文件，
技能只有一个脚本文件。

用法：
    python3 scripts/scheduler.py create --task-name "名称" --execute-type Loop --cron "0 0 9 * * ?" \
        --message "要做什么，然后用 nuwax-im 技能私聊把结果发给用户（见 SKILL.md）"
        # 群里建的：--message 改成优先 send-group 发回那个群（要通知谁就加 --mention-user-ids），
        #           并补上退路「报 IM_20104 就改用 send-p2p 通知本人」
    python3 scripts/scheduler.py update --task-id 123 --task-name "新名称" [--execute-type ... --cron ...]
    python3 scripts/scheduler.py cancel --task-id 123
    python3 scripts/scheduler.py list [--json]
"""

import os
import re
import sys
import json
import argparse
from datetime import datetime
from urllib import request
from urllib.error import HTTPError, URLError

# 目标平台所在时区偏移，默认中国时区 UTC+8，可用环境变量覆盖
TIMEZONE_OFFSET = os.getenv("TASK_TIMEZONE_OFFSET", "+08:00")

API_PREFIX = "/api/v1/4sandbox/task"

# 平台注入的 IM 上下文（群消息触发本次运行时才有；任务到点执行那次可能为空）
ENV_IM_SCENARIO = "IM_SCENARIO"
ENV_IM_GROUP_CONV_ID = "IM_GROUP_CONV_ID"
ENV_IM_CONV_ID = "IM_CONV_ID"

# --message 里把「会话 ID / 被 @ 的 userId」写成了变量（如 --conv-id $IM_GROUP_CONV_ID）
VAR_ID_PATTERN = re.compile(r"--(?:conv-id|mention-user-ids)[\s=]+\"?\$")

# IM 侧「当前身份不是该群成员」的错误码：群发失败要按退路回落私聊，不能静默
IM_NOT_MEMBER_CODE = "IM_20104"


# --------------------------------------------------------------------------
# 公共逻辑
# --------------------------------------------------------------------------

def get_env(*names):
    """
    读取环境变量，缺失时抛出 ValueError。

    Args:
        *names: 需要读取的环境变量名

    Returns:
        dict: {变量名: 值}

    Raises:
        ValueError: 存在缺失的环境变量
    """
    values = {name: os.getenv(name) for name in names}
    missing = [name for name, value in values.items() if not value]
    if missing:
        raise ValueError(
            "Missing required environment variables: " + ", ".join(missing)
        )
    return values


def format_datetime_to_iso(date_string):
    """
    将日期字符串转换为 ISO 8601 格式（带时区偏移）。

    Args:
        date_string: 日期字符串，支持 YYYY-MM-DD HH:MM:SS 或 YYYY-MM-DDTHH:MM:SS

    Returns:
        ISO 8601 格式字符串；无法解析时原样返回。
    """
    if not date_string:
        return None

    formats = [
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d %H:%M:%S.%f",
        "%Y-%m-%dT%H:%M:%S.%f",
    ]

    for fmt in formats:
        try:
            dt = datetime.strptime(date_string, fmt)
            return dt.isoformat() + TIMEZONE_OFFSET
        except ValueError:
            continue

    return date_string


def validate_quartz_cron(cron):
    """
    校验 cron 表达式符合平台要求的 Quartz 格式。

    平台侧用 Quartz 解析 cron，必须是 6 或 7 字段（秒 分 时 日 月 周 [年]），
    且「日」与「周」两个字段有且只有一个写 `?`。
    注意这不是 Linux crontab 的 5 字段写法。

    Raises:
        ValueError: 字段数不对，或 `?` 的用法不符合 Quartz 要求
    """
    parts = cron.split()
    if len(parts) not in (6, 7):
        raise ValueError(
            "cron 必须是 6 字段的 Quartz 表达式（秒 分 时 日 月 周），"
            "例如每天 9 点写 '0 0 9 * * ?'；当前是 %d 字段：%s" % (len(parts), cron)
        )
    if (parts[3] == "?") == (parts[5] == "?"):
        raise ValueError(
            "cron 的「日」与「周」必须有且只有一个写 '?'，"
            "例如 '0 0 9 * * ?'；当前：%s" % cron
        )
    return cron


def post_json(url, access_key, data=None):
    """
    发送 POST 请求并解析 JSON 响应。

    Args:
        url: 请求地址
        access_key: 认证密钥（Bearer Token）
        data: 请求体 dict，None 表示无请求体

    Returns:
        dict: 响应数据；HTTP 错误时附加 statusCode，网络错误时返回 code=-1。
    """
    headers = {
        "Content-Type": "application/json",
        "Authorization": "Bearer " + access_key,
    }

    req_data = json.dumps(data).encode("utf-8") if data is not None else None
    req = request.Request(url, data=req_data, headers=headers, method="POST")

    try:
        with request.urlopen(req) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as e:
        error_data = json.loads(e.read().decode("utf-8"))
        error_data["statusCode"] = e.code
        return error_data
    except URLError as e:
        return {
            "code": -1,
            "message": "Network error: " + str(e.reason),
            "data": None,
        }


def is_success(result):
    """判断 API 响应是否成功（success 为真，或 code 为 0000/0）。"""
    return bool(result.get("success", False)) or result.get("code") in ("0000", "0", 0)


def print_error(result, action):
    """统一打印失败信息。"""
    print("❌ " + action + "失败: " + str(result.get("message", "Unknown error")))
    if result.get("statusCode"):
        print("HTTP 状态码: " + str(result["statusCode"]))
    if result.get("code"):
        print("错误码: " + str(result["code"]))
    if result.get("displayCode"):
        print("源系统状态码: " + str(result["displayCode"]))
    if result.get("tid"):
        print("跟踪ID: " + str(result["tid"]))


def print_tid(result):
    """有跟踪 ID 就打印一行。"""
    if result.get("tid"):
        print("📋 跟踪ID: " + str(result["tid"]))


def group_conv_id():
    """
    本次运行若是群消息触发的，返回群会话 ID；否则返回 None。

    ★判据与平台 ImChatIdentity 同口径：认 IM_SCENARIO=group 或 IM_GROUP_CONV_ID 键。
    不用 IS_GROUP_MESSAGE —— 那是上游写入的布尔值，可被伪造，平台侧也刻意不拿它当判据。
    """
    group_id = (os.getenv(ENV_IM_GROUP_CONV_ID) or "").strip()
    if group_id:
        return group_id
    if (os.getenv(ENV_IM_SCENARIO) or "").strip() == "group":
        return (os.getenv(ENV_IM_CONV_ID) or "").strip() or None
    return None


def print_group_message_hint(message):
    """
    群场景下 --message 的防呆提示：只提醒、不阻断（用户可能已明说「不用推」）。

    群场景要写进 --message 的几件事：用 send-group 发回群、群会话 ID 的**具体数字**；
    要通知某个人，就在同一条 send-group 里 @ 他（--mention-user-ids + 他的**数字** ID）。
    ★退路：群发报 IM_20104（当前身份不是该群成员）时要回落 send-p2p 通知本人 —— 也写进
    --message，否则群发失败会静默结束，群里和私聊都收不到。
    ★群会话 ID 与被 @ 的 userId 都必须写成数字：任务到点那次执行时这些 IM_* 变量可能为空，
    写变量会在执行时展开成空串，命令就没有目标了。

    注：send-group 与 send-p2p **同时出现是正常的**（后者是 IM_20104 的退路），不算问题。
    """
    conv_id = group_conv_id()
    if not conv_id or not message:
        return

    problems = []
    has_group = "send-group" in message
    if not has_group:
        problems.append("没有看到 send-group —— 群场景要用它把结果发回群里")
        if "send-p2p" in message:
            problems.append("这里用的是 send-p2p —— 私聊群里看不到，它只能当 send-group 失败后的退路")
        if "system-notify" in message:
            problems.append("任务内容里用了 system-notify —— 要通知某个人就在群消息里 @ 他")
    elif IM_NOT_MEMBER_CODE not in message:
        problems.append("send-group 后面没写退路 —— 补一句「若报 " + IM_NOT_MEMBER_CODE +
                        "（当前身份不是该群成员）就改用 send-p2p 通知本人」，别让群发失败静默结束")
    if conv_id not in message:
        problems.append("没有看到群会话 ID " + conv_id + " —— 要写成这个具体数字，"
                        "别写 $IM_GROUP_CONV_ID（任务执行时该变量可能为空，会展开成空串）")
    if not problems:
        return

    print("⚠️  群场景提醒：本次运行由群消息触发（群会话 ID " + conv_id + "），任务结果要发回这个群：")
    for item in problems:
        print("   - " + item)
    print("   写法：... send-group --conv-id " + conv_id + " --text \"<结果正文>\""
          "；若报 " + IM_NOT_MEMBER_CODE + "，改用 send-p2p --to-user-id <本人的数字> --text \"<结果正文>\"")
    print("   要通知某个人：在同一条 send-group 里加 --mention-user-ids <他的数字ID>，不要直接改成私聊")


def print_message_id_hint(message):
    """
    --message 里把会话 ID / 被 @ 的 userId 写成变量的通用防呆（与场景无关）。

    ★`--to-user-id "$SYS_USER_ID"` 是**对的**（SYS_USER_ID 任务执行时仍在），所以只查
    `--conv-id` 与 `--mention-user-ids`：它们的值只能来自 IM_* 变量，而任务到点那次执行
    这些变量可能为空，写变量会展开成空串 —— 消息没有目标、@ 也会 @ 空。
    """
    if message and VAR_ID_PATTERN.search(message):
        print("⚠️  提醒：--message 里的 --conv-id / --mention-user-ids 写成了变量 ——")
        print("   任务到点那次执行的环境里 IM_* 变量可能为空（会展开成空串），这两个 ID 要写成具体数字。")
        print("   （IM_SCENARIO / IM_GROUP_CONV_ID 不是必须的，为空照样能发群。）")


# --------------------------------------------------------------------------
# 接口调用
# --------------------------------------------------------------------------

def create_task(task_name, execute_type, lock_time=None, cron=None, message=None,
                space_id=None):
    """
    创建定时任务。

    Args:
        space_id: 可选，指定任务归属的空间ID。传入后服务端只校验当前用户是否为该
            空间成员（不校验智能体所在空间）；不传则回落到用户个人空间。

    Raises:
        ValueError: 环境变量缺失或参数验证失败
    """
    env = get_env("PLATFORM_BASE_URL", "CONVERSATION_ID", "SANDBOX_ACCESS_KEY")

    # 验证参数
    if execute_type == "Once" and not lock_time:
        raise ValueError("lock_time is required for Once execute_type")
    if execute_type == "Loop" and not cron:
        raise ValueError("cron is required for Loop execute_type")
    if not message or not message.strip():
        raise ValueError("message is required（任务触发时发给智能体的内容）")
    if execute_type == "Loop":
        validate_quartz_cron(cron)

    try:
        conversation_id = int(env["CONVERSATION_ID"])
    except ValueError:
        raise ValueError("CONVERSATION_ID must be a number")

    # 构建请求数据
    data = {
        "taskName": task_name,
        "executeType": execute_type,
        "conversationId": conversation_id,
        "message": message,
    }

    # 添加可选参数
    if lock_time:
        data["lockTime"] = format_datetime_to_iso(lock_time)
    if cron:
        data["cron"] = cron
    if space_id is not None:
        data["spaceId"] = space_id

    url = env["PLATFORM_BASE_URL"] + API_PREFIX + "/create"
    return post_json(url, env["SANDBOX_ACCESS_KEY"], data)


def update_task(task_id, task_name=None, execute_type=None,
                lock_time=None, cron=None, message=None):
    """
    更新定时任务。

    Raises:
        ValueError: 环境变量缺失或参数验证失败
    """
    env = get_env("PLATFORM_BASE_URL", "SANDBOX_ACCESS_KEY")

    # 验证必填参数
    if not task_name:
        raise ValueError("task_name is required")

    # 服务端只认「executeType 非空时」才读 cron / lockTime —— 只给 cron 不给类型等于什么都没改，
    # 而且它会回成功。所以这里从「给了哪个周期参数」反推类型，把静默空操作堵掉。
    if not execute_type:
        if cron and lock_time:
            raise ValueError(
                "同时给了 cron 和 lock_time，无法判断类型；请显式指定 --execute-type（Loop 用 cron，Once 用 lock_time）"
            )
        if cron:
            execute_type = "Loop"
        elif lock_time:
            execute_type = "Once"

    if execute_type == "Loop" and not cron:
        raise ValueError("cron is required when execute_type is Loop")
    if execute_type == "Once" and not lock_time:
        raise ValueError("lock_time is required when execute_type is Once")
    if cron:
        validate_quartz_cron(cron)

    # 构建请求数据
    data = {
        "id": task_id,
        "taskName": task_name,
    }

    # 添加可选参数
    if execute_type:
        data["executeType"] = execute_type
    if lock_time:
        data["lockTime"] = format_datetime_to_iso(lock_time)
    if cron:
        data["cron"] = cron
    if message:
        data["message"] = message

    url = env["PLATFORM_BASE_URL"] + API_PREFIX + "/update"
    return post_json(url, env["SANDBOX_ACCESS_KEY"], data)


def cancel_task(task_id):
    """
    取消定时任务。

    Raises:
        ValueError: 环境变量缺失
    """
    env = get_env("PLATFORM_BASE_URL", "SANDBOX_ACCESS_KEY")
    url = env["PLATFORM_BASE_URL"] + API_PREFIX + "/cancel/" + str(task_id)
    return post_json(url, env["SANDBOX_ACCESS_KEY"])


def list_tasks():
    """
    查询定时任务列表。

    Raises:
        ValueError: 环境变量缺失
    """
    env = get_env("PLATFORM_BASE_URL", "SANDBOX_ACCESS_KEY")
    url = env["PLATFORM_BASE_URL"] + API_PREFIX + "/list"
    return post_json(url, env["SANDBOX_ACCESS_KEY"])


# --------------------------------------------------------------------------
# 输出格式化
# --------------------------------------------------------------------------

def format_task_list(tasks):
    """格式化任务列表为可读字符串。"""
    if not tasks:
        return "📋 暂无定时任务"

    lines = ["✅ 找到 " + str(len(tasks)) + " 个定时任务\n"]

    for idx, task in enumerate(tasks, 1):
        lines.append("[" + str(idx) + "] ID: " + str(task.get("id", "N/A")))
        lines.append("    任务名称: " + str(task.get("taskName", "N/A")))
        lines.append("    执行类型: " + str(task.get("executeType", "N/A")))

        if task.get("lockTime"):
            lines.append("    执行时间: " + str(task["lockTime"]))
        if task.get("cron"):
            lines.append("    执行周期: " + str(task["cron"]))
        if task.get("message"):
            lines.append("    消息内容: " + str(task["message"]))

        lines.append("")  # 空行分隔

    return "\n".join(lines)


# --------------------------------------------------------------------------
# 子命令
# --------------------------------------------------------------------------

def cmd_create(args):
    try:
        print_group_message_hint(args.message)
        print_message_id_hint(args.message)
        result = create_task(
            task_name=args.task_name,
            execute_type=args.execute_type,
            lock_time=args.lock_time,
            cron=args.cron,
            message=args.message,
            space_id=args.space_id,
        )

        if is_success(result) and result.get("data"):
            task_id = result["data"].get("id")
            # 服务端创建失败时会吞掉异常并回 0，此时信封仍是成功态 —— 必须单独判掉，
            # 否则会把「没建成」报成「已创建」。
            if not task_id:
                print("❌ 创建失败: 服务端未返回任务ID（任务没有建立）")
                if args.execute_type == "Loop":
                    print("💡 最常见原因是 cron 不是 Quartz 6 字段格式，例如每天 9 点应为 '0 0 9 * * ?'")
                print_tid(result)
                return 1
            print("✅ 定时任务创建成功!")
            print("🆔 任务ID: " + str(task_id))
            print_tid(result)
            return 0

        print_error(result, "创建")
        return 1

    except ValueError as e:
        print("⚠️  参数错误: " + str(e))
        return 1
    except Exception as e:
        print("💥 意外错误: " + str(e))
        return 1


def cmd_update(args):
    try:
        # 只在真的要改 message 时提醒（不传 message = 保持原内容，跟群场景无关）
        if args.message:
            print_group_message_hint(args.message)
            print_message_id_hint(args.message)
        result = update_task(
            task_id=args.task_id,
            task_name=args.task_name,
            execute_type=args.execute_type,
            lock_time=args.lock_time,
            cron=args.cron,
            message=args.message,
        )

        if is_success(result):
            print("✅ 定时任务更新成功!")
            print("🆔 任务ID: " + str(args.task_id))
            print_tid(result)
            return 0

        print_error(result, "更新")
        return 1

    except ValueError as e:
        print("⚠️  参数错误: " + str(e))
        return 1
    except Exception as e:
        print("💥 意外错误: " + str(e))
        return 1


def cmd_cancel(args):
    try:
        result = cancel_task(args.task_id)

        if is_success(result):
            print("✅ 定时任务取消成功!")
            print("🆔 任务ID: " + str(args.task_id))
            print_tid(result)
            return 0

        print_error(result, "取消")
        return 1

    except ValueError as e:
        print("⚠️  参数错误: " + str(e))
        return 1
    except Exception as e:
        print("💥 意外错误: " + str(e))
        return 1


def cmd_list(args):
    try:
        result = list_tasks()

        if is_success(result):
            if args.json:
                print(json.dumps(result, ensure_ascii=False, indent=2))
            else:
                print(format_task_list(result.get("data", [])))
                print_tid(result)
            return 0

        print_error(result, "查询")
        return 1

    except ValueError as e:
        print("⚠️  参数错误: " + str(e))
        return 1
    except Exception as e:
        print("💥 意外错误: " + str(e))
        return 1


def build_parser():
    parser = argparse.ArgumentParser(
        prog="scheduler.py",
        description="Nuwax 定时任务：创建 / 更新 / 取消 / 查询",
    )
    subparsers = parser.add_subparsers(dest="command")

    p_create = subparsers.add_parser("create", help="创建定时任务")
    p_create.add_argument("--task-name", required=True, help="任务名称")
    p_create.add_argument(
        "--execute-type",
        required=True,
        choices=["Once", "Loop"],
        help="执行类型（Once 一次性 / Loop 循环）",
    )
    p_create.add_argument(
        "--lock-time", help="一次性任务的执行时间，格式：YYYY-MM-DD HH:MM:SS"
    )
    p_create.add_argument("--cron", help="循环任务的cron表达式（Quartz 6 字段）")
    p_create.add_argument(
        "--message",
        required=True,
        help="任务触发时发给智能体的内容（必填）。★要含「用 nuwax-im 把结果推给用户」；"
             "本次运行若是群消息触发的，改成优先发回群里（send-group + 群会话ID 的数字，"
             "要通知谁就加 --mention-user-ids），并补上「报 IM_20104 就改用 send-p2p 通知本人」"
             "的退路，见 SKILL.md",
    )
    p_create.add_argument(
        "--space-id",
        type=int,
        help="可选，指定任务归属的空间ID。传入后服务端只校验当前用户是否为该空间成员"
             "（不校验智能体所在空间）；无权限会报「没有指定空间的权限」。不传则回落到用户个人空间",
    )
    p_create.set_defaults(func=cmd_create)

    p_update = subparsers.add_parser("update", help="更新定时任务")
    p_update.add_argument("--task-id", type=int, required=True, help="任务ID")
    p_update.add_argument("--task-name", required=True, help="任务名称（必填）")
    p_update.add_argument(
        "--execute-type",
        choices=["Once", "Loop"],
        help="执行类型（Once 一次性 / Loop 循环）",
    )
    p_update.add_argument(
        "--lock-time", help="一次性任务的执行时间，格式：YYYY-MM-DD HH:MM:SS"
    )
    p_update.add_argument("--cron", help="循环任务的cron表达式（Quartz 6 字段）")
    p_update.add_argument(
        "--message",
        help="任务消息内容；★改群任务的推送写法也走这里（send-group + 群会话ID 的数字，"
             "并带上报 IM_20104 时回落私聊的退路），见 SKILL.md",
    )
    p_update.set_defaults(func=cmd_update)

    p_cancel = subparsers.add_parser("cancel", help="取消定时任务")
    p_cancel.add_argument("--task-id", type=int, required=True, help="任务ID")
    p_cancel.set_defaults(func=cmd_cancel)

    p_list = subparsers.add_parser("list", help="查询定时任务列表")
    p_list.add_argument("--json", action="store_true", help="以 JSON 格式输出")
    p_list.set_defaults(func=cmd_list)

    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()

    if not getattr(args, "command", None):
        parser.print_help()
        return 1

    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
个人电脑临时代理链接

把用户电脑上监听的本地端口，创建成可对外访问的临时代理链接。

    python3 scripts/temp_link.py create --port 8080 [--host localhost]
    python3 scripts/temp_link.py list [--json]
    python3 scripts/temp_link.py delete <代理ID>
"""

import os
import sys
import json
import argparse
from urllib import request
from urllib.error import HTTPError, URLError


def _env(*names: str) -> dict:
    """
    取环境变量

    Args:
        names: 需要的变量名

    Returns:
        {变量名: 值}

    Raises:
        ValueError: 有变量缺失
    """
    values = {name: os.getenv(name) for name in names}
    missing = [name for name, value in values.items() if not value]
    if missing:
        raise ValueError(f"Missing required environment variables: {', '.join(missing)}")
    return values


def _post(url: str, access_key: str, payload: dict = None) -> dict:
    """
    向平台发一次 POST

    Args:
        url: 完整接口地址
        access_key: SANDBOX_ACCESS_KEY
        payload: 请求体（None 表示不带 body）

    Returns:
        API 响应数据
    """
    headers = {
        "Authorization": f"Bearer {access_key}",
        "Content-Type": "application/json"
    }
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = request.Request(url, data=data, headers=headers, method="POST")

    try:
        with request.urlopen(req) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as e:
        error_data = json.loads(e.read().decode("utf-8"))
        error_data["statusCode"] = e.code
        return error_data
    except URLError as e:
        return {
            "code": "-1",
            "message": f"Network error: {e.reason}",
            "data": None,
            "success": False
        }


def _is_success(result: dict) -> bool:
    """业务成败看 code / success，HTTP 200 也可能是失败"""
    return result.get("success", False) or result.get("code") in ["0000", "0", 0]


def _print_failure(action: str, result: dict) -> int:
    """统一的失败输出"""
    print(f"❌ {action}失败: {result.get('message', 'Unknown error')}")
    if "statusCode" in result:
        print(f"HTTP 状态码: {result['statusCode']}")
    if result.get("code"):
        print(f"错误码: {result['code']}")
    if result.get("displayCode"):
        print(f"源系统状态码: {result['displayCode']}")
    if result.get("tid"):
        print(f"跟踪ID: {result['tid']}")
    return 1


def cmd_create(args) -> int:
    env = _env("PLATFORM_BASE_URL", "SANDBOX_ID", "SANDBOX_ACCESS_KEY")
    result = _post(
        f"{env['PLATFORM_BASE_URL']}/api/v1/4sandbox/proxy/create",
        env["SANDBOX_ACCESS_KEY"],
        {
            "sandboxId": env["SANDBOX_ID"],
            "backendHost": args.host,
            "backendPort": args.port
        }
    )

    if _is_success(result) and result.get("data"):
        print("✅ 临时代理链接创建成功!")
        print(f"🔗 代理URL: {result['data'].get('tempLink', 'N/A')}")
        print(f"🆔 链接ID: {result['data'].get('id', 'N/A')}")
        if result.get("tid"):
            print(f"📋 跟踪ID: {result['tid']}")
        return 0
    return _print_failure("创建", result)


def cmd_list(args) -> int:
    env = _env("PLATFORM_BASE_URL", "SANDBOX_ID", "SANDBOX_ACCESS_KEY")
    result = _post(
        f"{env['PLATFORM_BASE_URL']}/api/v1/4sandbox/proxy/list/{env['SANDBOX_ID']}",
        env["SANDBOX_ACCESS_KEY"]
    )

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 0

    if not _is_success(result):
        return _print_failure("查询", result)

    proxies = result.get("data", [])
    if not proxies:
        print("ℹ️  当前没有代理链接")
        print("使用 'python3 scripts/temp_link.py create --port <端口>' 创建一个")
        return 0

    print(f"✅ 找到 {len(proxies)} 个代理链接\n")
    for i, proxy in enumerate(proxies, 1):
        print(f"[{i}] ID: {proxy.get('id', 'N/A')}")
        print(f"    链接: {proxy.get('tempLink', 'N/A')}")
        if proxy.get("backendPort"):
            print(f"    端口: {proxy.get('backendPort')}")
        if proxy.get("backendHost"):
            print(f"    主机: {proxy.get('backendHost')}")
        if proxy.get("created"):
            print(f"    创建时间: {proxy['created']}")
        print()
    return 0


def cmd_delete(args) -> int:
    env = _env("PLATFORM_BASE_URL", "SANDBOX_ACCESS_KEY")
    result = _post(
        f"{env['PLATFORM_BASE_URL']}/api/v1/4sandbox/proxy/delete/{args.id}",
        env["SANDBOX_ACCESS_KEY"]
    )

    if _is_success(result):
        print("✅ 代理链接删除成功!")
        print(f"🆔 代理ID: {args.id}")
        if result.get("tid"):
            print(f"📋 跟踪ID: {result['tid']}")
        return 0
    return _print_failure("删除", result)


def main() -> int:
    parser = argparse.ArgumentParser(description="个人电脑临时代理链接")
    sub = parser.add_subparsers(dest="command", required=True)

    p_create = sub.add_parser("create", help="创建临时代理链接")
    p_create.add_argument("--port", type=int, required=True, help="用户电脑本机上监听的端口号")
    p_create.add_argument("--host", default="localhost", help="用户电脑本机上监听的地址 (默认: localhost)")
    p_create.set_defaults(func=cmd_create)

    p_list = sub.add_parser("list", help="查询当前电脑的全部代理链接")
    p_list.add_argument("--json", action="store_true", help="以 JSON 格式输出")
    p_list.set_defaults(func=cmd_list)

    p_delete = sub.add_parser("delete", help="删除指定代理链接（不可逆）")
    p_delete.add_argument("id", type=int, help="代理ID（用 list 查看）")
    p_delete.set_defaults(func=cmd_delete)

    args = parser.parse_args()

    try:
        return args.func(args)
    except ValueError as e:
        print(f"⚠️  配置错误: {e}")
        return 1
    except Exception as e:
        print(f"💥 意外错误: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())

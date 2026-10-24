#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
nuwax-oauth2 技能客户端 —— 让「你自己的业务系统」接入女娲平台的登录认证与开放接口。

★这个脚本【不代替你的系统做认证】。授权码回调、令牌保管、刷新、调接口都跑在你自己的
  服务端里；本脚本干两件事：① 帮你在平台上把应用配好（建应用、登记回调、申请 scope、
  取凭据）；② 用 scaffold 生成一套预填好参数、可直接落进你仓库的对接代码。
  其余协议侧子命令是给你【联调自检】用的：验证你登记的回调地址 / scope / 凭据确实可用。

一个入口脚本，18 个子命令，形如：

    python3 scripts/oauth2.py dev-app --path /oauth2/callback   # ★第一步：定位应用 + 配好回调
    python3 scripts/oauth2.py endpoints                 # 离线列出全部子命令，不发请求
    python3 scripts/oauth2.py client-list               # 我名下有哪些三方应用（拿 projectId / clientId）
    python3 scripts/oauth2.py client-get --project-id 1234 --reveal-secret
    python3 scripts/oauth2.py scaffold --stack all --out ./nuwax-integration   # ★主交付物
    python3 scripts/oauth2.py auth-url --scope profile,chat:read               # 以下是联调自检
    python3 scripts/oauth2.py token-m2m                 # 机器对机器，全自动
    python3 scripts/oauth2.py call --path im/contact/list

★★本脚本横跨【两套完全不同的凭据】，混用必失败：

    ① 沙箱 AK（环境变量 SANDBOX_ACCESS_KEY，ak- 前缀）
       只能打 /api/v1/4sandbox/**，用于「管理三方应用」：建应用、登记回调地址、
       申请 scope、取 client_id / client_secret。
    ② OAuth2 客户端凭据（client_id + client_secret）与用户令牌（oa- 前缀）
       只能打 /api/oauth2/**，用于「真正调平台能力」。

    ★把沙箱 AK 带进 /api/oauth2/** 会得到 code 4030「API Key 无访问权限」——
      沙箱型 AK 被服务端钉死只允许 /api/v1/4sandbox 前缀。脚本已按子命令分好，
      别手工把 AK 塞进 --access-token。

★在沙箱里运行且没给 --client-id / --client-secret 时，脚本会用沙箱 AK 自动定位
  你名下的三方应用并就地取出 client_id 与 secret（secret 只在本次进程内存里用，
  绝不写进缓存文件、也绝不打印）。这是「零配置接入」的路径。
  定位优先级：--project-id / --app-id > 环境变量 DEV_APP_ID > NUWAX_OAUTH2_PROJECT_ID
             > 名下恰好一个应用。
  ★DEV_APP_ID 是平台在「开发某个全栈应用」的沙箱里自动注入的应用 id（见 dev-app 子命令），
    有它就等于已经知道要接哪个应用，不需要再问人。

★退出码就是错误分类，别只看 stdout：

    0  成功
    1  参数问题：本地用法错误（请求没发出去），或平台业务拒绝（看 message / error 原文）
    2  环境变量或凭据缺失（请求没发出去）
    3  ★需要人来决定（只有 dev-app 会返回）：没法自动定位要接哪个应用，
       stdout 里有一段 NUWAX_CHOICE_REQUIRED 开头的 JSON 候选清单 ——
       调用方（AI）必须把它拿给用户选，或问用户要不要新建应用，然后带 --app-id 重跑。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import secrets
import stat
import sys
import time
import unicodedata
from urllib import request as urlrequest
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse, parse_qsl

# ---------------------------------------------------------------- 常量

SANDBOX_PREFIX = "/api/v1/4sandbox"
OAUTH2_PREFIX = "/api/oauth2"
TIMEOUT_SECONDS = 30

# 缓存目录：PKCE 中间态与 access_token 必须跨调用存活（授权码流程分两步），
# 否则半自动授权根本走不完。★secret 一律不落盘。
CACHE_DIR_NAME = ".nuwax-oauth2"
PENDING_FILE = "pending.json"   # auth-url 生成的 state / code_verifier
TOKEN_FILE = "token.json"       # token / token-m2m / refresh 换到的令牌

EXIT_OK = 0
EXIT_USAGE = 1
EXIT_ENV = 2
# ★3 = 「得问人」，不是失败。只有 dev-app 会返回：自动定位不到要接哪个应用时，
#   把候选清单打成机器可读的 JSON 交给调用方（AI），由它去问用户，而不是脚本自己猜。
EXIT_NEEDS_CHOICE = 3

# 平台信封的成功码
CODE_OK = ("0000", "0")

# 三方应用可选类型；UserApp（全栈应用）也走 OAuth2 接入，但 projectId 语义不同（见 exchangeToPK）
PROJECT_TYPES = ("ThirdApp", "UserApp")

# 全栈应用的域名绑定类型（userapp_domain.domain_type / UserAppDomainType 枚举）。
#   Dev    平台创建应用时分配的开发域名（后缀 userapp.domain.dev-suffix）
#   Prod   生产域名 —— ★与 Dev 在【创建应用时一并分配】（UserAppApplicationServiceImpl.create 里
#          ensureSystemBinding 调两次，注释原文「生产域名与开发域名同时分配（幂等），发布上架时不再分配」）
#   Custom 用户自己绑的域名（要先 CNAME 到官方接入点）
# ★Dev/Prod 均不可改、不可解绑 ⇒ 拿它拼回调地址是稳定的（Custom 可能被换绑）。
# ★默认不挑：一个应用天生就同时有 Dev 与 Prod，两个都登记才不会出现「开发能登、发布后回调失配」。
DOMAIN_TYPES = ("Dev", "Prod", "Custom")
# 登记顺序：数据库按 modified 倒序返回，而 Dev/Prod 是同秒写入的 ⇒ 次序不稳。
# 回调地址是逐字匹配的，输出顺序飘一次人工核对就白做，所以这里固定成 Dev → Prod → Custom。
DOMAIN_TYPE_ORDER = ("Dev", "Prod", "Custom")

# 回调地址的默认 path。★path 由开发者的 AI 决定（它要知道自己系统里回调挂在哪个路由上），
#   这里只给一个最常见、且不与业务路由冲突的默认值。
DEFAULT_CALLBACK_PATH = "/oauth2/callback"
DEFAULT_HOMEPAGE_PATH = "/"


# ---------------------------------------------------------------- 基础件

def die(message, code=EXIT_USAGE):
    """打印错误并退出。用法错误与业务拒绝都是 1。"""
    print("❌ " + message, file=sys.stderr)
    sys.exit(code)


def positive_int(text):
    """ID 类参数：必须是正整数，且以 JSON number 送出（写成字符串服务端反序列化失败）。"""
    raw = str(text).strip()
    try:
        value = int(raw)
    except ValueError:
        raise argparse.ArgumentTypeError("不是合法整数: %r" % text)
    if value <= 0:
        raise argparse.ArgumentTypeError("必须是正整数: %r" % text)
    return value


def pad(text, width):
    """
    按【显示宽度】补空格对齐。

    ★不能用 "%-Ns"：中文是全角占 2 列，而 %s 只数字符个数，混排时列会歪
      （「写」补 5 个空格 = 7 列，「只读」补 4 个 = 8 列）。
    """
    text = str(text)
    used = sum(2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1 for ch in text)
    return text + " " * max(1, width - used)


def cache_dir():
    """缓存目录（0700）。刻意放 HOME 下而不是仓库里 —— 令牌不该进 git。"""
    path = os.path.join(os.path.expanduser("~"), CACHE_DIR_NAME)
    os.makedirs(path, mode=0o700, exist_ok=True)
    return path


def cache_load(name):
    try:
        with open(os.path.join(cache_dir(), name), "r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None


def cache_save(name, payload):
    """写缓存并立刻收紧权限到 0600。★令牌是活凭据，落盘必须只有本人可读。"""
    path = os.path.join(cache_dir(), name)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
    os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
    return path


def cache_drop(name):
    try:
        os.unlink(os.path.join(cache_dir(), name))
    except OSError:
        pass


def sandbox_env(required=True):
    """
    读沙箱环境变量。

    required=False 时缺失就返回 (None, None)，让调用方走别的凭据来源；
    required=True 时缺失直接退出码 2（请求不会发出去）。
    """
    base = (os.getenv("PLATFORM_BASE_URL") or "").rstrip("/")
    key = os.getenv("SANDBOX_ACCESS_KEY") or ""
    if not base or not key:
        if not required:
            return None, None
        print("❌ 缺少环境变量: " + ", ".join(
            n for n, v in (("PLATFORM_BASE_URL", base), ("SANDBOX_ACCESS_KEY", key)) if not v),
            file=sys.stderr)
        print("   「管理三方应用」这一组子命令需要沙箱环境变量 PLATFORM_BASE_URL 与 SANDBOX_ACCESS_KEY；", file=sys.stderr)
        print("   不在 4sandbox 里运行时，请改用 --client-id / --client-secret 显式提供凭据", file=sys.stderr)
        print("   （协议侧子命令 auth-url / token / token-m2m / refresh / revoke / call / userinfo 不需要 AK）。", file=sys.stderr)
        sys.exit(EXIT_ENV)
    return base, key


def platform_base():
    """协议侧子命令只要基址，不要 AK（★AK 打进 /api/oauth2/** 会 4030）。"""
    base = (os.getenv("PLATFORM_BASE_URL") or "").rstrip("/")
    if not base:
        print("❌ 缺少环境变量: PLATFORM_BASE_URL", file=sys.stderr)
        print("   协议侧子命令需要平台基址来拼 /api/oauth2/** 的地址。", file=sys.stderr)
        sys.exit(EXIT_ENV)
    return base


# ---------------------------------------------------------------- HTTP

def http(url, method="GET", headers=None, data=None):
    """
    最底层的一次请求，返回 (status, text)。

    ★HTTP 错误不抛：三种响应格式的失败判据不同（协议端点看 HTTP 状态码 + error 字段，
      门面端点看信封里的 code，裸对象端点拿到对象就算成功），必须把状态码和响应体一起交给上层。
    """
    req = urlrequest.Request(url, data=data, headers=headers or {}, method=method)
    try:
        with urlrequest.urlopen(req, timeout=TIMEOUT_SECONDS) as resp:
            return resp.getcode(), resp.read().decode("utf-8", "replace")
    except HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")
    except URLError as exc:
        # 到不了平台 —— 请求根本没出去。
        return -1, json.dumps({"code": "-1", "message": "网络错误，请求未送达平台: %s" % exc.reason},
                              ensure_ascii=False)


def parse_json(text):
    try:
        value = json.loads(text)
        return value if isinstance(value, dict) else {"data": value}
    except ValueError:
        return {"message": (text or "(空响应体)")[:800]}


def call_sandbox(path, method="GET", payload=None):
    """
    打 /api/v1/4sandbox/** —— 用沙箱 AK，响应是平台信封。

    租户永远不需要传：服务端按 AK 自行解析租户，客户端不带任何租户参数/头。
    """
    base, key = sandbox_env()
    url = base + SANDBOX_PREFIX + path
    headers = {"Authorization": "Bearer " + key}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    status, text = http(url, method=method, headers=headers, data=data)
    envelope = parse_json(text)
    envelope.setdefault("statusCode", status)
    return envelope


def call_oauth2_form(path, form):
    """
    打 /api/oauth2/token 与 /revoke —— 表单编码，响应是 **RFC 6749 扁平结构**。

    ★客户端凭据走表单字段（服务端表单优先、Basic 头只是回退）。
      这里刻意不用 Basic：字段形态在日志与调试里更直观，且服务端两条路径都认。
    """
    base = platform_base()
    url = base + OAUTH2_PREFIX + path
    headers = {"Content-Type": "application/x-www-form-urlencoded",
               "Accept": "application/json"}
    data = urlencode({k: v for k, v in form.items() if v is not None}).encode("utf-8")
    status, text = http(url, method="POST", headers=headers, data=data)
    body = parse_json(text)
    body["statusCode"] = status
    return body


def call_oauth2_api(path, token, method="GET", query=None, body=None):
    """
    打 /api/oauth2/** 的端点 —— 用 oa- 令牌。返回响应体的原始 JSON。

    ★这里绝不能再带沙箱 AK（见 F6：会 4030）。
    ★响应形状有两类：门面端点回平台信封（有 code），/userinfo 与 /user/search 回裸对象（没有 code）。
      用 is_envelope() 区分，别一律按信封去取 data。
    """
    base = platform_base()
    if not path.startswith("/"):
        # 允许简写：im/contact/list → /api/oauth2/im/contact/list
        path = OAUTH2_PREFIX + "/" + path
    elif not path.startswith(OAUTH2_PREFIX):
        path = OAUTH2_PREFIX + path
    url = base + path
    if query:
        url += ("&" if "?" in url else "?") + urlencode(
            {k: v for k, v in query.items() if v is not None})
    headers = {"Authorization": "Bearer " + token, "Accept": "application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    status, text = http(url, method=method, headers=headers, data=data)
    envelope = parse_json(text)
    envelope.setdefault("statusCode", status)
    return envelope


def is_envelope(body):
    """
    这个响应是不是平台信封。

    ★判据机械且完备：平台只对返回类型是统一信封的端点套外壳，所以「响应里有 code」才是信封。
      /userinfo 与 /user/search 回裸对象（sub/user_id/name/avatar/tenant_id），/token、/revoke 回 RFC 结构，
      两者都没有 code。真失败时平台会把异常加工成带 code 的信封，网络层失败也由 http() 合成
      {"code": "-1"} —— 所以「没有 code」不等于失败，但也不是无脑成功，见 classify()。
    """
    return isinstance(body, dict) and "code" in body


def is_unparsed(body):
    """
    这个响应体是不是「根本不是 JSON」——parse_json() 对解析失败会兜成 {"message": <原文>}。

    ★HTTP 200 也可能是这种：网关错误页、响应被截断、代理插了一页 HTML。
      它没有 code（不是信封）、也不是裸对象，光看 HTTP 状态码会误判成成功，所以要单独认出来。

    判据是「有 message 且顶层字段只有 message/statusCode」：正常端点不会只给一个 message。
    ★必须带 message 这一项——否则空对象 {} 会因为 set() <= {...} 恒真而被判成解析失败，
      而 {} 恰恰是 /user/search 查无此人的正常语义（刻意防枚举），误判会把正常结果变成报错。
    """
    return isinstance(body, dict) and "message" in body and set(body) <= {"message", "statusCode"}


# ---------------------------------------------------------------- 凭据解析

def env_or(name, fallback=None):
    value = (os.getenv(name) or "").strip()
    return value or fallback


def dev_app_id():
    """
    读环境变量 DEV_APP_ID —— 平台在「正在开发某个全栈应用」的沙箱里注入的应用 id。

    ★值域（实测钉死，别猜）：注入点是 SandboxAgentClient，只在会话的 devTargetType==UserApp 时写；
      写进去的是 conversation.devTargetId，而它来自 UserProjectRpcServiceImpl 的
      `refId != null ? refId : id` ⇒ **就是 UserApp 在 ref 表（userapp）里的主键**，
      与 page-query 返回行的 projectId 同一个值域，可直接用于 /setting、/secret、/userapp/domain/list。
    ★坑：/setting/{id} 的返回体里 projectId 会被换成 user_project 的真主键（实测 201 → 430）。
      后续所有调用必须【继续用 DEV_APP_ID 原值】+ projectType=UserApp，拿返回值里的 id 再调
      会被当成 ThirdApp 主键处理，打错行或直接「项目不存在」。
    """
    raw = (os.getenv("DEV_APP_ID") or "").strip()
    if not raw:
        return None
    try:
        value = int(raw)
    except ValueError:
        print("⚠️  环境变量 DEV_APP_ID 不是整数（%r），已忽略。" % raw, file=sys.stderr)
        return None
    if value <= 0:
        print("⚠️  环境变量 DEV_APP_ID 不是正整数（%s），已忽略。" % value, file=sys.stderr)
        return None
    return value


def list_third_apps(page_size=50, max_pages=4):
    """
    列出当前用户名下可接入 OAuth2 的项目。

    ★借道 /api/user-project/page-query：平台没有「列出某用户全部三方应用」的专用接口。
      入参类是 system.spec.page.PageQueryVo —— 分页字段叫 current / pageSize（不是 pageNo），
      过滤条件整个塞在 queryFilter 里；传错字段名会静默走默认分页。
    ★不传 spaceId 时服务端按「用户所属全部空间」过滤，不是按创建者 ⇒
      团队协作下同一空间成员互相可见对方项目，返回的不严格等于「我创建的」。
    """
    records = []
    for page in range(1, max_pages + 1):
        envelope = call_sandbox("/user-project/page-query", method="POST", payload={
            "queryFilter": {
                "projectTypes": list(PROJECT_TYPES),
                "archivedFilter": "exclude",
            },
            "current": page,
            "pageSize": page_size,
        })
        if str(envelope.get("code")) not in CODE_OK:
            return records, envelope
        data = envelope.get("data") or {}
        batch = data.get("records") or []
        records.extend(batch)
        total = data.get("total") or 0
        if not batch or len(records) >= total or page >= max_pages:
            break
    return records, None


def find_project(project_id):
    """在列表里按 projectId 找一行，用于自动判定 projectType（ThirdApp / UserApp）。"""
    records, error = list_third_apps()
    if error is not None:
        return None, error
    for row in records:
        if str(row.get("projectId")) == str(project_id):
            return row, None
    return None, None


def resolve_project_type(project_id, explicit):
    """
    定 projectType。显式传参永远优先；否则查列表自动判定；查不到按 ThirdApp 处理并警告。

    ★为什么必须判定：page-query 回的 projectId 是「refId 优先」的值 ——
      ThirdApp 的 refId 为 null，projectId 就是 user_project 主键，可直接用；
      UserApp 的 projectId 是 ref 表主键，服务端要靠 projectType=UserApp 走 exchangeToPK
      才能换回真主键。传错会得到「项目不存在或无权访问」，看不出真因。
    """
    if explicit:
        return explicit
    row, error = find_project(project_id)
    if row:
        return row.get("projectType") or "ThirdApp"
    if error is not None:
        print("⚠️  没法自动判定 projectType（列表查询失败：%s），按 ThirdApp 处理。" % error.get("message"),
              file=sys.stderr)
    else:
        print("⚠️  在名下项目列表里没找到 projectId=%s，按 ThirdApp 处理；"
              "如果它其实是全栈应用，请加 --project-type UserApp。" % project_id, file=sys.stderr)
    return "ThirdApp"


def query_project_type(project_id, project_type):
    """UserApp 必须显式带 projectType；ThirdApp 省略（服务端缺省即按主键处理）。"""
    return {"projectType": project_type} if project_type == "UserApp" else None


def fetch_client_secret(project_id, project_type):
    """
    取 client_secret 明文。★只在需要就地使用它的那一刻调用，返回值不落盘、不打印。

    /secret 会走 ensureCredential（没有凭证就当场生成），并在服务端留审计日志。
    """
    query = query_project_type(project_id, project_type)
    suffix = ("?" + urlencode(query)) if query else ""
    envelope = call_sandbox("/user-project/oauth2/secret/%s%s" % (project_id, suffix))
    if str(envelope.get("code")) not in CODE_OK:
        return None, envelope
    # ★这个端点的 data 是裸字符串，不是对象
    secret = envelope.get("data")
    if not isinstance(secret, str) or not secret:
        return None, {"code": "0001", "message": "服务端没返回密钥明文（data 为空）"}
    return secret, None


def list_app_domains(app_id):
    """
    查全栈应用已绑定的域名（userapp_domain 表），返回 (rows, error)。

    走 GET /api/v1/4sandbox/userapp/domain/list?appId=N → 重写为 /api/userapp/domain/list。
    ★appId 用 DEV_APP_ID 原值（userapp 主键），不是 user_project 主键。
    ★这个端点按设计【不校验空间权限】（服务端原注释：前端要先查域名才能访问应用），
      所以它不是越权面；写操作（绑定/解绑）没有放开给沙箱 AK。
    ★返回顺序是 modified 倒序 ⇒ 「第一条」不确定（同一应用的 Dev 与 Prod 常常同秒写入），
      所以别直接拿 rows[0]，交给 select_domains() 按类型定序。本条已把应用的全部绑定域名取回，
      调用方默认应该全用（Dev + Prod 一次登记完），不要只挑一条。
    """
    envelope = call_sandbox("/userapp/domain/list?" + urlencode({"appId": app_id}))
    if str(envelope.get("code")) not in CODE_OK:
        return None, envelope
    data = envelope.get("data")
    if not isinstance(data, list):
        return [], None
    return [row for row in data if isinstance(row, dict) and row.get("domain")], None


def select_domains(rows, want_type=None):
    """
    从域名列表里挑出「这次要登记回调地址」的那些域名，返回 ([(domain, domainType), ...], 说明)。

    ★默认全要（want_type 为 None）：全栈应用创建时平台就同时分配了 Dev 与 Prod 两个域名，
      一次把两个回调地址都登记进补充列表，发布后就不会出现「开发能登、生产回调失配」的隐性故障。
      想只登记一类才传 want_type；传了但那类不存在，会退回到全部并在说明里点出来。
    ★顺序固定按 DOMAIN_TYPE_ORDER（Dev → Prod → Custom → 其它）：接口按 modified 倒序返回，
      而 Dev 与 Prod 是同秒写入的、次序不稳，输出飘一次人工核对就白做。
    """
    if not rows:
        return [], "没有绑定任何域名"
    picked, seen = [], set()
    for kind in DOMAIN_TYPE_ORDER:
        if want_type and kind != want_type:
            continue
        for row in rows:
            name = str(row.get("domain") or "").strip().lower()
            if not name or name in seen or str(row.get("domainType")) != kind:
                continue
            seen.add(name)
            picked.append((name, kind))
    if not picked:
        # 兜底：没有已知类型（或指定类型不存在）⇒ 先把已知类型的都收进来，再不行才拿原始顺序
        for row in rows:
            name = str(row.get("domain") or "").strip().lower()
            if name and name not in seen:
                seen.add(name)
                picked.append((name, str(row.get("domainType") or "?")))
        if want_type:
            return picked, ("没有 %s 类型的域名，改成登记全部 %d 条（避免漏掉生产域）"
                            % (want_type, len(picked)))
        return picked, "域名类型都不在已知枚举里，按接口返回顺序登记"
    if want_type:
        return picked, "按 --domain-type %s 收窄到 %d 条" % (want_type, len(picked))
    return picked, "登记全部 %d 条绑定域名（Dev/Prod 平台创建时一并分配）" % len(picked)


def pick_single_project(reason):
    """
    没指定应用时的自动定位：名下恰好一个就用它，0 个或多个都要求显式指定。

    ★刻意不做「没有就自动建一个」：创建会在用户名下落一行项目并生成凭据，是有副作用的写操作，
      且需要约定一个固定名称（容易撞名或留垃圾行）。
    """
    records, error = list_third_apps()
    if error is not None:
        die("%s：查询名下三方应用失败 —— code=%s message=%s"
            % (reason, error.get("code"), error.get("message")))
    if not records:
        die("%s：名下没有任何三方应用。\n"
            "   先创建一个：python3 scripts/oauth2.py client-create --name \"我的系统\"\n"
            "   ⚠️  如果确实建过却查不到，很可能是沙箱 AK 归属用户与当前平台租户不一致"
            "（服务端按 Host 解析租户，而 AK 分支不覆盖它），换对环境的 AK 再试。" % reason)
    if len(records) > 1:
        lines = ["%s：名下有 %d 个应用，无法自动决定用哪个。请显式指定：" % (reason, len(records))]
        for row in records[:20]:
            lines.append("   projectId=%s  %s  %s  clientId=%s" % (
                row.get("projectId"), row.get("projectType"),
                (row.get("name") or "(未命名)"), row.get("oauthClientId") or "-"))
        lines.append("   然后加 --project-id <id>（必要时再加 --project-type UserApp）。")
        die("\n".join(lines))
    return records[0]


def resolve_client_credential(args):
    """
    解析 (client_id, client_secret, projectId, projectType, 来源说明)。

    优先级：显式参数 > 环境变量 > 沙箱 AK 自动定位。
    ★secret 只返回给本次进程用，绝不写缓存文件。
    """
    client_id = getattr(args, "client_id", None) or env_or("NUWAX_OAUTH2_CLIENT_ID")
    client_secret = getattr(args, "client_secret", None) or env_or("NUWAX_OAUTH2_CLIENT_SECRET")
    project_id = getattr(args, "project_id", None) or env_or("NUWAX_OAUTH2_PROJECT_ID")
    project_type = getattr(args, "project_type", None)
    if not project_id:
        project_id = dev_app_id()
        if project_id:
            # DEV_APP_ID 只会在全栈应用的开发沙箱里出现 ⇒ 类型不必再去查列表
            project_type = project_type or "UserApp"

    if client_id and client_secret:
        return client_id, client_secret, project_id, project_type, "显式提供（参数或环境变量）"

    # 需要沙箱 AK 才能继续自动解析
    sandbox_env()

    row = None
    if project_id:
        project_type = resolve_project_type(project_id, project_type)
    else:
        row = pick_single_project("没给 --client-id / --client-secret，也没给 --project-id")
        project_id = row.get("projectId")
        project_type = row.get("projectType") or "ThirdApp"

    # 只给了 client_id 没给 secret：仍需按 projectId 取 secret；顺带核对 id 是否一致
    secret, error = fetch_client_secret(project_id, project_type)
    if error is not None:
        message = str(error.get("message") or "")
        hint = ""
        if "IllegalStateException" in message or "aes-key" in message.lower() or "key" in message.lower():
            hint = ("\n   ★取密钥失败是平台侧的配置问题，【重试无用】⇒ 请联系平台运维。"
                    "其余配置（client_id、回调地址、scope）照常可用，"
                    "可以先把代码接起来、密钥后补。")
        die("取 client_secret 失败 —— code=%s message=%s%s"
            % (error.get("code"), message, hint))

    if not client_id:
        envelope = call_sandbox("/user-project/oauth2/setting/%s%s" % (
            project_id,
            ("?" + urlencode(query_project_type(project_id, project_type)))
            if query_project_type(project_id, project_type) else ""))
        if str(envelope.get("code")) not in CODE_OK:
            die("取 client_id 失败 —— code=%s message=%s"
                % (envelope.get("code"), envelope.get("message")))
        client_id = (envelope.get("data") or {}).get("clientId")
        if not client_id:
            die("服务端没返回 clientId（projectId=%s）" % project_id)
    return client_id, secret, project_id, project_type, "沙箱 AK 自动定位（projectId=%s）" % project_id


def resolve_access_token(args):
    """
    解析 oa- 令牌：显式参数 > 环境变量 > 缓存（未过期）> 缓存里的 refresh_token 自动续期。

    ★自动续期是刻意的：access_token 只有 2h，不自动续期的话每两小时就得重走一次
      人工授权，半自动流程会很难用。续期用的 client_secret 同样只在内存里过一遍。
    """
    token = getattr(args, "access_token", None) or env_or("NUWAX_OAUTH2_ACCESS_TOKEN")
    if token:
        return token, "显式提供"

    cached = cache_load(TOKEN_FILE) or {}
    access = cached.get("access_token")
    expires_at = cached.get("expires_at") or 0
    if access and expires_at > time.time() + 60:
        return access, "本地缓存（还剩 %d 秒）" % int(expires_at - time.time())

    refresh = cached.get("refresh_token")
    if access and not refresh:
        # 过期且没有 refresh（client_credentials 不签发 refresh_token）
        die("缓存的 access_token 已过期，且没有 refresh_token 可续期"
            "（client_credentials 模式不签发 refresh_token）。\n"
            "   重新取一个：token-m2m（机器对机器）或 auth-url + token（授权码）。")
    if not refresh:
        die("没有可用的 access_token。\n"
            "   三种取法任选：\n"
            "    ① 显式传：--access-token <oa-...>（或环境变量 NUWAX_OAUTH2_ACCESS_TOKEN）\n"
            "    ② 机器对机器：python3 scripts/oauth2.py token-m2m\n"
            "    ③ 代表真人授权：python3 scripts/oauth2.py auth-url，浏览器登录后 token 换令牌\n"
            "   ★令牌只有 2 小时；授权码模式会一并拿到 30 天的 refresh_token，脚本会自动续期。")

    client_id = cached.get("client_id") or env_or("NUWAX_OAUTH2_CLIENT_ID")
    client_secret = env_or("NUWAX_OAUTH2_CLIENT_SECRET")
    source = "refresh_token 自动续期"
    if not client_secret:
        # 缓存里没有 secret（刻意不存），用沙箱 AK 现取一次
        project_id = cached.get("project_id") or env_or("NUWAX_OAUTH2_PROJECT_ID")
        project_type = cached.get("project_type")
        if project_id:
            project_type = resolve_project_type(project_id, project_type)
            client_secret, error = fetch_client_secret(project_id, project_type)
            if error is not None:
                die("续期需要 client_secret，但取不到 —— %s" % error.get("message"))
        else:
            die("缓存里有 refresh_token 但没有 client_id/project_id，无法自动续期。\n"
            "   重新走一次 token-m2m 或 auth-url + token 即可重建缓存。")
    if not client_id:
        die("缓存的令牌记录缺 client_id，无法续期；请重新走一次授权。")

    body = call_oauth2_form("/token", {
        "grant_type": "refresh_token",
        "refresh_token": refresh,
        "client_id": client_id,
        "client_secret": client_secret,
    })
    if rfc_failed(body, need="access_token"):
        die("用 refresh_token 续期失败 —— %s: %s\n"
            "   ★the authorization has been revoked 表示用户已在平台撤销授权，必须重走授权码流程；\n"
            "     refresh token is invalid or expired 表示 30 天有效期已过。"
            % (body.get("error"), body.get("error_description")))
    store_token(body, client_id, cached.get("project_id"), cached.get("project_type"))
    print("ℹ️  access_token 已过期，用 refresh_token 自动续期成功（refresh_token 已轮换）。", file=sys.stderr)
    return body["access_token"], source


def store_token(body, client_id, project_id=None, project_type=None):
    """把令牌写进缓存（0600）。★只存令牌，不存 client_secret。"""
    now = int(time.time())
    previous = cache_load(TOKEN_FILE) or {}
    payload = {
        "client_id": client_id,
        "access_token": body.get("access_token"),
        # refresh 模式会轮换：没返回新的就沿用旧的（client_credentials 恒不返回）
        "refresh_token": body.get("refresh_token") or previous.get("refresh_token"),
        "scope": body.get("scope"),
        "token_type": body.get("token_type") or "Bearer",
        "expires_at": now + int(body.get("expires_in") or 7200),
        "obtained_at": now,
    }
    if project_id is not None:
        payload["project_id"] = project_id
    if project_type:
        payload["project_type"] = project_type
    cache_save(TOKEN_FILE, payload)
    return payload


# ---------------------------------------------------------------- PKCE

def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def make_pkce():
    """
    生成 code_verifier 与 S256 challenge。

    verifier 必须落在 [A-Za-z0-9-._~]{43,128}（RFC 7636 §4.1）；32 字节熵 base64url 后是 43 字符，
    正好踩在下限上，合法。method 恒用 S256 —— plain 会让 challenge 等于 verifier 原文，
    授权码一旦泄漏就等同令牌泄漏，没有任何理由用它。
    """
    verifier = b64url(secrets.token_bytes(32))
    challenge = b64url(hashlib.sha256(verifier.encode("ascii")).digest())
    return verifier, challenge


def build_authorize_url(base, client_id, redirect_uri, scope, state, challenge):
    query = {
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "state": state,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    }
    if scope:
        query["scope"] = scope
    return base + OAUTH2_PREFIX + "/authorize?" + urlencode(query)


# ---------------------------------------------------------------- 渲染

def print_failure(envelope, action):
    """平台信封的失败渲染 + 按 code / message 追加针对性提示。"""
    code = envelope.get("code")
    message = envelope.get("message") or "(无 message)"
    print("❌ %s 失败" % action)
    if code is not None:
        print("code: %s" % code)
    else:
        # ★没有 code 行 = 这个响应根本不是平台信封（多半是把裸对象端点当门面在读）。
        #   把实际顶层字段名打出来，比「（code=）：未知错误」那种提示能查得多。
        top = envelope if isinstance(envelope, dict) else {}
        print("顶层字段: %s" % (", ".join(sorted(top)) or "(无)"))
        print("★ 响应里没有 code ⇒ 它不是平台信封。/userinfo 与 /user/search 返回裸对象"
              "（sub / user_id / name / avatar / tenant_id），响应体本身就是数据，"
              "不要按信封去取 code / message / data。")
    if envelope.get("statusCode") and envelope["statusCode"] != 200:
        print("httpStatus: %s" % envelope["statusCode"])
    print("message: %s" % message)
    if envelope.get("tid"):
        print("tid: %s" % envelope["tid"])

    text = str(message)
    if str(code) == "4040" or "No static resource" in text:
        print("★ 4040 / No static resource = 这条路径没有对沙箱开放（不是接口不存在，也不是脚本问题）。")
        print("  其中「删除应用 / 改应用名」本就不开放给脚本 ⇒ 请到平台网页控制台做。")
    elif str(code) == "4030":
        print("★ 4030 = 无权限。两种成因：① 沙箱 AK 无效/不属于该空间成员；")
        print("  ② 你在用 oa- 令牌打一个它没有对应 scope 的门面端点（此时 message 是「access_token 权限不足」）。")
    elif str(code) == "4033":
        print("★ 4033 = 当前用户没有该资源权限：网页端能看到这个应用的人，才能用脚本操作它。")
    elif str(code) == "4010":
        print("★ 4010 = 未登录或登录超时。用沙箱 AK 的路径不该出现它；")
        print("  出现说明 AK 没被接受，检查 SANDBOX_ACCESS_KEY。")
    elif str(code) == "4000":
        print("★ 4000 = 参数错误，请求已被拒绝、没有产生任何副作用。按上面的原文改参数，别原样重试。")
    elif "IllegalStateException" in text or "aes" in text.lower():
        print("★ 取密钥失败：这是平台侧的配置问题，重试无用 ⇒ 请联系平台运维。")
        print("  其余配置照常可用，可以先把代码接起来、密钥后补。")
    return EXIT_USAGE


def rfc_failed(body, need=None):
    """
    协议端点的失败判定。

    ★不能只看有没有 error 字段：网络层根本没连通时，call_oauth2_form 会合成一个
      {"code":"-1"} 的信封（那是平台信封的形状，不是 RFC 的），里面【没有 error】——
      只判 error 会把「请求没送出去」渲染成「✅ 已拿到令牌」，而所有字段都是 None。
      这是最坏的一类误判：使用者会拿着空令牌去调后面的接口。
    need: 成功时必须存在的字段（token 端点是 access_token）。
    """
    if "error" in body:
        return True
    if str(body.get("code")) == "-1":
        return True
    if body.get("statusCode") not in (200, None):
        return True
    if need and not body.get(need):
        return True
    return False


def print_rfc_failure(body, action):
    """RFC 6749 错误的渲染（协议端点专用）。"""
    print("❌ %s 失败" % action)
    if str(body.get("code")) == "-1":
        # 网络层失败：响应体是我们自己合成的，没有 RFC 结构可言
        print("message: %s" % body.get("message"))
        print("★请求根本没送到平台（DNS / 网络 / PLATFORM_BASE_URL 写错）。"
              "这不是凭据问题，别去换 client_secret。")
        return EXIT_USAGE
    print("httpStatus: %s" % body.get("statusCode"))
    if not body.get("error"):
        print("message: %s" % (body.get("message") or "(响应里既没有 error 也没有预期的成功字段)"))
        return EXIT_USAGE
    print("error: %s" % body.get("error"))
    if body.get("error_description"):
        print("error_description: %s" % body["error_description"])

    error = str(body.get("error") or "")
    description = str(body.get("error_description") or "")
    if error == "invalid_client":
        print("★ invalid_client = client_id 不存在 / 应用已停用 / client_secret 不对。")
        print("  自动定位模式下也可能是「名下的应用不是你以为的那个」，用 client-list 核对。")
    elif error == "invalid_grant":
        print("★ invalid_grant 的几种成因看 error_description：")
        print("  authorization code is invalid, expired or already used → 授权码 5 分钟过期且一次性，")
        print("    ★换令牌失败时授权码已被消费，无法重试，只能重走 auth-url。")
        print("  redirect_uri does not match the authorization request → token 阶段的 redirect_uri")
        print("    必须与 authorize 阶段【逐字相等】。")
        print("  code_verifier is missing or invalid → PKCE 不匹配；pending 缓存被清过就会这样。")
        print("  the authorization has been revoked → 用户已在平台撤销授权，必须重新授权。")
    elif error == "invalid_scope":
        if "client_credentials" in description:
            print("★ 这个 scope 标记为「仅授权码」，机器对机器模式拿不到 ——")
            print("  im:read / im:push / model:read / model:key 必须走 auth-url + token（真人授权）。")
        else:
            print("★ 申请的 scope 未在应用上登记。用 client-get 看当前已生效的 scopes，")
            print("  要加就用 client-setting --scopes ... 提交申请（需管理员审核后才生效）。")
    elif error == "unsupported_grant_type":
        print("★ 平台只支持 authorization_code / refresh_token / client_credentials 三种。")
    return EXIT_USAGE


def print_items(rows, empty_hint):
    if not rows:
        print("（空）")
        print("★ 这是成功返回，不是失败：" + empty_hint)
        return
    for index, row in enumerate(rows, 1):
        print("[%d] projectId: %s" % (index, row.get("projectId")))
        print("    名称    : %s" % (row.get("name") or "(未命名)"))
        print("    类型    : %s" % row.get("projectType"))
        print("    clientId: %s" % (row.get("oauthClientId") or "(尚未生成，调一次 client-get 会自动生成)"))
        print("    回调地址: %s" % ((row.get("redirectUri") or "(主地址未登记)")
                                   + "（★只列主地址；补充列表与生效集合用 client-get 看）"))
        print("    已启用  : %s" % row.get("oauthEnabled"))
        if row.get("spaceId"):
            print("    spaceId : %s" % row.get("spaceId"))
        print()


def print_client_get(setting, info, secret, project_id, project_type):
    print("✅ 应用配置（projectId=%s，类型 %s）" % (project_id, project_type))
    print()
    if info:
        print("名称        : %s" % (info.get("name") or "(未命名)"))
        if info.get("description"):
            print("描述        : %s" % info["description"])
        print("spaceId     : %s" % info.get("spaceId"))
        print("creatorId   : %s" % info.get("creatorId"))
        print("发布状态    : %s" % info.get("publishStatus"))
        print("创建 / 修改 : %s / %s" % (info.get("created"), info.get("modified")))
        print()
    print("clientId    : %s" % setting.get("clientId"))
    if secret:
        print("clientSecret: %s" % secret)
        print("  ★明文只在此刻显示。请立刻存进你自己系统的密钥管理里；")
        print("    本技能不缓存 secret，需要时重跑本命令即可（服务端有审计日志）。")
    else:
        print("clientSecret: (未显示 —— 加 --reveal-secret 取明文；hasClientSecret=%s)"
              % setting.get("hasClientSecret"))
    print("主页地址    : %s" % (setting.get("homepageUrl") or "(未登记)"))
    # ★回调地址分三行给（主地址 / 补充列表 / 生效集合）：授权时比对的是【并集】，只印主地址
    #   会把「补充列表里已经登记过」误读成「还没登记」，也看不出「主地址为空」是合法状态。
    _print_effective_uris(setting)
    print("OAuth2 启用 : %s" % setting.get("enabled"))
    print()
    print("已生效 scope: %s" % ("、".join(setting.get("scopes") or []) or "(空 ⇒ 回落平台默认 profile)"))
    print("  ★上面这行是【登记集】，不自动含默认 profile。两端口径实测有差：")
    print("    · token 端（M2M）：登记 scope 后仍可授予 profile，逗号/空格分隔都接受；")
    print("    · authorize 端：scope 校验发生在真人登录【之后】（匿名探测永远放行，别拿它下结论），")
    print("      登记集之外的请求会被拒 invalid_scope（真实事故推断）。/userinfo 强依赖 profile ⇒")
    print("      登录流的授权请求必须含 profile，且登记集里也建议有它。离线预检：scope-check --scope <串>")
    status = setting.get("scopeApplyStatus")
    if status == "Pending":
        pending = setting.get("pendingScopes")
        print("scope 审核  : Pending（待管理员审核）")
        print("  申请的目标: %s" % ("、".join(pending) if pending else
                                 "(空数组 = 申请清空、回落平台默认 profile)"))
        print("  ★审核通过前，应用仍按上面「已生效 scope」运行。")
    elif status == "Rejected":
        print("scope 审核  : Rejected（已拒绝）")
        print("  拒绝原因  : %s" % (setting.get("scopeRejectReason") or "(未给)"))
    elif status == "Approved":
        print("scope 审核  : Approved（最近一次申请已通过）")
    else:
        print("scope 审核  : (从未提交过申请)")

    if project_type == "UserApp":
        print()
        print("★全栈应用（UserApp）注意两点：")
        print("  ① 这里显示的 projectId 属于另一个编号体系，不能拿去调后续接口；")
        print("     调 /setting、/secret、/regenerate 时【必须】带 projectType=UserApp，")
        print("     本脚本已自动带上。")
        print("  ② /info 端点不接受 projectType，因此全栈应用查不到上面那段「名称/描述/时间」——")
        print("     这不是失败，是服务端接口的既有限制。")


def print_token(body, mode):
    print("✅ 已拿到令牌（%s）" % mode)
    print("access_token : %s" % body.get("access_token"))
    print("token_type   : %s" % body.get("token_type"))
    print("expires_in   : %s 秒" % body.get("expires_in"))
    if body.get("refresh_token"):
        print("refresh_token: %s" % body["refresh_token"])
    else:
        print("refresh_token: (无 —— 机器对机器模式不签发；令牌过期后重跑本命令即可)")
    print("scope        : %s" % (body.get("scope") or "(空)"))
    print()
    print("★已缓存到 ~/%s/%s（权限 0600，不进 git）。" % (CACHE_DIR_NAME, TOKEN_FILE))
    print("  后续 call / userinfo / revoke 会自动用它，过期时自动用 refresh_token 续期。")
    print("  调用示例：python3 scripts/oauth2.py userinfo")
    print("  （拿身份要用 userinfo 子命令；它回的是裸对象，不走信封那一套，别用 call --path userinfo）")


# ---------------------------------------------------------------- 子命令实现

def _normalize_path(raw, default, label):
    """把 path 规整成以 / 开头、无尾斜杠、无 #fragment 的形式。"""
    text = (raw or "").strip() or default
    if "#" in text:
        die("%s 不能带 #fragment：服务端 requireValidUrl 直接拒（oauth2UrlInvalid），"
            "而且 redirect_uri 是精确匹配的，带片段几乎必然对不上。当前值：%r" % (label, text))
    if not text.startswith("/"):
        text = "/" + text
    if text != "/":
        text = text.rstrip("/") or "/"
    if " " in text:
        die("%s 里有空格：%r" % (label, text))
    return text


def _mask_secret(secret):
    """
    secret 的默认呈现：只给长度与首尾，够核对身份，不够被 shoulder-surf 抄走。

    ★明文只有两条出口：--reveal-secret（打印一次）与 --write-env（写 0600 文件）。
      本技能任何缓存文件都不存 secret。
    """
    if not secret:
        return "(没取到)"
    head = secret[:7] if len(secret) > 9 else secret[:2]
    return "%s…%s（长度 %d；明文加 --reveal-secret 或 --write-env）" % (head, secret[-2:], len(secret))


def resolve_dev_app(args):
    """
    定位「这次要对接哪个应用」，返回 (app_id, project_type, source, candidates, error)。

    优先级：--app-id > DEV_APP_ID > NUWAX_OAUTH2_PROJECT_ID > 名下恰好一个应用。
    一个都定不下来时不猜：把候选清单交回调用方（AI），由它去问用户（退出码 3）。
    """
    if args.app_id:
        return args.app_id, args.project_type, "--app-id 显式指定", None, None

    env_id = dev_app_id()
    if env_id:
        # 注入规则决定了 DEV_APP_ID 一定是全栈应用；显式 --project-type 仍可覆盖
        return env_id, args.project_type or "UserApp", "环境变量 DEV_APP_ID", None, None

    env_project = env_or("NUWAX_OAUTH2_PROJECT_ID")
    if env_project:
        if not env_project.isdigit():
            die("环境变量 NUWAX_OAUTH2_PROJECT_ID 不是正整数：%r" % env_project)
        return int(env_project), args.project_type, "环境变量 NUWAX_OAUTH2_PROJECT_ID", None, None

    records, error = list_third_apps()
    if error is not None:
        return None, None, None, None, error
    candidates = [{
        "appId": row.get("projectId"),
        "name": row.get("name") or "(未命名)",
        "projectType": row.get("projectType"),
        "clientId": row.get("oauthClientId"),
        "redirectUri": row.get("redirectUri"),
        "homepageUrl": row.get("homepageUrl"),
        "spaceId": row.get("spaceId"),
    } for row in records]
    if len(records) == 1:
        row = records[0]
        return (row.get("projectId"), args.project_type or row.get("projectType"),
                "名下只有这一个应用，自动选它", candidates, None)
    return None, None, None, candidates, None


def _print_choice_required(reason, candidates):
    """
    定不下来要接哪个应用时，把决定权交回给人。

    ★为什么脚本不自己选：接错应用会把回调地址写进别人的项目（setting/save 是写操作），
      而「哪个应用对应他正在开发的系统」只有用户知道。脚本能做的就是把候选列清楚。
    ★调用方（AI）拿到这段后要用 ask_question 工具问用户，不要自己挑一个。
    """
    print("⚠️ 没法自动决定要对接哪个应用：%s" % reason)
    print()
    if candidates:
        print("名下可接入的应用（%d 个）：" % len(candidates))
        for index, row in enumerate(candidates, 1):
            print("  [%d] appId=%s  %s  %s" % (index, row["appId"], row["projectType"], row["name"]))
            print("      clientId=%s  回调=%s" % (row["clientId"] or "(未生成)",
                                                 row["redirectUri"] or "(未登记)"))
    else:
        print("名下没有任何可接入的应用。")
    print()
    print("★这一步必须【问用户】，不要替他挑：")
    print("  · 有候选 → 问「要对接哪一个」，答案里的 appId 带 --app-id 重跑本命令；")
    print("  · 也可以问「要不要新建一个三方应用」→ client-create --name \"<他的系统名>\"，")
    print("    建完拿返回的 projectId 带 --app-id 重跑本命令。")
    print()
    print("NUWAX_CHOICE_REQUIRED " + json.dumps({
        "reason": reason,
        "candidates": candidates or [],
        "retryWith": "--app-id <用户选的 appId>",
        "orCreate": 'client-create --name "<应用名>"',
    }, ensure_ascii=False))
    return EXIT_NEEDS_CHOICE


def _supplementary_uris(setting):
    """读回补充回调地址列表（设置页/信息接口都回 [{'uri','source','createdBy','createdAt'}]）。"""
    rows = setting.get("redirectUris") if isinstance(setting, dict) else None
    if not isinstance(rows, list):
        return []
    return [row for row in rows if isinstance(row, dict) and (row.get("uri") or "").strip()]


def _effective_redirect_uris(setting):
    """
    生效的回调地址集合 = 主地址 ∪ 补充列表每条 uri。

    ★授权时服务端就是拿这个集合做逐字比对（任一相等即通过），所以判断「要不要写」必须看它，
      只看主地址会把「补充列表里已经有了」误判成「还没登记」而重复提交。
    """
    effective = set()
    primary = (setting.get("redirectUri") or "").strip() if isinstance(setting, dict) else ""
    if primary:
        effective.add(primary)
    for row in _supplementary_uris(setting):
        effective.add(str(row.get("uri")).strip())
    return effective


def _first_supplementary_uri(setting):
    rows = _supplementary_uris(setting)
    return str(rows[0].get("uri")).strip() if rows else ""


def _print_effective_uris(setting):
    """回显「主地址 / 补充列表（带来源）/ 生效集合」。授权看的是并集，所以要一起给。"""
    print("主回调地址: %s" % ((setting.get("redirectUri") or "").strip()
                             or "(空 —— 属应用属主，只由网页端配；沙箱/AI 不写这一格)"))
    rows = _supplementary_uris(setting)
    if rows:
        print("补充回调地址:")
        for row in rows:
            print("   %s  [%s%s]" % (
                row.get("uri"), row.get("source") or "?",
                ("，by %s" % row.get("createdBy")) if row.get("createdBy") is not None else ""))
    else:
        print("补充回调地址: (无)")
    effective = _effective_redirect_uris(setting)
    print("生效地址集合: %s" % ("、".join(sorted(effective)) if effective
                               else "(空 ★授权码流程走不了，至少登记一个)"))


def cmd_dev_app(args):
    """
    ★一步做完接入的第一步：定位应用 → 取 client_id 与 secret → 用应用绑定的域名拼出
      回调地址与主页地址 → 写回平台。对应「有 DEV_APP_ID 就自动接上」的那条路。

    刻意【不碰 scope】：范围由用户提前在平台上配好，本命令提交 setting/save 时不带 scopes
    字段（带了就会落一条待审核申请，白白多一个卡点）。要看已生效范围，输出末尾会列出来。
    ★回调地址只往【补充列表】加（addRedirectUris），一次登记全部绑定域名；主地址那一格一律不写
      （它只属应用属主、由网页端维护，提交非空值会被 3353 拒，库中为空也不放行）。
    """
    app_id, project_type, source, candidates, error = resolve_dev_app(args)
    if error is not None:
        message = str(error.get("message") or "")
        hint = ""
        if str(error.get("code")) == "4040" or "No static resource" in message:
            hint = ("\n   ★4040 = 这条路径没有对沙箱开放，不是脚本的问题；"
                    "先确认 SANDBOX_ACCESS_KEY 与环境匹配，仍不行就联系平台运维。")
        die("查名下应用失败 —— code=%s message=%s%s" % (error.get("code"), message, hint))
    if not app_id:
        reason = ("名下没有任何可接入的应用" if not candidates
                  else "名下有 %d 个应用，脚本不知道哪个对应你正在开发的系统" % len(candidates))
        if args.json:
            print(json.dumps({"code": "CHOICE_REQUIRED", "message": reason,
                              "data": {"candidates": candidates or []}}, ensure_ascii=False, indent=2))
            return EXIT_NEEDS_CHOICE
        return _print_choice_required(reason, candidates)

    project_type = resolve_project_type(app_id, project_type)
    query = query_project_type(app_id, project_type)
    suffix = ("?" + urlencode(query)) if query else ""

    # ---- ① 读应用现状（★/setting 首次访问会顺带生成凭证，这是平台既有语义）
    envelope = call_sandbox("/user-project/oauth2/setting/%s%s" % (app_id, suffix))
    if str(envelope.get("code")) not in CODE_OK:
        if args.json:
            print(json.dumps(envelope, ensure_ascii=False, indent=2))
            return classify(envelope)
        return print_failure(envelope, "dev-app（读应用配置）")
    setting = envelope.get("data") or {}
    client_id = setting.get("clientId")

    # ---- ② 取 secret（失败只警告：client_id + 回调地址才是这一步的主体）
    secret, secret_error = fetch_client_secret(app_id, project_type)
    if secret_error is not None:
        message = str(secret_error.get("message") or "")
        print("⚠️ 没取到 client_secret —— code=%s message=%s"
              % (secret_error.get("code"), message), file=sys.stderr)
        if "IllegalState" in message or "aes-key" in message.lower():
            print("   ★取密钥失败是平台侧的配置问题，重试无用 ⇒ 请联系平台运维；"
                  "其余配置照常可用，可以先把代码接起来、密钥后补。", file=sys.stderr)

    # ---- ③ 域名 → 回调地址 / 主页地址
    notes = []
    domains, domain_error = ([], None)
    if project_type == "UserApp":
        domains, domain_error = list_app_domains(app_id)
        if domain_error is not None:
            message = str(domain_error.get("message") or "")
            notes.append("查域名失败（code=%s message=%s）%s"
                         % (domain_error.get("code"), message,
                            "★4040 = 这条路径没对沙箱开放 ⇒ 用 --domain 显式给域名，或直接给 --redirect-uri"
                            if str(domain_error.get("code")) == "4040"
                            or "No static resource" in message else ""))
            domains = []
    else:
        notes.append("三方应用没有绑定域名 ⇒ 回调地址必须由 --redirect-uri 给"
                     "（它是外部系统的地址，平台本来也不知道）")

    # ★一次配好：默认把应用绑定的【全部】域名都拼出回调地址（Dev + Prod + Custom），
    #   平台在建应用时就把 Dev/Prod 一并分配好了，所以这一步就能拿到两个环境的地址。
    #   给了 --domain-type 才收窄；--domain / --redirect-uri 是显式覆盖，绕过拼装。
    explicit_domain = (args.domain or "").strip().lower()
    if explicit_domain:
        candidates, cand_note = [(explicit_domain, "给定")], "--domain 显式指定"
    else:
        candidates, cand_note = select_domains(domains, args.domain_type)
    if candidates:
        notes.append("%s：%s" % (cand_note,
                                "、".join("%s(%s)" % (name, kind) for name, kind in candidates)))

    callback_path = _normalize_path(args.path, DEFAULT_CALLBACK_PATH, "--path")
    homepage_path = _normalize_path(args.homepage_path, DEFAULT_HOMEPAGE_PATH, "--homepage-path")
    # 本次要登记的回调地址（有序）。显式 --redirect-uri 时只登记它一条。
    explicit_redirect = (args.redirect_uri or "").strip()
    if explicit_redirect:
        redirect_uris = [explicit_redirect]
    else:
        redirect_uris = ["https://%s%s" % (name, callback_path) for name, _kind in candidates]
    for uri in redirect_uris:
        if not uri.startswith("https://"):
            notes.append("⚠️ 回调地址不是 https：%s（服务端只要求 http/https，但浏览器侧混合内容会拦）"
                         % uri)

    # 代表值：给 .env、摘要首行和「沿用已登记」兜底用。取第一条（Dev 优先）。
    domain = candidates[0][0] if candidates else ""
    redirect_uri = redirect_uris[0] if redirect_uris else ""
    homepage_url = (args.homepage_url or "").strip()
    if not homepage_url and domain:
        homepage_url = "https://%s%s" % (domain, homepage_path)
    if not redirect_uri:
        redirect_uri = setting.get("redirectUri") or _first_supplementary_uri(setting)
        if redirect_uri:
            redirect_uris = [redirect_uri]
            notes.append("没能拼出回调地址（没域名也没给 --redirect-uri）⇒ 沿用平台上已登记的那个")
        else:
            notes.append("★回调地址还是空的 ⇒ 授权码流程走不了。给 --redirect-uri，"
                         "或（全栈应用）给 --domain <域名>")
    if not homepage_url:
        homepage_url = setting.get("homepageUrl") or ""

    # ---- ④ 写回平台（--no-save 时只演练）
    # ★回调地址写哪一格：登记的是「主地址 redirect_uri + 补充列表 redirect_uris」两个字段，
    #   生效集合取并集。沙箱里的 AI 【一律只写补充列表】——主地址那一格连库中为空时都不写：
    #   主地址属应用属主（只由网页端配），AI 占位后人工再想配反而会被 3353 挡住；
    #   而生效集合取并集、主地址为空不影响授权，所以 AI 没有任何写它的理由。
    saved = None
    if args.no_save:
        notes.append("--no-save：没有写回平台，下面是【将要】保存的值")
    elif not redirect_uris and not homepage_url:
        notes.append("两个地址都是空的 ⇒ 没有可保存的内容，跳过 setting/save")
    else:
        payload = {"projectId": app_id, "projectType": project_type if project_type == "UserApp" else None}
        effective = _effective_redirect_uris(setting)
        already = [uri for uri in redirect_uris if uri in effective]
        to_add = [uri for uri in redirect_uris if uri and uri not in effective]
        if already:
            notes.append("已在生效集合里、不重复写：%s" % "、".join(already))
        if to_add:
            payload["addRedirectUris"] = to_add
            notes.append("回调地址写进【补充列表】（%d 条，不碰主地址 %s）"
                         % (len(to_add), setting.get("redirectUri") or "（空，保持空）"))
        if homepage_url:
            payload["homepageUrl"] = homepage_url
        # ★刻意不带 scopes：scope 由用户提前配好，带上去只会多落一条待审核申请
        saved = call_sandbox("/user-project/oauth2/setting/save", method="POST", payload=payload)
        if str(saved.get("code")) not in CODE_OK:
            if args.json:
                print(json.dumps(saved, ensure_ascii=False, indent=2))
                return classify(saved)
            return print_failure(saved, "dev-app（保存回调地址）")
        data = saved.get("data") or {}
        redirect_uri = data.get("redirectUri") or redirect_uri
        homepage_url = data.get("homepageUrl") or homepage_url
        setting = data
        for skipped in data.get("skippedRedirectUriRemovals") or []:
            notes.append("⚠️ 回调地址 %s 没能删除（不是本命令加的，或不是本应用的人工配置）" % skipped)

    # ---- ⑤ 可选：把凭据写进 .env（0600）
    env_path = None
    if args.write_env:
        env_path = _write_dev_env(args.write_env, client_id, secret, redirect_uri, homepage_url,
                                  app_id, project_type, setting)

    if args.json:
        print(json.dumps({"code": "0000", "data": {
            "appId": app_id, "projectType": project_type, "locatedBy": source,
            "clientId": client_id,
            "clientSecret": secret if args.reveal_secret else None,
            "hasClientSecret": bool(secret),
            "redirectUri": redirect_uri, "homepageUrl": homepage_url,
            "registeredRedirectUris": redirect_uris,
            "domainCandidates": [{"domain": name, "domainType": kind} for name, kind in candidates],
            "primaryRedirectUri": (setting.get("redirectUri") or "") or None,
            "supplementaryRedirectUris": _supplementary_uris(setting),
            "effectiveRedirectUris": sorted(_effective_redirect_uris(setting)),
            "skippedRedirectUriRemovals": setting.get("skippedRedirectUriRemovals") or [],
            "domains": domains, "domainUsed": domain or None,
            "scopes": setting.get("scopes") or [],
            "envFile": env_path, "notes": notes,
        }}, ensure_ascii=False, indent=2))
        return EXIT_OK

    _print_dev_app_summary(app_id, project_type, source, client_id, secret, args.reveal_secret,
                           redirect_uri, homepage_url, domains, domain, setting, env_path, notes, args,
                           redirect_uris=redirect_uris, candidates=candidates)
    return EXIT_OK


def _write_dev_env(path, client_id, secret, redirect_uri, homepage_url,
                   app_id, project_type, setting):
    """
    写一个可直接 source 的 .env（0600）。★这是 secret 唯一允许落盘的地方，且必须显式要求。

    键名与 scaffold 生成的 .env.example 逐字一致 ⇒ 两边可以互相覆盖，不用改代码。
    """
    target = os.path.abspath(os.path.expanduser(path))
    parent = os.path.dirname(target)
    if parent:
        os.makedirs(parent, exist_ok=True)
    lines = [
        "# 由 nuwax-oauth2 dev-app 生成。★含 client_secret 明文：权限已收成 0600，别提交进 git。",
        "# ★部署在女娲 UserApp 上时不看这个文件：运行时环境变量只认 project.manifest.toml 的 [env]",
        "#   （明文、改完必须【重新发布】才生效，且会静默覆盖代码默认值）—— 把需要的键抄进 [env]，",
        "#   或让你的启动脚本 source 本文件。",
        "NUWAX_BASE=\"%s\"" % (env_or("PLATFORM_BASE_URL") or "https://REPLACE_ME"),
        "NUWAX_CLIENT_ID=\"%s\"" % (client_id or "nwxa_REPLACE_ME"),
        "NUWAX_CLIENT_SECRET=\"%s\"" % (secret or "nwxa_s_REPLACE_ME"),
        "NUWAX_REDIRECT_URI=\"%s\"" % (redirect_uri or "https://REPLACE_ME/oauth2/callback"),
        "NUWAX_SCOPE=\"%s\"" % ",".join(setting.get("scopes") or []),
        "# 平台侧应用标识（调管理面接口时用；UserApp 必须连 projectType 一起带）",
        "NUWAX_PROJECT_ID=\"%s\"" % app_id,
        "NUWAX_PROJECT_TYPE=\"%s\"" % project_type,
        "NUWAX_HOMEPAGE_URL=\"%s\"" % (homepage_url or ""),
        "",
    ]
    with open(target, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines))
    os.chmod(target, stat.S_IRUSR | stat.S_IWUSR)
    return target


def _print_dev_app_summary(app_id, project_type, source, client_id, secret, reveal,
                           redirect_uri, homepage_url, domains, domain, setting, env_path,
                           notes, args, redirect_uris=(), candidates=()):
    print("✅ 应用已定位并配好（appId=%s，类型 %s）" % (app_id, project_type))
    print("   定位依据：%s" % source)
    print()
    print("client_id   : %s" % (client_id or "(服务端没返回)"))
    print("client_secret: %s" % (secret if (reveal and secret) else _mask_secret(secret)))
    if reveal and secret:
        print("  ★明文只在此刻显示一次。本技能不缓存 secret，重跑本命令即可再取（服务端有审计日志）。")
    print()
    if candidates:
        print("本次登记的回调地址（按绑定域名，%d 条）：" % len(redirect_uris))
        for index, uri in enumerate(redirect_uris):
            kind = candidates[index][1] if index < len(candidates) else "?"
            print("   [%-6s] %s" % (kind, uri))
    primary = (setting.get("redirectUri") or "").strip()
    print("主回调地址  : %s" % (primary or "(空 —— 属应用属主，只由网页端配；AI 不写这一格)"))
    supplementary = _supplementary_uris(setting)
    if supplementary:
        print("补充回调地址: %d 条" % len(supplementary))
        for row in supplementary:
            print("   %s  [%s%s]" % (
                row.get("uri"),
                row.get("source") or "?",
                ("，by %s" % row.get("createdBy")) if row.get("createdBy") is not None else ""))
    else:
        print("补充回调地址: (无)")
    effective = _effective_redirect_uris(setting)
    print("生效地址集合: %s" % ("、".join(sorted(effective)) if effective
                               else "(空 ★授权码流程走不了：给 --redirect-uri，或给 --domain <域名>)"))
    for uri in redirect_uris:
        if uri and uri not in effective:
            print("   ⚠️ 本次要用的 %s 还没进生效集合" % uri)
    print("主页地址    : %s" % (homepage_url or "(未登记)"))
    print("已生效 scope: %s" % ("、".join(setting.get("scopes") or [])
                               or "(空 ⇒ 回落平台默认 profile)"))
    print("★本命令不碰 scope：范围请提前在平台上配好（提交申请要管理员审核，会卡住接入）。")
    print("  登记集不自动含默认 profile，而 /userinfo 强依赖它；authorize 的 scope 校验在真人登录后"
          "（匿名探测永远放行）⇒ 预检用 scope-check / token-m2m，别拿用户当探针。")
    if domains:
        print()
        print("应用绑定的域名（共 %d 条）：" % len(domains))
        for row in domains:
            name = str(row.get("domain")).lower()
            mark = " ← 登记了" if any(name == item[0] for item in candidates) else ""
            print("   %-9s %s%s" % (row.get("domainType"), row.get("domain"), mark))
    if env_path:
        print()
        print("凭据已写入 : %s（权限 0600）" % env_path)
        print("  键名与 scaffold 生成的 .env.example 一致，可直接 source。★别提交进 git。")
        print("  ★部署在女娲 UserApp 上时这份文件不被加载：运行时只认 project.manifest.toml 的 [env]，"
              "且改完要【重新发布】——该段是明文，不要把长期密钥写进去。")
    if notes:
        print()
        print("提示：")
        for note in notes:
            print("   · %s" % note)
    print()
    print("★返回体里的 projectId 可能是另一个编号（平台内部换算过），不是给后续调用用的。")
    print("  后续所有调用【继续用 appId=%s】+ projectType=%s，别拿那个数字再调，会打错行。"
          % (app_id, project_type))
    print()
    print("下一步：")
    print("  ① 问用户要对接哪些模块（仅认证 / 用户信息 / 会话 / 模型调用 / 工具调用…），")
    print("     没问清楚就生成代码等于白生成 —— scope 与端点都随模块变。")
    print("  ② 生成能落进他仓库的对接代码：")
    print("     python3 scripts/oauth2.py scaffold --stack all --out ./nuwax-integration"
          " --project-id %s%s" % (app_id, " --project-type UserApp" if project_type == "UserApp" else ""))
    print("  ③ 接入文档按模块给：references/integration_guide.md（流程）+ references/api/ 下对应模块文件"
          "（端点逐个，一个模块一个文件，别整目录加载）")
    _print_manifest_env_warning([os.getcwd()])
    print()
    print("（要给程序解析就重跑并加 --json；退出码：0 成功 / 1 失败 / 3 需要问用户）")


def cmd_client_list(args):
    records, error = list_third_apps(page_size=args.page_size)
    if error is not None:
        return emit_envelope(error, "client-list", args.json)
    if args.json:
        print(json.dumps({"code": "0000", "data": records}, ensure_ascii=False, indent=2))
        return EXIT_OK
    print("名下可接入 OAuth2 的项目（共 %d 个）" % len(records))
    print()
    print_items(records,
                "名下没有三方应用。要接入就创建一个新的：\n"
                "     python3 scripts/oauth2.py client-create --name \"我的系统\"\n"
                "   ⚠️  如果确实建过却查不到，很可能是沙箱 AK 归属用户与当前平台租户不一致。")
    if records:
        print("下一步：python3 scripts/oauth2.py client-get --project-id <projectId> --reveal-secret")
    return EXIT_OK


def cmd_client_get(args):
    project_id = args.project_id
    project_type = resolve_project_type(project_id, args.project_type)
    query = query_project_type(project_id, project_type)
    suffix = ("?" + urlencode(query)) if query else ""

    setting_env = call_sandbox("/user-project/oauth2/setting/%s%s" % (project_id, suffix))
    if str(setting_env.get("code")) not in CODE_OK:
        return emit_envelope(setting_env, "client-get", args.json)
    setting = setting_env.get("data") or {}

    info = None
    if project_type != "UserApp":
        # /info 只吃 user_project 主键、且不支持 projectType ⇒ 仅 ThirdApp 可用
        info_env = call_sandbox("/user-project/oauth2/info/%s" % project_id)
        if str(info_env.get("code")) in CODE_OK:
            info = info_env.get("data")

    secret = None
    if args.reveal_secret:
        secret, error = fetch_client_secret(project_id, project_type)
        if error is not None:
            return emit_envelope(error, "client-get --reveal-secret", args.json)

    if args.json:
        print(json.dumps({"code": "0000", "data": {"setting": setting, "info": info,
                                                  "clientSecret": secret}},
                         ensure_ascii=False, indent=2))
        return EXIT_OK
    print_client_get(setting, info, secret, project_id, project_type)
    return EXIT_OK


def cmd_client_create(args):
    payload = {}
    if args.space_id:
        payload["spaceId"] = args.space_id
    if args.name:
        payload["name"] = args.name
    if args.description:
        payload["description"] = args.description
    if args.icon:
        payload["icon"] = args.icon
    envelope = call_sandbox("/user-project/oauth2/create", method="POST", payload=payload)
    if args.json:
        print(json.dumps(envelope, ensure_ascii=False, indent=2))
        return classify(envelope)
    if str(envelope.get("code")) not in CODE_OK:
        return print_failure(envelope, "client-create")
    data = envelope.get("data") or {}
    print("✅ 三方应用已创建")
    print("projectId : %s" % data.get("projectId"))
    print("clientId  : %s" % data.get("clientId"))
    print("有 secret : %s" % data.get("hasClientSecret"))
    print()
    print("★回调地址还没登记 —— 授权码流程必须先把 redirect_uri 存上去（服务端要求精确匹配）：")
    print("   python3 scripts/oauth2.py client-setting --project-id %s \\" % data.get("projectId"))
    print("       --redirect-uri https://你的系统/callback")
    print("★取 secret 明文：client-get --project-id %s --reveal-secret" % data.get("projectId"))
    return EXIT_OK


def cmd_client_setting(args):
    scopes = None
    if args.scopes is not None:
        raw = args.scopes.strip()
        # ★空串是有意义的：表示「申请清空、回落平台默认 profile」，与「不传=不修改」不同
        scopes = [item.strip() for item in re.split(r"[,\s]+", raw) if item.strip()]

    payload = {"projectId": args.project_id, "projectType": args.project_type or None}
    changed = []
    if args.homepage_url is not None:
        if not args.homepage_url.strip():
            print("⚠️  --homepage-url 传空串不会清空它：服务端把空串折叠成「不修改」。已忽略。", file=sys.stderr)
        else:
            payload["homepageUrl"] = args.homepage_url.strip()
            changed.append("homepageUrl")
    # ★--redirect-uri 的语义是【加入补充列表】（append），不是覆盖主地址：
    #   主地址由网页端人工维护，脚本一改就会把人工配的值顶掉。
    if args.redirect_uri is not None:
        if not args.redirect_uri.strip():
            print("⚠️  --redirect-uri 传空串没有意义（本字段是追加动作）。已忽略。", file=sys.stderr)
        else:
            payload["addRedirectUris"] = [args.redirect_uri.strip()]
            changed.append("追加回调地址 %s" % args.redirect_uri.strip())
    for raw in args.add_redirect_uri or []:
        if not raw.strip():
            continue
        payload.setdefault("addRedirectUris", []).append(raw.strip())
        changed.append("追加回调地址 %s" % raw.strip())
    for raw in args.remove_redirect_uri or []:
        if not raw.strip():
            continue
        payload.setdefault("removeRedirectUris", []).append(raw.strip())
        changed.append("删除回调地址 %s" % raw.strip())
    if args.primary_redirect_uri is not None and args.primary_redirect_uri.strip():
        # ★本地就挡掉，不发这颗注定被拒的请求：主地址对沙箱（AI）一律冻结 —— 库里为空时也不放行，
        #   服务端会回 3353。这类请求发出去只会白跑一趟、还让调用方以为「可能是我参数没写对」。
        #   本脚本只走沙箱 AK，所以这个参数在这里 100% 不可能成功。主地址请让用户在网页端改。
        die("--primary-redirect-uri 不能用：主回调地址只由应用属主在网页端配置（沙箱/AI 一律不能写，"
            "库里为空时也不能，服务端会回 3353）。\n"
            "   你的回调地址请走补充列表：--redirect-uri <地址> 或 --add-redirect-uri <地址>"
            "（追加、幂等、只能删自己加的）。\n"
            "   ★生效集合取并集，主地址为空不影响授权 ⇒ AI 没有任何写它的需要。")
    if scopes is not None:
        payload["scopes"] = scopes
        changed.append("scopes（提交后进入待审核）")
    if not changed:
        die("没有任何要改的：至少给 --redirect-uri / --add-redirect-uri / --remove-redirect-uri / "
            "--homepage-url / --scopes 之一。\n"
            "   ★--scopes \"\" 表示申请清空并回落平台默认（profile），这也是一个有效改动。")

    envelope = call_sandbox("/user-project/oauth2/setting/save", method="POST", payload=payload)
    if args.json:
        print(json.dumps(envelope, ensure_ascii=False, indent=2))
        return classify(envelope)
    if str(envelope.get("code")) not in CODE_OK:
        return print_failure(envelope, "client-setting")
    data = envelope.get("data") or {}
    print("✅ 设置已保存：%s" % "、".join(changed))
    _print_effective_uris(data)
    skipped = data.get("skippedRedirectUriRemovals") or []
    if skipped:
        print("⚠️ 以下回调地址【没能删除】（不是本命令加的条目）：%s" % "、".join(skipped))
        print("   ★沙箱只能删自己加的（source=Sandbox）条目；人工配的要到网页端删。")
    print("主页地址  : %s" % (data.get("homepageUrl") or "(未登记)"))
    print("已生效    : %s" % ("、".join(data.get("scopes") or []) or "(空)"))
    if data.get("scopeApplyStatus") == "Pending":
        print("待审核    : %s" % ("、".join(data.get("pendingScopes") or []) or "(申请清空)"))
        print("★scope 变更需管理员审核；通过前应用仍按「已生效」那一行运行。")
    return EXIT_OK


def cmd_scopes(args):
    envelope = call_sandbox("/user-project/oauth2/scopes")
    if args.json:
        print(json.dumps(envelope, ensure_ascii=False, indent=2))
        return classify(envelope)
    if str(envelope.get("code")) not in CODE_OK:
        return print_failure(envelope, "scopes")
    rows = envelope.get("data") or []
    if not rows:
        print("（空）平台没有返回任何 scope。")
        return EXIT_OK
    print("平台支持的 scope（共 %d 个）" % len(rows))
    print()
    print("%-14s %-8s %s" % ("scope", "敏感", "说明"))
    print("-" * 92)
    for row in rows:
        print("%-14s %-8s %s" % (row.get("scope"), "是" if row.get("sensitive") else "否",
                                 row.get("description") or ""))
    print()
    print("★接口不返回「是否仅授权码」这一维，但下面 4 个 scope 机器对机器（client_credentials）拿不到，")
    print("  必须走真人授权（auth-url + token）：im:read、im:push、model:read、model:key。")
    print("  依据见 references/api_reference.md §2 scope 全集表。")
    print("★申请方式：client-setting --project-id <id> --scopes profile,chat:read")
    print("  提交后是【待管理员审核】状态，通过前不生效。")
    return EXIT_OK


def _platform_scope_names():
    """合法 scope 名全集。优先平台实时查，没有 AK / 查不到就退回 scaffold.py 内置表（离线可用）。"""
    _base, key = sandbox_env(required=False)
    if key:
        envelope = call_sandbox("/user-project/oauth2/scopes")
        if str(envelope.get("code")) in CODE_OK:
            names = [row.get("scope") for row in (envelope.get("data") or []) if row.get("scope")]
            if names:
                return set(names), "平台实时"
    try:
        return set(scaffold_module().known_scope_names()), "技能内置表（平台没查到，离线判定）"
    except Exception:
        return set(), None


def cmd_scope_check(args):
    """免真人登录的 scope 预检：名称是否合法 + 是否在应用登记集里。

    为什么需要它：authorize 的 scope 校验发生在【真人登录之后】（匿名 auth-url 探测永远放行，
    全是假阴性），而 token 端与 authorize 端口径不同。拿用户当探针去试错要重登、要发布，
    这个命令用沙箱 AK 静态判定，一次出结论。退出码：0 = 全部通过；1 = 有不过或查不到登记集。
    """
    requested = [item for item in re.split(r"[,\s]+", (args.scope or "").strip()) if item]
    if not requested:
        die("要用 --scope 给出待预检的 scope 串，例如：--scope profile,im:read,im:push")

    known, known_source = _platform_scope_names()

    project_id = args.project_id or dev_app_id()
    registered = None
    setting_error = None
    project_type = None
    _base, has_ak = sandbox_env(required=False)
    if project_id and not has_ak:
        setting_error = {"code": "-", "message": "环境里没有 SANDBOX_ACCESS_KEY"}
    elif project_id:
        project_type = resolve_project_type(project_id, args.project_type)
        query = query_project_type(project_id, project_type)
        suffix = ("?" + urlencode(query)) if query else ""
        envelope = call_sandbox("/user-project/oauth2/setting/%s%s" % (project_id, suffix))
        if str(envelope.get("code")) in CODE_OK:
            registered = list((envelope.get("data") or {}).get("scopes") or [])
        else:
            setting_error = envelope

    verdicts = []
    for name in requested:
        if known and name not in known:
            verdicts.append((name, "❌ 名称不合法",
                             "平台不认识这个名字（合法名全集：%s）；拼错的 scope 会被静默忽略或拒绝"
                             % ("、".join(sorted(known)) if known else "scopes 子命令查")))
        elif registered is None:
            verdicts.append((name, "❓ 无法判定", "查不到应用登记集，只能确认名称合法"))
        elif name in registered:
            verdicts.append((name, "✅ 已登记", "authorize / token 两端都认"))
        elif name == "profile":
            verdicts.append((name, "⚠️ 未登记",
                             "★token 端（M2M）实测登记后仍可授予 profile；但 authorize 的后置校验"
                             "疑似只认登记集（真实事故推断）⇒ 授权码登录流请让用户把 profile 一并申请"))
        else:
            verdicts.append((name, "⚠️ 未登记",
                             "authorize 登录后会拒（invalid_scope）⇒ 先在平台上申请，审核通过才生效"))

    findings = []
    findings.append("★上面判的是「平台登记集」，它只是前提：真正写进令牌的是**应用自己发出的那次授权请求**"
                    "带的 scope（三方 = --scope / 代码里的配置常量；UserApp = manifest `[env]` 的 NUWAX_SCOPE）"
                    "⇒ 登记了但请求里没带，令牌里同样没有、调用照旧 4030；反过来请求里只能带登记集里有的名字，"
                    "夹带未登记的会让整轮登录 invalid_scope 失败")
    if registered is not None:
        missing = [n for n in registered if n not in requested]
        if missing:
            findings.append("登记集里这些本次请求串没带（令牌里不会有）：%s" % "、".join(missing))
    if "profile" not in requested:
        findings.append("★请求串里没有 profile：/userinfo 强依赖它（令牌缺 profile ⇒ 4030，"
                        "登录流断在「取用户信息」）；纯机器对机器、不取身份可忽略")
    code_only = [n for n in requested
                 if n in ("im:read", "im:push", "model:read", "model:key")]
    if code_only:
        findings.append("%s 机器对机器拿不到，只能走「代表真人登录」的授权码流程"
                        % "、".join(code_only))
    if setting_error is not None:
        findings.append("查应用登记集失败（code=%s message=%s）⇒ 只能做名称合法性判定；"
                        "带 --project-id / --project-type 重试"
                        % (setting_error.get("code"), setting_error.get("message")))
    if not project_id:
        findings.append("没有 DEV_APP_ID 也没给 --project-id ⇒ 没查登记集，只判了名称合法性")

    failed = any(not status.startswith("✅") for _n, status, _r in verdicts)

    if args.json:
        print(json.dumps({"code": "0000" if not failed else "4001", "data": {
            "projectId": project_id, "projectType": project_type,
            "knownSource": known_source, "registered": registered,
            "verdicts": [{"scope": n, "status": s, "reason": r} for n, s, r in verdicts],
            "findings": findings,
        }}, ensure_ascii=False, indent=2))
        return EXIT_USAGE if failed else EXIT_OK

    print("🔎 scope 预检（合法名来源：%s）" % (known_source or "不可用"))
    if registered is not None:
        print("   应用登记集: %s" % ("、".join(registered) or "(空 ⇒ 回落平台默认 profile)"))
    print()
    for name, status, reason in verdicts:
        print("   %-14s %s" % (name, status))
        if not status.startswith("✅"):
            print("      %s" % reason)
    if findings:
        print()
        for finding in findings:
            print("   · %s" % finding)
    print()
    print("★校验时点（实测结论，别再拿匿名探测下结论）：")
    print("  · authorize 的 scope 校验发生在【真人登录之后】⇒ auth-url 匿名探测永远放行，是假阴性；")
    print("  · token 端实时校验、可离线鉴证：token-m2m --scope %s（接受 ⇒ 名称合法）；"
          % ",".join(requested))
    print("  · 两端允许集口径不一致（token 端含默认 profile、authorize 端疑似不含）⇒ 授权码流程以登记集为准。")
    return EXIT_USAGE if failed else EXIT_OK


def _redirect_hint_key(uri):
    """把地址压成「忽略协议 / 默认端口 / 结尾斜杠 / 大小写」的形态——**只用于给差异提示**，判定仍逐字。"""
    text = (uri or "").strip().rstrip("/")
    text = re.sub(r"^[A-Za-z][A-Za-z0-9+.-]*://", "", text, count=1)
    text = re.sub(r":(443|80)(?=/|$)", "", text)
    return text.lower()


def _redirect_mismatch_reason(uri, effective):
    for candidate in sorted(effective):
        if _redirect_hint_key(candidate) == _redirect_hint_key(uri):
            return ("与登记值 %s 只差【协议 / 默认端口 / 结尾斜杠 / 大小写】——服务端是逐字 equals，"
                    "这些差别都会被拒" % candidate)
    return ("生效集合里没有相近的地址 ⇒ 逐字对照上面的集合；确认确实没登记就用 "
            "client-setting --redirect-uri '%s' 加进补充列表" % uri)


def cmd_redirect_check(args):
    """免真人登录的回调地址预检：与应用的【生效集合】逐字比对。

    为什么需要它：authorize 端点拿请求里的 redirect_uri 与登记值做字符串精确比对（equals，
    不是前缀匹配），差一个结尾斜杠 / http 与 https / 显式端口 / 多一个 query 都会被拒，
    而报错只有一句 invalid_request。本命令用沙箱 AK 静态读回生效集合逐条判定。
    退出码：0 = 全部在集合内；1 = 有不在集合内，或查不到登记集（判定不出）。
    """
    targets = []
    for raw in args.redirect_uri or []:
        targets.extend(item for item in re.split(r"[,\s]+", (raw or "").strip()) if item)
    if not targets:
        die("要用 --redirect-uri 给出待预检的回调地址，例如："
            "--redirect-uri https://my.app/oauth2/callback")

    project_id = args.project_id or dev_app_id()
    setting = None
    setting_error = None
    project_type = None
    _base, has_ak = sandbox_env(required=False)
    if not project_id:
        setting_error = {"code": "-", "message": "没有 DEV_APP_ID 也没给 --project-id"}
    elif not has_ak:
        setting_error = {"code": "-", "message": "环境里没有 SANDBOX_ACCESS_KEY"}
    else:
        project_type = resolve_project_type(project_id, args.project_type)
        query = query_project_type(project_id, project_type)
        suffix = ("?" + urlencode(query)) if query else ""
        envelope = call_sandbox("/user-project/oauth2/setting/%s%s" % (project_id, suffix))
        if str(envelope.get("code")) in CODE_OK:
            setting = envelope.get("data") or {}
        else:
            setting_error = envelope

    effective = _effective_redirect_uris(setting) if setting is not None else set()
    verdicts = []
    for uri in targets:
        if setting is None:
            verdicts.append((uri, "❓ 无法判定", "没读到应用的生效集合，没法比对"))
        elif uri in effective:
            verdicts.append((uri, "✅ 已登记", "授权时传它不会因「地址不一致」被拒"))
        else:
            verdicts.append((uri, "❌ 不在集合内", _redirect_mismatch_reason(uri, effective)))

    findings = []
    if setting is not None:
        if not effective:
            findings.append("★生效集合为空 ⇒ 授权码流程现在【一定走不通】（授权端回 3347）；"
                            "先登记一个：client-setting --redirect-uri <地址>")
        if not (setting.get("redirectUri") or "").strip():
            findings.append("主回调地址为空是合法状态：AI / 沙箱只往补充列表写，"
                            "要动主地址得让应用属主在网页端改")
        if any(re.match(r"^https?://(127\.0\.0\.1|localhost)(:\d+)?(/|$)", u) for u in targets):
            findings.append("本地回环地址平台不禁止登记，但别在生产沿用")
    if setting_error is not None:
        findings.append("查应用生效集合失败（code=%s message=%s）⇒ 没比对登记集"
                        % (setting_error.get("code"), setting_error.get("message")))

    failed = any(not status.startswith("✅") for _u, status, _r in verdicts)

    if args.json:
        print(json.dumps({"code": "0000" if not failed else "4001", "data": {
            "projectId": project_id, "projectType": project_type,
            "primaryUri": (setting or {}).get("redirectUri"),
            "supplementary": [row.get("uri") for row in _supplementary_uris(setting or {})],
            "effective": sorted(effective),
            "verdicts": [{"uri": u, "status": s, "reason": r} for u, s, r in verdicts],
            "findings": findings,
        }}, ensure_ascii=False, indent=2))
        return EXIT_USAGE if failed else EXIT_OK

    print("🔎 回调地址预检（服务端逐字 equals，不是前缀匹配）")
    if setting is not None:
        _print_effective_uris(setting)
        print()
    for uri, status, reason in verdicts:
        print("   %-12s %s" % (status, uri))
        if not status.startswith("✅"):
            print("      %s" % reason)
    if findings:
        print()
        for finding in findings:
            print("   · %s" % finding)
    print()
    print("★退出码：0 = 全部在生效集合内；1 = 有不在集合内或无法判定。")
    print("  · 现场报错长这样：授权端点回 invalid_request: 回调地址与登记的不一致"
          "（集合整体为空则是 3347）；")
    print("  · 补登记（不覆盖主地址）：client-setting --project-id %s --redirect-uri <地址>"
          % (project_id or "<id>"))
    return EXIT_USAGE if failed else EXIT_OK


def cmd_regenerate(args):
    if not args.yes:
        die("重置凭证是破坏性操作，必须显式加 --yes。\n"
            "   ★旧的 client_secret 会【立即失效】：所有正在用它换令牌的集成会当场断掉。\n"
            "   新密钥明文只在本次响应里出现一次，请确认你能立刻保存它。")
    project_type = resolve_project_type(args.project_id, args.project_type)
    query = query_project_type(args.project_id, project_type)
    suffix = ("?" + urlencode(query)) if query else ""
    envelope = call_sandbox("/user-project/oauth2/credential/regenerate/%s%s" % (args.project_id, suffix),
                            method="POST", payload={})
    if args.json:
        print(json.dumps(envelope, ensure_ascii=False, indent=2))
        return classify(envelope)
    if str(envelope.get("code")) not in CODE_OK:
        return print_failure(envelope, "regenerate")
    data = envelope.get("data") or {}
    print("✅ 凭证已重置（旧 client_secret 即刻失效）")
    print("clientId    : %s" % data.get("clientId"))
    print("clientSecret: %s" % data.get("clientSecret"))
    print()
    print("★请立即保存上面这行密钥 —— 平台不再重复展示（可用 client-get --reveal-secret 重取）。")
    cache_drop(TOKEN_FILE)
    print("★本地令牌缓存已清除：旧凭证签发的令牌不再可用。")
    return EXIT_OK


def cmd_auth_url(args):
    base = platform_base()
    client_id = args.client_id or env_or("NUWAX_OAUTH2_CLIENT_ID")
    redirect_uri = args.redirect_uri or env_or("NUWAX_OAUTH2_REDIRECT_URI")
    scope = args.scope
    project_id = args.project_id or env_or("NUWAX_OAUTH2_PROJECT_ID")
    project_type = args.project_type

    # 缺 client_id 或 redirect_uri 时，用沙箱 AK 从应用配置里补
    if (not client_id or not redirect_uri) and not args.no_sandbox:
        sandbox_env()
        if project_id:
            project_type = resolve_project_type(project_id, project_type)
        else:
            row = pick_single_project("没给 --client-id / --redirect-uri，也没给 --project-id")
            project_id = row.get("projectId")
            project_type = row.get("projectType") or "ThirdApp"
        query = query_project_type(project_id, project_type)
        suffix = ("?" + urlencode(query)) if query else ""
        envelope = call_sandbox("/user-project/oauth2/setting/%s%s" % (project_id, suffix))
        if str(envelope.get("code")) not in CODE_OK:
            return emit_envelope(envelope, "auth-url", args.json)
        data = envelope.get("data") or {}
        client_id = client_id or data.get("clientId")
        redirect_uri = redirect_uri or data.get("redirectUri")
        registered = data.get("scopes") or []
        if scope and registered:
            # ★本地先拦一道：服务端会 302 回 invalid_scope，但错误藏在浏览器跳转里很难看见
            unknown = [item for item in re.split(r"[,\s]+", scope.strip()) if item and item not in registered]
            if unknown:
                print("⚠️  这些 scope 没在应用上登记，授权会被服务端以 invalid_scope 拒绝：%s"
                      % "、".join(unknown), file=sys.stderr)
                print("   已生效的是：%s" % ("、".join(registered) or "(空)"), file=sys.stderr)
                print("   要加就先申请：client-setting --project-id %s --scopes %s（需管理员审核）"
                      % (project_id, scope), file=sys.stderr)

    if not client_id:
        die("拿不到 client_id：请用 --client-id 指定，或在沙箱里运行让脚本自动定位。")
    if not redirect_uri:
        die("拿不到 redirect_uri：应用还没登记回调地址。\n"
            "   先登记：python3 scripts/oauth2.py client-setting --project-id %s \\\n"
            "              --redirect-uri https://你的系统/callback\n"
            "   ★服务端要求回调地址【精确匹配】（不是前缀匹配），登记的与授权时传的必须逐字相同。"
            % (project_id or "<projectId>"))

    verifier, challenge = make_pkce()
    state = b64url(secrets.token_bytes(16))
    url = build_authorize_url(base, client_id, redirect_uri, scope, state, challenge)

    cache_save(PENDING_FILE, {
        "state": state,
        "code_verifier": verifier,
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "scope": scope,
        "project_id": project_id,
        "project_type": project_type,
        "created_at": int(time.time()),
        "authorize_url": url,
    })

    if args.json:
        print(json.dumps({"state": state, "code_verifier": verifier,
                          "code_challenge": challenge, "authorize_url": url},
                         ensure_ascii=False, indent=2))
        return EXIT_OK

    print("✅ 授权地址已生成（PKCE S256）")
    print()
    print(url)
    print()
    print("下一步（★必须由真人在浏览器里完成，脚本无法代劳）：")
    print("  ① 把上面这条地址发给用户，让他在浏览器里打开；未登录会先被送到平台登录页，")
    print("     登录后自动回到授权流程（平台当前无同意页，授权静默通过）。")
    print("  ② 浏览器会跳到你的回调地址，形如 %s?code=...&state=%s" % (redirect_uri, state))
    print("  ③ 把【整条回调地址】贴回来换令牌：")
    print("     python3 scripts/oauth2.py token --callback-url \"<整条回调地址>\"")
    print("     （也可以只给 code：token --code <code>）")
    print()
    print("★state 与 code_verifier 已缓存到 ~/%s/%s（0600），token 子命令会自动取用并校验 state。"
          % (CACHE_DIR_NAME, PENDING_FILE))
    print("★授权码只有 5 分钟有效期、且一次性 —— 拿到后尽快换令牌。")
    return EXIT_OK


def cmd_token(args):
    pending = cache_load(PENDING_FILE) or {}
    code = args.code
    state = args.state

    if args.callback_url:
        parsed = urlparse(args.callback_url)
        query = dict(parse_qsl(parsed.query))
        code = code or query.get("code")
        state = state or query.get("state")
        if not code and parsed.fragment:
            code = dict(parse_qsl(parsed.fragment)).get("code")
        if query.get("error"):
            die("回调地址里带的是错误，不是授权码 —— %s: %s\n"
                "  ★invalid_scope = 申请的 scope 没在应用上登记；"
                "unsupported_response_type = response_type 不是 code。"
                % (query["error"], query.get("error_description") or ""))
        if not code:
            die("从回调地址里解析不出 code。请确认你贴的是完整的跳转后地址（含 ?code=...）。")

    if not code:
        die("必须给 --code 或 --callback-url 之一。\n"
            "   先跑 auth-url 生成授权地址，在浏览器里登录，再把回调地址贴回来。")

    # state 校验：防 CSRF。缓存丢了只警告不阻断（用户可能手工管理 state）
    expected_state = state or pending.get("state")
    if state and pending.get("state") and state != pending["state"]:
        die("state 不匹配！回调里的 state=%s，本地缓存的是 %s。\n"
            "   ★这通常意味着这次回调不属于本次授权请求（CSRF 风险），或你混用了两次 auth-url。\n"
            "   确认无误后可用 --force-state 跳过本检查。" % (state, pending["state"]))
    if args.force_state:
        print("⚠️  已按 --force-state 跳过 state 校验。", file=sys.stderr)

    client_id = args.client_id or pending.get("client_id") or env_or("NUWAX_OAUTH2_CLIENT_ID")
    redirect_uri = args.redirect_uri or pending.get("redirect_uri") or env_or("NUWAX_OAUTH2_REDIRECT_URI")
    verifier = args.code_verifier or pending.get("code_verifier")

    if not client_id:
        die("拿不到 client_id：pending 缓存里没有，也没显式传。请加 --client-id。")
    if not redirect_uri:
        die("拿不到 redirect_uri：token 阶段必须传，且要与 authorize 阶段【逐字相等】。请加 --redirect-uri。")
    if not verifier:
        print("⚠️  没有 code_verifier（pending 缓存可能被清过）。若授权时带了 PKCE，本次换令牌会以",
              file=sys.stderr)
        print("   invalid_grant / code_verifier is missing or invalid 失败；重跑 auth-url 再走一遍。",
              file=sys.stderr)

    client_secret = args.client_secret or env_or("NUWAX_OAUTH2_CLIENT_SECRET")
    if not client_secret:
        project_id = args.project_id or pending.get("project_id") or env_or("NUWAX_OAUTH2_PROJECT_ID")
        project_type = args.project_type or pending.get("project_type")
        if not project_id:
            row = pick_single_project("token 需要 client_secret，但没给 --client-secret 也没给 --project-id")
            project_id = row.get("projectId")
            project_type = row.get("projectType") or "ThirdApp"
        else:
            project_type = resolve_project_type(project_id, project_type)
        client_secret, error = fetch_client_secret(project_id, project_type)
        if error is not None:
            die("取 client_secret 失败 —— %s" % error.get("message"))
    else:
        project_id, project_type = args.project_id, args.project_type

    body = call_oauth2_form("/token", {
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": redirect_uri,
        "code_verifier": verifier,
        "client_id": client_id,
        "client_secret": client_secret,
    })
    if args.json:
        print(json.dumps(body, ensure_ascii=False, indent=2))
    if rfc_failed(body, need="access_token"):
        return print_rfc_failure(body, "token")
    store_token(body, client_id, project_id, project_type)
    cache_drop(PENDING_FILE)
    if not args.json:
        print_token(body, "授权码模式")
        print()
        print("★这套令牌代表【授权用户本人】：能以他的身份调门面接口，也能拿到 im:* / model:* 这类")
        print("  仅授权码可用的 scope。刷新令牌 30 天有效，每次刷新会轮换（旧的立即作废）。")
    return EXIT_OK


def cmd_token_m2m(args):
    client_id, client_secret, project_id, project_type, source = resolve_client_credential(args)
    body = call_oauth2_form("/token", {
        "grant_type": "client_credentials",
        "client_id": client_id,
        "client_secret": client_secret,
        "scope": args.scope,
    })
    if args.json:
        print(json.dumps(body, ensure_ascii=False, indent=2))
    if rfc_failed(body, need="access_token"):
        return print_rfc_failure(body, "token-m2m")
    store_token(body, client_id, project_id, project_type)
    if not args.json:
        print_token(body, "机器对机器 client_credentials")
        print()
        print("★凭据来源：%s" % source)
        print("★这套令牌【不代表任何真人】，因此拿不到仅授权码可用的 scope：")
        print("  im:read、im:push、model:read、model:key。要这些能力请走 auth-url + token。")
        print("★不签发 refresh_token：过期（默认 2 小时）后重跑本命令即可。")
    return EXIT_OK


def cmd_refresh(args):
    cached = cache_load(TOKEN_FILE) or {}
    refresh = args.refresh_token or cached.get("refresh_token") or env_or("NUWAX_OAUTH2_REFRESH_TOKEN")
    if not refresh:
        die("没有 refresh_token：缓存里没有，也没显式传。\n"
            "   ★client_credentials 模式不签发 refresh_token，只有授权码模式有。")
    client_id = args.client_id or cached.get("client_id") or env_or("NUWAX_OAUTH2_CLIENT_ID")
    client_secret = args.client_secret or env_or("NUWAX_OAUTH2_CLIENT_SECRET")
    project_id = args.project_id or cached.get("project_id")
    project_type = args.project_type or cached.get("project_type")
    if not client_id or not client_secret:
        client_id, client_secret, project_id, project_type, _ = resolve_client_credential(args)

    body = call_oauth2_form("/token", {
        "grant_type": "refresh_token",
        "refresh_token": refresh,
        "client_id": client_id,
        "client_secret": client_secret,
    })
    if args.json:
        print(json.dumps(body, ensure_ascii=False, indent=2))
    if rfc_failed(body, need="access_token"):
        return print_rfc_failure(body, "refresh")
    store_token(body, client_id, project_id, project_type)
    if not args.json:
        print_token(body, "refresh_token 续期")
        print()
        print("★refresh_token 已轮换：上面这个新值才是有效的，旧的已作废。")
    return EXIT_OK


def cmd_revoke(args):
    cached = cache_load(TOKEN_FILE) or {}
    token = args.token or cached.get("access_token") or env_or("NUWAX_OAUTH2_ACCESS_TOKEN")
    if not token:
        die("没有要撤销的令牌：缓存里没有，也没显式传 --token。")
    client_id, client_secret, _, _, _ = resolve_client_credential(args)
    body = call_oauth2_form("/revoke", {
        "token": token,
        "token_type_hint": args.token_type_hint,
        "client_id": client_id,
        "client_secret": client_secret,
    })
    if args.json:
        print(json.dumps(body, ensure_ascii=False, indent=2))
    if rfc_failed(body):
        return print_rfc_failure(body, "revoke")
    cache_drop(TOKEN_FILE)
    if not args.json:
        print("✅ 撤销请求已被接受（HTTP 200）")
        print()
        print("★按 RFC 7009，撤销端点【无论令牌是否有效都返回 200 与空对象】——")
        print("  所以这个 ✅ 只说明「请求被接受」，不证明那个令牌真的存在过。")
        print("★撤销是【用户级】的：该 (应用, 用户) 名下所有早于此刻签发的令牌一并失效，")
        print("  refresh_token 续期也会被拒（the authorization has been revoked），需要重新授权。")
        print("★本地令牌缓存已清除。")
    return EXIT_OK


def cmd_userinfo(args):
    token, source = resolve_access_token(args)
    # ★/userinfo 回裸对象（sub/user_id/name/avatar/tenant_id），没有 code/message/data 外壳。
    #   所以不能按信封去取 data —— 那不但字段全是 None，还会把正常响应判成「未知错误」。别改回去。
    body = call_oauth2_api("/userinfo", token)
    if args.json:
        print(json.dumps(body, ensure_ascii=False, indent=2))
        return classify(body)
    if is_envelope(body):
        # 失败走这里：本端点的异常由平台统一异常加工成信封（★HTTP 仍是 200）
        return print_failure(body, "userinfo")
    status = body.get("statusCode")
    # 既不是信封、又不像本端点的用户对象（身份字段一个都没有）= 拿到的不是正常响应。
    # ★这道防线不能省：gateway 错误页/被截断的响应常是 HTTP 200 且没有 code，
    #   只看状态码会把「一页 HTML」渲染成 ✅ 再拿去当登录身份用。
    if is_unparsed(body) or not ("sub" in body or "user_id" in body):
        print("❌ userinfo 失败")
        print("httpStatus: %s" % (status if status is not None else "(未拿到)"))
        print("顶层字段: %s" % (", ".join(sorted(body)) or "(无)"))
        print("★ 既不是平台信封、也没有 sub/user_id ⇒ 这不是本端点的正常响应"
              "（网关错误页 / 响应被截断），不能当身份用。")
        return EXIT_USAGE
    data = body
    print("✅ 授权用户信息（令牌来源：%s）" % source)
    print("sub       : %s" % data.get("sub"))
    print("user_id   : %s" % data.get("user_id"))
    print("name      : %s" % data.get("name"))
    print("avatar    : %s" % (data.get("avatar") or "(无)"))
    print("tenant_id : %s" % data.get("tenant_id"))
    print()
    print("★这就是「用女娲账号登录」在你系统里拿到的身份：sub / user_id 是你该存的稳定标识。")
    return EXIT_OK


def cmd_call(args):
    token, source = resolve_access_token(args)
    query = {}
    for pair in args.query or []:
        if "=" not in pair:
            die("--query 的每一项都要是 key=value 形式，收到：%r" % pair)
        key, value = pair.split("=", 1)
        query[key.strip()] = value
    body = None
    if args.body:
        raw = args.body.strip()
        if raw.startswith("@"):
            try:
                with open(raw[1:], "r", encoding="utf-8") as handle:
                    body = json.load(handle)
            except OSError as exc:
                die("--body 读文件失败: %s" % exc)
            except ValueError as exc:
                die("--body 文件不是合法 JSON: %s" % exc)
        else:
            try:
                body = json.loads(raw)
            except ValueError as exc:
                die("--body 不是合法 JSON: %s" % exc)
    elif args.method.upper() == "POST":
        # ★工具执行类端点的 params 就是整个请求体，无参时必须给 {}，不能不给 body
        body = {}

    path = args.path
    if not path.startswith("/"):
        path = OAUTH2_PREFIX + "/" + path
    envelope = call_oauth2_api(path, token, method=args.method.upper(), query=query, body=body)
    if args.json:
        print(json.dumps(envelope, ensure_ascii=False, indent=2))
        return classify(envelope)
    if not is_envelope(envelope):
        # ★裸对象端点（/userinfo、/user/search）：响应体本身就是数据，没有 code 可判，
        #   过码闸只会把它判成失败。
        # ★空对象 {} 要通过：/user/search 查无此人就回 {}，那是刻意的正常语义（防枚举）。
        #   只挡「根本不是 JSON」的那种（网关页 / 被截断），别把正常空结果也判成失败。
        if envelope.get("statusCode") not in (200, None) or is_unparsed(envelope):
            print("❌ call %s 失败" % path)
            print("httpStatus: %s" % (envelope.get("statusCode") if envelope.get("statusCode") is not None else "(未拿到)"))
            print("顶层字段: %s" % (", ".join(sorted(envelope)) or "(无)"))
            print("message: %s" % (envelope.get("message") or "(空响应体)"))
            print("★ 既不是平台信封、也不是裸对象 ⇒ 这不是本端点的正常响应（网关错误页 / 响应被截断）。")
            return EXIT_USAGE
        print("✅ %s %s（令牌来源：%s）" % (args.method.upper(), path, source))
        # statusCode 是脚本自己塞进去的，不属于响应体，回显「数据本身」时要去掉
        print(json.dumps({k: v for k, v in envelope.items() if k != "statusCode"},
                         ensure_ascii=False, indent=2, default=str))
        print()
        print("★ 这是裸对象端点（没有平台信封）：上面的内容就是数据本身，"
              "/userinfo 与 /user/search 都是这类，别按信封去取 code / data。")
        return EXIT_OK
    if str(envelope.get("code")) not in CODE_OK:
        return print_failure(envelope, "call %s" % path)
    data = envelope.get("data")
    print("✅ %s %s（令牌来源：%s）" % (args.method.upper(), path, source))
    if data is None:
        print("data: (无 —— 这类端点成功时不返回内容)")
    else:
        print(json.dumps(data, ensure_ascii=False, indent=2))
    # 工具执行类端点的业务成败在 data.success 里，外层 code 恒 0000
    if isinstance(data, dict) and "success" in data and data.get("success") is False:
        print()
        print("⚠️  ★外层 code=0000 但 data.success=false：这次执行【失败】了。")
        print("   error: %s" % (data.get("error") or data.get("message") or "(未给)"))
    return EXIT_OK


def classify(body):
    """
    响应 → 退出码。

    ★没有 code 的响应要分两种情况：HTTP 正常（或拿不到状态码）= 拿到了裸对象端点的数据，成功；
      HTTP 不正常 = 拿到的不是本端点的正常响应（网关错误页 / 响应被截断），必须算失败 ——
      否则一页 HTML 会被渲染成 ✅ 并拿去当身份用。
    """
    if not is_envelope(body):
        if is_unparsed(body):
            return EXIT_USAGE
        return EXIT_OK if body.get("statusCode") in (200, None) else EXIT_USAGE
    return EXIT_OK if str(body.get("code", "")) in CODE_OK else EXIT_USAGE


def emit_envelope(envelope, action, json_mode):
    if json_mode:
        print(json.dumps(envelope, ensure_ascii=False, indent=2))
        return classify(envelope)
    return print_failure(envelope, action)


# ---------------------------------------------------------------- 对接代码生成

_SCAFFOLD = None


def scaffold_module():
    """
    惰性载入同目录的 scaffold.py（模板与生成逻辑都在那边）。

    ★刻意不做顶层 import：scaffold 只服务 scaffold 这一个子命令，
      顶层 import 一旦失败会把其余子命令一起拖下水。
    """
    global _SCAFFOLD
    if _SCAFFOLD is not None:
        return _SCAFFOLD
    here = os.path.dirname(os.path.abspath(__file__))
    if here not in sys.path:
        sys.path.insert(0, here)
    try:
        import scaffold as module
    except ImportError as exc:
        die("找不到 scaffold.py（应与 oauth2.py 同在 %s）—— %s" % (here, exc))
    _SCAFFOLD = module
    return module


def scaffold_context(args):
    """
    组装生成代码要预填的参数，返回 (ctx, notes)。

    优先级：显式参数 > 沙箱 AK 现查（★只读 setting，绝不取 secret）> 可见的占位符。
    ★自动查失败一律降级成占位符 + 一条 note，不 die：生成对接代码是离线能力，
      不该因为管理面白名单没生效就跑不了。
    """
    module = scaffold_module()
    notes = []

    base = (args.base_url or "").strip() or (os.getenv("PLATFORM_BASE_URL") or "").rstrip("/")
    ctx = {
        "base": base or "https://REPLACE_ME",
        "client_id": (args.client_id or "").strip(),
        "redirect": (args.redirect_uri or "").strip(),
        "scope": (args.scope or "").strip(),
        # ★DEV_APP_ID 兜底：在「开发某个全栈应用」的沙箱里，不给 --project-id 也能预填对
        "project_id": args.project_id or dev_app_id(),
        "homepage": "",
        "scope_status": "(未查询)",
        "granted_scopes": [],
        # 生效集合（主地址 ∪ 补充列表）；只在查到平台配置时被填充，没查到就是空列表
        "effective_redirects": [],
        "package": args.package,
    }
    if not base:
        notes.append("没有 PLATFORM_BASE_URL 也没给 --base-url ⇒ 基址填了占位符，生成后要自己改")

    _base_env, key = sandbox_env(required=False)
    if args.no_sandbox:
        notes.append("--no-sandbox：没去平台查应用配置，参数按你显式给的 + 占位符生成")
    elif not key:
        notes.append("环境里没有 SANDBOX_ACCESS_KEY ⇒ 没去平台查应用配置，"
                     "client_id / 回调地址 / scope 用占位符（生成后自己替换）")
    else:
        _autofill_from_sandbox(ctx, args, notes)

    # scope 拼错会静默少给权限（服务端按登记集取交集，不认识的名字直接忽略），所以这里就拦
    if ctx["scope"]:
        known = set(module.known_scope_names())
        asked = [item for item in re.split(r"[,\s]+", ctx["scope"]) if item]
        unknown = [item for item in asked if item not in known]
        if unknown:
            notes.append("⚠️ 这些 scope 平台不认识，会被静默忽略：%s（可用：scopes 子命令查全表）"
                         % "、".join(unknown))
        ctx["scope"] = ",".join(asked)
        if "profile" not in asked:
            notes.append("★--scope 里没有 profile：/userinfo 强依赖它（令牌缺 profile ⇒ 4030）。"
                         "要生成授权码登录流就必须加上；纯机器对机器、不取身份的调用可忽略")

    for field, label in (("client_id", "client_id"), ("redirect", "redirect_uri")):
        if not ctx[field]:
            notes.append("★%s 还是占位符 —— 生成物能编译但跑不通，务必替换" % label)
    return ctx, notes


def _autofill_from_sandbox(ctx, args, notes):
    """用沙箱 AK 查应用配置来预填。任何一步失败都只记 note，不中断。"""
    project_id = ctx["project_id"]
    project_type = args.project_type
    if project_id and not args.project_id and not project_type:
        # 这个 id 来自 DEV_APP_ID：注入规则决定了它一定是全栈应用，直接定死类型，
        # 省掉一次 page-query（也避免列表查不到时误判成 ThirdApp）。
        project_type = "UserApp"
        notes.append("projectId 取自环境变量 DEV_APP_ID=%s（全栈应用）" % project_id)
    row = None
    if not project_id:
        records, error = list_third_apps()
        if error is not None:
            notes.append("查名下应用失败（code=%s message=%s）⇒ 用占位符。"
                         "★若是 4040 / No static resource，那是这条路径没对沙箱开放，"
                         "不是脚本问题"
                         % (error.get("code"), error.get("message")))
            return
        if not records:
            notes.append("名下没有任何三方应用 ⇒ 用占位符。先 client-create --name \"我的系统\" 建一个")
            return
        if len(records) > 1:
            listing = "；".join("%s(%s)" % (r.get("projectId"), r.get("name") or "未命名")
                                for r in records[:10])
            notes.append("名下有 %d 个应用，没法自动决定 ⇒ 用占位符。加 --project-id 指定其一：%s"
                         % (len(records), listing))
            return
        row = records[0]
        project_id = row.get("projectId")
        project_type = project_type or row.get("projectType")

    # ★显式给了 --project-id 也要判定类型：UserApp 的 projectId 是 ref 表主键，
    #   服务端要靠 projectType=UserApp 走 exchangeToPK 换回真主键，传错只会得到
    #   「项目不存在或无权访问」，看不出真因。
    project_type = resolve_project_type(project_id, project_type)
    query = query_project_type(project_id, project_type)
    suffix = ("?" + urlencode(query)) if query else ""
    envelope = call_sandbox("/user-project/oauth2/setting/%s%s" % (project_id, suffix))
    if str(envelope.get("code")) not in CODE_OK:
        notes.append("查应用配置失败（code=%s message=%s）⇒ 用占位符。"
                     "★4040 = 这条路径没对沙箱开放；「项目不存在或无权访问」= projectType 传错"
                     % (envelope.get("code"), envelope.get("message")))
        return

    setting = envelope.get("data") or {}
    ctx["project_id"] = project_id
    ctx["client_id"] = ctx["client_id"] or (setting.get("clientId") or "")
    # ★预填看【生效集合】而不是主地址：AI 自己登记的回调地址全在补充列表里，主地址那一格
    #   按规则永远是空的（它只属网页端）。只读 redirectUri 的话预填必然是空的，生成的对接代码
    #   里 redirect_uri 就成了占位符，开发者还得自己改一遍——而平台上其实早就配好了。
    #   顺序：主地址（人工配过就用它）→ 补充列表首条 → 留空走占位符。
    ctx["redirect"] = ctx["redirect"] or (setting.get("redirectUri") or "").strip() \
        or _first_supplementary_uri(setting)
    ctx["effective_redirects"] = sorted(_effective_redirect_uris(setting))
    ctx["homepage"] = setting.get("homepageUrl") or ""
    granted = list(setting.get("scopes") or [])
    ctx["granted_scopes"] = granted
    if not ctx["scope"]:
        scope_list = list(granted)
        # ★ /userinfo 强依赖 profile（令牌里没有 ⇒ 4030，登录流断在「取用户信息」一步），
        #   而平台登记集常恰好不含 profile（它是默认 scope，申请时容易漏）。照抄登记集预填
        #   会复刻「登记 scope 后登录反而挂了」，所以这里补在最前面。
        if scope_list and "profile" not in scope_list:
            scope_list.insert(0, "profile")
            notes.append("★登记集里没有 profile，但登录流的 /userinfo 离不开它 ⇒ 预填已补成 "
                         "profile,%s。若 authorize 报 invalid_scope（后置校验只认登记集），"
                         "让用户在平台上把 profile 一并申请" % ",".join(granted))
        ctx["scope"] = ",".join(scope_list)

    # ★实测：没有待审核申请时 scopeApplyStatus 是 null（不是 Approved），
    #   所以 null 要渲染成「无待审核变更」而不是「未知」，否则看着像查询失败。
    status = setting.get("scopeApplyStatus")
    label = {"Pending": "⏳ 有待审核的变更", "Rejected": "❌ 上次申请被拒",
             "Approved": "✅ 申请已通过"}.get(status) or (
                 str(status) if status else "无待审核变更（已生效那一行就是全部）")
    if setting.get("pendingScopes"):
        label += "；待审核 %s" % "、".join(setting["pendingScopes"])
    if setting.get("scopeRejectReason"):
        label += "；拒绝原因 %s" % setting["scopeRejectReason"]
    ctx["scope_status"] = label
    if not setting.get("enabled", True):
        notes.append("⚠️ 这个应用在平台上是停用状态，换到的令牌会被拒 —— 先启用它")
    if not ctx["redirect"]:
        notes.append("★平台上还没登记任何回调地址 ⇒ 授权码流程（模式 B）走不了。"
                     "先 client-setting --project-id %s --redirect-uri https://你的系统/callback"
                     "（写进补充列表，主地址不动）" % project_id)
    elif ctx.get("effective_redirects"):
        # 生效集合可能不止一条（主地址 + 补充列表）。只把其中一条填进配置的代码，换个环境部署就会
        # 因 redirect_uri 逐字不等被拒，所以这里把全集摆出来，让开发者自己决定按环境各配一条。
        notes.append("平台上登记的回调地址（%d 条，均可用）：%s —— 已挑第一条做预填；"
                     "多环境部署请把各自那条填进自己的配置"
                     % (len(ctx["effective_redirects"]),
                        "、".join(ctx["effective_redirects"])))
    notes.append("参数取自平台（projectId=%s，%s）" % (project_id, project_type))


def cmd_scaffold(args):
    module = scaffold_module()
    ctx, notes = scaffold_context(args)

    if args.stack == "all":
        files = module.build_all(ctx)
    else:
        files = module.build(args.stack, ctx)
    # 第二步的后半：把接口文档按要接的模块切成小片，开发者的 AI 只读它需要的那片。
    # ★切片是运行时从技能 references/ 里按小节抽的，技能仓库不存第二份文档。
    docs = module.build_docs(ctx)
    if docs:
        files.extend(docs)
    else:
        notes.append("没切出文档片（读不到技能里的 references/）⇒ 接口文档直接看技能目录。")
    # ★宁可生成失败也不能把密钥写进交付物
    module.assert_no_secret(files)

    params = {
        "平台基址": ctx["base"],
        "client_id": ctx["client_id"] or "(占位符)",
        "redirect_uri": ctx["redirect"] or "(占位符)",
        "projectId": str(ctx["project_id"] or "(占位符)"),
        "平台登记集（已生效）": "、".join(ctx.get("granted_scopes") or []) or "(空 ⇒ 平台默认 profile)",
        "写进生成物的 scope": ctx["scope"] or "profile",
        "scope 审核状态": ctx["scope_status"],
        "client_secret": "★不写进生成物，只留环境变量 %s" % module.SECRET_ENV,
        "文档切片": ("docs/ → auth + common%s"
                     % "".join(" + " + entry[0] for entry in module.selected_doc_modules(ctx)))
                    if docs else "(没切出来)",
    }
    # ★登记 ≠ 令牌里有：登记集只是前提，真正决定令牌内容的是应用自己发出的那次授权请求
    #   （三方 --scope / 配置常量，UserApp 是 manifest [env]）。两行不一致必须当场喊。
    granted_now = set(ctx.get("granted_scopes") or [])
    asked_now = [n for n in re.split(r"[,\s]+", ctx.get("scope") or "") if n]
    not_registered = [n for n in asked_now if n not in granted_now] if granted_now else []
    if not_registered:
        notes.append("★写进生成物的 scope 里有 %s 还没在平台登记 ⇒ 授权码登录会在真人登录之后被拒 "
                     "invalid_scope（整轮登录失败，不只是那个功能不可用）。先去平台上申请。"
                     % "、".join(not_registered))

    if len(ctx.get("effective_redirects") or []) > 1:
        # 多环境部署时每条环境要用各自的回调地址：单条预填不够用，全集在这儿一次给全
        params["回调地址全集"] = "、".join(ctx["effective_redirects"])

    if args.json:
        print(json.dumps({"code": "0000", "data": {
            "stack": args.stack,
            "out": args.out,
            "files": [{"path": path, "bytes": len(content.encode("utf-8"))} for path, content in files],
            "params": params,
            "notes": notes,
        }}, ensure_ascii=False, indent=2))
        return EXIT_OK

    if args.to_stdout:
        # docs/ 那几片是接口文档，整篇打到 stdout 只会刷屏；摘要里照样列出，用 --out 落盘才读得到。
        shown = [item for item in files if not item[0].startswith("docs/")]
        hidden = len(files) - len(shown)
        for path, content in shown:
            print("=" * 78)
            print("===== %s =====" % path)
            print("=" * 78)
            print(content)
        if hidden:
            print()
            print("（docs/ 下 %d 片接口文档没打到 stdout —— 太长会刷屏；用 --out 落盘后读）" % hidden)
        _scaffold_summary(args, ctx, files, params, notes, written=None)
        return EXIT_OK

    written = None
    if args.out:
        written = module.write_files(files, os.path.expanduser(args.out))

    _scaffold_summary(args, ctx, files, params, notes, written)
    return EXIT_OK


def _read_manifest_env(path):
    """极简解析 toml 的 [env] 段，取 NUWAX_* 键。不引 tomllib（沙箱 python3 可能低于 3.11）。"""
    env = {}
    in_env = False
    try:
        with open(path, "r", encoding="utf-8") as handle:
            for line in handle:
                stripped = line.strip()
                if stripped.startswith("["):
                    in_env = stripped.split("]", 1)[0].lstrip("[").strip().lower() == "env"
                    continue
                if in_env and "=" in stripped and not stripped.startswith("#"):
                    key, _sep, value = stripped.partition("=")
                    key = key.strip()
                    if key.startswith("NUWAX_"):
                        env[key] = value.strip().strip('"').strip("'")
    except OSError:
        return {}
    return env


def _scan_manifest_env(roots, max_depth=3):
    """扫 roots 下的 *.manifest.toml，返回 [(路径, {NUWAX_* 键: 值})]。

    为什么值得扫：UserApp 的运行时环境变量唯一通道是 project.manifest.toml 的 [env]，
    它会【静默覆盖】代码里的默认值——初版接入把 NUWAX_* 固化进去之后，任何「只改代码」的
    scope/回调调整都不生效，且没有任何报错。「改了没生效」的排障必须先查这里。
    """
    found = []
    seen = set()
    for root in roots:
        root = os.path.abspath(os.path.expanduser(root)) if root else ""
        if not root or not os.path.isdir(root) or root in seen:
            continue
        seen.add(root)
        for dirpath, dirnames, filenames in os.walk(root):
            if os.path.relpath(dirpath, root).count(os.sep) >= max_depth:
                dirnames[:] = []
            dirnames[:] = [d for d in dirnames
                           if not d.startswith(".") and d not in ("node_modules", "__pycache__")]
            for name in sorted(filenames):
                if name.endswith(".manifest.toml"):
                    env = _read_manifest_env(os.path.join(dirpath, name))
                    if env:
                        found.append((os.path.join(dirpath, name), env))
    return found


def _print_manifest_env_warning(roots, recommended_scope=None):
    hits = _scan_manifest_env(roots)
    for path, env in hits:
        print()
        print("⚠ 检测到 %s 的 [env] 已固化 NUWAX_* 值：" % path)
        for key in sorted(env):
            extra = ""
            if key == "NUWAX_SCOPE" and recommended_scope and env[key] != recommended_scope:
                extra = "   ← 本次推荐 %s，不一致 ⇒ 需同步" % recommended_scope
            print("    %s = %s%s" % (key, env[key], extra))
        print("  UserApp 运行时 [env] 会覆盖代码默认值：改 scope/回调 = 改 [env] 并【重新发布】，")
        print("  只改代码不生效。口诀：线上行为与代码不符 ⇒ 先查 [env] 覆盖。")


def _scaffold_summary(args, ctx, files, params, notes, written):
    title = "已生成" if written else ("已打印" if args.to_stdout else "将生成")
    print()
    print("📦 %s %s 对接代码与文档（%d 个文件）" % (title, args.stack, len(files)))
    if written:
        print("   目录：%s" % os.path.abspath(args.out))
    for path, content in files:
        print("   %-46s %6d B" % (path, len(content.encode("utf-8"))))
    print()
    print("预填的接入参数：")
    for key, value in params.items():
        print("   %s%s" % (pad(key, 18), value))
    if notes:
        print()
        print("提示：")
        for note in notes:
            print("   · %s" % note)
    if not written and not args.to_stdout:
        print()
        print("★还没写文件（默认只打印摘要，便于先核对预填参数）。二选一：")
        print("   --out ./nuwax-integration    写进目录")
        print("   --print                      直接打到 stdout")
    elif written:
        print()
        readme = os.path.join(args.out, "README.md" if args.stack == "all"
                              else os.path.join(args.stack, "README.md"))
        print("下一步：读 %s —— 里面是已按你要接的模块切好的接口文档索引；"
              % os.path.join(args.out, "docs", "README.md"))
        print("        喂给 AI 时给 auth + common + 要接的那一片就够，别整目录塞。")
        print("        可运行代码与六条平台契约见 %s。" % readme)
        print("★client_secret 要你自己注入环境变量 %s（生成物里没有，这样才能安全进 git）。"
              % scaffold_module().SECRET_ENV)
        print("★密钥与回调地址怎么注入、多环境怎么配，见 %s 的「密钥注入」与「多环境部署」两节。"
              % readme)
    # UserApp 的 [env] 固化值是「改了 scope/回调但线上没生效」的头号原因，扫到就提醒
    roots = [os.getcwd()]
    if args.out:
        out_dir = os.path.abspath(os.path.expanduser(args.out))
        roots = [out_dir, os.path.dirname(out_dir)] + roots
    _print_manifest_env_warning(roots, recommended_scope=ctx.get("scope"))
    print("（要让程序解析这次结果，重跑并加 --json。）")


# ---------------------------------------------------------------- 端点定义

ENDPOINTS = {}


def endpoint(name, summary, mode, func, args):
    ENDPOINTS[name] = dict(summary=summary, mode=mode, func=func, args=args)


def arg(*flags, **kwargs):
    return (flags, kwargs)


# ---- 第一步：定位应用 + 配好回调地址（有 DEV_APP_ID 就全自动）

endpoint(
    "dev-app", "★第一步：定位应用（DEV_APP_ID）→ 取 client_id/secret → 用绑定域名拼回调与主页地址并写回", "写",
    cmd_dev_app,
    [
        arg("--path", help="回调地址的 path，默认 %s。★由你（开发者的 AI）决定 —— "
                           "它要与你系统里真实接收 code 的那个路由一致。"
                           "★登记的是【浏览器侧】那一段：反代/网关若 strip 掉前缀，后端收到的是另一条路径，"
                           "以浏览器地址栏为准（原样回传就行，别在回调里重拼）"
                           % DEFAULT_CALLBACK_PATH),
        arg("--homepage-path", help="主页地址的 path，默认 %s" % DEFAULT_HOMEPAGE_PATH),
        arg("--app-id", type=positive_int,
            help="要接哪个应用；不给则用环境变量 DEV_APP_ID，再不给则查名下应用"
                 "（恰好一个就自动用，多个/零个则返回退出码 3 要求问用户）"),
        arg("--project-type", choices=PROJECT_TYPES,
            help="UserApp（全栈应用）或 ThirdApp；DEV_APP_ID 定位到的恒为 UserApp，其余自动判定"),
        arg("--domain-type", choices=DOMAIN_TYPES,
            help="只登记这一类绑定域名；★默认不传 = 登记【全部】（Dev/Prod 在创建应用时一并分配，"
                 "一次把两个环境的回调都配上，免得发布后回调失配）"),
        arg("--domain", help="直接指定域名，跳过域名查询（三方应用、或查不到域名时用）"),
        arg("--redirect-uri", help="直接给完整回调地址，跳过「域名 + path」拼装。★必须与授权时逐字相等"),
        arg("--homepage-url", help="直接给完整主页地址，跳过拼装"),
        arg("--reveal-secret", action="store_true", help="打印 client_secret 明文（默认只给长度与首尾）"),
        arg("--write-env", metavar="PATH",
            help="把凭据写进这个 .env 文件（权限 0600，键名与 scaffold 的 .env.example 一致）。"
                 "★这是 secret 唯一允许落盘的出口，且必须显式要求。"
                 "★若他的系统部署在女娲 UserApp 上，这份 .env 不是运行时来源 —— 要改 "
                 "project.manifest.toml 的 [env] 并【重新发布】；该段是明文，别把长期密钥写进去"),
        arg("--no-save", action="store_true", help="只演练：不写回平台，打印将要保存的值"),
    ],
)

# ---- 主交付物：生成「开发者自己的系统」要用的对接代码（离线）

endpoint(
    "scaffold", "★生成你自己系统要用的对接代码 + 按模块切好的接口文档（4 种栈，预填 client_id / 回调地址 / 已生效 scope）", "离线",
    cmd_scaffold,
    [
        arg("--stack", choices=("python", "node", "spring-boot", "curl", "all"), default="python",
            help="技术栈，默认 python。python=仅标准库；node=Node18+ 零依赖；"
                 "spring-boot=Boot3/JDK17；curl=bash+curl+jq；all=四种全生成"),
        arg("--out", metavar="DIR", help="写进这个目录（不给 --out 也不给 --print 时只打印摘要，不落盘）"),
        arg("--print", dest="to_stdout", action="store_true", help="把生成内容直接打到 stdout"),
        arg("--project-id", type=positive_int, help="用哪个应用的配置来预填；不给则名下恰好一个时自动用它"),
        arg("--project-type", choices=PROJECT_TYPES, help="全栈应用需显式传 UserApp"),
        arg("--client-id", help="覆盖预填值（不在沙箱里跑、或想给别的机器生成时用）"),
        arg("--redirect-uri", help="覆盖预填值。★必须与平台上登记的逐字相等"),
        arg("--scope", help="覆盖预填值，逗号或空格分隔；不给则用应用「已生效」的那一组。"
                            "★它同时决定 docs/ 下切出哪几片接口文档"),
        arg("--base-url", help="覆盖平台基址；不给则用环境变量 PLATFORM_BASE_URL"),
        arg("--package", help="spring-boot 的 Java 包名，默认 com.example.nuwax"),
        arg("--no-sandbox", action="store_true",
            help="不去平台查应用配置（纯离线生成，参数全靠自己给 + 占位符）"),
    ],
)

# ---- 管理三方应用（沙箱 AK，打 /api/v1/4sandbox/user-project/**）

endpoint(
    "client-list", "列出名下可接入 OAuth2 的应用（拿 projectId / clientId / 回调地址）", "只读",
    cmd_client_list,
    [arg("--page-size", type=positive_int, default=50, help="单页条数，默认 50（最多翻 4 页）")],
)

endpoint(
    "client-get", "查某个应用的完整接入配置；加 --reveal-secret 才打印 client_secret 明文", "只读",
    cmd_client_get,
    [
        arg("--project-id", type=positive_int, required=True, help="client-list 里的 projectId"),
        arg("--project-type", choices=PROJECT_TYPES,
            help="ThirdApp（默认）或 UserApp；不传则自动判定。★全栈应用必须带 UserApp，"
                 "因为它回的是 ref 表主键、服务端要靠它换回真主键"),
        arg("--reveal-secret", action="store_true",
            help="额外取 client_secret 明文（服务端留审计日志；本技能不缓存 secret）"),
    ],
)

endpoint(
    "client-create", "创建三方应用（★写操作：会在名下落一行项目并生成凭证，不自动触发）", "写",
    cmd_client_create,
    [
        arg("--name", help="应用名；留空按平台当前语言取默认名"),
        arg("--description", help="应用描述"),
        arg("--icon", help="应用图标地址"),
        arg("--space-id", type=positive_int,
            help="归属空间；不传取当前用户的个人空间（没有个人空间则取第一个所属空间）"),
    ],
)

endpoint(
    "client-setting", "增删补充回调地址 / 改主页地址，并提交 scope 变更申请（需管理员审核）", "写",
    cmd_client_setting,
    [
        arg("--project-id", type=positive_int, required=True, help="要改哪个应用"),
        arg("--project-type", choices=PROJECT_TYPES, help="全栈应用需显式传 UserApp"),
        arg("--redirect-uri",
            help="★把回调地址【加入补充列表】（不覆盖主地址）。服务端【精确匹配】（不是前缀），"
                 "必须与授权时传的逐字相同；须是绝对 http(s) 地址、有 host、无 #fragment"),
        arg("--add-redirect-uri", action="append", metavar="URL",
            help="同 --redirect-uri，可重复；重复项服务端自动去重（幂等）"),
        arg("--remove-redirect-uri", action="append", metavar="URL",
            help="从补充列表删除，可重复。★沙箱只能删自己加的（source=Sandbox）条目；"
                 "删人工条目会被跳过并打印在结果里"),
        arg("--primary-redirect-uri",
            help="⛔ 不可用：主回调地址只由应用属主在网页端配置，沙箱/AI 一律不能写（库里为空时也不能，"
                 "服务端会回 3353）⇒ 本参数在这里必然失败，脚本会直接拦下。要加地址请用 "
                 "--redirect-uri / --add-redirect-uri"),
        arg("--homepage-url", help="应用主页地址；传空串不会清空（会被忽略）"),
        arg("--scopes",
            help="申请哪些 scope，逗号或空格分隔。★不给 = 不修改；给空串 = 申请清空并回落平台默认 profile；"
                 "提交后进入待审核，通过前应用按原范围生效"),
    ],
)

endpoint(
    "scopes", "列出平台支持的 13 个 scope（含敏感标记与 M2M 限制说明）", "只读",
    cmd_scopes, [],
)

endpoint(
    "scope-check", "★免真人登录的 scope 预检：名称是否合法 + 是否在应用登记集（退出码 0 通过 / 1 不过）",
    "只读",
    cmd_scope_check,
    [
        arg("--scope", required=True,
            help="待预检的 scope 串，逗号或空格分隔，例如 profile,im:read,im:push"),
        arg("--project-id", type=positive_int, help="查哪个应用的登记集；不给用 DEV_APP_ID"),
        arg("--project-type", choices=PROJECT_TYPES, help="全栈应用需显式传 UserApp"),
    ],
)

endpoint(
    "redirect-check",
    "★免真人登录的回调地址预检：与生效集合（主地址 ∪ 补充列表）逐字比对（退出码 0 在集合内 / 1 不在）",
    "只读",
    cmd_redirect_check,
    [
        arg("--redirect-uri", action="append", required=True, metavar="URL",
            help="待预检的回调地址，可重复或用逗号/空格分隔多个。★给的是【浏览器地址栏里那个】"
                 "完整地址，与服务端登记值逐字比对"),
        arg("--project-id", type=positive_int, help="查哪个应用的生效集合；不给用 DEV_APP_ID"),
        arg("--project-type", choices=PROJECT_TYPES, help="全栈应用需显式传 UserApp"),
    ],
)

endpoint(
    "regenerate", "重置 client_id / client_secret（★★破坏性：旧密钥立即失效，必须加 --yes）", "写",
    cmd_regenerate,
    [
        arg("--project-id", type=positive_int, required=True, help="要重置哪个应用"),
        arg("--project-type", choices=PROJECT_TYPES, help="全栈应用需显式传 UserApp"),
        arg("--yes", action="store_true", help="我确认要打断所有正在使用旧密钥的集成"),
    ],
)

# ---- 协议侧（不需要沙箱 AK；缺 client 凭据时才回落用 AK 自动定位）

endpoint(
    "auth-url", "生成带 PKCE(S256) 的授权地址，缓存 state 与 code_verifier", "离线",
    cmd_auth_url,
    [
        arg("--scope", help="申请的 scope，逗号或空格分隔；不给 = 应用登记的全部"),
        arg("--client-id", help="不给则用环境变量 NUWAX_OAUTH2_CLIENT_ID，再不给则用沙箱 AK 自动定位"),
        arg("--redirect-uri", help="不给则用应用上已登记的回调地址"),
        arg("--project-id", type=positive_int, help="自动定位时用哪个应用"),
        arg("--project-type", choices=PROJECT_TYPES, help="全栈应用需显式传 UserApp"),
        arg("--no-sandbox", action="store_true",
            help="禁止回落用沙箱 AK 补全参数（在非沙箱环境跑、只想手工给全参数时用）"),
    ],
)

endpoint(
    "token", "授权码换令牌（贴整条回调地址即可；自动带 PKCE verifier 与 state 校验）", "写",
    cmd_token,
    [
        arg("--callback-url", help="★推荐：把浏览器跳转后的整条回调地址原样贴进来，自动解析 code 与 state"),
        arg("--code", help="只给授权码（此时不做 state 校验）"),
        arg("--state", help="回调里的 state，用于与本地缓存比对"),
        arg("--force-state", action="store_true", help="跳过 state 校验（★放弃 CSRF 防护，仅调试用）"),
        arg("--code-verifier", help="不给则用 auth-url 缓存的那个"),
        arg("--client-id", help="不给则用 auth-url 缓存的 / 环境变量 / 沙箱 AK 自动定位"),
        arg("--client-secret", help="不给则用沙箱 AK 就地取（不落盘）"),
        arg("--redirect-uri", help="★必须与 authorize 阶段逐字相等；不给则用缓存的"),
        arg("--project-id", type=positive_int, help="自动定位 client_secret 时用哪个应用"),
        arg("--project-type", choices=PROJECT_TYPES, help="全栈应用需显式传 UserApp"),
    ],
)

endpoint(
    "token-m2m", "机器对机器换令牌（client_credentials，全自动，无需真人）", "写",
    cmd_token_m2m,
    [
        arg("--scope", help="申请的 scope；不给 = 应用登记的全集再收窄（剔除仅授权码的）"),
        arg("--client-id", help="不给则用环境变量 / 沙箱 AK 自动定位"),
        arg("--client-secret", help="不给则用沙箱 AK 就地取（不落盘）"),
        arg("--project-id", type=positive_int, help="自动定位时用哪个应用"),
        arg("--project-type", choices=PROJECT_TYPES, help="全栈应用需显式传 UserApp"),
    ],
)

endpoint(
    "refresh", "用 refresh_token 续期（★会轮换：返回的新 refresh_token 才有效）", "写",
    cmd_refresh,
    [
        arg("--refresh-token", help="不给则用缓存里的"),
        arg("--client-id", help="不给则用缓存 / 环境变量 / 沙箱 AK 自动定位"),
        arg("--client-secret", help="不给则用沙箱 AK 就地取（不落盘）"),
        arg("--project-id", type=positive_int, help="自动定位时用哪个应用"),
        arg("--project-type", choices=PROJECT_TYPES, help="全栈应用需显式传 UserApp"),
    ],
)

endpoint(
    "revoke", "撤销令牌（★用户级：该应用下该用户的全部令牌一并失效）", "写",
    cmd_revoke,
    [
        arg("--token", help="要撤销的 access_token 或 refresh_token（按 or- 前缀自动识别）；不给则用缓存的 access_token"),
        arg("--token-type-hint", help="access_token / refresh_token；★服务端只记日志，不参与判定"),
        arg("--client-id", help="不给则用环境变量 / 沙箱 AK 自动定位"),
        arg("--client-secret", help="不给则用沙箱 AK 就地取（不落盘）"),
        arg("--project-id", type=positive_int, help="自动定位时用哪个应用"),
        arg("--project-type", choices=PROJECT_TYPES, help="全栈应用需显式传 UserApp"),
    ],
)

endpoint(
    "userinfo", "查当前令牌代表的授权用户（这就是「用女娲登录」拿到的身份）", "只读",
    cmd_userinfo,
    [arg("--access-token", help="不给则用环境变量 / 缓存 / refresh_token 自动续期")],
)

endpoint(
    "call", "用令牌调任意门面端点（27 个，路径可简写：im/contact/list）", "只读",
    cmd_call,
    [
        arg("--path", required=True,
            help="端点路径。可写全路径 /api/oauth2/im/contact/list，也可简写 im/contact/list。"
                 "★拿身份请用 userinfo 子命令：/userinfo 与 /user/search 回裸对象、没有信封，"
                 "本命令对这两个路径不做 code 闸，输出形态也和门面端点不同"),
        arg("--method", default="GET", help="GET（默认）或 POST"),
        arg("--query", action="append", metavar="K=V",
            help="查询参数，可重复。例：--query agentId=1596 --query limit=20"),
        arg("--body", help="POST 的 JSON 请求体，或 @文件路径。★POST 且不给 body 时自动发 {}"),
        arg("--access-token", help="不给则用环境变量 / 缓存 / refresh_token 自动续期"),
    ],
)


# ---------------------------------------------------------------- CLI 骨架

class Parser(argparse.ArgumentParser):
    """
    ★argparse 默认用退出码 2 表示「参数有错」，而本脚本用 2 表示「环境变量/凭据缺失」。
    两者混在一起时，使用者会把「参数写错」误判成「环境没配好」，然后去查错方向。
    所以这里把 argparse 的错误也归到 1（用法错误）。
    """

    def error(self, message):
        self.print_usage(sys.stderr)
        die(message)


def build_parser():
    parser = Parser(
        prog="oauth2.py",
        description="nuwax-oauth2：帮开发者把【自己的系统】接上女娲的认证与开放接口。"
                    "★认证跑在你自己的服务端里，本脚本不代做 —— 它负责配好平台侧应用、"
                    "生成可直接落进你仓库的对接代码（scaffold），并提供联调自检用的协议子命令。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="先看 python3 scripts/oauth2.py endpoints 列出全部子命令。\n"
               "★主线三步：dev-app（定位应用 + 配好回调，有 DEV_APP_ID 就全自动）\n"
               "           → 问用户要接哪些模块（仅认证 / 用户信息 / 会话 / 模型 / 工具…）\n"
               "           → scaffold --stack all --out ./nuwax-integration（拿能跑的代码）\n"
               "★不走 scope 申请：范围由用户提前在平台上配好（申请要管理员审核，会卡住接入）。\n"
               "★两套凭据不能混用：沙箱 AK 只打 /api/v1/4sandbox/**，\n"
               "  client_id/secret 与 oa- 令牌只打 /api/oauth2/**（带 AK 打后者会 4030）。\n"
               "接入文档（给外部开发者照着接的那份）：references/integration_guide.md（流程）\n"
               "+ references/api/ 分模块端点契约 + references/api_reference.md（响应格式/scope/错误码总览）",
    )
    parser.add_argument("--json", action="store_true", help="打印原始响应，不做渲染")
    sub = parser.add_subparsers(dest="command")
    # ★--json 在子命令后也要能用。不带 SUPPRESS 的话，子解析器的 default=False 会【覆盖】
    #   顶层已设好的 True，于是 `oauth2.py --json client-list` 静默变成不打印原始响应。
    child_json = dict(action="store_true", default=argparse.SUPPRESS, help="打印原始响应，不做渲染")
    for name, spec in ENDPOINTS.items():
        child = sub.add_parser(name, help=spec["summary"], description=spec["summary"])
        for flags, kwargs in spec["args"]:
            child.add_argument(*flags, **kwargs)
        child.add_argument("--json", **child_json)
    sub.add_parser("endpoints", help="列出全部子命令（离线，不发请求）").add_argument("--json", **child_json)
    return parser


def print_endpoints():
    print("nuwax-oauth2 子命令（共 %d 个）" % (len(ENDPOINTS) + 1))
    print()
    print("%s%s%s" % (pad("子命令", 17), pad("类型", 7), "说明"))
    print("-" * 100)
    for name, spec in ENDPOINTS.items():
        print("%s%s%s" % (pad(name, 17), pad(spec["mode"], 7), spec["summary"]))
    print("%s%s%s" % (pad("endpoints", 17), pad("离线", 7), "列出全部子命令（就是本输出）"))
    print()
    print("用法: python3 scripts/oauth2.py <子命令> --help")
    print()
    print("★本技能不代你做认证：授权码回调、令牌保管与刷新都跑在【你自己的服务端】里。")
    print("  脚本的活是：把平台侧应用配好 → scaffold 生成能跑的对接代码 → 交付接入文档。")
    print()
    print("主线（给开发者交付对接资产）：")
    print("  ① dev-app      定位应用（读环境变量 DEV_APP_ID）→ 取 client_id/secret")
    print("                 → 用应用绑定的域名拼 https://域名/path 当回调地址与主页地址 → 写回平台")
    print("                 ★一次登记【全部】绑定域名（Dev/Prod 建应用时一并分配），地址写进补充列表；")
    print("                   主地址那一格一律不写（属应用属主、只由网页端配，写了报 3353）")
    print("  ② 问用户要接哪些模块（仅认证 / 用户信息 / 会话 / 模型调用 / 工具调用…）")
    print("     ★没说清楚就问，别默认全接：模块决定要哪些 scope、要生成哪些端点的代码")
    print("  ③ scaffold     按选中的模块生成能直接落进他仓库的对接代码；接口文档也按模块切好，")
    print("                 落在生成物的 docs/ 下（auth + common 恒带，其余由 --scope 决定）")
    print()
    print("★不走 scope 申请流程：范围由用户提前在平台上配好。dev-app 提交时【不带 scopes】，")
    print("  免得白白落一条待管理员审核的申请。要看已生效范围：client-get --project-id <id>")
    print("  （确实要改范围时才用 client-setting --scopes，那是写操作 + 需审核。）")
    print()
    print("★dev-app 定不下要接哪个应用时返回【退出码 3】（不是失败）：")
    print("  stdout 里有一行 NUWAX_CHOICE_REQUIRED {json} —— 把候选拿给用户选（或问要不要新建应用），")
    print("  然后带 --app-id <id> 重跑。不要替他挑：接错应用会把回调地址写进别人的项目。")
    print()
    print("★两套凭据，绝不能混用：")
    print("  ① 沙箱 AK（SANDBOX_ACCESS_KEY）→ 只打 /api/v1/4sandbox/**，用于管理三方应用")
    print("     （dev-app / client-list / client-get / client-create / client-setting / scopes / regenerate）")
    print("  ② client_id + client_secret 与 oa- 令牌 → 只打 /api/oauth2/**，用于真正调平台能力")
    print("     （auth-url / token / token-m2m / refresh / revoke / userinfo / call）")
    print("  把 ① 带进 ② 的地址会得到 code 4030「API Key 无访问权限」。")
    print("  scaffold 是离线的：不发请求，只用 ① 读一次应用配置来预填参数（★绝不取 secret）。")
    print()
    print("② 组那 7 个子命令的定位是【联调自检】：验证你登记的回调地址 / scope / 凭据确实可用，")
    print("  不是给你的系统当运行时用 —— 运行时请用 scaffold 生成的代码。")
    print("★在沙箱里运行时，② 组命令不给 --client-id/--client-secret 也能跑：")
    print("  脚本会用 ① 自动定位你名下的三方应用并就地取出凭据（secret 不落盘、不打印）。")
    print("★授权码流程无法全自动：auth-url 生成的地址必须由真人在浏览器里登录一次。")
    print("  token / token-m2m / revoke 是免登录端点，脚本可直调。")
    print("★im:read、im:push、model:read、model:key 四个 scope 仅授权码可用，M2M 拿不到。")
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
    return spec["func"](args)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        # ★SystemExit 与 KeyboardInterrupt 都派生自 BaseException，不会被这里吞掉，
        #   所以 die() 的退出码与 Ctrl-C 行为照常。
        print("💥 意外错误: %s: %s" % (type(exc).__name__, exc), file=sys.stderr)
        print("   这不是平台的业务拒绝，是脚本自己出问题了。两个常见诱因：", file=sys.stderr)
        print("   ① PLATFORM_BASE_URL 含非 ASCII 字符、空格或多余引号（HTTP 头只接受 latin-1）；", file=sys.stderr)
        print("   ② 缓存文件被写坏：rm -rf ~/%s 后重来（里面只有令牌与 PKCE 中间态，删了不丢配置）。"
              % CACHE_DIR_NAME, file=sys.stderr)
        sys.exit(EXIT_USAGE)

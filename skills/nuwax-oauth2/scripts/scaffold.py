# -*- coding: utf-8 -*-
"""
scaffold —— 生成「开发者自己的系统」要用的女娲 OAuth2 对接代码。

★这是本技能的主交付物。技能本身【不代做认证】：授权码回调、令牌保管、刷新与调接口
  都跑在开发者自己的服务端里。这里产出的是能直接落进他仓库的源码骨架，并预填好他的
  真实接入参数（client_id / 已登记的 redirect_uri / 已生效 scope / 平台基址），
  省掉「读 900 行指南 + 手抄示例 + 自己逐个填参数」这一段。

★client_secret 绝不写进任何生成文件 —— 只留环境变量占位 NUWAX_CLIENT_SECRET，
  由开发者从自己的密钥管理注入。因此生成物可以安全进 git、发给同事、贴进工单。

★模板一律用 @@TOKEN@@ 占位 + str.replace 填充，刻意【不用】% / format / f-string：
  目标语言自己就大量使用 %（Python 格式化、shell 的 printf 与 ${x%%y}、curl 的 -w），
  再叠一层格式化必然互相踩。

被 oauth2.py 的 scaffold 子命令调用。
"""

from __future__ import annotations

import os
import re

STACKS = ("python", "node", "spring-boot", "curl")

SECRET_ENV = "NUWAX_CLIENT_SECRET"
SECRET_PLACEHOLDER = "nwxa_s_REPLACE_ME"
PLACEHOLDER_REDIRECT = "https://REPLACE_ME/oauth/callback"

# client_secret 在各栈生成物里的注入点文件名（README 的「密钥注入」节点名指路用）
_SECRET_ENV_FILE = {
    "python": "`.env.example`（复制成 `.env` 后填值）",
    "node": "`.env.example`（复制成 `.env` 后填值）",
    "curl": "`.env.example`（复制成 `.env` 后 source 进去）",
    "spring-boot": "`application-nuwax.yml`（`nuwax.client-secret: ${NUWAX_CLIENT_SECRET:}`，值仍来自环境变量）",
}

# ------------------------------------------------------------------ 平台契约数据
# 这两张表是生成物的「事实来源」，与 references/api_reference.md §2（scope 全集）
# 及 references/api/ 各模块文件（门面端点）同源。
# 改了平台侧的 scope 或门面端点，这里要跟着改，否则生成的 README 会骗人。

# (scope, 能力, 是否敏感, 是否仅授权码可用 —— M2M 拿不到)
SCOPE_TABLE = (
    ("profile", "读取授权用户自身信息（昵称、头像等）", False, False),
    ("user:search", "按用户名 / 邮箱 / 手机号检索租户内用户", True, False),
    ("user:space", "查询授权用户可见的空间列表", False, False),
    ("file:write", "以授权用户身份上传文件", False, False),
    ("file:read", "为授权用户的文件签发带凭证的下载地址", False, False),
    ("chat:read", "读取授权用户的任务会话与消息记录", False, False),
    ("chat:write", "以授权用户身份创建会话、发起与停止对话", True, False),
    ("im:read", "读取授权用户的 IM 会话、群组、联系人与消息", True, True),
    ("im:push", "以授权用户身份向指定目标推送 IM 消息", True, True),
    ("model:read", "查询租户可用模型列表", False, True),
    ("model:key", "创建模型 API Key 并授权可用模型", True, True),
    ("tool:read", "检索已发布的插件与工作流、读取其出入参定义", False, False),
    ("tool:exec", "执行已发布的插件与工作流", True, False),
)

CODE_ONLY_SCOPE_NAMES = tuple(name for name, _desc, _sensitive, only in SCOPE_TABLE if only)


def known_scope_names():
    """13 个 scope 名。给调用方校验用户传的 --scope 有没有拼错（拼错会被服务端静默忽略）。"""
    return tuple(name for name, _desc, _sensitive, _only in SCOPE_TABLE)

# (相对 /api/oauth2 的路径, 方法, 所需 scope, 入参要点)
ENDPOINTS = (
    ("space/list", "GET", "user:space", "无参数"),
    ("file/upload", "POST multipart", "file:write",
     "表单字段 file 必填；可选 targetType(默认 Default)/targetId(默认 -1)/metadata"),
    ("file/url", "GET", "file:read",
     "query fileUrl 必填且需 URL 编码；可选 download。★不校验文件归属"),

    ("chat/conversation/list", "GET", "chat:read",
     "query agentId 必填；可选 lastId/limit(默认 10)/topic"),
    ("chat/conversation/detail", "GET", "chat:read", "query conversationId 必填"),
    ("chat/message/list", "GET", "chat:read",
     "query conversationId 必填；可选 index/limit(默认 10)"),
    ("chat/message/subscribe", "GET SSE", "chat:read",
     "query conversationId 必填；text/event-stream 持续推送"),
    ("chat/conversation/create", "POST", "chat:write",
     "body ConvCreateDto；data 为新会话 id（创建失败可能为 null）"),
    ("chat/conversation/update", "POST", "chat:write",
     "body ConversationUpdateDto；data 为更新后的会话详情"),
    ("chat/conversation/delete", "POST", "chat:write",
     "★conversationId(Long) 在 query 或 form，不是 JSON body"),
    ("chat/message/send", "POST SSE", "chat:write",
     "body TryReqDto；流式返回；★from/filterSensitive/agentConfig 被服务端强制覆盖"),
    ("chat/message/stop", "POST", "chat:write",
     "★conversationId 是 String 且在 query 或 form"),
    ("chat/message/clear", "POST", "chat:write",
     "★conversationId(Long) 在 query 或 form"),
    ("chat/message/permission-response", "POST", "chat:write",
     "body PermissionRequestResponseDto；非 TaskAgent 会话直接成功"),

    ("im/conversation/list", "GET", "im:read", "query cursor/limit/traceId 均可选"),
    ("im/group/list", "GET", "im:read", "同上"),
    ("im/contact/list", "GET", "im:read", "query traceId 可选；返回三态混合、上限 500"),
    ("im/message/list", "GET", "im:read",
     "query convId(Long) 必填；可选 startTime/endTime/afterSeq/limit/traceId"),
    ("im/message/push", "POST", "im:push",
     "body ImPushRequest：toUserId 与 convId ★二选一必填；msgType、body 必填；"
     "bizMsgId 是幂等键，不传则服务端生成并回显"),

    ("model/list", "GET", "model:read", "无参数"),
    ("model/api-key/create", "POST", "model:key",
     "body modelIds 必填，且每个都须在用户可用模型集合内"),
    ("model/api-key/list", "GET", "model:key", "无参数"),
    ("model/api-key/update", "POST", "model:key", "body id 必填"),
    ("model/api-key/revoke", "POST", "model:key", "body id 必填"),

    ("tool/list", "GET", "tool:read",
     "query type(plugin|workflow，省略=两类各查一页)、spaceId(须是你所属空间)、kw、page、pageSize(≤100)"),
    ("tool/schema", "GET", "tool:read",
     "query type ★必填、id 必填；返回执行地址 + 入参 JSON Schema + 响应结构"),

    ("tool/plugin/execute", "POST", "tool:exec",
     "query pluginId 必填；★params 就是整个请求体，无参也要给 {}；可选 header Trace-Id"),
    ("tool/workflow/execute", "POST", "tool:exec",
     "query workflowId 必填；★同步且无超时，长任务改用 sse-execute"),
    ("tool/workflow/sse-execute", "POST SSE", "tool:exec",
     "每帧 data: 后有一个空格再接 JSON；★失败不抛异常，在流内推 success=false 帧"),
)


# ------------------------------------------------------------------ 模板：Python
PY_CLIENT = '''\
"""女娲平台 OAuth2 对接客户端（Python 3.7+，仅标准库）。

由 nuwax-oauth2 技能的 scaffold 生成，已预填本应用的接入参数。
★client_secret 从环境变量 @@SECRET_ENV@@ 读取，不在本文件里。

两种模式：
  A. client_credentials（机器对机器）—— 全自动，但拿不到 im:* / model:*
  B. authorization_code + PKCE —— 需要真人在浏览器登录一次，能拿全部 13 个 scope
"""
import base64
import hashlib
import json
import os
import secrets as _secrets
import urllib.error
import urllib.parse
import urllib.request

BASE_URL = os.environ.get("NUWAX_BASE", "@@BASE@@")
CLIENT_ID = os.environ.get("NUWAX_CLIENT_ID", "@@CLIENT_ID@@")
# ★必须与平台上登记的回调地址逐字相等
REDIRECT_URI = os.environ.get("NUWAX_REDIRECT_URI", "@@REDIRECT@@")
DEFAULT_SCOPE = os.environ.get("NUWAX_SCOPE", "@@SCOPE@@")
CLIENT_SECRET = os.environ.get("@@SECRET_ENV@@", "")
TIMEOUT = 30

CODE_OK = ("0000", "0")        # ★门面端点 HTTP 恒 200，成败只能看 code


class NuwaxError(RuntimeError):
    """对接失败的基类。"""


class NuwaxProtocolError(NuwaxError):
    """协议端点（/token、/revoke）返回的 RFC 6749 错误，HTTP 状态码是真实的。"""

    def __init__(self, status, error, description):
        super(NuwaxProtocolError, self).__init__(
            "HTTP %s %s: %s" % (status, error, description or ""))
        self.status, self.error, self.description = status, error, description


class NuwaxApiError(NuwaxError):
    """29 个门面端点信封里的 code 不是 0000。

    ★裸对象端点（/userinfo、/user/search）不抛本异常——它们没有信封，见 unwrap()。
    """

    def __init__(self, code, message, envelope):
        super(NuwaxApiError, self).__init__("code=%s message=%s" % (code, message))
        self.code, self.message, self.envelope = code, message, envelope


def _b64url(raw):
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def new_pkce():
    """返回 (code_verifier, code_challenge)。verifier 43 字符，满足平台 43~128 约束。"""
    verifier = _b64url(_secrets.token_bytes(32))
    return verifier, _b64url(hashlib.sha256(verifier.encode("ascii")).digest())


def new_state():
    return _secrets.token_urlsafe(16)


def authorization_url(state, code_challenge, scope=None):
    """拼授权地址。把用户浏览器重定向到这里；state 与 verifier 要自己存进 session。"""
    return BASE_URL + "/api/oauth2/authorize?" + urllib.parse.urlencode({
        "response_type": "code",
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT_URI,
        "scope": scope or DEFAULT_SCOPE,
        "state": state,
        "code_challenge": code_challenge,
        "code_challenge_method": "S256",
    })


def _require_secret():
    if not CLIENT_SECRET:
        raise NuwaxError("环境变量 @@SECRET_ENV@@ 未设置")
    return CLIENT_SECRET


def _post_form(path, form):
    """协议端点：RFC 扁平结构，失败时 HTTP 状态码是真实的（400/401）。"""
    request = urllib.request.Request(
        BASE_URL + path,
        data=urllib.parse.urlencode(form).encode("utf-8"),
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            raw, status = response.read(), response.status
    except urllib.error.HTTPError as exc:
        raw, status = exc.read(), exc.code
    try:
        body = json.loads(raw.decode("utf-8") or "{}")
    except ValueError:
        body = {}
    if status != 200 or "error" in body:
        raise NuwaxProtocolError(status, body.get("error"), body.get("error_description"))
    return body


def exchange_code(code, code_verifier, redirect_uri=None):
    """授权码换令牌。★code 5 分钟内有效且一次性，失败也算已消费，不能重试。"""
    return _post_form("/api/oauth2/token", {
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": redirect_uri or REDIRECT_URI,   # 须与 authorize 阶段逐字相等
        "code_verifier": code_verifier,
        "client_id": CLIENT_ID,
        "client_secret": _require_secret(),
    })


def client_credentials_token(scope=None):
    """模式 A：机器对机器。★不返回 refresh_token，过期后重跑本函数即可。"""
    form = {"grant_type": "client_credentials",
            "client_id": CLIENT_ID, "client_secret": _require_secret()}
    if scope:
        form["scope"] = scope
    return _post_form("/api/oauth2/token", form)


def refresh(refresh_token):
    """续期。★refresh_token 会轮换：返回的新值才有效，必须覆盖你的存储。"""
    return _post_form("/api/oauth2/token", {
        "grant_type": "refresh_token",
        "refresh_token": refresh_token,
        "client_id": CLIENT_ID,
        "client_secret": _require_secret(),
    })


def revoke(token):
    """撤销。★恒返回 200（令牌无效也 200），且是用户级：该用户在本应用下令牌全失效。"""
    _post_form("/api/oauth2/revoke", {
        "token": token, "client_id": CLIENT_ID, "client_secret": _require_secret(),
    })


def unwrap(envelope):
    """门面端点信封 → data。★HTTP 恒 200，成败只看 code == "0000"。"""
    if "code" not in envelope:
        # ★缺 code 就是「拿错端点类型了」，不是业务失败——直接报顶层字段名，别让它退化成
        #   「(code=)：未知错误」那种查不下去的提示。
        raise NuwaxError(
            "响应不是平台信封（顶层字段：%s）。/userinfo 与 /user/search 返回裸对象、"
            "/token 与 /revoke 返回 RFC 结构，这三类都不能走 unwrap()。"
            % (", ".join(sorted(envelope)) or "(无)"))
    code = str(envelope.get("code", ""))
    if code not in CODE_OK:
        raise NuwaxApiError(code, envelope.get("message"), envelope)
    data = envelope.get("data")
    # 工具执行类端点外层恒 0000，业务成败藏在 data.success
    if isinstance(data, dict) and data.get("success") is False:
        raise NuwaxApiError(code, "工具执行失败: %s"
                            % (data.get("error") or data.get("message") or "(未给)"), envelope)
    return data


def api(path, access_token, method="GET", query=None, body=None):
    """发一次业务请求，返回响应体的原始 JSON。

    门面端点回来的是信封（要自己看 code / tid 就用这个）；/userinfo、/user/search 回来的是
    裸对象，本身就是要的数据。区分办法：响应里有 "code" 才是信封。
    """
    url = BASE_URL + "/api/oauth2/" + path.lstrip("/")
    if query:
        url += "?" + urllib.parse.urlencode(query)
    headers = {"Authorization": "Bearer " + access_token}
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    elif method.upper() == "POST":
        data = b"{}"      # ★POST 无参也要给空对象（工具执行类的 params 就是整个请求体）
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method.upper())
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            raw = response.read()
    except urllib.error.HTTPError as exc:
        raw = exc.read()
    text = raw.decode("utf-8", "replace")
    try:
        return json.loads(text or "{}")
    except ValueError:
        raise NuwaxError("响应不是合法 JSON（SSE 端点请改用流式读取）: %r" % text[:200])


def call(path, access_token, method="GET", query=None, body=None):
    """调 29 个门面端点之一，直接返回 data。path 形如 im/contact/list。"""
    return unwrap(api(path, access_token, method, query, body))


def userinfo(access_token):
    """模式 B 登录成功后拿身份：sub / user_id 是你该长期保存的稳定标识。

    ★本端点返回裸对象（sub/user_id/name/avatar/tenant_id），没有 code/message/data，
      所以不能走 call()——那会把正常响应判成失败。别顺手把它改回去。
    """
    body = api("/userinfo", access_token)
    if isinstance(body, dict) and "code" in body:
        return unwrap(body)      # 平台将来若统一成信封，这里自动跟上
    return body
'''

PY_SERVER = '''\
"""最小可跑的回调服务示例（标准库 http.server，零依赖，仅用于本地联调）。

    export @@SECRET_ENV@@="@@SECRET_PLACEHOLDER@@"
    python3 example_server.py 8000

然后浏览器打开 http://127.0.0.1:8000/login 。

★生产环境请换成你自己的框架，并把 pending / 令牌存进服务端会话或 Redis ——
  这里的内存字典只为演示，多进程部署会丢状态。
★平台上登记的 redirect_uri 必须与本服务对外地址逐字一致（含协议、端口、路径）。
★多环境部署时，把【本次授权用的 redirect_uri】连同 state、code_verifier 一起存进 session，
  回调阶段取出来原样回传给 exchange_code —— 重拼一个很容易与浏览器地址栏对不上。
"""
import html
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer

import nuwax_oauth2 as nuwax

# state -> (code_verifier, redirect_uri)：★redirect_uri 要跟 state 一起存、回调时原样回传，
# 因为 token 阶段会再校验一次、必须与 authorize 阶段逐字相等（演示用；生产请用服务端 session）
PENDING = {}
CALLBACK_PATH = urllib.parse.urlparse(nuwax.REDIRECT_URI).path

# ★登录入口 = 弹「小窗」发起授权，认证完成后由回调页自己关闭（见页面里的脚本）。
#   ⛔ 不要写成整页跳转（<a href="/oauth/start"> / location.href= / 服务端直接 302）——
#   用户会离开你的应用，认证完也回不去；只有弹窗被浏览器拦截时才降级为顶层跳转。
LOGIN_PAGE = """<!doctype html>
<meta charset="utf-8">
<title>登录</title>
<button type="button" onclick="nuwaxLogin()">用女娲账号登录</button>
<script>
var AUTH_START = "/oauth/start";        // 授权入口：服务端 302 到女娲授权端点
var WINDOW_NAME = "nuwax-oauth";        // 固定 name ⇒ 反复点击复用同一个窗口

function nuwaxLogin() {
  var win = window.open(AUTH_START, WINDOW_NAME, "width=520,height=640");
  if (!win) { location.href = AUTH_START; }   // ★被拦截才降级，别把它当默认
}

// 弹窗认证完成后回传结果 ⇒ 刷新本页登录态。★必须校验来源，只认本应用自己的 origin
window.addEventListener("message", function (event) {
  if (event.origin !== window.location.origin) return;
  if (event.data && event.data.type === "nuwax-oauth-done") location.reload();
});
</script>
"""


class Handler(BaseHTTPRequestHandler):

    def _send(self, status, body, headers=None):
        payload = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        query = dict(urllib.parse.parse_qsl(parsed.query))

        if parsed.path == "/oauth/start":
            state = nuwax.new_state()
            verifier, challenge = nuwax.new_pkce()
            # 把这次用的 redirect_uri 一起存下（多环境 / 多域名时尤其重要）
            PENDING[state] = (verifier, nuwax.REDIRECT_URI)
            self._send(302, "", {"Location": nuwax.authorization_url(state, challenge)})
            return

        if parsed.path == CALLBACK_PATH:
            return self._callback(query)

        self._send(200, LOGIN_PAGE)

    def _callback(self, query):
        if "error" in query:
            return self._send(400, "<h3>授权被拒</h3><pre>%s</pre>"
                              % html.escape(str(query)))
        state, code = query.get("state"), query.get("code")
        pending = PENDING.pop(state, None)
        if pending is None:
            # ★state 不匹配 = 疑似 CSRF，必须拒绝，不能图省事跳过
            return self._send(400, "<h3>state 校验失败，已拒绝（疑似 CSRF）</h3>")
        verifier, redirect_uri = pending
        try:
            token = nuwax.exchange_code(code, verifier, redirect_uri)
            me = nuwax.userinfo(token["access_token"])
        except nuwax.NuwaxError as exc:
            return self._send(502, "<h3>对接失败</h3><pre>%s</pre>" % html.escape(str(exc)))

        # ★把 access_token / refresh_token 存进你自己的会话或用户表。
        #   user_id（或 sub）就是「女娲用户 ↔ 你系统用户」的绑定键。
        self._send(200, """<h3>✅ 登录成功</h3>
<ul>
  <li>user_id: %(uid)s（★存这个作为你系统里的外部身份标识）</li>
  <li>昵称: %(name)s</li>
  <li>scope: %(scope)s</li>
  <li>access_token 有效期: %(exp)s 秒（每次校验通过会滑动续期）</li>
</ul>
<p>refresh_token 已拿到（★会轮换，续期后必须覆盖旧值），请存进你自己的会话存储。</p>
<script>
// ★本页由登录页的 window.open 打开 ⇒ 认证完成即通知原页面并关掉自己；
//   顶层打开（弹窗被拦截的降级场景）没有 opener，就照常显示本页。
if (window.opener && !window.opener.closed) {
  try { window.opener.postMessage({ type: "nuwax-oauth-done", ok: true },
                                  window.location.origin); } catch (e) {}
  window.close();
}
</script>
""" % {"uid": html.escape(str(me.get("user_id"))),
       "name": html.escape(str(me.get("name") or "")),
       "scope": html.escape(str(token.get("scope") or "")),
       "exp": html.escape(str(token.get("expires_in") or ""))})

    def log_message(self, fmt, *args):
        sys.stderr.write("[demo] " + (fmt % args) + "\\n")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    print("回调路径取自 REDIRECT_URI = %s" % nuwax.REDIRECT_URI)
    print("★若它不是 http://127.0.0.1:%d/... ，请先把平台上的登记改成一致" % port)
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
'''

PY_ENV = '''\
# 女娲平台对接凭据 —— 复制成 .env 后填入真实值；★.env 不要进 git
NUWAX_BASE="@@BASE@@"
NUWAX_CLIENT_ID="@@CLIENT_ID@@"
@@SECRET_ENV@@="@@SECRET_PLACEHOLDER@@"
NUWAX_REDIRECT_URI="@@REDIRECT@@"
NUWAX_SCOPE="@@SCOPE@@"
'''


# ------------------------------------------------------------------ 模板：Node.js
NODE_CLIENT = '''\
/**
 * 女娲平台 OAuth2 对接客户端（Node.js 18+，用内置 fetch / crypto，无第三方依赖）。
 * 由 nuwax-oauth2 技能的 scaffold 生成，已预填本应用的接入参数。
 * ★client_secret 从环境变量 @@SECRET_ENV@@ 读取，不在本文件里。
 */
"use strict";
const crypto = require("crypto");

const BASE_URL = process.env.NUWAX_BASE || "@@BASE@@";
const CLIENT_ID = process.env.NUWAX_CLIENT_ID || "@@CLIENT_ID@@";
// ★必须与平台上登记的回调地址逐字相等
const REDIRECT_URI = process.env.NUWAX_REDIRECT_URI || "@@REDIRECT@@";
const DEFAULT_SCOPE = process.env.NUWAX_SCOPE || "@@SCOPE@@";
const CLIENT_SECRET = process.env.@@SECRET_ENV@@ || "";
const CODE_OK = new Set(["0000", "0"]);   // ★门面端点 HTTP 恒 200，成败只看 code

class NuwaxProtocolError extends Error {
  constructor(status, error, description) {
    super(`HTTP ${status} ${error}: ${description || ""}`);
    this.status = status;
    this.error = error;
    this.description = description;
  }
}

class NuwaxApiError extends Error {
  constructor(code, message, envelope) {
    super(`code=${code} message=${message}`);
    this.code = code;
    this.envelope = envelope;
  }
}

const requireSecret = () => {
  if (!CLIENT_SECRET) throw new Error("环境变量 @@SECRET_ENV@@ 未设置");
  return CLIENT_SECRET;
};

/** 返回 { verifier, challenge }；verifier 43 字符，满足平台 43~128 约束。 */
function newPkce() {
  const verifier = crypto.randomBytes(32).toString("base64url");
  const challenge = crypto.createHash("sha256").update(verifier, "ascii").digest("base64url");
  return { verifier, challenge };
}

const newState = () => crypto.randomBytes(16).toString("base64url");

/** 拼授权地址；state 与 verifier 必须存进你的服务端 session。 */
function authorizationUrl(state, codeChallenge, scope) {
  const params = new URLSearchParams({
    response_type: "code",
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    scope: scope || DEFAULT_SCOPE,
    state,
    code_challenge: codeChallenge,
    code_challenge_method: "S256",
  });
  return `${BASE_URL}/api/oauth2/authorize?${params}`;
}

/** 协议端点：RFC 扁平结构，失败时 HTTP 状态码是真实的（400/401）。 */
async function postForm(path, form) {
  const response = await fetch(BASE_URL + path, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(form),
  });
  const text = await response.text();
  let body;
  try { body = JSON.parse(text || "{}"); } catch (_) { body = {}; }
  if (!response.ok || body.error) {
    throw new NuwaxProtocolError(response.status, body.error, body.error_description);
  }
  return body;
}

/** 授权码换令牌。★code 5 分钟有效且一次性，失败也算已消费，不能重试。 */
const exchangeCode = (code, codeVerifier, redirectUri) => postForm("/api/oauth2/token", {
  grant_type: "authorization_code",
  code,
  redirect_uri: redirectUri || REDIRECT_URI,   // 须与 authorize 阶段逐字相等
  code_verifier: codeVerifier,
  client_id: CLIENT_ID,
  client_secret: requireSecret(),
});

/** 模式 A：机器对机器。★不返回 refresh_token，过期后重跑即可。 */
function clientCredentialsToken(scope) {
  const form = {
    grant_type: "client_credentials",
    client_id: CLIENT_ID,
    client_secret: requireSecret(),
  };
  if (scope) form.scope = scope;
  return postForm("/api/oauth2/token", form);
}

/** 续期。★refresh_token 会轮换：新值才有效，必须覆盖存储。 */
const refresh = (refreshToken) => postForm("/api/oauth2/token", {
  grant_type: "refresh_token",
  refresh_token: refreshToken,
  client_id: CLIENT_ID,
  client_secret: requireSecret(),
});

/** 撤销。★恒 200（无效令牌也 200），且是用户级：该用户在本应用下令牌全失效。 */
const revoke = (token) => postForm("/api/oauth2/revoke", {
  token,
  client_id: CLIENT_ID,
  client_secret: requireSecret(),
});

/** 门面端点信封 → data。★HTTP 恒 200，成败只看 code。 */
function unwrap(envelope) {
  if (envelope.code === undefined || envelope.code === null) {
    // ★缺 code 就是「拿错端点类型了」，不是业务失败——直接报顶层字段名，别让它退化成
    //   「code=：未知错误」那种查不下去的提示。
    throw new Error(
      `响应不是平台信封（顶层字段：${Object.keys(envelope).join(", ") || "(无)"}）。` +
      "/userinfo 与 /user/search 返回裸对象、/token 与 /revoke 返回 RFC 结构，这三类都不能走 unwrap()。");
  }
  const code = String(envelope.code);
  if (!CODE_OK.has(code)) throw new NuwaxApiError(code, envelope.message, envelope);
  const data = envelope.data;
  // 工具执行类端点外层恒 0000，业务成败藏在 data.success
  if (data && typeof data === "object" && data.success === false) {
    throw new NuwaxApiError(code,
      `工具执行失败: ${data.error || data.message || "(未给)"}`, envelope);
  }
  return data;
}

/**
 * 发一次业务请求，返回响应体的原始 JSON。
 * 门面端点回来的是信封（要自己看 code / tid 就用这个）；/userinfo、/user/search 回来的是
 * 裸对象，本身就是要的数据。区分办法：响应里有 code 才是信封。
 * @param {string} path 形如 im/contact/list
 * @param {object} opts { method, query, body }
 *   ★conversation/delete、message/stop、message/clear 的参数走 query，不是 body
 *   ★POST 且不给 body 时自动发 {}（工具执行类的 params 就是整个请求体）
 *   ★SSE 端点（message/subscribe、message/send、workflow/sse-execute）请自行流式读取
 */
async function request(path, accessToken, opts) {
  const { method = "GET", query, body } = opts || {};
  const url = new URL(`${BASE_URL}/api/oauth2/${String(path).replace(/^\\/+/, "")}`);
  for (const [key, value] of Object.entries(query || {})) {
    if (value !== undefined && value !== null) url.searchParams.set(key, String(value));
  }
  const init = { method, headers: { Authorization: `Bearer ${accessToken}` } };
  if (body !== undefined && body !== null) {
    init.headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  } else if (String(method).toUpperCase() === "POST") {
    init.headers["Content-Type"] = "application/json";
    init.body = "{}";
  }
  const response = await fetch(url, init);
  const text = await response.text();
  try {
    return JSON.parse(text || "{}");
  } catch (_) {
    throw new Error(`响应不是合法 JSON（SSE 端点请改用流式读取）: ${text.slice(0, 200)}`);
  }
}

/** 调 29 个门面端点之一，直接返回 data。 */
async function call(path, accessToken, opts) {
  return unwrap(await request(path, accessToken, opts));
}

/**
 * 模式 B 登录成功后拿身份：sub / user_id 是你该长期保存的稳定标识。
 * ★本端点返回裸对象（sub/user_id/name/avatar/tenant_id），没有 code/message/data，
 *   所以不能走 call()——那会把正常响应判成失败。别顺手改回去。
 */
async function userinfo(accessToken) {
  const body = await request("/userinfo", accessToken, {});
  if (body && typeof body === "object" && body.code !== undefined) {
    return unwrap(body);     // 平台将来若统一成信封，这里自动跟上
  }
  return body;
}

module.exports = {
  BASE_URL, CLIENT_ID, REDIRECT_URI, DEFAULT_SCOPE,
  NuwaxProtocolError, NuwaxApiError,
  newPkce, newState, authorizationUrl,
  exchangeCode, clientCredentialsToken, refresh, revoke,
  unwrap, request, call, userinfo,
};
'''

NODE_SERVER = '''\
/**
 * 最小可跑的回调服务示例（Node 18+，内置 http，零依赖，仅用于本地联调）。
 *
 *     @@SECRET_ENV@@="@@SECRET_PLACEHOLDER@@" node example-server.js 3000
 *
 * 浏览器打开 http://127.0.0.1:3000/login 。
 * ★生产请换成你自己的框架，并把 pending / 令牌存进服务端 session 或 Redis。
 * ★平台上登记的 redirect_uri 必须与本服务对外地址逐字一致（含协议、端口、路径）。
 * ★多环境部署时，把本次授权用的 redirect_uri 连同 state、code_verifier 一起存进 session，
 *   回调阶段取出来原样回传给 exchangeCode —— 重拼一个很容易与浏览器地址栏对不上。
 */
"use strict";
const http = require("http");
const nuwax = require("./nuwax-oauth2");

// state -> { verifier, redirectUri }：★redirectUri 跟 state 一起存、回调时原样回传，
// 因为 token 阶段会再校验一次、必须与 authorize 阶段逐字相等（演示用）
const PENDING = new Map();
const CALLBACK_PATH = new URL(nuwax.REDIRECT_URI).pathname;

// ★登录入口 = 弹「小窗」发起授权，认证完成后由回调页自己关闭（见页面里的脚本）。
//   ⛔ 不要写成整页跳转（<a href="/oauth/start"> / location.href= / 服务端直接 302）——
//   用户会离开你的应用，认证完也回不去；只有弹窗被浏览器拦截时才降级为顶层跳转。
const LOGIN_PAGE = `<!doctype html>
<meta charset="utf-8">
<title>登录</title>
<button type="button" onclick="nuwaxLogin()">用女娲账号登录</button>
<script>
var AUTH_START = "/oauth/start";        // 授权入口：服务端 302 到女娲授权端点
var WINDOW_NAME = "nuwax-oauth";        // 固定 name ⇒ 反复点击复用同一个窗口

function nuwaxLogin() {
  var win = window.open(AUTH_START, WINDOW_NAME, "width=520,height=640");
  if (!win) { location.href = AUTH_START; }   // ★被拦截才降级，别把它当默认
}

// 弹窗认证完成后回传结果 ⇒ 刷新本页登录态。★必须校验来源，只认本应用自己的 origin
window.addEventListener("message", function (event) {
  if (event.origin !== window.location.origin) return;
  if (event.data && event.data.type === "nuwax-oauth-done") location.reload();
});
</script>
`;
const escape = (value) => String(value).replace(/[&<>"]/g, (ch) => (
  { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));

http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  const send = (status, body, headers) => {
    res.writeHead(status, Object.assign({ "Content-Type": "text/html; charset=utf-8" }, headers));
    res.end(body);
  };

  if (url.pathname === "/oauth/start") {
    const state = nuwax.newState();
    const { verifier, challenge } = nuwax.newPkce();
    PENDING.set(state, { verifier, redirectUri: nuwax.REDIRECT_URI });
    return send(302, "", { Location: nuwax.authorizationUrl(state, challenge) });
  }

  if (url.pathname === CALLBACK_PATH) {
    if (url.searchParams.get("error")) {
      const detail = {};
      for (const [key, value] of url.searchParams) detail[key] = value;
      return send(400, `<h3>授权被拒</h3><pre>${escape(JSON.stringify(detail, null, 2))}</pre>`);
    }
    const state = url.searchParams.get("state");
    const pending = PENDING.get(state);
    PENDING.delete(state);
    if (!pending) {
      // ★state 不匹配 = 疑似 CSRF，必须拒绝
      return send(400, "<h3>state 校验失败，已拒绝（疑似 CSRF）</h3>");
    }
    try {
      const token = await nuwax.exchangeCode(url.searchParams.get("code"),
                                             pending.verifier, pending.redirectUri);
      const me = await nuwax.userinfo(token.access_token);
      // ★把 access_token / refresh_token 存进你自己的会话或用户表；
      //   me.user_id（或 me.sub）就是「女娲用户 ↔ 你系统用户」的绑定键。
      return send(200, `<h3>✅ 登录成功</h3><ul>
        <li>user_id: ${escape(me.user_id)}（★存这个作为外部身份标识）</li>
        <li>昵称: ${escape(me.name || "")}</li>
        <li>scope: ${escape(token.scope || "")}</li>
        <li>access_token 有效期: ${escape(token.expires_in)} 秒（校验通过会滑动续期）</li>
      </ul><p>接着就能用 nuwax.call(path, accessToken) 调 29 个开放接口。</p>
<script>
// ★本页由登录页的 window.open 打开 ⇒ 认证完成即通知原页面并关掉自己；
//   顶层打开（弹窗被拦截的降级场景）没有 opener，就照常显示本页。
if (window.opener && !window.opener.closed) {
  try { window.opener.postMessage({ type: "nuwax-oauth-done", ok: true },
                                  window.location.origin); } catch (e) {}
  window.close();
}
</script>`);
    } catch (err) {
      return send(502, `<h3>对接失败</h3><pre>${escape(err.message)}</pre>`);
    }
  }

  send(200, LOGIN_PAGE);
}).listen(Number(process.argv[2] || 3000), "127.0.0.1", function () {
  console.log(`回调路径取自 REDIRECT_URI = ${nuwax.REDIRECT_URI}`);
  console.log(`★若它不是 http://127.0.0.1:${this.address().port}/... ，请先把平台登记改成一致`);
});
'''

NODE_ENV = '''\
# 女娲平台对接凭据 —— 复制成 .env 后填入真实值；★.env 不要进 git
NUWAX_BASE="@@BASE@@"
NUWAX_CLIENT_ID="@@CLIENT_ID@@"
@@SECRET_ENV@@="@@SECRET_PLACEHOLDER@@"
NUWAX_REDIRECT_URI="@@REDIRECT@@"
NUWAX_SCOPE="@@SCOPE@@"
'''


# ------------------------------------------------------------------ 模板：Spring Boot
JAVA_CLIENT = '''\
package @@PACKAGE@@;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 女娲平台 OAuth2 对接客户端（JDK 11+ HttpClient，无需引入 OAuth2 客户端库）。
 * 由 nuwax-oauth2 技能的 scaffold 生成。
 *
 * <p>★client_secret 走配置注入（见 application-nuwax.yml），绝不硬编码进源码。
 * <p>两种模式：A. client_credentials（全自动，拿不到 im:* / model:*）；
 * B. authorization_code + PKCE（真人登录一次，可拿全部 13 个 scope）。
 */
@Component
public class NuwaxOAuth2Client {

    /** ★门面端点 HTTP 恒 200，成败只能看信封里的 code。 */
    private static final String CODE_OK = "0000";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${nuwax.base-url:@@BASE@@}")
    private String baseUrl;

    @Value("${nuwax.client-id:@@CLIENT_ID@@}")
    private String clientId;

    /** ★必须与平台上登记的回调地址逐字相等。 */
    @Value("${nuwax.redirect-uri:@@REDIRECT@@}")
    private String redirectUri;

    @Value("${nuwax.client-secret:}")
    private String clientSecret;

    @Value("${nuwax.default-scope:@@SCOPE@@}")
    private String defaultScope;

    // ------------------------------------------------------------ PKCE

    /** 返回 [code_verifier, code_challenge]；verifier 43 字符（平台要求 43~128）。 */
    public static String[] newPkce() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String verifier = B64URL.encodeToString(raw);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return new String[]{verifier, B64URL.encodeToString(digest)};
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String newState() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return B64URL.encodeToString(raw);
    }

    // ------------------------------------------------------------ 模式 B：授权码

    /** 本环境配置的回调地址。授权开始时请与 verifier 一起存进 session，回调时原样回传。 */
    public String getRedirectUri() {
        return redirectUri;
    }

    /** 拼授权地址，把浏览器重定向过去。state 与 verifier 请存进 HttpSession。 */
    public String authorizationUrl(String state, String codeChallenge, String scope) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("scope", (scope == null || scope.isEmpty()) ? defaultScope : scope);
        params.put("state", state);
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        return baseUrl + "/api/oauth2/authorize?" + encodeForm(params);
    }

    /**
     * 授权码换令牌。★code 5 分钟内有效且一次性，失败也算已消费，不能重试。
     *
     * @return 含 access_token / refresh_token / expires_in / scope
     */
    public JsonNode exchangeCode(String code, String codeVerifier) throws Exception {
        return exchangeCode(code, codeVerifier, redirectUri);
    }

    /**
     * 同上，但显式指定回调地址 —— 多环境 / 多域名时，把 authorize 阶段实际用的那个原样传回来。
     * ★必须与 authorize 阶段逐字相等，不能在这一步重新拼。
     */
    public JsonNode exchangeCode(String code, String codeVerifier, String redirectUri) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri != null ? redirectUri : this.redirectUri);
        form.put("code_verifier", codeVerifier);
        return token(form);
    }

    /** 续期。★refresh_token 会轮换：返回的新值才有效，必须覆盖你的存储。 */
    public JsonNode refresh(String refreshToken) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        return token(form);
    }

    // ------------------------------------------------------------ 模式 A：机器对机器

    /** ★不返回 refresh_token；access_token 过期后重跑本方法即可。 */
    public JsonNode clientCredentialsToken(String scope) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        if (scope != null && !scope.isEmpty()) {
            form.put("scope", scope);
        }
        return token(form);
    }

    // ------------------------------------------------------------ 撤销

    /** ★恒返回 200（令牌无效也 200），且是用户级：该用户在本应用下的令牌全部失效。 */
    public void revoke(String tokenValue) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", tokenValue);
        form.put("client_id", clientId);
        form.put("client_secret", requireSecret());
        postForm("/api/oauth2/revoke", form);
    }

    // ------------------------------------------------------------ 业务接口

    /**
     * 模式 B 登录成功后取身份：sub / user_id 是你该长期保存的稳定标识。
     *
     * <p>★本端点返回裸对象（sub/user_id/name/avatar/tenant_id），没有 code/message/data，
     * 所以不能走 {@link #call}——那会把正常响应判成失败。别顺手改回去。
     */
    public JsonNode userinfo(String accessToken) throws Exception {
        JsonNode body = request("userinfo", accessToken, "GET", null, null);
        if (body.has("code")) {
            return unwrap(body);     // 平台将来若统一成信封，这里自动跟上
        }
        return body;
    }

    /** 调 29 个门面端点之一，直接返回信封里的 data。 */
    public JsonNode call(String path, String accessToken, String method,
                         Map<String, String> query, Object body) throws Exception {
        return unwrap(request(path, accessToken, method, query, body));
    }

    /**
     * 发一次业务请求，返回响应体的原始 JSON。
     *
     * <p>门面端点回来的是信封（要自己看 code / tid 就用这个）；/userinfo、/user/search 回来的是
     * 裸对象，本身就是要的数据。区分办法：响应里有 {@code code} 才是信封。
     *
     * @param path  形如 {@code im/contact/list}（不带 /api/oauth2 前缀）
     * @param query 查询参数，可为 null。★conversation/delete、message/stop、message/clear
     *              的参数是 {@code @RequestParam}，要放这里而不是 body
     * @param body  请求体对象，可为 null。★POST 且 body 为 null 时自动发 {}
     *              （工具执行类端点的 params 就是整个请求体，无参也得给空对象）
     */
    public JsonNode request(String path, String accessToken, String method,
                            Map<String, String> query, Object body) throws Exception {
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/api/oauth2/")
                .append(path.replaceAll("^/+", ""));
        if (query != null && !query.isEmpty()) {
            url.append('?').append(encodeForm(query));
        }
        String payload;
        if (body != null) {
            payload = mapper.writeValueAsString(body);
        } else if ("POST".equalsIgnoreCase(method)) {
            payload = "{}";
        } else {
            payload = null;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken);
        if (payload != null) {
            builder.header("Content-Type", "application/json")
                    .method(method.toUpperCase(),
                            HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        } else {
            builder.method(method.toUpperCase(), HttpRequest.BodyPublishers.noBody());
        }
        return mapper.readTree(send(builder.build()));
    }

    /** 门面端点信封 → data。★HTTP 恒 200，成败只看 code。 */
    public JsonNode unwrap(JsonNode envelope) {
        if (!envelope.has("code")) {
            // ★缺 code 就是「拿错端点类型了」，不是业务失败——直接报顶层字段名，别让它退化成
            //   「code=：未知错误」那种查不下去的提示。故意不用 NuwaxApiException：
            //   业务异常的 catch 块不该把「代码写错了」这种编程错误当成一次正常的业务失败吞掉。
            throw new NuwaxResponseShapeException(
                    "响应不是平台信封（顶层字段：" + fieldNames(envelope) + "）。"
                            + "/userinfo 与 /user/search 返回裸对象、/token 与 /revoke 返回 RFC 结构，"
                            + "这三类都不能走 unwrap()。", envelope);
        }
        String code = envelope.path("code").asText("");
        if (!CODE_OK.equals(code) && !"0".equals(code)) {
            throw new NuwaxApiException(code, envelope.path("message").asText(""), envelope);
        }
        JsonNode data = envelope.path("data");
        // 工具执行类端点外层恒 0000，业务成败藏在 data.success
        if (data.isObject() && data.has("success") && !data.path("success").asBoolean(true)) {
            String reason = data.hasNonNull("error") ? data.path("error").asText()
                    : data.path("message").asText("(未给)");
            throw new NuwaxApiException(code, "工具执行失败: " + reason, envelope);
        }
        return data;
    }

    // ------------------------------------------------------------ 内部

    private JsonNode token(Map<String, String> grant) throws Exception {
        grant.put("client_id", clientId);
        grant.put("client_secret", requireSecret());
        JsonNode body = mapper.readTree(postForm("/api/oauth2/token", grant));
        if (body.has("error")) {
            throw new NuwaxProtocolException(0, body.path("error").asText(),
                    body.path("error_description").asText(""));
        }
        return body;
    }

    /** 协议端点返回 RFC 6749 扁平结构，失败时 HTTP 状态码是真实的（400/401）。 */
    private String postForm(String path, Map<String, String> fields) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(fields), StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private String send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        String text = response.body() == null ? "" : response.body();
        if (status >= 400) {
            JsonNode body;
            try {
                body = mapper.readTree(text.isEmpty() ? "{}" : text);
            } catch (Exception e) {
                body = mapper.createObjectNode();
            }
            throw new NuwaxProtocolException(status,
                    body.path("error").asText("http_" + status),
                    body.path("error_description").asText(text));
        }
        return text;
    }

    private String requireSecret() {
        if (clientSecret == null || clientSecret.isEmpty()) {
            throw new IllegalStateException(
                    "未配置 nuwax.client-secret（建议用环境变量 @@SECRET_ENV@@ 注入）");
        }
        return clientSecret;
    }

    /** 顶层字段名列表，用于「这响应根本不是信封」时把实际拿到的字段告诉调用方 */
    private static String fieldNames(JsonNode node) {
        StringBuilder names = new StringBuilder();
        node.fieldNames().forEachRemaining(name -> {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(name);
        });
        return names.length() == 0 ? "(无)" : names.toString();
    }

    private static String encodeForm(Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    /** 协议端点的 RFC 错误。 */
    public static class NuwaxProtocolException extends RuntimeException {
        private final int status;

        public NuwaxProtocolException(int status, String error, String description) {
            super(error + ": " + description);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /** 拿到的响应根本不是平台信封（例如把裸对象端点 /userinfo 走了 unwrap）。 */
    public static class NuwaxResponseShapeException extends RuntimeException {
        private final transient JsonNode body;

        public NuwaxResponseShapeException(String message, JsonNode body) {
            super(message);
            this.body = body;
        }

        public JsonNode getBody() {
            return body;
        }
    }

    /** 29 个门面端点信封里的 code 不是 0000。 */
    public static class NuwaxApiException extends RuntimeException {
        private final String code;
        private final transient JsonNode envelope;

        public NuwaxApiException(String code, String message, JsonNode envelope) {
            super("code=" + code + " message=" + message);
            this.code = code;
            this.envelope = envelope;
        }

        public String getCode() {
            return code;
        }

        public JsonNode getEnvelope() {
            return envelope;
        }
    }
}
'''

JAVA_CONTROLLER = '''\
package @@PACKAGE@@;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * 授权码 + PKCE 回调示例。由 nuwax-oauth2 技能的 scaffold 生成。
 *
 * <p>★Spring Boot 2.x 请把 jakarta.servlet 换成 javax.servlet。
 * <p>★/oauth/callback 的路径必须与平台上登记的 redirect_uri 逐字一致。
 * <p>★多环境部署时，把本次授权实际用的 redirect_uri 连同 state、code_verifier 一起存进 session，
 * 回调阶段取出来原样交给 exchangeCode —— 在反代 / 容器里重拼一个，很容易与浏览器地址栏对不上。
 * <p>这里用 HttpSession 存 PKCE 中间态与令牌，仅为演示；多实例部署请换
 * Spring Session + Redis，或把令牌存进你自己的用户表。
 */
@RestController
public class NuwaxOAuth2Controller {

    private static final String ATTR_STATE = "NUWAX_OAUTH2_STATE";
    private static final String ATTR_VERIFIER = "NUWAX_OAUTH2_VERIFIER";
    /** 本次授权用的回调地址：★与 verifier 同时存、回调时原样回传（token 阶段要再校验一次） */
    private static final String ATTR_REDIRECT_URI = "NUWAX_OAUTH2_REDIRECT_URI";
    public static final String ATTR_ACCESS_TOKEN = "NUWAX_ACCESS_TOKEN";
    public static final String ATTR_REFRESH_TOKEN = "NUWAX_REFRESH_TOKEN";

    /** 登录页 HTML：★弹「小窗」发起授权，认证完成后由回调页自己关闭。 */
    private static final String LOGIN_PAGE =
            "<!doctype html><meta charset='utf-8'><title>登录</title>"
            + "<button type='button' onclick='nuwaxLogin()'>用女娲账号登录</button>"
            + "<script>"
            // 授权入口：服务端 302 到女娲授权端点；WINDOW_NAME 固定 ⇒ 反复点击复用同一个窗口
            + "var AUTH_START = '/oauth/start', WINDOW_NAME = 'nuwax-oauth';"
            + "function nuwaxLogin() {"
            + "  var win = window.open(AUTH_START, WINDOW_NAME, 'width=520,height=640');"
            // ★被浏览器拦截才降级为顶层跳转，别把它当默认
            + "  if (!win) { location.href = AUTH_START; }"
            + "}"
            // 弹窗认证完成后回传结果 ⇒ 刷新本页登录态。★必须校验来源，只认本应用自己的 origin
            + "window.addEventListener('message', function (event) {"
            + "  if (event.origin !== window.location.origin) return;"
            + "  if (event.data && event.data.type === 'nuwax-oauth-done') location.reload();"
            + "});"
            + "</script>";

    private final NuwaxOAuth2Client client;

    public NuwaxOAuth2Controller(NuwaxOAuth2Client client) {
        this.client = client;
    }

    /** 登录页：★弹「小窗」发起授权、认证完成自动关（见页面里的脚本），别整页跳转。 */
    @GetMapping(value = "/login", produces = "text/html;charset=UTF-8")
    public String loginPage() {
        return LOGIN_PAGE;
    }

    /** 第一步：把浏览器重定向到女娲授权端点（小窗导航到这里；弹窗被拦截时顶层也跳这里）。 */
    @GetMapping("/oauth/start")
    public void start(HttpSession session, HttpServletResponse response,
                      @RequestParam(required = false) String scope) throws IOException {
        String[] pkce = NuwaxOAuth2Client.newPkce();
        String state = NuwaxOAuth2Client.newState();
        session.setAttribute(ATTR_STATE, state);
        session.setAttribute(ATTR_VERIFIER, pkce[0]);
        // ★把本次授权用的回调地址与 verifier 一起存下，回调阶段原样回传（token 阶段要再校验一次）
        session.setAttribute(ATTR_REDIRECT_URI, client.getRedirectUri());
        response.sendRedirect(client.authorizationUrl(state, pkce[1], scope));
    }

    /**
     * 第二步：处理回调。★code 5 分钟有效且一次性，失败也算已消费，不能重试。
     * 平台当前没有同意页，登录后直接 302 回这里并带上 code 与 state。
     */
    @GetMapping(value = "/oauth/callback", produces = "text/html;charset=UTF-8")
    public String callback(@RequestParam Map<String, String> params, HttpSession session)
            throws Exception {
        if (params.containsKey("error")) {
            return "<h3>授权被拒</h3><pre>" + escape(params.toString()) + "</pre>";
        }
        String state = params.get("state");
        Object expected = session.getAttribute(ATTR_STATE);
        String verifier = (String) session.getAttribute(ATTR_VERIFIER);
        String redirectUri = (String) session.getAttribute(ATTR_REDIRECT_URI);
        session.removeAttribute(ATTR_STATE);
        session.removeAttribute(ATTR_VERIFIER);
        session.removeAttribute(ATTR_REDIRECT_URI);
        if (expected == null || !expected.equals(state) || verifier == null) {
            // ★state 不匹配 = 疑似 CSRF，必须拒绝，不能图省事跳过
            return "<h3>state 校验失败，已拒绝（疑似 CSRF）</h3>";
        }

        JsonNode token = client.exchangeCode(params.get("code"), verifier, redirectUri);
        String accessToken = token.path("access_token").asText();
        String refreshToken = token.path("refresh_token").asText(null);

        // ★令牌存进你自己的会话或用户表；refresh 会轮换 refresh_token，续期后必须覆盖旧值。
        session.setAttribute(ATTR_ACCESS_TOKEN, accessToken);
        if (refreshToken != null) {
            session.setAttribute(ATTR_REFRESH_TOKEN, refreshToken);
        }

        JsonNode me = client.userinfo(accessToken);
        long userId = me.path("user_id").asLong();
        // ★userId（或 sub）就是你在自己系统里该长期保存的外部身份标识，
        //   在这里完成「女娲用户 ↔ 你系统用户」的绑定或自动建号。

        return "<h3>✅ 登录成功</h3><ul>"
                + "<li>user_id: " + userId + "（★存这个作为外部身份标识）</li>"
                + "<li>昵称: " + escape(me.path("name").asText("")) + "</li>"
                + "<li>scope: " + escape(token.path("scope").asText("")) + "</li>"
                + "<li>access_token 有效期: " + token.path("expires_in").asLong()
                + " 秒（每次校验通过会滑动续期）</li>"
                + "</ul><p>接着就能用 client.call(path, accessToken, ...) 调 27 个开放接口。</p>"
                // ★本页由登录页的 window.open 打开 ⇒ 认证完成即通知原页面并关掉自己；
                //   顶层打开（弹窗被拦截的降级场景）没有 opener，就照常显示本页。
                + "<script>"
                + "if (window.opener && !window.opener.closed) {"
                + "  try { window.opener.postMessage({type:'nuwax-oauth-done', ok:true},"
                + " window.location.origin); } catch (e) {}"
                + "  window.close();"
                + "}"
                + "</script>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
'''

JAVA_YML = '''\
# 女娲平台对接配置 —— 并进你的 application.yml，或单独作为 application-nuwax.yml
# ★client-secret 用环境变量注入，不要把明文写进仓库
nuwax:
  base-url: @@BASE@@
  client-id: @@CLIENT_ID@@
  redirect-uri: @@REDIRECT@@        # ★必须与平台上登记的逐字相等
  default-scope: "@@SCOPE@@"
  client-secret: ${@@SECRET_ENV@@:}
'''


# ------------------------------------------------------------------ 模板：curl / shell
SHELL_SCRIPT = '''\
#!/usr/bin/env bash
# 女娲平台 OAuth2 对接助手（curl + jq）。由 nuwax-oauth2 技能的 scaffold 生成。
#
#   source nuwax.sh                     # 或 chmod +x 后直接执行
#   nuwax_m2m_token                     # 模式 A：机器对机器换令牌
#   nuwax_auth_url "profile,chat:read"  # 模式 B：打印授权地址（人工在浏览器打开）
#   nuwax_exchange_code "<code>"        # verifier/state 自动从状态文件读
#   nuwax_call im/contact/list          # 调业务接口，直接返回 data
#   nuwax_userinfo                      # 取当前登录用户身份（裸对象，不走 nuwax_call）
#
# ★client_secret 从环境变量 @@SECRET_ENV@@ 读取，不在本文件里。
# ★刻意不用 set -e/-u：本文件设计成可 source，会污染调用方 shell 的选项。

NUWAX_BASE="${NUWAX_BASE:-@@BASE@@}"
NUWAX_CLIENT_ID="${NUWAX_CLIENT_ID:-@@CLIENT_ID@@}"
# ★必须与平台上登记的回调地址逐字相等
NUWAX_REDIRECT_URI="${NUWAX_REDIRECT_URI:-@@REDIRECT@@}"
NUWAX_SCOPE="${NUWAX_SCOPE:-@@SCOPE@@}"
NUWAX_STATE_FILE="${NUWAX_STATE_FILE:-$HOME/.nuwax-oauth2-pending}"

for _dep in curl jq openssl; do
  command -v "$_dep" >/dev/null 2>&1 || echo "⚠️  缺依赖：$_dep" >&2
done
unset _dep

_nuwax_secret() {
  if [ -z "${@@SECRET_ENV@@:-}" ]; then
    echo "环境变量 @@SECRET_ENV@@ 未设置" >&2
    return 1
  fi
  printf '%s' "$@@SECRET_ENV@@"
}

# 协议端点：返回 RFC 6749 扁平结构，失败时 HTTP 状态码是真实的（400/401）。
# ★换令牌失败时授权码已被一次性消费，不能重试，只能重新生成授权地址。
_nuwax_token() {
  local secret response status body
  secret=$(_nuwax_secret) || return 1
  response=$(curl -sS -w '\\n%{http_code}' -X POST "$NUWAX_BASE/api/oauth2/token" \\
    -H 'Content-Type: application/x-www-form-urlencoded' \\
    --data-urlencode "client_id=$NUWAX_CLIENT_ID" \\
    --data-urlencode "client_secret=$secret" \\
    "$@")
  status="${response##*$'\\n'}"
  body="${response%$'\\n'*}"
  if [ "$status" != "200" ] || [ -n "$(jq -r '.error // empty' <<<"$body")" ]; then
    echo "换令牌失败 HTTP $status: $(jq -r '.error // ""' <<<"$body") / $(jq -r '.error_description // ""' <<<"$body")" >&2
    return 1
  fi
  printf '%s' "$body"
}

# 模式 A：机器对机器。★不返回 refresh_token，过期后重跑即可。
# ★拿不到 im:read / im:push / model:read / model:key（这四个仅授权码可用）。
nuwax_m2m_token() {
  if [ -n "${1:-}" ]; then
    _nuwax_token --data-urlencode grant_type=client_credentials --data-urlencode "scope=$1"
  else
    _nuwax_token --data-urlencode grant_type=client_credentials
  fi
}

# PKCE：verifier 43~128 位 [A-Za-z0-9-._~]，challenge = base64url(sha256(verifier))
nuwax_auth_url() {
  local scope="${1:-$NUWAX_SCOPE}" verifier challenge state redirect
  verifier=$(openssl rand -base64 48 | tr -d '=+/\\n' | cut -c1-64)
  challenge=$(printf '%s' "$verifier" | openssl dgst -binary -sha256 \\
    | openssl base64 | tr '+/' '-_' | tr -d '=\\n')
  state=$(openssl rand -hex 16)
  redirect=$(jq -rn --arg v "$NUWAX_REDIRECT_URI" '$v|@uri')
  # ★redirect_uri 与 state/verifier 一起存：token 阶段要逐字回传同一个值
  printf 'state=%s\\nverifier=%s\\nredirect_uri=%s\\n' \\
    "$state" "$verifier" "$NUWAX_REDIRECT_URI" > "$NUWAX_STATE_FILE"
  chmod 600 "$NUWAX_STATE_FILE"
  echo "★请在浏览器打开下面的地址并登录；平台当前没有同意页，登录后直接 302 回你的回调地址。" >&2
  echo "★回调地址必须与平台上登记的 redirect_uri 逐字相等，否则会被拒。" >&2
  printf '%s/api/oauth2/authorize?response_type=code&client_id=%s&redirect_uri=%s&scope=%s&state=%s&code_challenge=%s&code_challenge_method=S256\\n' \\
    "$NUWAX_BASE" "$NUWAX_CLIENT_ID" "$redirect" "$scope" "$state" "$challenge"
  echo "★state、code_verifier、redirect_uri 已存到 $NUWAX_STATE_FILE（0600）" >&2
}

# 模式 B：授权码换令牌。★redirect_uri 必须与 authorize 阶段逐字相等。
# 用法：nuwax_exchange_code <code> [verifier] [state]
#   不给 verifier 时自动读 $NUWAX_STATE_FILE（连 redirect_uri 一起读回来）；给了 state 则做 CSRF 校验。
nuwax_exchange_code() {
  local code="$1" verifier="${2:-}" state="${3:-}" redirect_uri=""
  if [ -z "$verifier" ] && [ -f "$NUWAX_STATE_FILE" ]; then
    verifier=$(sed -n 's/^verifier=//p' "$NUWAX_STATE_FILE")
    state=$(sed -n 's/^state=//p' "$NUWAX_STATE_FILE")
    redirect_uri=$(sed -n 's/^redirect_uri=//p' "$NUWAX_STATE_FILE")
  fi
  # 取回授权时用的那条；没有（旧状态文件/手工传 verifier）就退回当前配置
  [ -n "$redirect_uri" ] || redirect_uri="$NUWAX_REDIRECT_URI"
  if [ -n "${NUWAX_CALLBACK_STATE:-}" ] && [ "$NUWAX_CALLBACK_STATE" != "$state" ]; then
    echo "state 不匹配 —— 疑似 CSRF，已拒绝" >&2
    return 1
  fi
  if [ -z "$verifier" ]; then
    echo "缺 code_verifier（先跑 nuwax_auth_url，或作为第二个参数传入）" >&2
    return 1
  fi
  _nuwax_token --data-urlencode grant_type=authorization_code \\
    --data-urlencode "code=$code" \\
    --data-urlencode "redirect_uri=$redirect_uri" \\
    --data-urlencode "code_verifier=$verifier"
}

# 续期。★refresh_token 会轮换：输出里的新值才有效，必须覆盖你的存储。
nuwax_refresh() {
  _nuwax_token --data-urlencode grant_type=refresh_token --data-urlencode "refresh_token=$1"
}

# 撤销。★恒返回 200（令牌无效也 200），且是用户级：该用户在本应用下令牌全部失效。
nuwax_revoke() {
  local secret
  secret=$(_nuwax_secret) || return 1
  curl -sS -X POST "$NUWAX_BASE/api/oauth2/revoke" \\
    -H 'Content-Type: application/x-www-form-urlencoded' \\
    --data-urlencode "token=$1" \\
    --data-urlencode "client_id=$NUWAX_CLIENT_ID" \\
    --data-urlencode "client_secret=$secret"
  echo
}

# 门面端点：★HTTP 恒 200，成败只看信封里的 code == "0000"。
# 用法：nuwax_call <路径> [access_token] [方法] [JSON body] [query串]
#   nuwax_call im/contact/list "$TOKEN"
#   nuwax_call chat/conversation/delete "$TOKEN" POST '' 'conversationId=123456'
#   nuwax_call tool/plugin/execute "$TOKEN" POST '{}' 'pluginId=9'
# ★/userinfo 与 /user/search 返回裸对象、没有信封，走本函数会被判成失败 —— 用 nuwax_userinfo。
# ★SSE 端点（message/subscribe、message/send、workflow/sse-execute）请自行流式读取。
nuwax_call() {
  local path="$1" token="${2:-${NUWAX_ACCESS_TOKEN:-}}" method="${3:-GET}" \\
        body="${4:-}" query="${5:-}" url envelope code
  if [ -z "$token" ]; then
    echo "缺 access_token（第二个参数，或环境变量 NUWAX_ACCESS_TOKEN）" >&2
    return 1
  fi
  url="$NUWAX_BASE/api/oauth2/${path#/}"
  [ -n "$query" ] && url="$url?$query"
  if [ -n "$body" ]; then
    envelope=$(curl -sS -X "$method" "$url" -H "Authorization: Bearer $token" \\
      -H 'Content-Type: application/json' -d "$body")
  elif [ "$method" = "POST" ]; then
    # ★POST 无参也要给空对象：工具执行类端点的 params 就是整个请求体
    envelope=$(curl -sS -X POST "$url" -H "Authorization: Bearer $token" \\
      -H 'Content-Type: application/json' -d '{}')
  else
    envelope=$(curl -sS "$url" -H "Authorization: Bearer $token")
  fi
  code=$(jq -r '.code // empty' <<<"$envelope")
  if [ -z "$code" ]; then
    # ★缺 code 就是「拿错端点类型了」，不是业务失败 —— 报顶层字段名，别让它退化成查不下去的提示
    echo "★响应里没有 code 字段（顶层字段：$(jq -r 'keys | join(", ")' <<<"$envelope")）——这不是门面端点。" >&2
    echo "  /userinfo、/user/search 返回裸对象，请改用 nuwax_userinfo。" >&2
    printf '%s\\n' "$envelope"
    return 1
  fi
  if [ "$code" != "0000" ] && [ "$code" != "0" ]; then
    echo "★HTTP 可能是 200，但业务失败了 —— code=$code message=$(jq -r '.message // ""' <<<"$envelope")" >&2
    printf '%s\\n' "$envelope"
    return 1
  fi
  # 工具执行类端点外层恒 0000，业务成败藏在 data.success
  if [ "$(jq -r '.data.success // empty' <<<"$envelope")" = "false" ]; then
    echo "⚠️  外层 code=0000 但 data.success=false：这次执行失败了 —— $(jq -r '.data.error // .data.message // "(未给)"' <<<"$envelope")" >&2
  fi
  jq '.data' <<<"$envelope"
}

# 身份端点：★/userinfo 返回裸对象（sub/user_id/name/avatar/tenant_id），没有信封，
# 所以不能走 nuwax_call —— 那会把正常响应判成失败。
# 用法：nuwax_userinfo [access_token]
nuwax_userinfo() {
  local token="${1:-${NUWAX_ACCESS_TOKEN:-}}" body
  if [ -z "$token" ]; then
    echo "缺 access_token（第一个参数，或环境变量 NUWAX_ACCESS_TOKEN）" >&2
    return 1
  fi
  body=$(curl -sS "$NUWAX_BASE/api/oauth2/userinfo" -H "Authorization: Bearer $token")
  if printf '%s' "$body" | jq -e 'has("code")' >/dev/null 2>&1; then
    nuwax_call userinfo "$token"      # 平台将来若统一成信封，这里自动跟上
  else
    printf '%s\\n' "$body"
  fi
}
'''

SHELL_ENV = '''\
# 女娲平台对接凭据 —— 复制成 .env 后 source 它；★不要进 git
export NUWAX_BASE="@@BASE@@"
export NUWAX_CLIENT_ID="@@CLIENT_ID@@"
export @@SECRET_ENV@@="@@SECRET_PLACEHOLDER@@"
export NUWAX_REDIRECT_URI="@@REDIRECT@@"
export NUWAX_SCOPE="@@SCOPE@@"
'''


# ------------------------------------------------------------------ README
README = '''\
# 女娲平台对接包（@@STACK_LABEL@@）

由 `nuwax-oauth2` 技能生成。**认证跑在你自己的服务端里** —— 这里给的是可直接落进你仓库的
源码骨架，接入参数已按你名下这个应用预填好。

## 你的接入参数（已预填）

@@PARAMS_TABLE@@

★`client_secret` **不在生成物里** —— 这样生成物可以安全进 git、发给同事。
取法与注入方式见下面「密钥注入」；多环境怎么各配一份见「多环境部署」。

## 文件清单

@@FILES@@

## 三步跑起来

@@QUICKSTART@@

## ★六条平台契约（不照做一定会踩）

1. **三种响应格式并存，先认形状再判成败。** ①协议端点（`/token`、`/revoke`）返回 RFC 6749 扁平结构
   `{error, error_description}`，**HTTP 状态码是真实的**；②门面端点（29 个里除 3 个 SSE 外的 26 个）
   返回平台信封
   `{code, displayCode, message, data, tid}`，**HTTP 恒 200**（异常也 200）⇒ 判成败只看 `code == "0000"`，
   **不能看 HTTP 状态码**；③`userinfo`、`user/search` 返回**裸对象**（`sub`/`user_id`/`name`/`avatar`/
   `tenant_id` 或用户对象，没有 `code`/`message`/`data` 外壳），响应体本身就是你要的数据
   ⇒ **不能拿门面那套 `unwrap()` 去套**，套了会把正常响应判成失败。
   口诀：**响应里有 `code` 才是信封**。生成的客户端已按这个形状分好函数（`userinfo()` 不走 `unwrap`）。
2. **工具执行类有二级陷阱。** `tool/plugin/execute`、`tool/workflow/execute` 外层 `code`
   恒为 `0000`，真正的业务成败在 `data.success` 里；SSE 版失败时也不抛异常，而是在流内推一个
   `success=false, isComplete=true` 的帧。⇒ 必须解析帧内容，不能只看「连接成功了」。
3. **`redirect_uri` 是精确匹配，且一个应用可登记多个地址。** 服务端对登记值与请求值做字符串
   `equals`（不是前缀匹配），差一个斜杠、差一个端口、http 与 https 之差都会被拒；`token` 阶段
   还要再校验一次，必须与 `authorize` 阶段**逐字相等**。登记值须是绝对 http(s) 地址、有 host、
   不带 `#fragment`。★登记处分「主地址」与「补充列表」两格，**生效集合取并集**，任一相等即通过
   ⇒ 多环境（dev/staging/prod）各登记一条即可，不必来回改同一个地址。
4. **授权码 5 分钟有效且一次性。** 换令牌**失败也算已消费**，不能拿同一个 code 重试，
   只能重新走一遍授权。PKCE 的 `code_verifier` 与 `state` 必须在你服务端存活到回调
   （session / Redis），`state` 不匹配要直接拒绝（CSRF 防护）。
5. **`refresh_token` 会轮换。** 每次刷新返回的新值才有效，旧的立即作废 ⇒ 必须覆盖你的存储。
   `access_token` 默认 2 小时且**每次校验通过会滑动续期 2 小时**；`refresh_token` 默认 30 天。
   `client_credentials` **不签发 refresh_token**，过期后重新换一次即可。
6. **撤销是用户级的。** `/revoke` 按 RFC 7009 无论令牌是否有效都返回 200（这个「成功」只说明
   请求被接受），且会让该 (应用, 用户) 名下所有早于此刻签发的令牌一并失效，续期也会被拒。

## 选哪种模式

@@MODE_HINT@@

| | 模式 A：`client_credentials` | 模式 B：授权码 + PKCE |
|---|---|---|
| 谁的身份 | 你的服务端（机器身份） | **授权用户本人** |
| 要不要真人点浏览器 | 不要，全自动 | 要，登录一次 |
| 能拿的 scope | 8 个（**拿不到 `im:read`/`im:push`/`model:read`/`model:key`**） | 全部 12 个 |
| 有 refresh_token | ❌ 没有 | ✅ 有（会轮换） |
| 典型用途 | 后台批处理：查空间、传文件、发起对话、执行插件/工作流 | 「用女娲账号登录我的系统」 |

★显式用模式 A 申请那 4 个「仅授权码」scope 会被拒：`invalid_scope` /
`requires an end-user authorization`。

## 密钥注入

生成物里只有占位符 `@@SECRET_PLACEHOLDER@@`，真实 `client_secret` **不进代码、不进 git**。
取真实值：

- 在女娲沙箱里让智能体跑 `python3 scripts/oauth2.py client-get --project-id @@PROJECT_ID@@ --reveal-secret`；
- 或在平台网页控制台的应用设置页查看。

注入方式三选一（按你现有的密钥设施挑）：

| 方式 | 写法 | 适用 |
|---|---|---|
| 环境变量（推荐） | 本地 / 容器：`@@SECRET_ENV@@=真实值`；K8s 用 Secret + `envFrom` | 绝大多数场景 |
| 密钥管理服务 | Vault / KMS / 云厂商 Secret Manager，启动时拉取再注入环境变量 | 有合规审计要求 |
| 配置文件 | **仅限本地调试**，且该文件必须写进 `.gitignore` | 别用于线上 |

生成物读的就是环境变量 `@@SECRET_ENV@@`（注入点：@@SECRET_ENV_FILE@@），把它配好即可，
代码里不用改任何一行。★明文只显示一次；忘了就重取，或用 `regenerate` 重置
（★会让旧密钥立即失效、打断线上集成，动手前确认没有别人在用）。

## 多环境部署（回调地址与密钥各一份）

一个应用可以登记**多个**回调地址：登记的「主地址」与「补充回调地址列表」取**并集**，
任一逐字相等即通过（见契约 3）。所以 dev / staging / prod 各登记一条，各环境各用自己那条，
**不必来回改同一个地址**。密钥同理各环境一份：

| 环境 | `redirect_uri` | `client_secret` |
|---|---|---|
| 本地开发 | `http://127.0.0.1:8000/callback` | 开发用密钥 |
| 预发 / 联调 | `https://staging.你的域名/callback` | 同上，或另建一个应用单独管理 |
| 生产 | `https://你的域名/callback` | 生产密钥，单独存放 |

三条硬要求：

1. **回调地址必须与平台登记值逐字相等**（协议、主机、端口、结尾斜杠、大小写全都算），
   请求值不在生效集合里会被直接拒。
2. **`token` 阶段要把同一个 `redirect_uri` 再传一次**，与 `authorize` 阶段逐字相等
   ⇒ 授权开始时就把当次用的 `redirect_uri` 连同 `state`、`code_verifier` 一起存进
   服务端会话 / Redis，回调阶段取出来原样回传。**不要**在回调阶段重新拼一个 ——
   反代 / 容器里拼出来的主机名常与浏览器地址栏的不一致（`http` vs `https`、
   `127.0.0.1` vs `localhost`、多一层 `/api` 前缀都是经典踩法）。
3. ★**`authorize` 阶段同理：能从配置读就别在运行时拼**。生成物读的是 `NUWAX_REDIRECT_URI`，
   把它按环境配好，发起授权时直接用 —— 容器的网络视图里没有外部域名（反代会改写 `Host`），
   按请求头推出来的多半是 `http://127.0.0.1/...`，授权端点只会回
   `invalid_request: 回调地址与登记的不一致`。
   若确有动态需求、必须靠浏览器带来的来源信息还原，前端弹窗发起授权时**不带 `noopener`**
   （回调页要靠 `window.opener` 回传结果与自关）、**更别加 `noreferrer`**：它会连
   `Referer` 一并抹掉，后端随即只剩容器内 Host 可推；更稳的是显式带上
   `origin=<window.location.origin>` 让后端优先采用，别把正确性押在 `Referer` 上
   （浏览器策略可能压根不发）。

★**部署在女娲 UserApp（全栈应用）上？** 运行时环境变量的唯一通道是 `project.manifest.toml`
的 `[env]` 段：`NUWAX_SCOPE`、`NUWAX_REDIRECT_URI` 等**任何变更都必须改 `[env]` 并重新发布**。
`[env]` 会静默覆盖代码里的默认值，只改代码不生效、也不报错——排障口诀：
**线上行为与代码不符 ⇒ 先查 `[env]` 覆盖**（`scaffold` / `dev-app` 运行时会扫描并提醒固化值）。

## scope 全表（@@SCOPE_COUNT@@ 个）

@@SCOPE_TABLE@@

★scope 变更是**待管理员审核**的申请，审核通过前应用仍按原「已生效」范围运行。
★令牌校验时会与应用**当前登记集取交集** ⇒ 管理员收窄 scope 后，已签发的令牌即时失去对应能力，
  不必等令牌过期。
★`/userinfo` **强依赖 `profile`**：令牌缺 profile ⇒ 4030，登录流断在「获取用户信息」一步
  ⇒ 授权请求的 scope 必须包含 profile（预填默认已带；应用登记集里没有 profile 的，申请时一并登记）。

## 开放接口速查（27 个）

路径都相对 `@@BASE@@/api/oauth2/`，全部要带 `Authorization: Bearer <oa- 令牌>`。

@@ENDPOINT_TABLE@@

完整入参、返回数据结构与逐模块接入要点已按你要接的模块切好，放在同级的 `docs/` 目录下
（`auth.md` + `common.md` + 你勾的模块片）。从 [`docs/README.md`](../docs/README.md) 的索引进去读，
只读要接的那一片，别整个目录都塞给 AI。

## 排错

| 现象 | 原因 | 处置 |
|---|---|---|
| `code 4010` | 令牌无效 / 过期 / 已撤销（回落成未登录，★HTTP 仍是 200） | 有 refresh_token 就刷新；模式 A 重新换一次 |
| `code 4030`「access_token 权限不足」 | 令牌缺该端点对应的 scope | 申请 scope（需审核）；注意模式 A 拿不到那 4 个 |
| `code 4030`「API Key 无访问权限」 | 把**沙箱 AK**（`ak-` 前缀）当成了 OAuth2 凭据 | 两套凭据不能混：`ak-` 只打 `/api/v1/4sandbox/**`，`oa-` 只打 `/api/oauth2/**` |
| `invalid_client`（401） | client_id 不存在 / 应用已停用 / secret 不对 | 核对 secret；确认应用 enabled |
| `invalid_grant` + `code_verifier is missing or invalid` | PKCE 不匹配（中间态丢了，或混用了两次授权） | 重走一遍完整授权 |
| `invalid_grant` + `authorization code is invalid, expired or already used` | 授权码 5 分钟过期或已被消费（★失败也算消费） | 只能重新生成授权地址 |
| `invalid_scope` + `requires an end-user authorization` | 用模式 A 申请了「仅授权码」scope | 改走模式 B |
| 授权地址打开后跳到登录页 | `/api/oauth2/authorize` 需要登录态（正常行为） | 登录后会自动带原参数回到授权流程 |
| `redirect_uri` 不匹配被拒 | 请求值不在应用的**生效集合**里，或与登记值不是逐字相等 | 生效集合 = 主回调地址 ∪ 补充回调地址列表；改成完全一致（含协议 / 端口 / 结尾斜杠），或把该地址加进补充列表 |
| 失败输出里**没有** `code:` 这一行 | 拿到的响应根本不是信封 | 看顶层字段：出现 `sub`/`user_id`/`name` 就是裸对象端点（`userinfo`、`user/search`），直接用响应体，别走 `unwrap()` |
| 工具执行返回 `code 0000` 却没效果 | `data.success=false` | 看 `data.error`；SSE 版要解析帧 |

## 安全须知

- `client_secret` 只在服务端使用，**绝不能下发到浏览器 / 移动端 / 前端仓库**。
- 令牌按用户维度存储并加密；不要把令牌写进日志或 URL。
- 纯前端应用（SPA）拿不到 secret，而当前实现要求客户端认证 ⇒
  **请把令牌交换放在你的服务端**，前端只拿你自己的会话。
- 用户解除授权（撤销）后，你的系统应清理本地会话并引导重新登录。
'''

QUICKSTART = {
    "python": '''\
```bash
cd @@OUT_SUBDIR@@
cp .env.example .env        # 填入真实的 @@SECRET_ENV@@
set -a; . ./.env; set +a
python3 example_server.py 8000
# 浏览器打开 http://127.0.0.1:8000/login   # 点按钮「用女娲账号登录」⇒ 弹小窗，认证完自动关
```

`nuwax_oauth2.py` 是要落进你项目的那个模块（仅标准库，Python 3.7+），零依赖直接 import：

```python
import nuwax_oauth2 as nuwax

token = nuwax.client_credentials_token()              # 模式 A
spaces = nuwax.call("space/list", token["access_token"])
me = nuwax.userinfo(access_token)                     # 模式 B 登录后的身份
```

★示例服务用 `http.server` 是为了零依赖能直接跑；生产请换成你自己的框架
（Flask / FastAPI / Django），把 `nuwax_oauth2.py` 原样搬过去即可。''',

    "node": '''\
```bash
cd @@OUT_SUBDIR@@
cp .env.example .env        # 填入真实的 @@SECRET_ENV@@
set -a; . ./.env; set +a
node example-server.js 3000
# 浏览器打开 http://127.0.0.1:3000/login   # 点按钮「用女娲账号登录」⇒ 弹小窗，认证完自动关
```

`nuwax-oauth2.js` 是要落进你项目的那个模块（Node 18+，内置 fetch/crypto，零依赖）：

```js
const nuwax = require("./nuwax-oauth2");

const token = await nuwax.clientCredentialsToken();          // 模式 A
const spaces = await nuwax.call("space/list", token.access_token);
const me = await nuwax.userinfo(accessToken);                // 模式 B 登录后的身份
```

★CommonJS 写法；用 ESM 的话把 `require` / `module.exports` 换成 `import` / `export`。''',

    "spring-boot": '''\
```bash
# 1. 把两个 .java 放进你的 src/main/java/@@PACKAGE_DIR@@/ 下
# 2. 把 application-nuwax.yml 的内容并进你的配置
# 3. 注入 secret（不要写进仓库）
export @@SECRET_ENV@@="@@SECRET_PLACEHOLDER@@"
# 4. /login（弹小窗登录页）、/oauth/start、/oauth/callback 三个端点即可用；
#    ★callback 路径必须与平台登记的 redirect_uri 逐字一致
```

要求：Spring Boot 3.x + JDK 17（Boot 2.x 请把 `jakarta.servlet` 换成 `javax.servlet`）。
只用 `spring-boot-starter-web` 自带的 Jackson 与 JDK HttpClient，**无需引入 OAuth2 客户端库**。

```java
private final NuwaxOAuth2Client nuwax;   // 构造器注入

JsonNode token  = nuwax.clientCredentialsToken(null);                       // 模式 A
JsonNode spaces = nuwax.call("space/list", token.get("access_token").asText(),
                             "GET", null, null);
JsonNode me     = nuwax.userinfo(accessToken);                              // 模式 B 身份
```''',

    "curl": '''\
```bash
cd @@OUT_SUBDIR@@
cp .env.example .env        # 填入真实的 @@SECRET_ENV@@
source .env && source nuwax.sh

nuwax_m2m_token | jq .                                    # 模式 A：机器对机器
export NUWAX_ACCESS_TOKEN=$(nuwax_m2m_token | jq -r .access_token)
nuwax_call space/list                                     # 调业务接口，直接返回 data

nuwax_auth_url "profile,chat:read"                        # 模式 B：打印授权地址，人工去点
nuwax_exchange_code "<回调里的 code>"                      # verifier/state 自动读状态文件
```

依赖：`curl`、`jq`、`openssl`（PKCE 用）。''',
}


# ------------------------------------------------------------------ 组装

def _params_table(ctx):
    rows = [
        ("平台基址", ctx["base"]),
        ("client_id", ctx["client_id"]),
        ("redirect_uri（已登记）", ctx["redirect"] or "(★尚未登记 —— 模式 B 必须先登记)"),
        ("主页地址", ctx["homepage"] or "(未登记)"),
        ("projectId", str(ctx["project_id"] or "REPLACE_ME")),
        ("平台登记集（已生效）", "、".join(ctx.get("granted_scopes") or []) or "(空 ⇒ 平台默认 profile)"),
        ("写进生成物的 scope", ctx["scope"] or "profile"),
        ("scope 审核状态", ctx["scope_status"]),
        ("client_secret", "★不在生成物里，见下文「密钥注入」"),
    ]
    lines = ["| 项 | 值 |", "|---|---|"]
    for key, value in rows:
        lines.append("| %s | `%s` |" % (key, value))
    # ★登记 ≠ 令牌里有：登记集只是前提，令牌内容由「应用自己发出的那次授权请求」决定。
    #   两行不一致时必须当场喊，否则开发者会拿一份注定 4030 的代码去联调。
    granted = set(ctx.get("granted_scopes") or [])
    asked = [n for n in re.split(r"[,\s]+", ctx.get("scope") or "") if n]
    extra = [n for n in asked if n not in granted] if granted else []
    if extra:
        lines.append("")
        lines.append("★**上面两行不一致**：`%s` 还没在平台登记 ⇒ 授权码登录会在真人登录之后被拒 "
                     "`invalid_scope`（整轮登录失败）。先去平台申请，再把登记集申请全。"
                     % "`、`".join(extra))
    return "\n".join(lines)


def _scope_table(granted):
    lines = ["| scope | 能力 | 敏感 | ★仅授权码 | 本应用 |", "|---|---|---|---|---|"]
    for name, desc, sensitive, code_only in SCOPE_TABLE:
        lines.append("| `%s` | %s | %s | %s | %s |" % (
            name, desc, "是" if sensitive else "否", "**是**" if code_only else "否",
            "✅ 已生效" if name in granted else "—"))
    return "\n".join(lines)


# 响应形状总表。判据是机械的——端点的返回类型是不是平台统一信封：
# 是 ⇒ 信封；不是 ⇒ 裸对象（OIDC 风格）或 RFC 结构。照这张表选调用方式，别靠猜。
_ENDPOINT_SHAPES = (
    ("协议端点", "`/token`、`/revoke`、`/authorize`",
     "RFC 6749 扁平结构 `{error, error_description}`",
     "HTTP 状态码是真实的（400 / 401）"),
    ("门面端点", "24 个（下表除 3 个 SSE 外的全部）",
     "平台信封 `{code, displayCode, message, data, tid}`",
     "★HTTP 恒 200，只判 `code == \"0000\"`"),
    ("SSE 端点", "`chat/message/subscribe`、`chat/message/send`、`tool/workflow/sse-execute`",
     "`text/event-stream` 帧流",
     "失败不抛异常，在流内推 `success=false, isComplete=true` 帧"),
    ("裸对象端点", "`/userinfo`、`/user/search`",
     "响应体本身就是数据，没有 `code`/`message`/`data` 外壳",
     "★不能走门面那套 `unwrap()`；`user/search` 查不到时刻意返回 `{}`（防枚举），不是错误"),
)


def _endpoint_table():
    lines = ["| 路径 | 方法 | scope | 入参要点 |", "|---|---|---|---|"]
    for path, method, scope, note in ENDPOINTS:
        lines.append("| `%s` | %s | `%s` | %s |" % (path, method, scope, note or "—"))
    lines += ["", "### 响应形状（先认清形状，再判成败）", "",
              "| 类别 | 端点 | 响应 | 判读方式 |", "|---|---|---|---|"]
    for kind, paths, shape, verdict in _ENDPOINT_SHAPES:
        lines.append("| %s | %s | %s | %s |" % (kind, paths, shape, verdict))
    lines += ["", "口诀：**响应里有 `code` 才是信封**。`/api/oauth2/**` 下只有这四种形状，没有第五种。"]
    return "\n".join(lines)


def _mode_hint(ctx):
    granted = set(ctx["granted_scopes"])
    blocked = sorted(granted.intersection(CODE_ONLY_SCOPE_NAMES))
    if blocked:
        return ("**你需要模式 B（授权码 + PKCE）**：本应用已生效的 scope 里有 %s，"
                "这几个只有代表真人授权才拿得到，机器身份换不到。\n"
                "如果同时还有后台批处理需求，两条可以都用（同一个 client_id）。"
                % "、".join("`%s`" % name for name in blocked))
    return ("**模式 A（机器对机器）就够**：本应用已生效的 scope 里没有「仅授权码」项，"
            "服务端全自动换令牌即可，不需要真人点浏览器。\n"
            "以后要做「用女娲账号登录我的系统」时，再走模式 B。")


def _stack_label(stack):
    return {
        "python": "Python 3.7+，仅标准库",
        "node": "Node.js 18+，零依赖",
        "spring-boot": "Spring Boot 3.x / JDK 17",
        "curl": "bash + curl + jq",
        "all": "四种技术栈",
    }[stack]


def _tokens(ctx, stack):
    package = ctx.get("package") or "com.example.nuwax"
    return {
        "@@BASE@@": ctx["base"],
        "@@CLIENT_ID@@": ctx["client_id"],
        "@@REDIRECT@@": ctx["redirect"] or PLACEHOLDER_REDIRECT,
        "@@SCOPE@@": ctx["scope"] or "profile",
        "@@SCOPE_COUNT@@": str(len(SCOPE_TABLE)),
        "@@PROJECT_ID@@": str(ctx["project_id"] or "REPLACE_ME"),
        "@@PACKAGE@@": package,
        "@@PACKAGE_DIR@@": package.replace(".", "/"),
        "@@OUT_SUBDIR@@": stack,
        "@@STACK_LABEL@@": _stack_label(stack),
        "@@SECRET_ENV@@": SECRET_ENV,
        "@@SECRET_PLACEHOLDER@@": SECRET_PLACEHOLDER,
        "@@SECRET_ENV_FILE@@": _SECRET_ENV_FILE.get(stack, "`.env.example`（复制成 `.env` 后填值）"),
        "@@PARAMS_TABLE@@": _params_table(ctx),
        "@@ENDPOINT_TABLE@@": _endpoint_table(),
        "@@MODE_HINT@@": _mode_hint(ctx),
        "@@SCOPE_TABLE@@": _scope_table(set(ctx["granted_scopes"])),
    }


def _fill(text, tokens):
    for key, value in tokens.items():
        text = text.replace(key, value)
    return text


_CODE_FILES = {
    "python": (("nuwax_oauth2.py", lambda: PY_CLIENT),
               ("example_server.py", lambda: PY_SERVER),
               (".env.example", lambda: PY_ENV)),
    "node": (("nuwax-oauth2.js", lambda: NODE_CLIENT),
             ("example-server.js", lambda: NODE_SERVER),
             (".env.example", lambda: NODE_ENV)),
    "spring-boot": (("NuwaxOAuth2Client.java", lambda: JAVA_CLIENT),
                    ("NuwaxOAuth2Controller.java", lambda: JAVA_CONTROLLER),
                    ("application-nuwax.yml", lambda: JAVA_YML)),
    "curl": (("nuwax.sh", lambda: SHELL_SCRIPT),
             (".env.example", lambda: SHELL_ENV)),
}


def build(stack, ctx):
    """
    生成一个技术栈的文件集，返回 [(相对路径, 内容)]，路径带 `<stack>/` 前缀。

    ctx 需含：base / client_id / redirect / scope / project_id / homepage /
              scope_status / granted_scopes(list) / package(可选)
    """
    if stack not in STACKS:
        raise ValueError("未知技术栈：%s（可选 %s / all）" % (stack, " / ".join(STACKS)))
    tokens = _tokens(ctx, stack)
    tokens["@@FILES@@"] = "\n".join("- `%s/%s`" % (stack, name)
                                    for name, _getter in _CODE_FILES[stack])
    tokens["@@QUICKSTART@@"] = _fill(QUICKSTART[stack], tokens)

    files = [(stack + "/" + name, _fill(getter(), tokens))
             for name, getter in _CODE_FILES[stack]]
    files.append((stack + "/README.md", _fill(README, tokens)))
    return files


def build_all(ctx):
    """四种栈全生成，外加一份总入口 README。返回 [(相对路径, 内容)]。"""
    files = []
    for stack in STACKS:
        files.extend(build(stack, ctx))
    tokens = _tokens(ctx, "all")
    index = [
        "# 女娲平台对接包（四种技术栈）", "",
        "由 `nuwax-oauth2` 技能生成，接入参数已按你名下这个应用预填。",
        "**挑一个栈，进它自己的目录看 README.md**：", "",
    ]
    for stack in STACKS:
        index.append("- `%s/` —— %s" % (stack, _stack_label(stack)))
    index += [
        "", "## 你的接入参数", "", _params_table(ctx), "",
        "## 六条平台契约（四个栈都一样）", "",
        "每个栈的 README 里都写全了，这里只列标题：", "",
        "1. 三种响应格式并存 —— 门面端点 **HTTP 恒 200**、判 `code == \"0000\"`；协议端点是真实状态码 + "
        "RFC 扁平错误；`userinfo`、`user/search` 是**裸对象**，不能走门面的 `unwrap()`。"
        "口诀：响应里有 `code` 才是信封。",
        "2. 工具执行类的二级陷阱 —— 外层恒 `0000`，业务成败在 `data.success`；SSE 版失败也不抛异常。",
        "3. `redirect_uri` **精确匹配**（不是前缀匹配），且 `token` 阶段要与 `authorize` 阶段逐字相等；"
        "一个应用可登记多条（主地址 + 补充列表，**生效集合取并集**）。",
        "4. 授权码 **5 分钟有效且一次性**，失败也算已消费；`state` 不匹配必须拒绝。",
        "5. `refresh_token` **会轮换**，新值才有效；`access_token` 2 小时且滑动续期。",
        "6. 撤销是**用户级**的，且恒返回 200。",
        "", "## 模式选择", "", _mode_hint(ctx), "",
        "## 开放接口（27 个）", "", _endpoint_table(), "",
        "## scope 全表（%d 个）" % len(SCOPE_TABLE), "", _scope_table(set(ctx["granted_scopes"])), "",
        "## 密钥注入与多环境部署", "",
        "生成物里只有占位符 `%s`，真实值用 "
        "`python3 scripts/oauth2.py client-get --project-id %s --reveal-secret` 取，"
        "或到平台网页控制台的应用设置页查看；拿到后存进你自己的密钥管理，不要提交进仓库。" % (
            SECRET_PLACEHOLDER, ctx["project_id"] or "REPLACE_ME"),
        "",
        "回调地址一个应用可登记**多条**（主地址 + 补充列表取并集）⇒ dev / staging / prod "
        "**各登记一条**、各环境各用自己那条，不必来回改同一个地址。把当次用的 `redirect_uri` "
        "连同 `state`、`code_verifier` 一起存进服务端会话，`token` 阶段原样回传"
        "（重拼的地方很容易和浏览器地址栏对不上，`http`/`https`、`127.0.0.1`/`localhost` 都算不同地址）。",
        "★反代 / 容器里推不出外部域名（`Host` 被改写）⇒ `redirect_uri` **从配置读**、别在运行时拼；"
        "弹窗发起授权时**不要带 `noopener`**（回调页要靠 `window.opener` 回传结果），"
        "**更别加 `noreferrer`**（它会把 `Referer` 一起抹掉）。",
        "★登录跳转**只用「弹小窗 + 认证完自关」一种形态**："
        "`window.open(授权入口, 'nuwax-oauth', 'width=520,height=640')` ⇒ 回调页见 `window.opener` 就 "
        "`postMessage` 通知原页面 + `window.close()` 自关；被浏览器拦截才 `location.href` 降级。"
        "⛔ 别用 `<a href>` / `location.href` / 服务端 302 把整个页面导航到授权页 —— "
        "用户会离开你的应用、认证完也回不去。",
        "★部署在女娲 UserApp 上时，环境变量的唯一运行时通道是 `project.manifest.toml` 的 `[env]`："
        "scope / 回调地址任何变更都要**改 `[env]` 并重新发布**——`[env]` 会静默覆盖代码默认值，"
        "只改代码不生效（线上行为与代码不符 ⇒ 先查 `[env]` 覆盖）。",
        "★`/userinfo` 强依赖 `profile`（令牌缺它 ⇒ 4030）⇒ 授权请求的 scope 必须包含 profile。",
        "细节见各栈 `README.md` 的「密钥注入」「多环境部署」两节。", "",
    ]
    files.append(("README.md", "\n".join(index)))
    return files


# ------------------------------------------------------------------ 按模块切文档
# ★技能仓库里【不存第二份文档】：下面这些切片是运行时从 references/ 取的，落进
#   生成物的 docs/ 下。原文档改了，切片自动跟着变，不会漂移。
#   目的是让开发者的 AI 只读它要接的那一片，而不是整篇文档。
#   两种取法：
#   - 整文件复制：references/api/ 的分模块接口文档（每端点参数表+JSON 示例+坑），
#     模块文件本身就是切片事实源，复制时剥掉首行 H1（切片自带标题）；
#   - 按小节号抽取：guide 的流程章（§3/§4/§5、§8.1/§11/§12）与总览的公共节（§0/§4）。
# ★小节号是 guide 与 api_reference（总览）的契约：改这两份的章节编号，下表要跟着改；
#   api/ 模块文件按文件名对。取不到不会失败，会在文档里留一行「没抽到」并在摘要里记 note。

# (文件名, 标题, 这个模块用到的 scope, integration_guide 小节号, api_reference 小节号或 api/ 文件路径)
DOC_AUTH = ("auth", "认证与令牌", ("profile",), ("3", "4", "5"), "api/auth.md")
# common 取 guide §8.1 是因为只有它带「平台信封」的 JSON 样例（api §0 只有对比表）；
# §8.2/§8.3 刻意不取 —— 错误码全集在 api §4，取了就是一份文档两处事实。
DOC_COMMON = ("common", "响应格式、错误码与排错", (), ("8.1", "11", "12"), ("0", "4"))
DOC_MODULES = (
    ("user", "用户信息与文件",
     ("profile", "user:search", "user:space", "file:read", "file:write"),
     (), "api/user.md"),
    ("chat", "任务会话", ("chat:read", "chat:write"),
     (), "api/chat.md"),
    ("im", "消息通信 IM", ("im:read", "im:push"),
     (), "api/im.md"),
    ("model", "模型调用", ("model:read", "model:key"),
     (), "api/model.md"),
    ("tool", "工具检索与执行（插件 / 工作流）", ("tool:read", "tool:exec"),
     (), "api/tool.md"),
)

_HEADING = re.compile(r"^(#{1,6})\s+(\d+(?:\.\d+)*)[\.、]?\s*(.*)$")


def _reference_text(filename):
    """读技能自己的 references/<filename>；读不到返回 None（调用方降级，不 die）。"""
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(os.path.dirname(here), "references", filename)
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except OSError:
        return None


def _outline(lines):
    """列出真实标题 (层级, 小节号, 行号)。★跳过 ``` 围栏，否则 shell 注释会被当成标题。"""
    outline = []
    fenced = False
    for index, line in enumerate(lines):
        if line.lstrip().startswith("```") or line.lstrip().startswith("~~~"):
            fenced = not fenced
            continue
        if fenced:
            continue
        match = _HEADING.match(line)
        if match:
            outline.append((len(match.group(1)), match.group(2), index))
    return outline


def slice_section(text, number):
    """
    按小节号抽出整节（连它下面的子节一起）。抽不到返回 None。

    number 是文档里的编号：`"7.1"` 对应 `### 7.1 …`，`"4"` 对应 `## 4. …`。
    终止边界是下一个「同级或更高级」标题 ⇒ 抽 "4" 会自动带上 4.1~4.5。
    """
    if not text:
        return None
    lines = text.split("\n")
    outline = _outline(lines)
    for position, (level, section_number, start) in enumerate(outline):
        if section_number != number:
            continue
        end = len(lines)
        for next_level, _ignored, next_start in outline[position + 1:]:
            if next_level <= level:
                end = next_start
                break
        return "\n".join(lines[start:end]).rstrip() + "\n"
    return None


def wanted_scopes(ctx):
    """这次生成关心哪些 scope：平台登记集 ∪ 本次写进生成物的那份（取并集）。

    ★只认登记集会漏切片：开发者显式 --scope im:read 就是在说「我要接 IM」，可平台还没登记
    时登记集里没有它 ⇒ im.md 不切给他，他手上连接口文档都没有（而「该申请哪些」本来就要求
    他先看到接口）。并集两个方向都不漏。
    """
    names = set(ctx.get("granted_scopes") or [])
    names.update(item for item in re.split(r"[,\s]+", ctx.get("scope") or "") if item)
    return names


# ★profile 不能当模块判据：它是「仅认证」就有的，所有模块都隐含要它。
#   拿它判就会「只选了 profile ⇒ 把用户信息与文件整片带出来」，而那片里 3 个端点一个都调不通。
_JUDGE_EXCLUDED = frozenset(("profile",))


def selected_doc_modules(ctx):
    """按 scope 推出要切哪些模块（不含恒带的 auth / common）。给调用方写摘要用。"""
    granted = wanted_scopes(ctx)
    chosen = []
    for entry in DOC_MODULES:
        if granted.intersection(set(entry[2]) - _JUDGE_EXCLUDED):
            chosen.append(entry)
    return chosen


def _doc_prelude(scopes, granted):
    lines = ["> 由 `nuwax-oauth2` 技能按要接的模块，从平台接入文档里切出来的那一片。",
             "> 每片自包含（端点契约 + 返回结构 + 接入要点）；完整文档在技能的 `references/` 下。",
             ""]
    if not scopes:
        return lines
    if granted:
        marks = ["`%s`%s" % (name, " ✅已生效" if name in granted else " ⚠️还没生效")
                 for name in scopes]
    else:
        marks = ["`%s`" % name for name in scopes]
    lines += ["**这个模块要的 scope**：%s" % "  ".join(marks), ""]
    if granted and not set(scopes).issubset(granted):
        lines += ["⚠️ 标「还没生效」的 scope 对应的那几个端点会被拒（`4030`）；"
                  "标 ✅ 的现在就能用。要补哪个就去平台的应用设置里加上（★需管理员审核）。", ""]
    code_only = [name for name in scopes if name in CODE_ONLY_SCOPE_NAMES]
    if code_only:
        lines += ["★%s 这几个 scope **机器对机器拿不到**：必须有真人在浏览器里登录授权一次，"
                  "你的系统要存得住令牌。" % " ".join("`%s`" % name for name in code_only), ""]
    return lines


def build_docs(ctx):
    """
    生成 docs/ 下的按模块切片文档，返回 [(相对路径, 内容)]。

    ★两份 references 都读不到时返回 []（调用方记 note），代码生成照常。
    """
    guide = _reference_text("integration_guide.md")
    reference = _reference_text("api_reference.md")
    if not guide and not reference:
        return []

    granted = wanted_scopes(ctx)
    entries = [DOC_AUTH] + selected_doc_modules(ctx) + [DOC_COMMON]

    files = []
    rows = []
    for key, title, scopes, guide_numbers, reference_part in entries:
        body = ["# 女娲对接 · %s" % title, ""] + _doc_prelude(scopes, granted)
        missed = []
        if isinstance(reference_part, str):
            # api/ 模块文件：整文件复制（剥掉首行 H1，切片已有自己的标题）
            piece = _reference_text(reference_part)
            if piece:
                lines = piece.split("\n")
                if lines and lines[0].startswith("# "):
                    lines = lines[1:]
                body += ["---", "", "\n".join(lines).strip(), ""]
            else:
                missed.append("references/%s" % reference_part)
        else:
            for number in reference_part:
                piece = slice_section(reference, number)
                if piece:
                    body += ["---", "", piece.rstrip(), ""]
                else:
                    missed.append("接口参考 §%s" % number)
        for number in guide_numbers:
            piece = slice_section(guide, number)
            if piece:
                body += ["---", "", piece.rstrip(), ""]
            else:
                missed.append("接入指南 §%s" % number)
        if missed:
            body += ["---", "",
                     "★这几节没抽到：%s（多半是文档小节号变了）⇒ 直接读技能里的 `references/`。"
                     % "、".join(missed), ""]
        files.append(("docs/%s.md" % key, "\n".join(body).rstrip() + "\n"))
        rows.append((key, title, missed))

    index = ["# 对接文档（已按要接的模块切好）", "",
             "`client_id`：%s" % (ctx.get("client_id") or "(占位符)"),
             "平台基址：%s" % (ctx.get("base") or "(占位符)"), ""]
    if granted:
        index += ["已生效的 scope：%s" % " ".join("`%s`" % name for name in sorted(granted)), ""]
    index += ["| 文件 | 内容 | 什么时候读 |", "|---|---|---|"]
    for key, title, missed in rows:
        when = {"auth": "★接任何模块都先读这片",
                "common": "调接口出错、看不懂返回时读"}.get(key, "接这个模块时读")
        index.append("| [`%s.md`](%s.md) | %s%s | %s |"
                     % (key, key, title, "（★有节没抽到）" if missed else "", when))
    index += ["", "## 怎么用", "",
              "把 `auth.md` + `common.md` + 你要接的那一片一起读就够了，别把整个目录都塞进去。",
              "每片都自带该模块的端点契约、返回数据结构与接入要点；可运行的代码在同级各技术栈目录里。",
              "", "★这些文件是从技能的 `references/` 里按模块抽取 / 复制出来的，不是另一份文档："
              "平台文档更新后重跑一次 `scaffold` 就是新版。", ""]
    files.append(("docs/README.md", "\n".join(index).rstrip() + "\n"))
    return files


def write_files(files, out_dir):
    """落盘，返回实际写出的绝对路径列表。★不写 client_secret，生成物可安全进 git。"""
    written = []
    for relative, content in files:
        path = os.path.join(out_dir, relative)
        parent = os.path.dirname(path)
        if parent and not os.path.isdir(parent):
            os.makedirs(parent, exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(content)
        if relative.endswith(".sh"):
            os.chmod(path, 0o755)
        written.append(path)
    return written


_SECRET_PATTERN = re.compile(r"nwxa_s_[A-Za-z0-9_]*")
# 这些是「明显示例」的后缀：占位符本身、文档里用来讲形态的 xxx、以及只写了前缀的裸引用。
_SAFE_SECRET_SUFFIXES = frozenset(("REPLACE_ME", "xxx", ""))


def assert_no_secret(files):
    """
    自检：生成物里绝不能出现真实 secret，只能是占位符或文档里的示例形态。

    ★判定口径是「找出所有 nwxa_s_ 开头的串，逐个看后缀是否属于已知示例」，
      而不是数出现次数 —— docs/ 下的切片来自接入文档，里面本来就有 `nwxa_s_xxx`
      这种讲形态的示例，按次数比会误杀；而真密钥的后缀是长随机串，照样拦得住。

    返回 True；发现问题直接抛 AssertionError（宁可生成失败，也不能把密钥写进交付物）。
    """
    offenders = []
    for path, content in files:
        found = [match.group(0) for match in _SECRET_PATTERN.finditer(content)
                 if match.group(0)[len("nwxa_s_"):] not in _SAFE_SECRET_SUFFIXES]
        if found:
            offenders.append("%s（%d 处：%s…）"
                             % (path, len(found), found[0][:12]))
    if offenders:
        raise AssertionError("疑似把真实 secret 写进了交付物：" + "；".join(offenders))
    return True

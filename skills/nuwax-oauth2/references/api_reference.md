# 女娲平台 OAuth2 接口参考（总览）

面向**要在自己业务系统里接入女娲登录与接口**的开发者。本文件只保留**跨模块公共知识**（响应格式、scope 全集、错误码全集）；**逐端点契约按模块分文件放在 `api/` 目录**，要查哪个模块读哪个文件，别整目录加载。

- 协议端点与门面端点：`{PLATFORM_BASE_URL}/api/oauth2/**`，凭据是 `client_id` / `client_secret` / `oa-` 令牌。
- 三方应用管理端点（自助取 client_id/secret）：`{PLATFORM_BASE_URL}/api/v1/4sandbox/user-project/**`，凭据是沙箱 AK。

★两套凭据**绝不能混用**：把沙箱 AK（`ak-` 前缀）带进 `/api/oauth2/**` 会得到 `4030 API Key 无访问权限`——沙箱型 AK 只被允许打 `/api/v1/4sandbox` 前缀。

★§2 的 13 个 scope 就是 `scaffold` 生成的 README 里 scope 表的内容。改了这节要同步更新 `scripts/scaffold.py` 的对应常量表，否则代码与文档会漂移。

## 模块索引

| 文件 | 模块 | 端点数 | scope |
|---|---|---|---|
| [api/auth.md](api/auth.md) | 认证与令牌（协议端点：authorize / token / revoke / userinfo / user-search） | 5 | `profile`、`user:search` |
| [api/user.md](api/user.md) | 用户与文件（空间列表、文件上传、下载地址） | 3 | `user:space`、`file:write`、`file:read` |
| [api/chat.md](api/chat.md) | 任务会话（会话与消息的读写、SSE 对话） | 11 | `chat:read`、`chat:write` |
| [api/im.md](api/im.md) | 消息通信 IM（会话 / 群组 / 联系人 / 消息 / 推送） | 5 | `im:read`、`im:push` |
| [api/model.md](api/model.md) | 模型与 API Key（模型列表、Key 签发管理） | 5 | `model:read`、`model:key` |
| [api/tool.md](api/tool.md) | 工具检索与执行（工具列表与 schema、插件 / 工作流同步与 SSE 执行） | 5 | `tool:read`、`tool:exec` |
| [api/admin.md](api/admin.md) | 三方应用管理（建应用、回调地址、scope 申请、取凭证） | 10 | —（沙箱 AK，非 OAuth2 scope） |

接入流程（模式选择、授权码 + PKCE 时序、回调处理、令牌续期）见 [integration_guide.md](integration_guide.md)。

---

## 0. 三种响应格式（必须都认）

| 端点类别 | 响应体 | HTTP 状态码 | 判定成败 |
|---|---|---|---|
| 协议端点 `token` / `revoke` / `authorize` | **RFC 6749 扁平结构** `{"error","error_description"}`；成功时是令牌对象 | **真实状态码**（400 / 401 / 302 / 200） | 看 HTTP 状态码 + 有无 `error` 字段 |
| 门面端点：29 个业务端点里**除 3 个 SSE 外的 26 个** | **平台信封** `{"code","displayCode","message","data","tid"}` | **恒 200**（异常也 200） | 看 `code == "0000"`；`data` 才是业务结果 |
| OIDC 风格**裸对象** `userinfo` / `user/search` | **响应体本身就是数据**：`{"sub","user_id","name","avatar","tenant_id"}`，**没有** `code`/`message`/`data` 外壳 | **恒 200** | 拿到对象即成功；`user/search` 查无此人是**正常**的空对象 `{}` |

**口诀：响应里有 `code` 才是平台信封。** 别按信封去取 `code`/`data`——那不但字段全 `None`，还会把正常响应判成失败。

★另有 3 个 **SSE** 端点（`chat/message/subscribe`、`chat/message/send`、`tool/workflow/sse-execute`）：回 `text/event-stream` 帧流，**失败也不抛异常**，而是在流内推错误帧 ⇒ 必须解帧，不能只看连接成功。

平台信封结构：

```json
{
  "code": "4030",
  "displayCode": "4030",
  "message": "access_token 权限不足，无法访问该接口",
  "data": null,
  "tid": "..."
}
```

**`displayCode` == `code`**，判成败只看 `code`；`message` 会随请求语言（`lang` Cookie）在中英文之间切换，⛔ 不要拿 `message` 做程序判断。`tid` 是链路 ID，**报障时请一并附上**。

⇒ **只看 HTTP 状态码会误判**：门面接口 scope 不足、参数错误、会话不存在，HTTP 全是 200。

⇒ **最常见的两个集成 bug**：① 用「HTTP 200 = 成功」判断门面接口；② 把裸对象的 `userinfo` 当信封读——`sub` 恒为 `null`，还会把成功判成失败。

---

## 2. scope 全集（13 个）

| # | scope | 能力说明 | sensitive | ★仅授权码（机器对机器拿不到） | 覆盖路径 |
|---|---|---|---|---|---|
| 1 | `profile` | 读取授权用户自身信息（昵称、头像等） | 否 | 否 | `/api/oauth2/userinfo` |
| 2 | `user:search` | 按用户名 / 邮箱 / 手机号检索租户内用户 | **是** | 否 | `/api/oauth2/user/search` |
| 3 | `user:space` | 查询授权用户可见的空间列表 | 否 | 否 | `/api/oauth2/space/list` |
| 4 | `file:write` | 以授权用户身份上传文件 | 否 | 否 | `/api/oauth2/file/upload` |
| 5 | `file:read` | 为授权用户的文件签发带凭证的下载地址 | 否 | 否 | `/api/oauth2/file/url` |
| 6 | `chat:read` | 读取授权用户的任务会话与消息记录 | 否 | 否 | `/api/oauth2/chat/conversation/list`、`/conversation/detail`、`/message/list`、`/message/subscribe` |
| 7 | `chat:write` | 以授权用户身份创建会话、发起与停止对话 | **是** | 否 | `/api/oauth2/chat/conversation/create`、`/conversation/update`、`/conversation/delete`、`/message/send`、`/message/stop`、`/message/clear`、`/message/permission-response` |
| 8 | `im:read` | 读取授权用户的 IM 会话、群组、联系人与消息 | **是** | **是** | `/api/oauth2/im/conversation/list`、`/group/list`、`/contact/list`、`/message/list` |
| 9 | `im:push` | 以授权用户身份向指定目标推送 IM 消息 | **是** | **是** | `/api/oauth2/im/message/push` |
| 10 | `model:read` | 查询租户可用模型列表 | 否 | **是** | `/api/oauth2/model/list` |
| 11 | `model:key` | 创建模型 API Key 并授权可用模型 | **是** | **是** | `/api/oauth2/model/api-key/**` |
| 12 | `tool:read` | 检索已发布的插件与工作流、读取其出入参定义 | 否 | 否 | `/api/oauth2/tool/list`、`/tool/schema` |
| 13 | `tool:exec` | 执行已发布的插件与工作流 | **是** | 否 | `/api/oauth2/tool/plugin/execute`、`/workflow/execute`、`/workflow/sse-execute` |

**补充口径**

- 路径判定**只看 path（去查询串）、不看 HTTP 方法** ⇒ 读写分离靠不同路径字面量
- scope 串解析同时接受逗号与空白分隔
- 应用未登记 scope 时回落默认集 `[profile]`
- ★令牌校验时 scope 会与**应用当前登记集取交集**⇒ 管理员收窄应用 scope 后，已签发的令牌**即时**失去对应能力，不必等令牌过期
- ★**两端「允许集」口径有差（实测）**：登记 scope 后 **token 端**（机器对机器）仍可授予 `profile`；**authorize 端**的 scope 校验在真人登录之后，登记集之外的请求被拒 `invalid_scope`（真实事故推断：其允许集 = 登记集、不含默认 `profile`）⇒ 授权码登录流的 scope 必须**既在登记集里、又含 `profile`**（`/userinfo` 强依赖，缺它 4030）
- ★管理端点 `GET /scopes` 返回的对象只有 `scope` / `description` / `sensitive` 三个字段，**不含「仅授权码」标记** ⇒ 上表第 5 列只能查本文档，不能从接口读
- 敏感 scope（标 **是** 的）通常需要更严格的申请理由，管理员可能拒绝；最小权限原则：**只申请你真正要用的**

---

## 4. 错误码全集

### 4.1 RFC 格式（`error` / `error_description`）—— 仅协议端点

错误码只有四个，按下面对照排错：授权码 / verifier 问题 → `invalid_grant`；客户端或密钥问题 → `invalid_client`；范围问题 → `invalid_scope`；其余一律 `invalid_request`。

| error | HTTP | 触发条件 | `error_description` |
|---|---|---|---|
| `invalid_request` | 400 | authorize 的 PKCE method/长度非法；`user/search` 三参全空 | `code_challenge_method must be S256 or plain`；`code_challenge must be 43-128 characters`；`at least one of userName, email or phone is required`；或 BizException 的中英模板（如「回调地址与登记的不一致」） |
| `unsupported_response_type` | **302 回跳** | `response_type != code` | `response_type must be code` |
| `temporarily_unavailable` | **302 回跳** | `auto-consent=false` | `consent page is not available` |
| `invalid_scope` | **302 回跳** | authorize 申请的 scope 未登记 / 超出应用允许范围 | 「申请的权限范围不合法或超出应用允许范围」/ `The requested scope is invalid or exceeds the allowed scopes` |
| `invalid_scope` | 400 | `client_credentials` **显式**申请「仅授权码」scope | `scope [xxx] requires an end-user authorization and is not available with client_credentials; use grant_type=authorization_code` |
| `invalid_scope` | 400 | `client_credentials` 未申请 scope 且登记集全为「仅授权码」 | `this client only has user-authorization scopes registered; nothing is available with client_credentials` |
| `invalid_client` | **401**（token/revoke）；400（authorize） | 客户端不存在 / 已停用 / 类型不符 / secret 错误 / 无创建者 | 「OAuth2 客户端不存在或已停用」「客户端密钥不正确」；或 `the client has no owner` |
| `unsupported_grant_type` | 400 | `grant_type` 不在三值白名单 | `grant_type must be one of authorization_code, refresh_token, client_credentials` |
| `invalid_grant` | 400 | 授权码无效 / 过期 / 已用 | `authorization code is invalid, expired or already used` |
| `invalid_grant` | 400 | 授权码签给了别的 client | `authorization code was issued to another client` |
| `invalid_grant` | 400 | `redirect_uri` 缺失或与授权请求不一致 | `redirect_uri does not match the authorization request` |
| `invalid_grant` | 400 | PKCE verifier 缺失 / 非法 | `code_verifier is missing or invalid` |
| `invalid_grant` | 400 | refresh_token 无效 / 过期 / 属他人 | `refresh token is invalid or expired` |
| `invalid_grant` | 400 | 授权已被撤销后续签 | `the authorization has been revoked` |

★`unauthorized_client` 不会出现，上表就是全部。

### 4.2 平台格式（`code` / `message`，HTTP 恒 200）

| 场景 | code | message（zh / en） |
|---|---|---|
| 成功 | `0000` | 请求成功 |
| ★**scope 不足**（`oa-` 令牌打非授权路径） | **`4030`** | 「access_token 权限不足，无法访问该接口」/ `The access token does not have sufficient scope for this API`。★响应里只会是 `4030` |
| `oa-` 令牌无效 / 过期 / 已撤销（回落未登录） | `4010` | 「未登录或登录超时」/ `Not signed in or session expired.` |
| 授权用户被禁用 / 删除 | `4030` | 「账号已被禁用」/ `Account has been disabled.` |
| 门面必填参数缺失 | `4000` | 「参数'%s'不能为空」/ `Parameter '%s' is required.` |
| IM RPC 未配置 / 停用 | `3612` | 「IM 服务未配置或已停用，请联系管理员」 |
| IM RPC 暂时不可用（可带同 `bizMsgId` 重试） | `3613` | 「IM 服务暂时不可用，请稍后重试」 |
| IM 上游业务拒绝 | `3614` | 「IM 拒绝了本次操作: %s」 |
| 会话 id 非法 / 不属于当前用户（★三种情况不区分） | `0001` | `Invalid conversationId` |
| 智能体未发布（建会话） | `4000` | 「智能体不存在或已下架」 |
| 发布范围权限不足 | `0001`（chat：`Permission denied`）/ `4030`（tool：「无权限」） | 见左 |
| 模型 id 不在可用集合 | `4000` | `model not available for API access: [ids]`（★不区分「不存在 / 无权 / 未开放」三种情况） |
| 业务失败（通用） | `0001` | 例：`Too many API keys (limit 20), revoke unused ones first`、`No permission to upload file for this agent id`、`API key not found`、`id is required` |

**平台通用错误码**：`0000` 请求成功 / `0001` 请求失败 / `4000` 参数错误 / `4001` 邀请码错误 / `4010` 未登录或登录超时 / `4011` 未登录（浏览器跳转型）/ `4030` 无权限 / `4033` 无此资源权限 / `4040` 接口不存在 / `4050` 方法不允许 / `5000` 系统异常 / `6001` 项目启动中 / `0400` 许可证内容无效。

★`4040` + message 形如 `No static resource api/v1/4sandbox/...` 是**这条路径沙箱没放开**的典型表现（见 [api/admin.md](api/admin.md)）。

★`33xx` 这一段业务码（`3341` 客户端不存在 / `3342` 回调地址不一致 / `3344` 授权码无效 / `3345` 密钥不正确 / `3346` 仅限三方应用 / `3347` 生效回调地址集合为空 / `3348` 地址格式非法 / `3349` scope 非法 / `3351` 申请不存在 / `3352` 补充回调地址超限 / `3353` 主回调地址不可由沙箱写入）**只在管理端接口以原码出现**（见 [api/admin.md](api/admin.md)）；打到协议端点时会被转成上面那四个 RFC 错误码。

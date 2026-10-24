# 认证与令牌（协议端点，5 个）

拿到 `client_id` / `client_secret` 之后，换取和管理令牌都走本文件的 5 个端点，前缀 `{PLATFORM_BASE_URL}/api/oauth2`。

★协议端点**不用平台信封**，三种格式并存：

| 端点 | 响应体 | HTTP 状态码 | 判定成败 |
|---|---|---|---|
| `authorize` / `token` / `revoke` | RFC 6749 扁平结构（失败时 `{"error","error_description"}`） | **真实状态码**（200 / 302 / 400 / 401） | 看 HTTP 状态码 + 有无 `error` 字段 |
| `userinfo` / `user/search` | **裸对象**：响应体本身就是数据，没有 `code`/`message`/`data` 外壳 | 恒 200 | 拿到对象即成功 |

★`/token` 与 `/revoke` 不需要登录态就能直调（服务端校验 client 凭据）；`/authorize` 未登录会 302 跳平台登录页；`/userinfo` 与 `/user/search` 必须带 `oa-` 令牌。

完整接入流程（模式选择、授权码 + PKCE 时序、回调处理、令牌续期）见《接入指南》；本文件是逐端点契约。

---

## 1. 发起授权 —— `GET /api/oauth2/authorize`

授权码模式入口。真人浏览器访问，登录后 302 回你的 `redirect_uri` 并带授权码。

**请求参数**（全部在 query）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `client_id` | 字符串 | 事实必填 | 为空 → 400 JSON `invalid_client` |
| `redirect_uri` | 字符串 | 事实必填 | 须与登记的生效回调地址**逐字相等**（精确字符串比较，差一个斜杠都拒） |
| `response_type` | 字符串 | 事实必填 | 必须为 `code` |
| `scope` | 字符串 | 可选 | 逗号或空白分隔；空 = 应用登记的全部 scope |
| `state` | 字符串 | 可选 | 回跳时原样带回；为空则省略该参数 |
| `code_challenge` | 字符串 | 可选 | PKCE，43–128 字符 |
| `code_challenge_method` | 字符串 | 可选 | 仅 `S256` / `plain`，**大小写敏感**；challenge 非空而本参数空时按 `plain` |

**请求示例**

```
GET /api/oauth2/authorize?client_id=nwxa_0123456789abcdef0123456789abcdef
    &redirect_uri=https%3A%2F%2Fapp.example.com%2Fcallback
    &response_type=code
    &scope=profile%2Cchat%3Aread
    &state=xyz123
    &code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM
    &code_challenge_method=S256
```

**成功响应**：签发一次性授权码（★**5 分钟有效、只能用一次**），**302** 到：

```
https://app.example.com/callback?code=dGhpcyBpcyBhbiBleGFtcGxlIGNvZGUxMjM0&state=xyz123
```

（百分号编码，空值省略。）

**行为分支（按代码顺序）**

| 条件 | 结果 |
|---|---|
| 客户端不存在 / 已停用 / 类型不符 | **400 JSON**（不跳转，RFC 6749 §4.1.2.1）`invalid_client` |
| `redirect_uri` 与登记值不一致 | **400 JSON** |
| `response_type != code` | **302** 回 `redirect_uri`，带 `error=unsupported_response_type`、`error_description=response_type must be code`、`state` |
| `oauth2.auto-consent=false` | **302** `error=temporarily_unavailable`、`consent page is not available`。★当前实现**没有同意页**，该配置默认 `true` ⇒ 授权恒静默放行 |
| 申请的 scope 未登记 / 超出应用允许范围 | **302** `error=invalid_scope`。★本校验发生在**真人登录之后**——未登录时先 302 去登录页，匿名阶段永远不报 scope 错（探测全是假阴性） |
| `code_challenge_method` 非 S256/plain | **302** `invalid_request` / `code_challenge_method must be S256 or plain` |
| `code_challenge` 长度越界 | **302** `invalid_request` / `code_challenge must be 43-128 characters` |
| 全部通过 | 302 带 `code` 回跳（见上） |

**回跳错误示例**（302 的 Location 里带上的参数形态）

```json
{
  "error": "invalid_scope",
  "error_description": "The requested scope is invalid or exceeds the allowed scopes",
  "state": "xyz123"
}
```

**坑**

- ★PKCE 校验发生在客户端与回调地址校验**之后**——只有 `redirect_uri` 已可信才允许把错误回跳给它。
- **未登录时**：**302** 到 `{siteUrl}/login?redirect={URL 编码后的本次授权完整地址（含原始 query string）}`，响应体同时写一份 `4011` 的 JSON 便于 curl 调试。用户登录成功后会自动带着原参数回到授权端点续走流程。
- ⇒ **脚本无法全自动完成授权码流程**：必须由真人在浏览器里登录一次。

---

## 2. 换令牌 —— `POST /api/oauth2/token`

全部参数 form 或 query 均可，哪些必填由 `grant_type` 决定。

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `grant_type` | 字符串 | 事实必填 | 三值白名单：`authorization_code` / `refresh_token` / `client_credentials`，其他值 → 400 `unsupported_grant_type` |
| `code` | 字符串 | `authorization_code` 时 | 授权码 |
| `redirect_uri` | 字符串 | `authorization_code` 时 | 须与 authorize 请求**逐字相等** |
| `code_verifier` | 字符串 | 仅当授权码携带 challenge | PKCE 校验 |
| `refresh_token` | 字符串 | `refresh_token` 时 | 旧 refresh token |
| `scope` | 字符串 | 可选，仅 `client_credentials` 用 | 空 = 登记全集再收窄 |
| `client_id` + `client_secret` | 字符串 | 二选一（或 Basic 头） | 客户端认证 |
| `Authorization` 头 | 字符串 | 表单未给 `client_id` 时的回退 | `Basic base64(id:secret)` |

**请求示例**（机器对机器，form 表单）

```
POST /api/oauth2/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id=nwxa_0123456789abcdef0123456789abcdef&client_secret=nwxa_s_REPLACE_ME&scope=chat%3Aread
```

**成功响应示例**（200，`Content-Type: application/json`）

```json
{
  "access_token": "oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "token_type": "Bearer",
  "expires_in": 7200,
  "refresh_token": "or-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "scope": "profile,chat:read"
}
```

**成功响应字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `access_token` | 字符串 | `oa-` + 43 位 url-safe base64（32 字节熵，无 padding），总长 46 |
| `token_type` | 字符串 | 固定 `"Bearer"` |
| `expires_in` | 数字（秒） | = `oauth2.access-token-ttl`，**默认 7200（2h）** |
| `refresh_token` | 字符串 | `or-` 前缀，46 字符。★**仅 `authorization_code` 与 `refresh_token` 两种 grant 返回**；`client_credentials` **不返回**（机器对机器模式下令牌过期重换一次即可）——上例是授权码模式的形态 |
| `scope` | 字符串 | 逗号拼接的**实际授予集**（机器对机器模式可能比你申请的少，★以这里回告的为准） |

响应头：`Cache-Control: no-store`、`Pragma: no-cache`。

**失败响应示例**（400）

```json
{
  "error": "invalid_scope",
  "error_description": "scope [im:read] requires an end-user authorization and is not available with client_credentials; use grant_type=authorization_code"
}
```

**客户端认证失败** → **401** `invalid_client`，`error_description` 为业务异常文案。

**客户端凭据传递规则**：**表单字段优先**，`client_id` 表单参数非空即取表单对（此时忽略 Basic 头）；否则解析 `Authorization: Basic base64(id:secret)`（前缀大小写不敏感、按第一个 `:` 切分、非法 base64 视为未提供）。secret 比对为常量时间，防时间侧信道。

**PKCE 校验规则**

- verifier 须匹配 `^[A-Za-z0-9\-._~]{43,128}$`（RFC 7636 §4.1）
- `S256`：`base64url_nopad(SHA-256(verifier))` 与 challenge 比对
- `plain`：challenge 即 verifier 原文，直接字节比对（method 缺省按 plain）
- 失败（含 verifier 缺失）→ 400 `invalid_grant` / `code_verifier is missing or invalid`。★授权码此刻**已被一次性消费删除**，无法重试爆破

**refresh_token 机制**（轮换式）

- 旧 refresh 无效 / 过期 / 属他人 → 400 `invalid_grant` / `refresh token is invalid or expired`
- 用户授权已被撤销 → 400 `invalid_grant` / `the authorization has been revoked`
- 成功时**轮换**：旧 refresh_token 立即作废，签发新的一对
- TTL 默认 **30 天**
- access_token 每次校验通过即**滑动续期 2h**；彻底过期或被撤销后，门面接口返回 `4010`，客户端应改用 refresh grant 续签

**client_credentials 的 scope 收窄（M2M restrict）**

- **显式**申请「仅授权码」的 scope（`im:read` / `im:push` / `model:read` / `model:key`）→ 400 `invalid_scope` / `scope [xxx] requires an end-user authorization and is not available with client_credentials; use grant_type=authorization_code`
- 未申请 scope 但登记集全为「仅授权码」（收窄后为空）→ 400 `invalid_scope` / `this client only has user-authorization scopes registered; nothing is available with client_credentials`
- ★token 端允许集**含默认 `profile`**（实测）：登记了其他 scope 后，M2M 仍可申请到 `profile`

---

## 3. 撤销令牌 —— `POST /api/oauth2/revoke`

**请求参数**（form 或 query）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `token` | 字符串 | 事实必填（无效也返回 200） | access_token 或 refresh_token，**按前缀 `or-` 自动识别**，`token_type_hint` 不参与判定 |
| `token_type_hint` | 字符串 | 可选 | ★**仅记日志，无逻辑作用** |
| `client_id` / `client_secret` | 字符串 | 二选一（或 Basic 头） | 认证失败 → **401** `invalid_client` |

**响应**：恒 **200**，body 为**空对象**，`Cache-Control: no-store`；令牌无效同样 200（RFC 7009）。

```json
{}
```

★撤销是**用户级**而非单令牌级：写入撤销时间戳并删除授权记录，签发时间早于撤销时间的该 (client, user) **全部令牌即刻失效**，refresh 续签也会因授权不存在被拒。仅当令牌属于该 client 时才执行撤销。

---

## 4. 授权用户信息 —— `GET|POST /api/oauth2/userinfo`

GET 与 POST 都接受，**无任何请求参数**；请求头带 `Authorization: Bearer <access_token>`，令牌须含 `profile` scope。

★令牌缺 `profile` 返回 **4030**（实测），登录流会断在这一步；应用登记集常恰好不含 profile（默认 scope 申请时容易漏），授权请求务必带上它。

★**本端点回裸对象，不是平台信封**：响应的**顶层字段**直接取用，**不要**去找 `code`/`message`/`data`。失败（令牌无效、scope 不足）才由平台统一异常处理加工成带 `code` 的信封，HTTP 仍是 200。

**成功响应示例**（200）

```json
{
  "sub": "21",
  "user_id": 21,
  "name": "张三",
  "avatar": "https://files.example.com/f/xxxx?ak=yyyy",
  "tenant_id": 1
}
```

**响应字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `sub` | 字符串 | userId 的字符串形式 |
| `user_id` | 数字 | 平台用户 ID |
| `name` | 字符串 | 昵称，空则回落账号名 |
| `avatar` | 字符串 | 头像地址，**平台外可直接打开**（平台已带好访问凭证，有效期 24h）⇒ 直接塞进 `<img src>`，**别自己再换一次**、也别长期缓存；用户未设置头像时为 `null` |
| `tenant_id` | 数字 | 租户 ID |

HTTP 200；**无** `Cache-Control` 头。

**失败响应示例**（HTTP 仍 200，此时才是信封）

```json
{
  "code": "4030",
  "displayCode": "4030",
  "message": "access_token 权限不足，无法访问该接口",
  "data": null,
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

---

## 5. 按账号检索用户 —— `GET|POST /api/oauth2/user/search`

需 `user:search` scope（★不在默认 scope 集里，要单独申请）。

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `userName` / `email` / `phone` | 字符串 | **三选一** | 全空 → 400 `invalid_request` / `at least one of userName, email or phone is required`；多给按 userName > email > phone 取第一 |

**请求示例**

```
GET /api/oauth2/user/search?email=zhangsan%40example.com
Authorization: Bearer oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

**命中响应**（200，裸对象，字段与 `userinfo` **完全一致**）

```json
{
  "sub": "21",
  "user_id": 21,
  "name": "张三",
  "avatar": null,
  "tenant_id": 1
}
```

**查不到 / 跨租户 / 已删除** → 200 **空对象**，★**不区分原因**，带 `Cache-Control: no-store`：

```json
{}
```

★空对象 `{}` 是**正常语义**不是错误——该端点刻意不区分「不存在 / 无权 / 已删除」，否则会退化成「某手机号是否存在于别租户」的探测器。别把 `{}` 当异常抛。

## 6. 在 iframe 里跑登录（会话 Cookie + 跳转上下文）

把应用嵌进 iframe（平台的智能体预览就是这种形态）时有两个**互相独立**的问题，别混为一谈：

- **存不存得下** —— 取决于浏览器的第三方 Cookie 策略（应用域与顶层域不同站时，应用处在第三方上下文）；
- **带不带得上** —— 取决于 `SameSite`（跨站子帧导航只带 `SameSite=None` 的 Cookie）。

对应手段也不同：前者靠 CHIPS，后者靠「把跳转放到顶层上下文」。

### 6.1 会话 Cookie 要一次下发**两条**

只写一条非分区的 `SameSite=None; Secure`，在「阻止第三方 Cookie」的浏览器里会被**直接丢弃**（连 `Set-Cookie` 都不落地），症状是**「登录成功但回调拿不到会话」**。标准做法是一次下发两条，读取时取任一条：

```
Set-Cookie: <会话名>=<值>; SameSite=None; Secure; HttpOnly; Path=/
Set-Cookie: <会话名>=<值>; SameSite=None; Secure; HttpOnly; Path=/; Partitioned
```

- 第一条（非分区）服务**顶层直接打开**的场景；第二条带 `Partitioned`（CHIPS，独立分区 Cookie）服务 **iframe** 场景；
- `SameSite=None` 必须**同时**带 `Secure`，否则浏览器直接丢弃；`Partitioned` **强制** `Secure` ⇒ 本地 http 调试只有第一条能存，第二条被丢属正常；
- ★**实现坑**：`Partitioned` 直到 **Python 3.14** 才进 `http.cookies`。3.13 及更早在 Starlette 上 `set_cookie(..., partitioned=True)` 会直接抛 `ValueError` ⇒ 这条路径只能**手工拼 `set-cookie` 响应头**（或升到 3.14+）；
- `HttpOnly` 照常保留：`code`、`state`、会话标识都不该被页面脚本读到；
- 在 302 导航的响应里下发同样生效（OAuth 回调正是这条路径），不必先落一个中间页。

### 6.2 授权跳转：**弹小窗 + 认证完自关**，别整页跳走

iframe 自己发起的导航是**跨站子帧导航**，浏览器只带 `SameSite=None` 的 Cookie —— 平台的登录态凭据是 `SameSite=Lax`，**带不上**。症状就是「平台里明明已登录，应用在 iframe 里还是被甩到女娲登录页」。所以授权跳转必须发生在**顶层上下文**，而且**只认「弹小窗」这一种形态**：

```js
// ① 登录页：弹小窗（固定 name ⇒ 反复点击复用同一个窗口，不会开一堆）
const win = window.open('/oauth/start', 'nuwax-oauth', 'width=520,height=640')
if (!win) location.href = '/oauth/start'          // ★被浏览器拦截才降级，别当默认

// ② 回调页：认证完成 ⇒ 通知原页面，然后关掉自己
if (window.opener && !window.opener.closed) {
  window.opener.postMessage({ type: 'nuwax-oauth-done', ok: true }, window.location.origin)
  window.close()
}

// ③ 原页面：收到通知刷新登录态（★必须校验来源，只认自己域）
window.addEventListener('message', (event) => {
  if (event.origin !== window.location.origin) return
  if (event.data && event.data.type === 'nuwax-oauth-done') location.reload()
})
```

- ⛔ **别用整页跳转**（`<a href="/oauth/start">`、`location.href=`、服务端直接 `302`）—— 用户会离开你的应用、认证完停在授权页上不回来，观感就是「跳到新窗口再也关不掉」。四栈模板的登录页已按上面生成，改的时候别退回整页跳转。
- ⛔ **弹窗里不要带 `noopener`**：带了 `window.open` 直接返回 `null`（拿不到窗口句柄），回调页的 `window.opener` 也是 `null`（通知不了原页面）⇒ 只剩「用户自己发现登录好了」。代价是中间经过的第三方页（平台授权页）能持有 opener 引用 ⇒ **回传消息必须校验 `event.origin` 与消息类型**。`noreferrer` 仍然禁止：它会连 `Referer` 一起抹掉（见 §2.2）。
- **降级只在被拦截时发生**：`window.open` 返回 falsy ⇒ 退回 `location.href` 顶层跳转；此时回调页没有 `window.opener`、不自关，照常显示结果页。
- ⛔ 别走「先跳平台登录页、指望它认出已登录再自动跳回」这条捷径 —— 实测停在登录页，不会跳回；
- 诊断：看请求头 `sec-fetch-dest: iframe` 即可确认请求来自子帧；要在自己域上验证 SameSite 行为，自己设一条 `Lax` 与一条 `None` 的探针 Cookie 做**同刻对照**（对方域的 Cookie 属性你看不到，`HttpOnly` 读不到，别去猜）。

### 6.3 两条红线

- ⛔ **不要为了「能嵌入」去加或放宽 `X-Frame-Options` / CSP `frame-ancestors`** —— 官方整条跳转链（授权页、登录页、回调、`/userinfo`）本来就没有这两个头，应用能在 iframe 里完成整轮认证。白屏时的头号嫌疑恰恰是**你自己或框架模板**（Helmet / Spring Security / nginx）顺手带上的 `DENY` / `frame-ancestors 'none'`；要收紧也只放宽口径。
- ⛔ 判断「是不是第三方 Cookie 被拦」**不要用 `Sec-Fetch-Site`** —— 它表示**发起者与目标**的关系，iframe 里发起的**同源**请求照样显示 `same-origin`，决定权在**顶层站点**，这个头看不出来。

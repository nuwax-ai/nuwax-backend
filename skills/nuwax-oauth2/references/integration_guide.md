# 女娲平台 OAuth2 接入指南

> 本文的读者是**要在自己的业务系统里接入女娲平台**的开发者。
> 目标有两件事：① 让你的系统用**女娲账号登录**（你的用户不必再注册一套账号）；② 在你的系统里**代表该用户或你的服务端**调用女娲的开放接口。
>
> 文中所有示例的密钥与令牌均为占位符（`nwxa_xxx` / `oa-xxx`）。平台域名统一记作 `https://<平台域名>`，请替换为你所在租户的实际地址。
>
> 字段级契约见同目录 [api_reference.md](api_reference.md)（总览：响应格式、scope 全集、错误码全集）与 `api/` 目录的分模块接口文档（逐端点参数表 + JSON 示例 + 坑，一个模块一个文件，按需读取）。
>
> ★**最省事的入口是 §2.6**：平台内智能体用 `nuwax-oauth2` 技能的 `scaffold` 子命令，可以一条命令生成一套**参数已预填、可直接落进你仓库**的对接代码（Python / Node / Spring Boot / curl 四种栈，仅依赖标准库或你已有的框架）。第 3～10 节讲的是这套代码背后必须遵守的平台契约 —— 代码已经替你实现好了，你想手抄或核对语义时再读。技能用法见 [../SKILL.md](../SKILL.md)。

---

## 1. 两种接入模式，先选对

| | 模式 A：机器对机器 | 模式 B：授权码 + PKCE |
|---|---|---|
| `grant_type` | `client_credentials` | `authorization_code` |
| 令牌代表谁 | **你的应用自身**（无具体自然人） | **授权用户本人** |
| 需要真人操作吗 | ❌ 不需要，一次 HTTP 请求搞定 | ✅ 需要用户在浏览器里登录一次 |
| 能拿到的 scope | 8 个 | **全部 12 个** |
| 拿不到的 | `im:read`、`im:push`、`model:read`、`model:key` | — |
| 签发 refresh_token | ❌ 不签发（过期重换即可） | ✅ 签发，30 天，**轮换式** |
| 典型场景 | 你的后台服务批量执行插件/工作流、上传文件、发起对话 | 「用女娲账号登录」；以用户身份读写他的 IM、查他的会话、给他建模型 API Key |

**判断口径**：只要你的功能里出现「当前登录用户自己的数据」（他的 IM、他的会话、他的联系人、他名下可用的模型），就必须走**模式 B**。纯后台批处理走模式 A 更省事。

两种模式可以**共存于同一个应用**：同一个 `client_id` 既能 M2M 换令牌，也能走授权码，取决于你请求时传的 `grant_type`。

---

## 2. 前置准备：创建应用、登记回调、申请 scope

这四步在平台的**网页控制台**完成（也可以让平台内的智能体用 `nuwax-oauth2` 技能代跑，见 §2.5）。

### 2.1 创建三方应用

在控制台创建一个「三方应用」，得到一个 `projectId`（后续所有管理操作的句柄）。创建后平台自动生成一对凭据：

- `client_id`：`nwxa_` 前缀
- `client_secret`：`nwxa_s_` 前缀

★`client_secret` 是**加密存储**的，**不会**以明文出现在任何列表接口里。要取明文只能显式调用「查看密钥」（见 2.4），且平台会**留审计日志** —— 取到后请立刻存进你自己的密钥管理，别留在日志或聊天记录里。

### 2.2 登记回调地址（`redirect_uri`）—— 只有模式 B 需要

★**一个应用可以登记多个回调地址，分「主地址」与「补充列表」两处存**：

| | 字段 | 谁能改 |
|---|---|---|
| 主地址 | `redirectUri` | **只由网页端人工改**；沙箱里的 AI 提交非空值即报 `3353`（库中为空也不放行） |
| 补充列表 | `redirectUris[]`（写入用 `addRedirectUris` / 删除用 `removeRedirectUris`） | 谁都能加；**AI 只能删自己加的条目**（`source=Sandbox`），删人工条目会被跳过并在 `skippedRedirectUriRemovals` 里回报 |

**生效集合 = 主地址 ∪ 补充列表每条 `uri`**，授权时任一全等即通过。集合整体为空才拒授权（`3347`）。
所以「主地址为空、只有补充地址」是**合法**状态 —— 沙箱里的 AI 给新应用配地址，走的就是这条路径。
★每个应用最多 20 条补充地址，每条最长 512 字符（超了 `3352`）。

匹配规则（下面这些表）对主地址与补充地址**一视同仁**。

回调地址必须是：

- **绝对** http(s) URL，必须有 host；
- **不能带 `#fragment`**；
- 授权请求里传的 `redirect_uri` 与登记值做**字符串精确比对（equals）**。

⚠️ **不是前缀匹配**。以下都算不一致，会被直接拒绝：

| 登记值 | 授权时传的值 | 结果 |
|---|---|---|
| `https://my.app/cb` | `https://my.app/cb/` | ❌ 多一个斜杠 |
| `https://my.app/cb` | `http://my.app/cb` | ❌ http ≠ https |
| `https://my.app/cb` | `https://my.app:443/cb` | ❌ 显式端口 |
| `https://my.app/cb` | `https://my.app/cb?x=1` | ❌ 带了 query |

本地调试可以把 `http://127.0.0.1:8080/callback` 登记为回调地址（平台不禁止 loopback），但**不要在生产沿用**。

**★拼 `redirect_uri` 时别在运行时猜主机名。** 能从配置读就从配置读 —— 应用跑在容器里时，
它的网络视图里**没有**外部域名（反代会把 `Host` 改写成容器内地址，也不注入
`X-Forwarded-Host`），按 `request.base_url` / `req.headers.host` 之类的推出来的多半是
`http://127.0.0.1/...`，授权端点只会回 `invalid_request: 回调地址与登记的不一致`。

确有动态需求、必须靠浏览器带来的来源信息还原时，**前端弹窗发起授权时不要带 `noreferrer`**：

```js
// ❌ noreferrer 会把 Referer 一起抹掉，服务端连兜底都没了
window.open(loginUrl, 'nuwax-oauth', 'width=520,height=640,noreferrer')
// ✅ 弹小窗（登录跳转的规定形态，见 §6.2）
window.open(loginUrl, 'nuwax-oauth', 'width=520,height=640')
```

`noopener` 与 `noreferrer` 常被一起写，但这里**两个都不能带**：`noreferrer` 会把 `Referer`
一并抹掉（后端随即拿不到外部来源、回退到容器内地址，症状就是上面那个 `invalid_request`），
而 `noopener` 会让回调页拿不到 `window.opener`、没法通知原页面也没法自动关闭（见 §6.2）。
更稳的做法是前端**显式带上来源**（如 `?origin=<window.location.origin>`）让后端优先采用它，
不要把正确性押在 `Referer` 上 —— 浏览器策略可能压根不发。

#### ★反代之后怎么定 `redirect_uri`（多环境 / 多域名必读）

应用落在反代 / 网关后面时，「当前域名是什么」这件事**只能由浏览器告诉后端**，后端自己推不出正确值。四条：

1. **前端把 `window.location.origin` 随登录请求带上**（`/login?origin=...` 或请求体字段）—— 它是唯一与用户地址栏一致的事实源。
   两者都没有时 `Referer` 只能当兜底：浏览器策略可能不发，`noreferrer` 一定会抹掉。
2. **后端只在【已登记的白名单】里匹配**：命中就用它拼 `redirect_uri`；不命中回落到白名单首项；
   **白名单之外的 `origin` 一律不接受** —— 否则等于允许任意站点把授权码引到自己的域名上（开放重定向）。
3. **授权那一刻定下的 `redirect_uri` 存进服务端会话**（与 `state`、`code_verifier` 放一起），
   回调阶段**原样复用**，不要重新拼：重拼极易与浏览器地址栏差一个斜杠 / 少个 query，而平台是**逐字 equals**（见上面「匹配规则」）。
4. ⛔ **不要按 `Host` / `X-Forwarded-Host` 推导**。实测：平台入口到容器（Pingap 反代）之间会把 `Host` **重写成公网 IP**、
   协议记成 `http`，而且**只写 `x-forwarded-proto`、不写 `x-forwarded-host`** ⇒ 由请求头推出来的是 `http://<公网IP>/...`，
   必然与登记值不一致，授权端只会回 `invalid_request: 回调地址与登记的不一致`；落地页里也会带着这个 IP 露出来，
   ⛔ 别顺手把它登记成回调地址（换个环境又不匹配了）。

★自检：`redirect-check --redirect-uri "<浏览器地址栏里那个>"` —— 与生效集合（主地址 ∪ 补充列表）逐字比对，不在集合内会直接告诉你差在哪。

### 2.3 申请 scope（需管理员审核）

提交 scope 变更申请后：

1. 应用的 scope 审核状态变为 **待审核**，同时记录你申请的目标集合；
2. ★**审核通过前，应用仍按原来「已生效」的 scope 运行** —— 这不是 bug，是防止权限在无人复核的情况下即时扩大；
3. 审核通过后生效；被拒会给出拒绝原因；
4. 申请**清空** scope（传空集）是合法操作，效果是回落到平台默认的 `profile`。

⇒ 如果你刚加了 scope 却发现调用仍报权限不足，**先查审核状态**，别急着怀疑令牌。

还有两条容易忽略的动态行为，别混为一谈：

1. **每次校验令牌时，令牌的 scope 会与「应用当前已登记集」取交集**。所以管理员收窄你的应用 scope 后，**已经签发出去的令牌立刻失去对应能力**，不必等它过期。
   反过来，**扩权只对「令牌里本来就有、先前被登记集剪掉」的 scope 即时生效**（先登记 → 后来移除 → 又加回来，那次剪裁被撤回）。⛔ 别读成「扩权对所有存量令牌都即时生效」。
2. ★**scope 在令牌签发那一刻就固化在令牌里** —— 签发时没申请到的，之后平台登记得再全也补不进去。实测：一张签发时只有 `profile` 的令牌，随后平台把全部 13 项都登记上，这张老令牌里照旧没有 `chat:read` / `chat:write`，调用仍然 `4030`，**现象与「权限压根没开通」一模一样**。所以「补登权限 + 改配置 + 重新发布」这一串做完，**已登录用户手里那张旧令牌还是旧的**，唯一解是让他**重新登录一次**（排查法见 §11）。

★**两端的「允许集」口径实测有差**，别拿一端的行为去推另一端：

- **token 端（机器对机器）**：应用登记了非默认 scope 之后，`profile` 仍可授予；scope 串逗号 / 空格分隔都接受。
- **authorize 端（授权码）**：scope 校验发生在**真人登录之后** —— 匿名阶段打开授权地址不会报 scope 错（**探测全是假阴性**，别据此下结论）；登记集之外的 scope 请求会在登录后被拒 `invalid_scope`（真实事故实测；推断其后置校验口径 = 登记集、且不含默认 `profile`）。

⇒ 授权码登录流的 scope **务必包含 `profile`，且登记集里也要有它**：`/userinfo` 强依赖 `profile`，令牌缺它直接 4030，登录流断在「取用户信息」一步（见 §4.5）。

### 2.4 取 `client_id` / `client_secret`

控制台的应用设置页可直接查看；「查看密钥」会解密并返回明文，同时写审计日志。

★如果查看密钥时报服务端异常并提到 `aes-key`：这是平台侧的部署配置问题，**重试无用** ⇒ 请联系平台运维。此时其余配置（回调地址、scope、client_id）照常可用，可以先把代码接起来，密钥后补。

### 2.5 让平台内智能体代跑

如果你已经有女娲平台的沙箱环境，可以直接对智能体说「帮我接入女娲登录」，它会用 `nuwax-oauth2` 技能完成：列出你名下的应用 → 登记回调地址 → 提交 scope 申请 → 取凭据 → **生成一套可直接落进你仓库的对接代码**（见 2.6）→ 跑一遍链路自检验证配置无误。

### 2.6 ★一条命令拿到能跑的对接代码（最省事的入口）

上面 2.1～2.4 配好之后，第 3～10 节的所有代码**你都不必自己抄**。让智能体跑：

```bash
python3 scripts/oauth2.py scaffold --stack all --out ./nuwax-integration
```

它会在 `./nuwax-integration/` 下按技术栈生成四个子目录，每个都是「客户端 + 示例服务 + `.env.example` + README」四件套：

| `--stack` | 生成物 | 依赖 |
|---|---|---|
| `python` | `nuwax_oauth2.py`（客户端）、`example_server.py`（回调 + 调用示例） | **仅标准库**，Python 3.7+ |
| `node` | `nuwax-oauth2.js`、`example-server.js` | Node 18+（用内置 `fetch`），无第三方依赖 |
| `spring-boot` | `NuwaxOAuth2Client.java`、`NuwaxOAuth2Controller.java`、`application-nuwax.yml` | JDK 11+、Spring Boot、Jackson |
| `curl` | `nuwax.sh`（可 `source` 的 6 个函数） | `bash` + `curl` |
| `all` | 以上四种 + 顶层 `README.md`（怎么选栈） | — |

另外还会生成一个 `docs/` 目录：**这份指南与端点契约里，按你要接的模块切出来的那几片** —— `auth.md`（认证与令牌）、`common.md`（响应格式 / 错误码 / 排错）恒带，再加上 `user.md` / `chat.md` / `im.md` / `model.md` / `tool.md` 中与你 scope 对应的那些。切哪几片由 `--scope` 决定（不给就用平台上已生效的那一组）。★它不是另一份文档，就是从原文按小节抽出来的 ⇒ 你的工程师只读要接的那一片，不必读完整篇指南。

生成物已经**预填好**你的真实参数：平台基址、`client_id`、已登记的 `redirect_uri`、**已生效**的 scope 集、`projectId`。PKCE 的 verifier/challenge 生成、`state` 校验、三种响应格式的解包（信封 `code`、RFC `error`、裸对象直接取用）、工具执行类的 `data.success` 二级判定、`refresh_token` 轮换、缺密钥时的人话报错 —— 本文第 3～10 节讲的每一个坑，代码里都已经处理好了。

★**生成物里不含 `client_secret`**，只留环境变量 `NUWAX_CLIENT_SECRET` 与占位符 `nwxa_s_REPLACE_ME`。所以这套代码可以直接提交进 git、发给同事。你要做的只有一件事：

```bash
cd nuwax-integration/python
cp .env.example .env        # .env 记得进 .gitignore
# 把 NUWAX_CLIENT_SECRET 填成你在 2.4 取到的明文
```

每个栈的 README 里都有「快速上手」三步、六条平台契约、27 端点速查表、12 scope 表与排错表 —— **它本身就是本文的随代码版本**，你的工程师不必再来读这份指南。

`scaffold` 是**离线**的：不发协议请求，只在有沙箱凭据时读一次应用配置来预填参数（★只读 `setting`，绝不取 secret）。参数拿不到时会退化成看得见的占位符（`nwxa_REPLACE_ME`）并在摘要里逐条说明要手填什么，不会静默填假值。也可以显式指定：

```bash
python3 scripts/oauth2.py scaffold --stack spring-boot --package com.acme.nuwax \
    --client-id nwxa_xxx --redirect-uri https://my.app/oauth/callback \
    --scope profile,chat:read --base-url https://<平台域名> --out ./nuwax-integration
```

---

## 3. 模式 A：机器对机器

一次请求，无需真人：

```http
POST /api/oauth2/token HTTP/1.1
Host: <平台域名>
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id=nwxa_xxx&client_secret=nwxa_s_xxx
```

成功响应（HTTP **200**）：

```json
{
  "access_token": "oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "token_type": "Bearer",
  "expires_in": 7200,
  "scope": "profile,user:space,tool:read,tool:exec"
}
```

要点：

- **没有 `refresh_token` 字段**：机器对机器令牌代表应用自身，过期后重新换一把即可，不需要长期凭据。
- `scope` 是**实际授予集**。如果你不传 `scope` 参数，平台会用「应用已登记集」再按 M2M 规则收窄后如实回告 —— 以响应里的 `scope` 为准，不要假设。
- 客户端认证可以用**表单字段** `client_id` + `client_secret`，也可以用 `Authorization: Basic base64(client_id:client_secret)` 头。**表单字段优先**：一旦表单里给了非空的 `client_id`，Basic 头就被忽略。

**显式申请「仅授权码」scope 会被拒**（HTTP 400）：

```json
{
  "error": "invalid_scope",
  "error_description": "scope [im:push] requires an end-user authorization and is not available with client_credentials; use grant_type=authorization_code"
}
```

如果你的应用**只**登记了「仅授权码」类 scope，M2M 会因为收窄后为空而报另一种 `invalid_scope`（`this client only has user-authorization scopes registered...`）—— 此时这个应用根本不能走 M2M，请改用模式 B。

---

## 4. 模式 B：授权码 + PKCE（用女娲账号登录）

### 4.1 完整时序

```
你的前端            你的后端              女娲平台              用户的浏览器
   |                  |                     |                      |
   |--① 请求登录----->|                     |                      |
   |                  |--② 生成 PKCE + state（本地保存 verifier）  |
   |<--③ 302 授权地址-|                     |                      |
   |                  |                     |      ④ 打开授权地址-->|
   |                  |                     |<--⑤ 未登录?302 登录页-|
   |                  |                     |<--⑥ 用户登录成功------|
   |                  |                     |--⑦ 302 回你的回调地址-->
   |                  |        (redirect_uri?code=...&state=...)    |
   |                  |<--⑧ 回调请求（带 code + state）--------------|
   |                  |--⑨ 校验 state，用 code + code_verifier 换令牌-->
   |                  |<--⑩ access_token + refresh_token-------------|
   |                  |--⑪ GET /api/oauth2/userinfo（Bearer 令牌）-->
   |                  |<--⑫ sub / user_id / name / avatar / tenant_id|
   |<--⑬ 建你自己的会话（用 user_id 关联）---|                      |
```

★**第 ⑤–⑥ 步无法自动化**：`/api/oauth2/authorize` 需要登录态，未登录会被 302 送到平台登录页，并把本次授权的完整地址（含 query）编码进 `redirect` 参数，登录成功后自动回到授权流程续走。所以用户必须在浏览器里真人登录一次。

### 4.2 第一步：生成授权地址

```
https://<平台域名>/api/oauth2/authorize
  ?response_type=code
  &client_id=nwxa_xxx
  &redirect_uri=https%3A%2F%2Fmy.app%2Foauth%2Fcallback
  &scope=profile,im:read,chat:read
  &state=<随机串，回跳时原样带回>
  &code_challenge=<base64url(SHA-256(code_verifier))，无 padding>
  &code_challenge_method=S256
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `response_type` | 是 | 固定 `code`，其他值会以 `unsupported_response_type` 回跳 |
| `client_id` | 是 | 应用不存在或已停用时返回 **400 JSON**（不跳转） |
| `redirect_uri` | 是 | 必须与登记值**逐字相等**；不一致时返回 **400 JSON**（不跳转）。★**别在运行时拼主机名**（容器里没有外部域名）—— 取值方式与前端跳转的坑见 §2.2 |
| `scope` | 否 | 逗号或空白分隔；**省略 = 申请应用已登记的全部 scope** |
| `state` | 否 | 建议必传（CSRF 防护）；回跳时原样带回，为空则省略该参数 |
| `code_challenge` | 否（**强烈建议传**） | 43–128 字符 |
| `code_challenge_method` | 否 | 只接受 `S256` / `plain`，**大小写敏感**；传了 challenge 却省略本参数时按 `plain` 处理 |

**为什么强烈建议用 PKCE**：授权码会经过浏览器地址栏、可能的日志与 Referer 链。有了 PKCE，即使授权码被截获，攻击者没有 `code_verifier` 也换不到令牌。

**错误回跳 vs 错误 JSON 的分界**（很重要，会影响你的前端处理）：

| 情况 | 平台的行为 |
|---|---|
| `client_id` 无效 / `redirect_uri` 与登记值不一致 | **400 JSON**，不跳转（RFC 6749 §4.1.2.1：回调地址不可信时绝不回跳，避免把 code 送到攻击者的地址） |
| `response_type` 非法 / scope 未登记 / PKCE 参数非法 | **302 回跳**到你的 `redirect_uri`，带 `error` + `error_description` + `state` |
| 全部通过 | **302 回跳** `redirect_uri?code=<授权码>&state=<原值>` |

⇒ 你的回调处理逻辑**必须先检查 URL 里有没有 `error` 参数**，再取 `code`。

★同时有多处不对时，先报 `client_id` / `redirect_uri` 的问题，PKCE 的问题排在后面 ⇒ 排错按这个顺序查：先确认应用与回调地址，再看 verifier。

**当前没有同意页**：平台配置为自动同意，所以用户登录成功后**直接签发授权码并回跳**，不会看到「是否授权 XXX 访问你的信息」的确认页。如果你的产品需要向用户展示将获取哪些权限，请在**你自己的页面上**说明（scope 清单见 §6）。

### 4.3 第二步：处理回调

回调地址会收到：

```
https://my.app/oauth/callback?code=<32位授权码>&state=<你传的原值>
```

按顺序做三件事：

1. **校验 `state`** 与你发起时保存的值一致（不一致 = 疑似 CSRF，直接拒绝，不要继续）；
2. **检查有无 `error`** 参数，有就按 §8 的错误码表处理；
3. **立刻**用 `code` 换令牌（见 4.4）。

★授权码 **有效期 5 分钟，且一次性**。关键后果：**换令牌失败也算已消费** —— 你不能拿同一个 code 重试，只能让用户重走一遍授权。所以换令牌这段代码要有完善的日志与错误呈现，别静默失败。

### 4.4 第三步：换令牌

```http
POST /api/oauth2/token HTTP/1.1
Host: <平台域名>
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=<回调里的授权码>
&redirect_uri=https://my.app/oauth/callback
&code_verifier=<第一步生成的 verifier 原文>
&client_id=nwxa_xxx
&client_secret=nwxa_s_xxx
```

★`redirect_uri` 在这里**必须与授权请求时逐字相等** —— 平台会二次比对。这是最容易踩的坑：第一步用了 URL 编码、第三步传了未编码的原文（或反之），会得到 `invalid_grant` / `redirect_uri does not match the authorization request`。建议把第一步实际发出的那个字符串原样存下来，第三步直接用。

成功响应（HTTP 200）：

```json
{
  "access_token": "oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "token_type": "Bearer",
  "expires_in": 7200,
  "refresh_token": "or-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "scope": "profile,im:read,chat:read"
}
```

响应头带 `Cache-Control: no-store` 与 `Pragma: no-cache`。

**PKCE 校验规则**（服务端实现，供你对拍）：

- `code_verifier` 必须匹配 `^[A-Za-z0-9\-._~]{43,128}$`；
- `S256`：`base64url_nopad(SHA-256(code_verifier))` 与授权时的 `code_challenge` 比对；
- `plain`：`code_challenge` 即 verifier 原文，直接字节比对；
- 比对使用**常量时间**算法（防时间侧信道）；
- 校验失败（含 verifier 缺失）→ HTTP 400 `invalid_grant` / `code_verifier is missing or invalid`。

### 4.5 拿到身份：`/api/oauth2/userinfo`

```http
GET /api/oauth2/userinfo HTTP/1.1
Authorization: Bearer oa-xxx
```

需要 `profile` scope —— ★令牌缺它直接 **4030**，登录流断在这一步；应用登记集常恰好不含 profile（它是默认 scope，申请时容易漏），授权请求的 scope 务必带上它（见 §2.3）。响应（HTTP 200，★**裸对象、不是平台信封**，见 §8.1）——下面这些字段就是响应的**顶层字段**，直接取用：

```json
{
  "sub": "10086",
  "user_id": 10086,
  "name": "张三",
  "avatar": "https://...",
  "tenant_id": 1
}
```

⛔ 别按信封去找 `code`/`data`：本端点没有那层外壳，按信封取会把 `sub` 读成 `null`，还容易把正常响应判成失败。
（失败时——令牌无效、scope 不足——平台会把异常加工成带 `code` 的信封，HTTP 仍是 200。）

| 字段 | 说明 |
|---|---|
| `sub` | userId 的字符串形式（标准 OIDC 习惯，建议作为你系统里的外部用户主键） |
| `user_id` | 平台用户 ID（Long） |
| `name` | 昵称；昵称为空时回落为账号名 |
| `avatar` | 头像地址，**平台外可直接打开**（平台已带好访问凭证，有效期 24h）⇒ 直接塞进 `<img src>`，**别自己再换一次**、也别长期缓存；用户未设置头像时为 `null` |
| `tenant_id` | 租户 ID |

⇒ **「用女娲账号登录」到此完成**：拿 `sub`（或 `user_id`）在你自己的用户表里做关联/建号，然后签发你自己的会话。

补充：还有一个 `GET|POST /api/oauth2/user/search`（需 `user:search` scope，**敏感**，不在默认 scope 内），按 `userName` / `email` / `phone` **三选一**检索租户内用户，返回字段与 `userinfo` 完全一致。★查不到、跨租户、已删除**都返回空对象 `{}` 且不区分原因**（防账号枚举）。

---

## 5. 令牌生命周期

| 令牌 | 前缀 | 默认有效期 | 续期方式 |
|---|---|---|---|
| `access_token` | `oa-` + 43 位 | **2 小时** | ★**每次校验通过自动滑动续期 2 小时**；或用 refresh_token |
| `refresh_token` | `or-` + 43 位 | **30 天** | ★**轮换式**：每次刷新签发新值，旧的**立即作废** |
| 授权码 | 无前缀，32 位 | **5 分钟**，一次性 | 不可续期 |

### 5.1 滑动续期意味着什么

只要用户在 2 小时内有活动，`access_token` 就不会过期。所以**不要**依赖「算 expires_in 到点主动刷新」这一个策略 —— 更稳的做法是：**收到 `4010`（未登录/超时）就触发一次刷新**（见 5.2），刷新也失败才要求用户重新登录。

### 5.2 刷新

```http
POST /api/oauth2/token HTTP/1.1
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token&refresh_token=or-xxx&client_id=nwxa_xxx&client_secret=nwxa_s_xxx
```

响应结构同 §4.4（含**新的** `refresh_token`）。

★**轮换是硬约束**：刷新成功后，**必须把新 `refresh_token` 持久化并覆盖旧值**。如果你在多实例/多进程里并发用同一个旧 refresh_token 刷新，后到的那次会因「旧值已删除」而得到 `invalid_grant` / `refresh token is invalid or expired` —— 请给刷新操作加锁或串行化。

刷新失败的可能原因：refresh_token 无效/过期/属于别的用户；或**该用户对应用的授权已被撤销**（`the authorization has been revoked`）。后者只能让用户重走授权流程。

### 5.3 令牌失效的表现

`access_token` 彻底过期或被撤销后，门面接口不会返回 401，而是 **HTTP 200 + `code:"4010"`**（回落成未登录）。⇒ 你的 HTTP 客户端封装里，**判 401 是不够的**，必须同时判信封里的 `code`。

### 5.4 撤销（用户解除授权）

```http
POST /api/oauth2/revoke HTTP/1.1
Content-Type: application/x-www-form-urlencoded

token=oa-xxx&client_id=nwxa_xxx&client_secret=nwxa_s_xxx
```

- 传 `access_token` 或 `refresh_token` 都行，平台**按 `or-` 前缀自动识别**，`token_type_hint` 参数**只记日志、不参与判定**。
- 响应**恒为 HTTP 200，body 为空对象 `{}`** —— 即使令牌本来就无效（RFC 7009 要求）。所以「200」**只说明请求被受理**，不能用来断言「那个令牌确实存在且被删了」。

★**撤销是用户级的，不是单令牌级**：一旦撤销，该 (应用, 用户) 组合下**所有早于撤销时刻签发的令牌即刻失效**，后续用 refresh_token 续签也会被拒。设计「退出登录」时要意识到这一点：它等于解绑，不是只丢一个 token。

---

## 6. scope 全表（13 个）

| scope | 能力 | 敏感 | ★M2M 可用 |
|---|---|---|---|
| `profile` | 读取授权用户自身信息（昵称、头像等） | 否 | ✅ |
| `user:search` | 按用户名/邮箱/手机号检索租户内用户 | **是** | ✅ |
| `user:space` | 查询授权用户可见的空间列表 | 否 | ✅ |
| `file:write` | 以授权用户身份上传文件 | 否 | ✅ |
| `file:read` | 为文件签发带凭证的下载地址 | 否 | ✅ |
| `chat:read` | 读取授权用户的任务会话与消息记录 | 否 | ✅ |
| `chat:write` | 以授权用户身份创建会话、发起与停止对话 | **是** | ✅ |
| `im:read` | 读取授权用户的 IM 会话、群组、联系人与消息 | **是** | ❌ **仅授权码** |
| `im:push` | 以授权用户身份向指定目标推送 IM 消息 | **是** | ❌ **仅授权码** |
| `model:read` | 查询租户可用模型列表 | 否 | ❌ **仅授权码** |
| `model:key` | 创建模型 API Key 并授权可用模型 | **是** | ❌ **仅授权码** |
| `tool:read` | 检索已发布的插件与工作流、读取其出入参定义 | 否 | ✅ |
| `tool:exec` | 执行已发布的插件与工作流 | **是** | ✅ |

要点：

- **默认 scope 只有 `profile`**。应用没登记任何 scope 时按这个兜底。
- ★**「M2M 可用」这一列不能从接口读到** —— 管理端的 scope 列表接口只返回 `scope` / `description` / `sensitive` 三个字段。以本表为准。
- 权限判定**只看请求路径，不看 HTTP 方法**。所以读写分离是靠不同路径实现的（例如 `chat:read` 覆盖 `/conversation/list`，`chat:write` 覆盖 `/conversation/create`）。
- 敏感 scope（标 **是** 的）通常需要更严格的申请理由，管理员可能拒绝。
- 最小权限原则：**只申请你真正要用的**。scope 越宽，用户与管理员的审阅成本越高，被拒概率越大。

---

## 7. 开放接口清单（29 个）

全部挂在 `/api/oauth2` 下，用 `Authorization: Bearer <access_token>` 调用。响应**一律是平台信封**（§8），HTTP 状态码恒 200。

★**逐端点的参数表、JSON 示例与完整坑清单已移入 `api/` 目录的分模块文件**（要查哪个模块读哪个文件）：[auth](api/auth.md)（协议端点 5）· [user](api/user.md)（3）· [chat](api/chat.md)（11）· [im](api/im.md)（5）· [model](api/model.md)（5）· [tool](api/tool.md)（5）。本节只留每模块最易踩的点。

### 7.1 用户与文件（3）

空间列表、文件上传、文件下载地址，详见 [api/user.md](api/user.md)。要点：★`/file/url` **不校验文件归属**，别把内部文件 URL 暴露给不可信前端；`userinfo` 的 `avatar` 平台外可直接打开，**不需要**再调它换一次。

### 7.2 任务会话（11）

会话与消息的读写、SSE 实时对话，详见 [api/chat.md](api/chat.md)。最容易踩的三个点：

1. **删除 / 停止 / 清空三个接口的参数走 URL query 或 form**，**不是 JSON body**。写成 `{"conversationId":123}` 的 JSON body 会报「参数不能为空」。
2. **`/message/send` 有三个字段被服务端强制覆盖**：`from`（固定 `"api"`）、`filterSensitive`（固定 null）、`agentConfig`（固定 null）。你传了也没用。
3. **SSE 端点的错误在流内，不抛异常**。连接建立成功不代表任务成功 —— 必须解析帧内容（`eventType`、`error`、`completed`）。

### 7.3 消息通信 IM（5）

IM 会话、群组、联系人、消息与推送，详见 [api/im.md](api/im.md)。要点：

- ★**群组列表翻页判停只看 `hasMore`**（一页可能为空但 `hasMore` 仍为 true）；分页结果没有 `total`。
- 推送目标 `toUserId` / `convId` **二选一且只能给一个**；网络重试带同一 `bizMsgId`（幂等，不会重发）。
- ★发送者恒为授权用户本人；`msgId` 是雪花 ID，JavaScript 侧按字符串解析。

### 7.4 模型（5）

「用女娲的模型额度跑你自己的应用」：模型列表 + API Key 签发管理，详见 [api/model.md](api/model.md)。要点：

1. ★**`accessKey` 明文只在 create 响应里出现一次**，丢了只能吊销重建，务必当场存好。
2. ★**吊销（物理删除、不可恢复）是唯一有效的关闭手段**——`enabled` / `expire` 都不被模型代理校验，改它们挡不住调用。
3. `update` 的 `modelIds` 是**整体替换不是增量**；单用户上限 20 把。
4. ★签发的是**模型专用 Key，打不进平台的任何普通 HTTP 接口**，只有模型代理接受它。

### 7.5 工具检索与执行（5）

工具检索、schema 查询、插件与工作流的同步 / SSE 执行，详见 [api/tool.md](api/tool.md)。要点：

0. ★**先用 `tool/list` 发现工具、`tool/schema` 拿它的入参定义，再执行**——别把工具 id 硬编码；`tool/schema` 返回的 `requestBody` 可直接当 function calling 的 `parameters`。

1. **业务成败在 `data.success` 里，不在外层 `code`**——工具执行失败时外层信封仍是 `code:"0000"`，判定必须两层都看。
2. **参数不额外包一层**：请求体本身就是参数 Map，不要写成 `{"params": {...}}`。
3. **同步执行工作流没有超时限制**，长任务改用 SSE；★SSE 失败在流内推错误帧、不抛异常，连接建立成功 ≠ 执行成功。

---

## 8. 响应格式与错误处理

### 8.1 ★三种格式并存（必读）

| 端点类别 | 响应体 | HTTP 状态码 | 怎么判成败 |
|---|---|---|---|
| **协议端点**：`token`、`revoke`、`authorize` | RFC 6749 扁平结构，失败时 `{"error","error_description"}` | **真实状态码**（200 / 302 / 400 / 401） | 看 HTTP 码 + 有无 `error` 字段 |
| **门面端点**：§7 的 29 个 | 平台信封 `{code, displayCode, message, data, tid}` | **恒 200**（出异常也 200） | ★看 `code == "0000"` |
| **OIDC 风格裸对象**：`userinfo`、`user/search` | 响应体本身就是数据：`{sub, user_id, name, avatar, tenant_id}`，没有 `code`/`data` 外壳 | **恒 200** | 拿到对象即成功；`user/search` 查无此人是正常的空对象 `{}` |

**口诀：响应里有 `code` 才是平台信封。** 别用同一段代码去解三种格式。

⇒ **最常见的两个集成 bug**：① 用「HTTP 200 = 成功」判断门面接口——scope 不足、参数错误、会话不存在，HTTP 全是 200，错误信息在信封里；② 把裸对象的 `userinfo` 当信封读——`sub` 恒为 `null`，还会把成功判成失败。

平台信封示例：

```json
{
  "code": "4030",
  "displayCode": "4030",
  "message": "access_token 权限不足，无法访问该接口",
  "data": null,
  "tid": "..."
}
```

`displayCode` 与 `code` 取值一致；`message` 会按请求语言（`lang` Cookie）在中英文之间选择。`tid` 是链路 ID，**报障时请一并附上**。

### 8.2 协议端点的 RFC 错误码

| `error` | HTTP | 触发条件 |
|---|---|---|
| `invalid_client` | 401（token/revoke）；400（authorize） | 应用不存在 / 已停用 / 类型不符 / `client_secret` 错误 |
| `invalid_grant` | 400 | 授权码无效、过期或**已被使用**；授权码签给了别的应用；`redirect_uri` 与授权请求不一致；`code_verifier` 缺失或非法；refresh_token 无效/过期；授权已被撤销 |
| `invalid_scope` | 400 | M2M 申请了「仅授权码」scope |
| `invalid_scope` | 302 回跳 | authorize 阶段申请的 scope 未登记或超出应用允许范围 |
| `unsupported_grant_type` | 400 | `grant_type` 不是 `authorization_code` / `refresh_token` / `client_credentials` 三者之一 |
| `unsupported_response_type` | 302 回跳 | `response_type` 不是 `code` |
| `invalid_request` | 400 | PKCE 参数非法（method 不是 S256/plain、challenge 长度越界）；`user/search` 三个检索参数全空 |

`error_description` 会给出具体原因（例如 `authorization code is invalid, expired or already used`、`redirect_uri does not match the authorization request`），**请原样记进日志**。

★平台**不会**返回 `unauthorized_client`，不要为它写分支。

### 8.3 业务端点的信封 `code`

| `code` | 含义 | 处理 |
|---|---|---|
| `0000` | 成功 | 取 `data`。★工具执行类还要再看 `data.success` |
| `4010` | 未登录或登录超时 | `access_token` 无效/过期/已撤销 ⇒ 用 refresh_token 续签，续签失败则请用户重新授权 |
| `4030` | 无权限 | 两种情况：① **令牌 scope 不足**（message 为「access_token 权限不足，无法访问该接口」）⇒ 申请对应 scope；② 授权用户账号被禁用 |
| `4000` | 参数错误 | message 会指名哪个参数，如「参数'convId'不能为空」 |
| `0001` | 业务失败（通用） | 看 message 原文，如 `Invalid conversationId`、`Too many API keys (limit 20)...` |
| `4040` | 接口不存在 | 路径写错了；或用了未开放的端点 |
| `4050` | 方法不允许 | GET/POST 用反了 |
| `5000` | 系统异常 | 带 `tid` 联系平台 |
| `3612` | IM 服务未配置或已停用 | 环境未接入 IM，联系管理员 |
| `3613` | IM 服务暂时不可用 | **可带同一个 `bizMsgId` 重试**（幂等保证不会重发） |
| `3614` | IM 拒绝了本次操作 | message 里带上游原因 |

★`Invalid conversationId` 把「id 非法 / 会话不存在 / 会话不属于当前用户」**三种情况合并成同一句**，排障时请自己核对会话归属。

---

## 9. 返回数据结构（常用字段）

★字段表与 JSON 示例已并入 `api/` 目录各模块文件末尾的「数据结构」节，按需读取：空间 / 文件对象见 [api/user.md](api/user.md)；会话 / 消息 / SSE 帧 / 智能体详情见 [api/chat.md](api/chat.md)；IM 会话、联系人、消息见 [api/im.md](api/im.md)；模型与 API Key 对象见 [api/model.md](api/model.md)；工具结果对象见 [api/tool.md](api/tool.md)；管理端对象见 [api/admin.md](api/admin.md)。

---

## 10. 代码示例

★**本节全部示例都能一键生成、且生成得更完整**：`nuwax-oauth2` 技能的 `scaffold` 子命令会按 `python` / `node` / `spring-boot` / `curl` 四种栈产出「客户端 + 示例服务 + `.env.example` + README」，参数已预填你的真实 `client_id` 与回调地址，且把本节讲的每个坑（PKCE、`state` 校验、三种响应格式、`data.success` 二级判定、`refresh_token` 轮换）都实现好了。见 §2.6。下面这些片段适合你想**手抄进已有代码**时参考。

以下示例统一用环境变量承载敏感值，**不要把凭据硬编码进源码或提交进仓库**。

```bash
export NUWAX_BASE="https://<平台域名>"
export NUWAX_CLIENT_ID="nwxa_xxx"
export NUWAX_CLIENT_SECRET="nwxa_s_xxx"        # 只在服务端使用
export NUWAX_REDIRECT_URI="https://my.app/oauth/callback"
```

### 10.1 curl

```bash
# ── 模式 A：M2M 换令牌 ──
curl -sS -X POST "$NUWAX_BASE/api/oauth2/token" \
  -d grant_type=client_credentials \
  -d client_id="$NUWAX_CLIENT_ID" \
  -d client_secret="$NUWAX_CLIENT_SECRET"

# ── 模式 B：打印授权地址（人工在浏览器打开）──
# code_verifier: 43~128 位 [A-Za-z0-9-._~]
VERIFIER=$(openssl rand -base64 48 | tr -d '=+/' | cut -c1-64)
CHALLENGE=$(printf '%s' "$VERIFIER" | openssl dgst -binary -sha256 | openssl base64 | tr '+/' '-_' | tr -d '=')
STATE=$(openssl rand -hex 16)
echo "$NUWAX_BASE/api/oauth2/authorize?response_type=code&client_id=$NUWAX_CLIENT_ID&redirect_uri=$(python3 -c "import urllib.parse,os;print(urllib.parse.quote(os.environ['NUWAX_REDIRECT_URI'],safe=''))")&scope=profile,chat:read&state=$STATE&code_challenge=$CHALLENGE&code_challenge_method=S256"
echo "code_verifier=$VERIFIER  # 保存下来，换令牌时要用"

# ── 模式 B：授权码换令牌（回调拿到 code 后）──
curl -sS -X POST "$NUWAX_BASE/api/oauth2/token" \
  -d grant_type=authorization_code \
  -d code="<回调里的 code>" \
  -d redirect_uri="$NUWAX_REDIRECT_URI" \
  -d code_verifier="$VERIFIER" \
  -d client_id="$NUWAX_CLIENT_ID" \
  -d client_secret="$NUWAX_CLIENT_SECRET"

# ── 调业务接口：★判 code，不是判 HTTP 状态 ──
curl -sS "$NUWAX_BASE/api/oauth2/userinfo" -H "Authorization: Bearer $TOKEN"

# ── 执行插件：★参数就是整个请求体，无参也要给 {} ──
curl -sS -X POST "$NUWAX_BASE/api/oauth2/tool/plugin/execute?pluginId=9" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'

# ── 删除会话：★参数在 query，不是 body ──
curl -sS -X POST "$NUWAX_BASE/api/oauth2/chat/conversation/delete?conversationId=123456" \
  -H "Authorization: Bearer $TOKEN"

# ── 刷新 / 撤销 ──
curl -sS -X POST "$NUWAX_BASE/api/oauth2/token" -d grant_type=refresh_token \
  -d refresh_token="$REFRESH" -d client_id="$NUWAX_CLIENT_ID" -d client_secret="$NUWAX_CLIENT_SECRET"
curl -sS -X POST "$NUWAX_BASE/api/oauth2/revoke" -d token="$TOKEN" \
  -d client_id="$NUWAX_CLIENT_ID" -d client_secret="$NUWAX_CLIENT_SECRET"
```

### 10.2 Python（标准库，含 PKCE 与信封判定）

```python
import base64, hashlib, json, os, secrets, urllib.parse, urllib.request

BASE = os.environ["NUWAX_BASE"]
CLIENT_ID = os.environ["NUWAX_CLIENT_ID"]
CLIENT_SECRET = os.environ["NUWAX_CLIENT_SECRET"]
REDIRECT_URI = os.environ["NUWAX_REDIRECT_URI"]


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def make_pkce():
    verifier = _b64url(secrets.token_bytes(32))          # 43 字符，满足 43~128
    challenge = _b64url(hashlib.sha256(verifier.encode()).digest())
    return verifier, challenge


def build_authorize_url(scope: str, state: str, challenge: str) -> str:
    """生成授权地址。verifier 与 state 必须自己存下来（session / 加密 cookie）。"""
    return BASE + "/api/oauth2/authorize?" + urllib.parse.urlencode({
        "response_type": "code",
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT_URI,
        "scope": scope,
        "state": state,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    })


def _post_form(path: str, form: dict):
    """协议端点：RFC 扁平格式，失败时 HTTP 码是真实的。"""
    req = urllib.request.Request(
        BASE + path,
        data=urllib.parse.urlencode(form).encode(),
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def exchange_code(code: str, state: str, expected_state: str, verifier: str) -> dict:
    if state != expected_state:
        raise RuntimeError("state mismatch — 疑似 CSRF，拒绝继续")
    status, body = _post_form("/api/oauth2/token", {
        "grant_type": "authorization_code", "code": code,
        "redirect_uri": REDIRECT_URI, "code_verifier": verifier,
        "client_id": CLIENT_ID, "client_secret": CLIENT_SECRET,
    })
    if status != 200 or "error" in body:
        raise RuntimeError("换令牌失败 %s: %s / %s" % (
            status, body.get("error"), body.get("error_description")))
    return body     # 含 access_token / refresh_token(★新值，须覆盖存储) / expires_in / scope


def token_m2m() -> dict:
    status, body = _post_form("/api/oauth2/token", {
        "grant_type": "client_credentials",
        "client_id": CLIENT_ID, "client_secret": CLIENT_SECRET,
    })
    if status != 200 or "error" in body:
        raise RuntimeError("M2M 换令牌失败: %s" % body)
    return body     # 无 refresh_token


def refresh(refresh_token: str) -> dict:
    status, body = _post_form("/api/oauth2/token", {
        "grant_type": "refresh_token", "refresh_token": refresh_token,
        "client_id": CLIENT_ID, "client_secret": CLIENT_SECRET,
    })
    if status != 200 or "error" in body:
        raise RuntimeError("刷新失败: %s" % body)
    return body


def api(token: str, path: str, method: str = "GET", query=None, body=None):
    """业务端点：平台信封，HTTP 恒 200 ⇒ 必须判 code。"""
    url = BASE + "/api/oauth2/" + path.lstrip("/")
    if query:
        url += "?" + urllib.parse.urlencode(query)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": "Bearer " + token,
        "Content-Type": "application/json",
    })
    with urllib.request.urlopen(req, timeout=30) as resp:
        envelope = json.loads(resp.read())
    if envelope.get("code") != "0000":
        raise RuntimeError("[%s] %s (tid=%s)" % (
            envelope.get("code"), envelope.get("message"), envelope.get("tid")))
    data = envelope.get("data")
    # ★工具执行类：外层成功不代表业务成功
    if isinstance(data, dict) and data.get("success") is False:
        raise RuntimeError("工具执行失败: %s" % data.get("error"))
    return data


if __name__ == "__main__":
    tok = token_m2m()["access_token"]
    print(api(tok, "space/list"))
```

### 10.3 Java（JDK 11+ `HttpClient`）

```java
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;

public class NuwaxOAuth2 {
    static final String BASE = System.getenv("NUWAX_BASE");
    static final HttpClient HTTP = HttpClient.newHttpClient();

    /** 协议端点：RFC 扁平格式，看 HTTP 状态码 + error 字段 */
    static HttpResponse<String> token(String form) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 || resp.body().contains("\"error\"")) {
            throw new IllegalStateException("token 端点失败 " + resp.statusCode() + ": " + resp.body());
        }
        return resp;
    }

    static String m2mToken() throws Exception {
        String form = "grant_type=client_credentials"
                + "&client_id=" + enc(System.getenv("NUWAX_CLIENT_ID"))
                + "&client_secret=" + enc(System.getenv("NUWAX_CLIENT_SECRET"));
        return token(form).body();   // 实际项目请用 JSON 库解析出 access_token
    }

    /** 业务端点：平台信封，HTTP 恒 200 ⇒ 必须判 code == "0000" */
    static String api(String accessToken, String path, String method, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + "/api/oauth2/" + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json");
        if ("POST".equalsIgnoreCase(method)) {
            b.POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody));
        } else {
            b.GET();
        }
        String body = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
        // 用你的 JSON 库判定：code != "0000" 即失败；工具执行类还要判 data.success
        if (!body.contains("\"code\":\"0000\"")) {
            throw new IllegalStateException("接口失败: " + body);
        }
        return body;
    }

    static String enc(String v) {
        return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
```

### 10.4 Node.js（后端，`fetch`）

```javascript
const BASE = process.env.NUWAX_BASE;
const CLIENT_ID = process.env.NUWAX_CLIENT_ID;
const CLIENT_SECRET = process.env.NUWAX_CLIENT_SECRET;   // 只在服务端
const REDIRECT_URI = process.env.NUWAX_REDIRECT_URI;

// ── 协议端点：RFC 扁平格式 ──
async function token(form) {
  const res = await fetch(`${BASE}/api/oauth2/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(form),
  });
  const body = await res.json();
  if (!res.ok || body.error) {
    throw new Error(`${body.error}: ${body.error_description ?? res.status}`);
  }
  return body;
}

const m2m = () => token({
  grant_type: "client_credentials", client_id: CLIENT_ID, client_secret: CLIENT_SECRET,
});

// ── 生成授权地址（PKCE，Node 18+ 内置 WebCrypto）──
async function buildAuthorizeUrl(scope, state) {
  const verifier = Buffer.from(crypto.getRandomValues(new Uint8Array(32)))
    .toString("base64url");                                  // 43 字符
  const challenge = Buffer.from(
    await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)),
  ).toString("base64url");                                   // 无 padding
  const url = new URL(`${BASE}/api/oauth2/authorize`);
  Object.entries({
    response_type: "code", client_id: CLIENT_ID, redirect_uri: REDIRECT_URI,
    scope, state, code_challenge: challenge, code_challenge_method: "S256",
  }).forEach(([k, v]) => url.searchParams.set(k, v));
  return { url: url.toString(), verifier, state };           // verifier/state 存进 session
}

// ── 回调处理 ──
async function handleCallback(query, session) {
  if (query.error) throw new Error(`${query.error}: ${query.error_description ?? ""}`);
  if (query.state !== session.state) throw new Error("state mismatch — 疑似 CSRF");
  const tok = await token({
    grant_type: "authorization_code", code: query.code,
    redirect_uri: REDIRECT_URI, code_verifier: session.verifier,
    client_id: CLIENT_ID, client_secret: CLIENT_SECRET,
  });
  // ★tok.refresh_token 是新值，必须覆盖存储（旧的已作废）
  return tok;
}

// ── 业务端点：HTTP 恒 200，必须判 code ──
async function api(accessToken, path, { method = "GET", query, body } = {}) {
  const url = new URL(`${BASE}/api/oauth2/${path.replace(/^\//, "")}`);
  Object.entries(query ?? {}).forEach(([k, v]) => url.searchParams.set(k, v));
  const res = await fetch(url, {
    method,
    headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
    body: method === "POST" ? JSON.stringify(body ?? {}) : undefined,
  });
  const env = await res.json();
  if (env.code !== "0000") throw new Error(`[${env.code}] ${env.message} (tid=${env.tid})`);
  if (env.data?.success === false) throw new Error(`工具执行失败: ${env.data.error}`);
  return env.data;
}
```

**SSE 端点**（`/chat/message/send`、`/chat/message/subscribe`、`/tool/workflow/sse-execute`）请用你语言的流式客户端逐帧读取；注意工具工作流的 SSE 帧在 `data:` 后**多一个空格**才是 JSON。

---

## 11. 排错对照表

★**先认形状，再判成败**：失败输出里**没有** `code:` 这一行、而是打印了顶层字段名 → 你拿到的**根本不是信封**，多半是把 `userinfo` / `user/search` 这类裸对象端点当门面在读。
这时别去查 `client_secret`、scope、令牌有效期 —— **结构假设错了，改代码**（见 §8.1）。

| 现象 | 最可能的原因 | 处置 |
|---|---|---|
| HTTP 200 但业务没效果 | 只判了 HTTP 码，没判信封 `code` | 见 §8.1 |
| `userinfo` 取到的 `sub` / `user_id` 全是 `null`，或报「未知错误」 | 按信封去读了 `data`（本端点是**裸对象**） | 直接用响应体本身，见 §4.5 / §8.1 |
| `user/search` 报「用户不存在」类异常 | 把正常空对象 `{}` 判成了错误（查无此人**是正常语义**） | 空对象按「查不到」处理，不是异常 |
| 保存回调地址返回 `3352` | 补充回调地址超 20 条 | 先 `removeRedirectUris` 清掉不用的 |
| 保存回调地址返回 `3353` | 沙箱里往主地址 `redirectUri` 写了值（库中为空也一样拒） | 主地址对 AI 一律冻结：改用 `addRedirectUris` 加进补充列表；主地址要改请到网页端 |
| 删了回调地址但还在 | 沙箱删了人工（`source=Manual`）条目 | 看响应里的 `skippedRedirectUriRemovals` 确认是哪些被跳过 |
| `code 4030`「access_token 权限不足」 | 令牌没有该端点对应的 scope | 端点所需 scope 见 `api/` 对应模块文件（或 §6 清单），走 §2.3 申请（需审核） |
| 刚加了 scope 还是 4030 | ★申请**还在待审核** | 查应用的 scope 审核状态；通过前按原范围生效 |
| `code 4010` | 令牌过期 / 已撤销 / 用户被禁用 | 用 refresh_token 续签；失败则请用户重新授权 |
| `invalid_grant` + `authorization code is invalid, expired or already used` | 授权码超过 5 分钟，或**已经用过一次（失败也算）** | 只能重走授权，无法重试 |
| `invalid_grant` + `redirect_uri does not match` | 换令牌时的 `redirect_uri` 与授权时不逐字相等（编码差异最常见） | 把授权时实际发出的字符串原样存下来复用 |
| `invalid_grant` + `code_verifier is missing or invalid` | verifier 丢了、被截断、或与 challenge 不配对 | verifier 与 challenge 必须同一次生成、成对保存 |
| `invalid_grant` + `refresh token is invalid or expired` | ★轮换后仍在用旧值；或并发刷新；或授权已撤销 | 保存最新值 + 给刷新加锁 |
| `invalid_client`（401） | `client_secret` 错、应用被停用 | 重新查看密钥；确认应用启用状态 |
| `invalid_scope` + `requires an end-user authorization` | M2M 申请了「仅授权码」scope | 改用模式 B，或从申请里去掉这些 scope |
| 授权地址打开是 400 JSON 而不是登录页 | `client_id` 无效，或 `redirect_uri` 与登记值不一致 | ★这两种情况**不回跳**；核对 §2.2 的精确匹配表 |
| ★回调地址**明明登记对了**，仍然报「回调地址与登记的不一致」 | 服务端在**运行时拼** `redirect_uri`，且拼错了主机名 | 两种成因：① 按请求头推（容器里没有外部域名）⇒ 改成从配置读；② 前端用了 `noreferrer` 把 `Referer` 抹了 ⇒ 拿掉它并显式传 `origin`（★`noopener` 也不能带，见 §6.2）。见 §2.2「反代之后怎么定 `redirect_uri`」。先静态核一遍：`redirect-check --redirect-uri "<地址栏里那个>"`（逐字比对，差一个斜杠都会指出来） |
| `avatar` 塞进 `<img>` 是破图 / 401 | 自己拼了平台文件路径（`{站点}/api/f/{fileKey}`），没带访问凭证 | 直接用 `/userinfo` / `/user/search` 返回的 `avatar` **原值**（平台已带好凭证）；要给别的文件签名才用 `GET /api/oauth2/file/url` |
| 回调收到 `error=invalid_scope`（★真人登录后才报） | 两种成因：① scope 名解析失败（典型：空格被 URL 编码成 `+`，连成一个非法名）；② 名字合法但不在应用**登记集** | 鉴别法：用同一 scope 串向 token 端（M2M）请求 —— 接受 ⇒ ②，查登记集（UserApp 再查 manifest `[env]` 覆盖）；token 端也拒 ⇒ ①，查分隔符与 URL 编码 |
| 匿名打开授权地址「没报错」，据此认为 scope 合法 | ★authorize 的 scope 校验在真人登录**之后**，匿名阶段永远放行（假阴性） | 用技能 `scope-check` 静态预检，或 `token-m2m --scope <串>` 离线鉴证（token 端实时校验） |
| 改了代码里的 scope / 回调地址，线上行为没变 | UserApp 运行时环境变量只认 `project.manifest.toml` 的 `[env]` 固化值，会**静默覆盖**代码默认值 | 改 `[env]` 并**重新发布**；口诀：线上行为与代码不符 ⇒ 先查 `[env]` 覆盖 |
| 补了 scope、改了配置、也重新发布了，**老用户还是 4030** | ★令牌签发时 scope 已固化，旧令牌里没有新 scope | 比对两个集合：**令牌自己的 scope** vs **配置里申请的那份**。配置有、令牌没有 ⇒ 让他**重新登录一次**（文案：「重新登录一次就好，记录不会丢」+ 给按钮）；配置里也没有 ⇒ 平台还没登记这项权限（§2.3）。★两句别合成一句，否则又退化成「去重新登录」一把梭 |
| 应用嵌在 iframe 里，登录**白屏 / 跳不出去** | ① 自己（或框架模板）加的 `X-Frame-Options` / CSP `frame-ancestors`；② iframe 内的跨站导航带不上平台的登录 Cookie | ① 去掉那两个头 —— **官方整条跳转链本来就没有它们**，不需要你为「嵌入」去加（Helmet / Spring Security / nginx 很容易顺手带上）；② 授权跳转走**顶层上下文**，且只用「弹小窗 + 认证完自关」这一种形态（别让 iframe 自己导航、也别整页跳走）。详见 `api/auth.md` §6.2 |
| 登录后**停在女娲授权页 / 新窗口关不掉**，或跳过去就回不来 | 登录入口做成了整页跳转（`<a href>` / `location.href` / 服务端 302） | 改成**弹小窗 + 回调自关**：`window.open(入口, 'nuwax-oauth', 'width=520,height=640')` ⇒ 回调页见 `window.opener` 就 `postMessage` 通知原页面 + `window.close()`；被拦截才 `location.href` 降级。详见 `api/auth.md` §6.2 |
| `/userinfo` 返回 4030，登录断在「取用户信息」 | 令牌里没有 `profile`（登记集常恰好不含它） | 授权请求 scope 补上 profile；登记集没有就一并申请（§2.3） |
| `revoke` 返回 200 但令牌还能用 | 撤销的是**另一个** (应用, 用户) 组合，或你看的是缓存 | 撤销是用户级的；确认令牌归属 |
| 工具执行 `code 0000` 却没结果 | `data.success=false` | 看 `data.error` |
| 工作流 SSE 一连上就结束 | 工作流不存在/未发布/无权 —— **错误在流内** | 解析首帧的 `success` 与 `message` |
| 群组列表翻不完 / 漏数据 | 按「本页 < limit」判停 | ★**只看 `hasMore`** |
| 模型 Key 改了 `enabled=false` 还能调 | ★代理不校验 `enabled` 与 `expire` | 要停用只能 `revoke`（物理删除、不可恢复） |
| `model not available for API access` | modelId 不在你的可用集合内 | 先 `GET /model/list` 拿真实可用的 id（★不存在/无权/未开放三种情况报同一句） |
| 删除会话报「参数不能为空」 | 把 `conversationId` 放进了 JSON body | ★它是 query/form 参数 |
| 插件执行参数没生效 | 把参数包成了 `{"params":{...}}` | ★参数 Map **就是**请求体本身 |

---

## 12. 安全须知

1. **`client_secret` 只能存在于服务端**。前端/移动端/桌面端属于公开客户端，**绝不能**内嵌 secret —— 这类客户端应由你的后端代理换令牌，或走「后端签发短期凭据给前端」的模式。
2. **一定用 PKCE（`S256`）**。授权码会经过浏览器地址栏与可能的日志链路，PKCE 是截获后的最后一道防线。`plain` 方式几乎不提供保护，不要用。
3. **`state` 必传并严格校验**，否则回调端点可被 CSRF（攻击者把他的授权码塞给你的回调，让你的系统绑上他的账号）。
4. **令牌按机密管理**：服务端集中存储（加密），不下发到浏览器 localStorage；日志里**不要打印完整令牌**（打印前 8 位足够定位）。
5. **不要把回调地址登记成通配或过于宽泛的路径**（平台做的是精确匹配，这本身已是保护 —— 不要试图通过登记多个地址来绕开）。
6. **最小 scope 原则**：只申请真正要用的能力，敏感 scope 要有明确的业务理由。
7. **模型 API Key 的 `accessKey` 明文只出现一次**，拿到就存进密钥管理系统；停用只能吊销（不可恢复）。
8. **`/api/oauth2/file/url` 不校验文件归属**：不要把内部文件 URL 暴露给不可信前端。
9. 报障时附上响应里的 `tid` 与 `Trace-Id`，能显著加快定位。

---

## 13. 能力边界（明确不提供）

- ❌ **没有 OIDC 发现文档**（`/.well-known/openid-connect-configuration`）、**没有 `id_token`**、**没有 JWKS 公钥端点**。这是 OAuth2 授权，不是完整 OIDC —— 用户身份请通过 `/api/oauth2/userinfo` 获取，**不要**期待 JWT 自解析。
- ❌ **没有同意页**（当前配置为自动同意）。需要向用户展示权限范围时请在你自己的页面上做。
- ❌ **没有 `unauthorized_client` 错误码**，也没有 §8.2 之外的 RFC 错误码。
- ❌ **不支持 implicit 与 password 两种 grant**，只有 `authorization_code` / `refresh_token` / `client_credentials` 三种。
- ❌ **IM 不开放**：以系统身份发消息、事件推送、撤回消息。
- ❌ **不开放**智能体的创建/修改/发布、知识库管理、空间成员管理。
- ❌ 分页接口**不返回 `total`**，无法做总数展示。
- ❌ 工具执行接口**不接受** `apiKey` 之类的凭据参数，鉴权只认 `Authorization` 头。

---

## 附：快速上手清单

- [ ] 在平台创建三方应用，记下 `projectId`、`client_id`
- [ ] 查看并安全保存 `client_secret`（明文只显示一次的地方是模型 Key，client_secret 可反复查看但有审计日志）
- [ ] 登记 `redirect_uri`（模式 B 必需，注意精确匹配）
- [ ] 按 §6 选最小 scope 集合并提交申请，**等管理员审核通过**
- [ ] ★跑 `scaffold --stack all --out ./nuwax-integration` 拿对接代码（§2.6），把 `client_secret` 注入 `NUWAX_CLIENT_SECRET`
- [ ] 模式 A：跑通 `token_m2m` → `space/list`
- [ ] 模式 B：跑通 `auth-url` → 浏览器登录 → 回调换令牌 → `userinfo`
- [ ] 封装统一的响应判定：协议端点看 HTTP 码 + `error`，业务端点看 `code == "0000"`（工具类再看 `data.success`）
- [ ] 实现令牌刷新（★保存轮换后的新 refresh_token，并加锁防并发）
- [ ] 实现「退出登录 = revoke」（★用户级撤销，会连带失效该用户全部令牌）

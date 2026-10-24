---
name: nuwax-oauth2
description: 当用户要把「他自己的业务系统」接到女娲平台上（他的用户用女娲账号登录他的系统、他的服务端调女娲接口），或要一份女娲接入文档、对接代码时使用。★技能不代替他的系统做认证，产出三样东西：① 平台上配好的应用（client_id / client_secret / 回调地址 / 主页地址）② 能直接落进他仓库的对接代码（python / node / spring-boot / curl）③ 给他工程师读的接入文档。主线三步：dev-app 配好应用 → 问用户要接哪些模块（仅认证 / 用户信息 / 会话 / 消息通信 IM / 模型调用 / 工具调用）→ scaffold 生成代码并按模块给文档。scope 由用户提前在平台上配好，技能不提交申请；★要接认证以外的能力时，先用 ask_question 向用户确认一次「是否已向官方申请对应 scope」，用户选「已申请」才继续。开发全栈应用时平台会注入 DEV_APP_ID，据此自动定位应用；定不下要接哪个应用时用 ask_question 问用户，不许自己挑。需要环境变量 PLATFORM_BASE_URL 与 SANDBOX_ACCESS_KEY；scaffold 离线可用。
tags: [oauth2, sso, 单点登录, 三方登录, 授权码, pkce, access_token, scope, 三方应用, 开放接口, 接入文档, 对接代码, scaffold, 快速接入]
version: "1.1.0"
---

# 女娲 OAuth2 接入

把**用户自己的业务系统**接到女娲上：他的用户用女娲账号登录他的系统，他的服务端调女娲接口。

★技能**不代替他的系统做认证** —— 回调、令牌保管、刷新、调接口都跑在他自己的服务端里。技能产出三样交付物：

| 交付物 | 怎么拿 |
|---|---|
| ① 平台上配好的应用（`client_id` / `client_secret` / 回调地址 / 主页地址） | `dev-app`（一条命令） |
| ② 能直接落进他仓库的对接代码（4 种技术栈，参数已预填） | `scaffold` |
| ③ 给他工程师读的接入文档 | `references/integration_guide.md`（流程）+ `references/api/` 分模块端点契约 |

**什么时候不用这个技能**：用户只是想在当前沙箱里做一件事（发消息、建定时任务、传文件）—— 那走 `nuwax-im` / `nuwax-agent-scheduler` 等技能，不需要绕 OAuth2。

---

## 三步

所有命令都在技能根目录下跑。

★**先判部署形态，别问用户 —— 直接看环境变量 `DEV_APP_ID`**（`printenv DEV_APP_ID` / `echo $DEV_APP_ID`）：**有值 ⇒ 他的系统就部署在女娲 UserApp 上**（这个变量是平台注入给全栈应用的），下面三条**同时**成立；**没有值 ⇒ 是自建服务器 / 容器 / 本地**，三条都不适用。
（判据与 `dev-app` 自动定位应用用的是同一个变量，口径一致；脚本里可直接 `os.environ.get("DEV_APP_ID")` 判，不用额外探测。）

| 支线 | 具体影响 |
|---|---|
| `[env]` 固化 | UserApp 的运行时环境变量只认 `project.manifest.toml` 的 `[env]`（**明文**、改完**必须重新发布**才生效、会**静默覆盖**代码默认值）⇒ 让他改 `[env]`，别只改代码；长期密钥不要写在这一段 |
| 入口会改写 `Host` | 平台入口到容器之间 `Host` 被重写成载体地址、协议记成 `http` ⇒ ⛔ **不要**按 `Host` / `X-Forwarded-Host` 推 `redirect_uri`，按「§2.2 反代之后怎么定 redirect_uri」那套（前端带来源、后端只在白名单里挑） |
| 一次绑了 Dev + Prod 两个域名 | `dev-app` 默认把**全部**绑定域名的回调一并登记 ⇒ 他的系统必须**按访问来源**动态选当次的 `redirect_uri`，不能写死一个 |

`DEV_APP_ID` 为空（自建服务器 / 容器 / 本地开发）⇒ 三条都不适用，按下面三步走。

### 第 1 步 · 配好应用

```bash
python3 scripts/oauth2.py dev-app --path /oauth2/callback
```

`--path` 是**开发者系统里真正接收授权码的那个路由**（这段代码后续要开发到他的项目里，所以由你和他确认）。

一条命令做完：定位应用 → 取 `client_id` 和 `client_secret` → 用该应用绑定的**全部**域名拼出回调地址与主页地址 → 写回平台。★**不碰 scope**，范围由用户提前配好。

★**回调地址有两个位置，AI 只写「补充列表」**：一个应用可登记多个回调地址，生效集合 = 主地址 `redirectUri` ∪ 补充列表 `redirectUris[]`，授权时任一等值即通过。
本命令把算出来的地址**全部**写进补充列表（`addRedirectUris`），已登记过的不重复写。
⛔ **主地址 `redirectUri` 一律不写**（库里为空时也不写）——它只属应用属主、由网页端配置，提交非空值会报 `3353`。AI 的回调地址走补充列表就够用（生效集合取并集）。

★**默认一次登记全部绑定域名**（Dev / Prod 在建应用时就一并分配好），免得发布后回调失配。
只想登记其中一类时用 `--domain-type Dev|Prod|Custom`。
★代价是**应用侧不能把回调地址写死一个**：多个域名都在生效集合里，他的系统要能按**访问来源**（浏览器带来的 `origin`）在已登记集合里挑当次用的那个，挑不中就回落集合首项 —— 判据与边界见 `references/integration_guide.md` §2.2「反代之后怎么定 redirect_uri」。

常用附加参数：

| 参数 | 用途 |
|---|---|
| `--write-env ./.nuwax.env` | 把凭据写成 `.env`（权限 0600，键名与 `scaffold` 的一致），交给开发者 |
| `--reveal-secret` | 打印一次 `client_secret` 明文 |
| `--no-save` | 只演练，不写回平台 |
| `--app-id 201` | 显式指定接哪个应用 |
| `--domain-type Dev` | 只登记这一类绑定域名；★默认不传 = 全部 |
| `--domain <域名>` | 显式指定用哪个域名拼地址 |
| `--redirect-uri` / `--homepage-url` | 完全自己给地址（跳过域名查询） |

**跑完看退出码**：

| 退出码 | 含义 | 你的动作 |
|---|---|---|
| `0` | 配好了 | 核对输出里的 `client_id`、回调地址、**已生效 scope**，然后进第 2 步 |
| `3` | ★**要用户来定，不是失败** | stdout 有一行 `NUWAX_CHOICE_REQUIRED {json}` 候选清单 ⇒ **用 `ask_question` 问用户接哪个、或要不要新建**，拿到答案再带 `--app-id` 重跑。⛔ 不许自己挑：接错应用会把回调地址写进别人的项目 |
| `1` | 真失败 | 按 stderr 提示处置，见「出错了怎么办」 |

输出里的 `client_secret` 默认是掩码。要交给开发者时用 `--write-env`（推荐）或 `--reveal-secret`，⛔ 不要写进任何文档、资料库或对话总结。

### 第 2 步 · 问清楚要接哪些模块

**用户没明确说要接哪些接口时，用 `ask_question` 问他**（可多选），别替他假设「全都要」。

| 模块 | 需要的 scope | 第 3 步会切出这片文档 |
|---|---|---|
| 仅认证（用女娲账号登录他的系统） | `profile` | `docs/auth.md`（★恒带） |
| 用户信息 | `profile` `user:search` `user:space` `file:read` `file:write` | `docs/user.md` |
| 会话（任务会话管理） | `chat:read` `chat:write` | `docs/chat.md` |
| 消息通信 IM | `im:read` `im:push` ★仅授权码 | `docs/im.md` |
| 模型调用 | `model:read` `model:key` ★仅授权码 | `docs/model.md` |
| 工具调用（插件 / 工作流） | `tool:read` `tool:exec` | `docs/tool.md` |

`docs/common.md`（响应格式 / 错误码全集 / 排错表 / 安全须知）与 `docs/auth.md` 一样**恒带**，不用勾。

问完做三件事：

1. **拿第 1 步输出的「平台登记集」对照上表**。不够 ⇒ 告诉用户「还缺哪几个 scope，请去平台上加」。⛔ 不要替他提交申请（那会落一条待管理员审核的申请，卡住接入）。
   ★**登记 ≠ 令牌里有**：登记集只是前提，真正决定令牌内容的是**应用自己发出的那次授权请求里带的 scope**（三方是 `--scope` / 代码里的配置常量；UserApp 是 manifest `[env]` 的 `NUWAX_SCOPE`）。只登记、授权请求没带 ⇒ 令牌里就没这个 scope，调用照旧 `4030`，现象与「还没审批」无法区分。反过来，请求里**只能带登记集里有的名字**：夹带未登记的名字会让**整轮登录**失败（`invalid_scope`），不是只有那个功能不可用。摘要里这两行不一致时按告警改。
2. ★勾了 IM 或模型 ⇒ **必须提醒他**：这四个 scope **机器对机器拿不到**，只能走「代表真人登录」的授权码流程，他的系统里必须有真人授权环节和令牌存储。
3. ★**勾选里含「仅认证」以外的任何模块 ⇒ 进第 3 步前必须用 `ask_question` 向用户确认一次**（这一问同时是提醒）：「对接这些能力需要对应 scope 已在平台生效，你是否已向官方申请了权限？」选项给「已申请，继续」/「还没申请」。
   - 用户选**「已申请，继续」⇒ 才能进第 3 步**；
   - 选「还没申请」⇒ 停下，列出所缺 scope（照第 1 条的对照结果）并告诉他去平台上加，加好后再回来继续；
   - 一次对接**只问这一次**，用户确认过就不再重复问。

### 第 3 步 · 生成代码 + 给文档

```bash
# 四种栈全出（每种一个子目录 + 顶层 README）
python3 scripts/oauth2.py scaffold --stack all --out ./nuwax-integration \
    --scope profile,chat:read

# 只要一种栈：--stack python | node | spring-boot | curl
python3 scripts/oauth2.py scaffold --stack spring-boot --out ./nuwax-integration --package com.acme.nuwax

# 先看摘要确认预填参数对不对，再决定落盘
python3 scripts/oauth2.py scaffold
```

- ★`--scope` 传第 2 步用户勾选的那些模块对应的 scope（照上表抄）—— 它同时决定 `docs/` 下切出哪几片文档。不传就用平台上已生效的那一份。
- 第 1 步跑完，这里**通常什么都不用填**：`client_id`、回调地址、平台基址都会自动带上。
- 每种栈都是四件套：客户端 + 示例服务 + `.env.example` + README（README 自带平台契约、29 个端点表、13 个 scope 表、排错表 ⇒ 开发者拿到代码就等于拿到文档）。
- 生成物里还有 `docs/`：**按勾选模块切好的接口文档片**（端点契约 + 返回数据结构 + 接入要点），`auth.md` 与 `common.md` 恒带。这些是从技能 `references/` 按小节抽出来的，不是第二份文档 ⇒ 平台文档更新后重跑一次就是新版。
- ★生成物里**绝不会出现真实 `client_secret`**，只留 `NUWAX_CLIENT_SECRET` 占位 ⇒ 可以直接进 git、发给同事。
- 参数没读到时会退化成看得见的占位符（`nwxa_REPLACE_ME`）并在摘要里说明，**不报错**。

**交付时务必说清六句话**：① `client_secret` 去平台控制台取，注入 `NUWAX_CLIENT_SECRET` 环境变量；② `.env.example` 复制成 `.env`，`.env` 不进 git；③ 令牌刷新后 `refresh_token` 会换新的，他系统里的存储必须跟着覆盖；④ **多环境（本地/预发/生产）各自把回调地址登记进平台**（生效集合取并集），授权开始时把当次用的 `redirect_uri` 连同 `state`、`code_verifier` 一起存进服务端会话，回调阶段原样回传 —— 重拼一个很容易与浏览器地址栏对不上；⑤ 他系统若部署在**女娲 UserApp** 上，`[env]` 那套支线全都要讲（见开头的部署形态表）；⑥ **登录入口要弹小窗、认证完自己关**（`window.open(入口, 'nuwax-oauth', 'width=520,height=640')` + 回调页 `postMessage` 通知 + `window.close()`），别做成整页跳转 —— 生成的四栈模板已经是对的，他合并进自己项目时最容易在这里退回成 `<a href>`。生成物 README 的「密钥注入」「多环境部署」两节写了这些，可直接指给开发者。

**接入文档**别现编，也别把 `references/` 整篇刷屏：`scaffold` 已经在生成物的 `docs/` 下按勾选模块切好片，把 `auth.md` + `common.md` + 对应模块那一片交给开发者（或让他的 AI 读）就够。

---

## 命令速查（18 个子命令）

```bash
python3 scripts/oauth2.py endpoints          # 离线列出全部子命令
python3 scripts/oauth2.py <子命令> --help    # 看某个子命令的全部参数
```

| 子命令 | 作用 | 凭据 |
|---|---|---|
| **`dev-app`** | ★第 1 步：定位应用 + 取凭据 + 配回调地址 | 沙箱 AK |
| **`scaffold`** | ★第 3 步：生成对接代码 | 离线（AK 可选，只为预填参数） |
| `client-list` | 名下有哪些应用 | 沙箱 AK |
| `client-get` | 看某个应用的配置与已生效 scope；`--reveal-secret` 才打印密钥 | 沙箱 AK |
| `client-create` | 新建三方应用（★经用户同意再做） | 沙箱 AK |
| `client-setting` | 手工改回调 / 主页地址（`--scopes` 只在用户明确要改范围时用） | 沙箱 AK |
| `scopes` | 平台支持的 13 个 scope | 沙箱 AK |
| **`scope-check`** | ★scope 预检（**免真人登录**）：名称合法性 + 是否在应用登记集；退出码 `0` 全过 / `1` 有不过或无法确认项 | 沙箱 AK（无 AK 离线降级，只判名称） |
| **`redirect-check`** | ★回调地址预检（**免真人登录**）：与生效集合（主地址 ∪ 补充列表）**逐字**比对；退出码 `0` 在集合内 / `1` 不在 | 沙箱 AK |
| `regenerate` | 重置密钥（★★破坏性，需 `--yes`） | 沙箱 AK |
| `auth-url` | 生成授权地址（给真人去点） | 离线 |
| `token` | 授权码换令牌 | client 凭据 |
| `token-m2m` | 机器对机器换令牌（全自动） | client 凭据 |
| `refresh` | 令牌续期 | client 凭据 |
| `revoke` | 撤销令牌 | client 凭据 |
| `userinfo` | 查当前令牌代表的授权用户 | `oa-` 令牌 |
| `call` | 调任意一个开放接口 | `oa-` 令牌 |
| `endpoints` | 列出全部子命令 | — |

★`scope-check` / `redirect-check` 是**接入前预检**：authorize 的 scope 校验发生在真人登录**之后**、回调地址只做逐字比对（差一个斜杠就拒），两者都没法从报错里看出真因 ⇒ 别拿用户当探针，先静态判定。
★`auth-url` … `call` 这 7 个是**联调自检**：替开发者先验证一遍配置对不对，不是给他系统当运行时。运行时用的代码由 `scaffold` 生成。`token-m2m` 额外是**授权码问题的离线鉴证工具**：token 端实时校验 scope，同一串 scope 它接受 ⇒ 名称合法，问题在允许集。

**自检怎么跑**：

```bash
python3 scripts/oauth2.py scope-check --scope profile,im:read   # scope 静态预检（免真人）
python3 scripts/oauth2.py redirect-check --redirect-uri "https://他的域名/oauth2/callback"  # 回调地址静态预检
python3 scripts/oauth2.py token-m2m                    # 机器对机器，全自动
python3 scripts/oauth2.py call --path space/list
python3 scripts/oauth2.py auth-url --scope profile     # 授权码路线：地址交给真人去点
python3 scripts/oauth2.py token --callback-url "https://他的域名/oauth2/callback?code=...&state=..."
python3 scripts/oauth2.py userinfo
python3 scripts/oauth2.py call --path im/contact/list --method GET
```

自检跑通只证明**你**能调通，**他**的系统一行代码都没多 ⇒ 交付物始终是代码 + 文档，把自检结论（不是令牌）写进交付说明。

---

## 环境变量

| 变量 | 说明 |
|---|---|
| `PLATFORM_BASE_URL` | 平台基址，必填 |
| `SANDBOX_ACCESS_KEY` | 沙箱 AK，管理应用的那组子命令要；`scaffold` 只用它读一次配置来预填参数 |
| `DEV_APP_ID` | ★平台自动注入，不要手工编。有它 ⇒ `dev-app` 与 `scaffold` 都不用给应用 id |

★租户永远不用传，服务端自己解析。
★`DEV_APP_ID` 的值可以直接用在所有后续命令上；⛔ 不要拿 `client-get` 返回体里的 `projectId` 去调后面的命令（那是另一个编号体系，会打错行）。
可选兜底：`NUWAX_OAUTH2_CLIENT_ID` / `_CLIENT_SECRET` / `_PROJECT_ID` / `_ACCESS_TOKEN` / `_REFRESH_TOKEN` / `_REDIRECT_URI`。

---

## 出错了怎么办

| 现象 | 怎么办 |
|---|---|
| `dev-app` 退出码 `3` + `NUWAX_CHOICE_REQUIRED` | 用 `ask_question` 问用户接哪个应用，再带 `--app-id` 重跑 |
| 提示查不到域名 | 用 `--domain <域名>` 显式给一个（域名在平台网页的应用详情里能看到），或直接给 `--redirect-uri` |
| `code 3353` | 动了主回调地址（对 AI 一律冻结，库里为空时也不放行）⇒ 改用 `--redirect-uri` 加进补充列表；主地址要动请让用户在网页端改 |
| `code 3352` | 补充回调地址超 20 条 ⇒ 先 `client-setting --remove-redirect-uri <自己加的>` 清几条 |
| 输出的 `skippedRedirectUriRemovals` 非空 | 删到了**人工配的**条目（AI 只能删自己加的）⇒ 这是设计如此，改用网页端删，别重试 |
| 自己加的地址不在「生效地址集合」里 | 看 `主回调地址` / `补充回调地址` 两行核对；地址必须**逐字**一致才会进集合 |
| 授权跳转（或落地页）里出现**公网 IP**、或授权端报地址不一致 | 反代把 `Host` 改写成了载体地址（协议还记成 `http`）⇒ ⛔ **不要**把这个 IP 登记成回调地址（登记了下一个环境又不匹配）；让前端把 `window.location.origin` 随登录请求带上、后端只在已登记白名单里挑（§2.2）。核验：`redirect-check --redirect-uri "<地址栏里那个>"` |
| 提示取不到密钥明文 / 提到 aes-key | 服务端部署缺配置，**重试无用** ⇒ 告诉用户去平台网页控制台取密钥 |
| `code 4030`「API Key 无访问权限」 | 两套凭据混用了：沙箱 AK 只能管应用，`oa-` 令牌才能调接口 |
| `code 4030`「access_token 权限不足」 | 令牌缺 scope ⇒ 看 `client-get` 的已生效 scope，让用户去平台上加 |
| `code 4040` | 这条路径沙箱没放开。★删除应用、改应用名是**刻意不放开**的，让用户去网页控制台做 |
| `code 4010` | 令牌无效 / 过期 ⇒ 有 refresh_token 会自动续期，没有就重跑 `token-m2m` 或重走授权 |
| `invalid_client`（401） | `client_id` 或 `client_secret` 不对，或定位到的应用不是你以为的那个 ⇒ 用 `client-list` 核对 |
| `invalid_grant` + 授权码相关 | 授权码只有 5 分钟且**一次性**（换令牌失败也算用掉）⇒ 重跑 `auth-url` 走一遍 |
| `invalid_grant` + `code_verifier` | PKCE 中间态丢了 ⇒ 重跑 `auth-url` |
| `invalid_scope` + `requires an end-user authorization` | 用机器对机器申请了「仅授权码」scope ⇒ `im:*` / `model:*` 必须走授权码路线 |
| `invalid_scope`（授权码流，真人登录后才报） | 两种成因：① scope 名解析失败（典型：空格被 URL 编码成 `+`，连成一个非法名）；② 名字合法但不在应用**登记集** | 鉴别：先 `token-m2m --scope <同一串>` —— token 端接受 ⇒ ②（查登记集；UserApp 再查 manifest `[env]` 覆盖）；token 端也拒 ⇒ ①（查分隔符与编码） |
| 改了代码里的 scope / 回调，线上没变化 | UserApp 运行时环境变量只认 `project.manifest.toml` 的 `[env]` 固化值，会**静默覆盖**代码默认值 | 改 `[env]` 并**重新发布**；`scaffold` / `dev-app` 运行时会扫描到固化值并提醒 |
| 补了 scope、改了配置、也重新发布了，**老用户还是 4030** | 令牌签发时 scope 已固化，旧令牌里没有新 scope | 比对两个集合：令牌里的 scope vs 配置里申请的那份。**配置有、令牌没有** ⇒ 让他**重新登录一次**（文案：「重新登录一次就好，记录不会丢」）；**配置里也没有** ⇒ 平台还没登记，去申请。★两句别合成一句 |
| 应用嵌在 iframe 里登录**白屏 / 跳不出去** | ① 自己或框架模板加的 `X-Frame-Options` / `frame-ancestors`；② iframe 内的跨站导航带不上平台的登录 Cookie | ① 去掉那两个头（官方跳转链本来就没有，别为「嵌入」去加）；② 授权跳转走**顶层上下文**，且只用「弹小窗 + 认证完自关」（别让 iframe 自己导航、也别整页跳走）。见 `docs/auth.md` §6.2 |
| 登录后**停在女娲授权页 / 新窗口关不掉**，或跳过去就回不来 | 登录入口做成了整页跳转（`<a href>` / `location.href` / 服务端 302） | 改成**弹小窗 + 回调自关**：`window.open(入口, 'nuwax-oauth', 'width=520,height=640')` ⇒ 回调页见 `window.opener` 就 `postMessage` 通知原页面 + `window.close()`；被拦截才 `location.href` 降级。见 `docs/auth.md` §6.2 |
| `/userinfo` 4030，登录流断在「取用户信息」 | 令牌里没有 `profile`（登记集常恰好不含它） | 授权请求 scope 补上 profile（scaffold 预填已默认带）；登记集没有就让用户在平台上一并申请 |
| 想验证 scope 又不想让用户重登 | authorize 的 scope 校验在真人登录**之后**，匿名 `auth-url` 探测永远放行（假阴性） | `scope-check --scope <串>` 静态预检；`token-m2m --scope <串>` 离线鉴证 |
| `client-list` 为空但用户说建过 | 多半是 AK 与环境不匹配 ⇒ 换对环境的 AK |
| 接口返回 HTTP 200 但没效果 | 业务接口 HTTP 恒 200，成败看返回体 `code == "0000"`；工具执行类还要再看 `data.success` |
| `scaffold` 摘要里是 `nwxa_REPLACE_ME` | 不是错误，是没读到平台配置 ⇒ 显式给 `--client-id` / `--redirect-uri` / `--scope` |
| `scaffold` 警告 scope 名不认识 | 拼错的 scope 平台会静默忽略 ⇒ 按警告改成合法名（`scopes` 子命令可列全） |

---

## 必须遵守

- ⛔ **不在技能里实现认证**：不起回调服务、不替他保管令牌、不当他系统的运行时。
- ⛔ **退出码 3 时不许自己挑应用**，必须问用户。
- ⛔ **主线不申请 scope**：缺就告诉用户去平台上加，不要 `client-setting --scopes`（那是待审核申请）。
- ⛔ **要接认证以外的能力，必须先问过用户「是否已向官方申请权限」**（第 2 步第 3 条）：用户明确选「已申请」才能进第 3 步 scaffold；没问就生成代码，用户拿到的是调不通的接口（缺 scope 一律 4030）。
- ⛔ **不把真实 `client_secret` / `access_token` / 沙箱 AK 写进**文档、资料库、对话总结或生成物。示例一律用占位符。
- ⛔ **授权码路线必须真人在浏览器登录一次**，脚本无法全自动；不要试图起本地服务自动收 code。
- ⛔ **回调地址要逐字一致**：登记的、授权时传的、他系统里真实接 code 的，三个必须完全相同（差一个斜杠都会被拒）。
- ⛔ **登录跳转只用「弹小窗 + 认证完自关」一种形态**：`window.open(授权入口, 'nuwax-oauth', 'width=520,height=640')`，回调页见 `window.opener` 就 `postMessage` 通知原页面再 `window.close()` 自己关；被浏览器拦截才退回 `location.href`。**不要**用 `<a href>` / `location.href` / 服务端 `302` 把整个页面导航到授权页 —— 用户会离开应用、认证完也回不去（四栈模板已按此生成，见 `references/api/auth.md` §6.2）。
- ⛔ **改回调地址只用「补充列表」**：新增走 `dev-app`（默认把全部绑定域名一次登记完）或 `client-setting --redirect-uri` / `--add-redirect-uri`（追加、幂等），删自己加的用 `--remove-redirect-uri`。**主地址 `--primary-redirect-uri` 一律不能用**（它只属应用属主、由网页端维护，沙箱提交会被 3353 拒，脚本已就地拦下）。也不要想删人工配的条目：服务端会跳过并在 `skippedRedirectUriRemovals` 里回报，删不掉是设计如此，不是 bug。
- ⛔ **不自动建应用**（`client-create` 是写操作且要起名），名下没有应用时只提示用户。
- ⛔ **`regenerate` 只在用户明确要求时执行**，且先说清「旧密钥立即失效、既有集成会当场断」。

---

## 令牌与缓存

- `access_token` 默认 2 小时（每次校验通过滑动续期），`refresh_token` 默认 30 天。
- ★`refresh` 会**轮换** `refresh_token`：返回的新值才有效。交付时提醒开发者：他系统里的存储必须跟着覆盖，否则第一次续期后就再也刷不动。
- `revoke` 无论令牌是否有效都返回成功，且是**用户级**：该应用下该用户的全部令牌一并失效。
- 机器对机器模式**不签发 refresh_token**，过期重跑 `token-m2m` 即可。
- 授权码流程的中间态与令牌落在 `~/.nuwax-oauth2/`（目录 0700、文件 0600，不进 git）；★`client_secret` 一律不落盘。`rm -rf ~/.nuwax-oauth2` 可随时清空。

---

## 交付清单

| # | 交付物 | 怎么产出 |
|---|---|---|
| 1 | 应用配置已写回平台 | `dev-app --path <他系统里接 code 的路由>` |
| 2 | `client_secret` | `dev-app --write-env <路径>`，单独交给他保管 |
| 3 | 对接代码 | `scaffold --stack all --out <目录> --scope <勾选模块的 scope>` |
| 4 | 接入文档 | `scaffold` 生成物的 `docs/` 下已按勾选模块切好片 ⇒ 给他 `auth.md` + `common.md` + 对应模块片 |
| 5 | 自检结论（可选） | 「M2M 能换到令牌、某端点因 scope 未批返回 4030」这类事实，不是令牌本身 |

---

## References

- **[references/integration_guide.md](references/integration_guide.md)** —— ★写给外部开发者的接入文档，用户要「接入文档 / 怎么接」时以它为事实源，别现编：两种接入模式怎么选、授权码 + PKCE 完整流程、回调地址登记、scope 与模块对照、令牌有效期与续期、排错表。**交付时不要整篇甩给他** —— `scaffold` 已按模块把要接的那片切进 `docs/`。
- **[references/api_reference.md](references/api_reference.md)** —— 总览：三种响应格式、13 个 scope 全集、两套错误码全集 + 模块索引表。★它是 `scaffold` 生成的 scope 表的同源事实，改了这里要顺手改 `scripts/scaffold.py` 的对应常量。
- **references/api/ 分模块文件（7 个）** —— 端点级契约：每端点参数表 + JSON 请求/响应示例 + 坑，文末数据结构字段表。`auth` / `user` / `chat` / `im` / `model` / `tool` 给开发者，`admin`（三方应用管理端点）是技能自用、不进开发者切片。★**要查哪个模块读哪个文件，别整目录加载**。
- **随代码交付的 README 与 `docs/`** —— `scaffold` 给每种栈都生成一份 README（平台契约 + 端点表 + scope 表 + 排错表 + 该栈快速上手），另在 `docs/` 下按模块切好接口文档片（api/ 模块文件整文件复制，guide 流程章按小节号抽取）。**开发者不需要访问本技能仓库。**
- ★切片器的契约 = guide 与 api_reference（总览）的**小节编号** + api/ 的**文件名**：改了任一个要同步 `scripts/scaffold.py` 里的 `DOC_AUTH` / `DOC_COMMON` / `DOC_MODULES`。取不到不会报错，但生成物里会留一行「这几节没抽到」，摘要也会记 note —— 看到这行就是契约漂了。

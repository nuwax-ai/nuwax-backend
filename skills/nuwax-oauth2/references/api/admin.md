# 三方应用管理端点（自助取 client_id / client_secret）

这一组**不是 OAuth2 协议端点**，而是平台管理面：创建三方应用、登记回调地址、申请 scope、取出 `client_id` / `client_secret`。凭据是**沙箱 AK**（`Authorization: Bearer <SANDBOX_ACCESS_KEY>`），不是 `oa-` 令牌。

★沙箱 AK **绝不能**打 `/api/oauth2/**`（会得到 `4030`）；反之 `oa-` 令牌也打不进本组端点。两套凭据各走各的。

## 两种访问方式

| 方式 | 路径前缀 | 凭据 | 适用 |
|---|---|---|---|
| 网页 / 登录态 | `/api/user-project/oauth2/**` | `ticket` Cookie 或 JWT | 人在平台控制台上操作 |
| ★沙箱 AK（本技能用） | `/api/v1/4sandbox/user-project/oauth2/**` | `Authorization: Bearer <SANDBOX_ACCESS_KEY>` | 脚本自动化 |

★两者是**同一套接口**：带沙箱前缀的那条由平台重写后交给同一段服务端代码处理，行为完全一致。下文路径都写沙箱形态。

权限与网页端**完全一致**：能操作哪些应用、哪些空间，取决于沙箱 AK 归属用户的权限，脚本不会放大权限；越权时单个应用返回「项目不存在或无权访问」，列表返回 `4033`。

★**删除应用、改应用名没有开放给脚本**：打过去得到 `4040`，这不是故障 ⇒ 让用户去平台网页控制台做。

★`33xx` 业务错误码（`3341` 客户端不存在 / `3342` 回调地址不一致 / `3344` 授权码无效 / `3345` 密钥不正确 / `3346` 仅限三方应用 / `3347` 生效回调地址集合为空 / `3348` 地址格式非法 / `3349` scope 非法 / `3351` 申请不存在 / `3352` 补充回调地址超限 / `3353` 主回调地址不可由沙箱写入）**只在本组端点以原码出现**；打到协议端点时会被转成 RFC 四错误码。

响应为**平台信封**（`code=="0000"` 成功，HTTP 恒 200）。

---

## 端点清单

★一个三方应用就是平台上的一个「应用」条目：创建之后它会出现在应用列表里，用户也能在网页控制台看到并管理它。

| # | 端点 | 方法与路径 | 沙箱可达 |
|---|---|---|---|
| 1 | 创建三方应用 | `POST .../oauth2/create` | ✅ |
| 2 | 查询认证信息 | `GET .../oauth2/setting/{projectId}` | ✅ |
| 3 | 查询应用信息 | `GET .../oauth2/info/{projectId}` | ✅ |
| 4 | 查看 Secret 明文 | `GET .../oauth2/secret/{projectId}` | ✅ |
| 5 | 保存设置 + 申请 scope | `POST .../oauth2/setting/save` | ✅ |
| 6 | 平台 scope 全集 | `GET .../oauth2/scopes` | ✅ |
| 7 | 重新生成凭证 | `POST .../oauth2/credential/regenerate/{projectId}` | ✅ |
| 8 | 列出应用 | `POST .../user-project/page-query` | ✅ |
| 9 | 更新基本信息 | `POST .../oauth2/update` | ❌ 未放开（4040） |
| 10 | 删除应用 | `POST .../oauth2/delete/{projectId}` | ❌ 未放开（删除会随行吊销凭证，已签发授权码与令牌立即失效） |

---

## 1. 创建三方应用 —— `POST /api/v1/4sandbox/user-project/oauth2/create`

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `spaceId` | 数字 | 否 | 不传则取当前用户的**个人空间**（没有个人空间取第一个空间；一个空间都没有 → `Invalid user data`）。显式传入需校验成员权限 |
| `name` | 字符串 | 否 | 留空按当前语言取默认名 |
| `description` | 字符串 | 否 | 应用描述 |
| `icon` | 字符串 | 否 | 应用图标 |

★回调地址与主页地址**不在创建时填写**：建行后经端点 5 维护。

**请求示例**

```json
{ "name": "订单中心集成", "description": "订单系统接入女娲登录" }
```

**响应示例**（`data` = [认证设置对象](#数据结构认证设置对象)，★含新生成的凭证信息）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": {
    "projectId": 812,
    "clientId": "nwxa_0123456789abcdef0123456789abcdef",
    "hasClientSecret": false,
    "homepageUrl": null,
    "redirectUri": null,
    "redirectUris": [],
    "skippedRedirectUriRemovals": null,
    "scopes": ["profile"],
    "scopeApplyStatus": null,
    "pendingScopes": null,
    "scopeRejectReason": null,
    "enabled": true
  },
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

---

## 2. 查询认证信息 —— `GET /api/v1/4sandbox/user-project/oauth2/setting/{projectId}`

**请求参数**

| 位置 | 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| path | `projectId` | 数字 | **是** | 应用 id |
| query | `projectType` | 字符串 | 否 | 全栈应用（`UserApp`）必须带，见端点 8 的坑 |

**响应**：`data` = [认证设置对象](#数据结构认证设置对象)。★**首次访问自动生成凭证**（还没有 client_id/secret 时这一调用就会建出来）。

---

## 3. 查询应用信息 —— `GET /api/v1/4sandbox/user-project/oauth2/info/{projectId}`

无请求参数（path 里给 `projectId`）。

**响应**：`data` = [应用信息对象](#数据结构应用信息对象)。★**只读，不会自动生成凭证**——想触发凭证生成用端点 2。

---

## 4. 查看 Secret 明文 —— `GET /api/v1/4sandbox/user-project/oauth2/secret/{projectId}`

query 可选 `projectType`（同端点 2）。

**响应**：`data` 是**裸字符串**（secret 明文本身，不是对象）：

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": "nwxa_s_REPLACE_ME",
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- ★每次调用**审计留痕**（日志不含密钥本身）。
- ★secret 是加密存储的，只有本端点能取明文。若报服务端异常并提到 `aes-key`，说明该环境缺部署配置——**重试无用**，联系平台运维；此时回调地址等其余配置照常可用。

---

## 5. 保存设置 + 申请 scope —— `POST /api/v1/4sandbox/user-project/oauth2/setting/save`

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `projectId` | 数字 | **是** | 应用 id |
| `projectType` | 字符串 | 否 | `NormalProject` / `UserApp` / `ThirdApp`。★传 `UserApp` 时**返回体里的 `projectId` 会被换成另一个编号**，⛔ 后续调用仍用你传进来的原值 |
| `homepageUrl` | 字符串 | 否 | ★**留空表示不修改**（空串也折叠成不修改 ⇒ 本接口无法清空该字段） |
| `redirectUri` | 字符串 | 否 | **主回调地址**。留空 = 不修改；须为绝对 http(s) URL、有 host、无 fragment。★**沙箱调用提交非空值一律报 `3353`**（库中为空也不行）——主地址只由网页端人工维护 |
| `addRedirectUris` | 字符串数组 | 否 | 往**补充回调地址列表**追加：逐条校验同一格式；与主地址或已有条目重复的自动丢弃（幂等）；超 20 条 → `3352`。`null` = 本次不追加；★**空数组不等于清空**（清空用 `removeRedirectUris`） |
| `removeRedirectUris` | 字符串数组 | 否 | 从补充列表删除（删不存在的地址是无操作，不算失败）。★**沙箱只能删自己加的条目**（`source=Sandbox`），删不动的跳过并在响应 `skippedRedirectUriRemovals` 里回报，**不抛错** |
| `scopes` | 字符串数组 | 否 | ★`null` = 不修改；**空数组 = 申请清空并回落平台默认（`profile`）**；提交后**待管理员审核**，通过前应用按原范围生效 |

**请求示例**（给应用追加一条沙箱回调地址）

```json
{
  "projectId": 812,
  "addRedirectUris": ["https://sandbox-812.agent.example.com/callback"]
}
```

**响应**：`data` = [认证设置对象](#数据结构认证设置对象)，其中 `skippedRedirectUriRemovals` 仅本端点会填。

**回调地址的归属模型（必读）**

- ★**生效回调地址集合 = 主地址 `redirectUri` ∪ 补充列表每条 `redirectUris[].uri`**；`/authorize` 与换码都拿集合做**精确字符串比较，不是前缀匹配**（差一个斜杠都会被拒）。集合**整体为空**才拒授权 → `3347`；集合非空但传入地址不在其中 → `3342`。
- ★每条补充地址带来源归属（`source`：`Manual` = 网页人工配置 / `Sandbox` = 沙箱里的 AI；`createdBy` / `createdAt` 供审计）。**`source` 是服务端执行删除权限的唯一判据**——AI 只能增删自己加的条目，覆盖不了人工主地址，也删不掉人工条目。

---

## 6. 平台 scope 全集 —— `GET /api/v1/4sandbox/user-project/oauth2/scopes`

无请求参数。

**响应示例**（`data` = [scope 条目](#数据结构scope-条目)数组）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": [
    { "scope": "profile", "description": "读取授权用户自身信息（昵称、头像等）", "sensitive": false },
    { "scope": "chat:read", "description": "读取授权用户的任务会话与消息记录", "sensitive": false },
    { "scope": "chat:write", "description": "以授权用户身份创建会话、发起与停止对话", "sensitive": true }
  ],
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- ★返回**只有 `scope` / `description` / `sensitive` 三个字段，不含「仅授权码」标记**——「机器对机器能不能拿到」只能查总览文档的 scope 全集表，不能从本接口读。

---

## 7. 重新生成凭证 —— `POST /api/v1/4sandbox/user-project/oauth2/credential/regenerate/{projectId}`

query 可选 `projectType`。

**响应**：`data` = [凭证对象](#数据结构凭证对象)：

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": {
    "clientId": "nwxa_0123456789abcdef0123456789abcdef",
    "clientSecret": "nwxa_s_REPLACE_ME"
  },
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- ★**旧凭证立即失效**（旧 secret 签发的令牌链路全断），明文密钥**仅本次返回**，务必当场存好。

---

## 8. 列出应用 —— `POST /api/v1/4sandbox/user-project/page-query`

**请求体**：分页字段 + 业务过滤条件。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `current` | 数字 | 否 | 页码，默认 1 |
| `pageSize` | 数字 | 否 | 每页条数，默认 10 |
| `orders` / `filters` / `columns` | 数组 | 否 | 排序 / 过滤 / 列（罕用） |
| `queryFilter` | 对象 | 否 | 业务条件：`spaceId`(数字)、`creatorId`(数字)、`projectTypes`(字符串数组，不传默认 `[NormalProject, UserApp, ThirdApp]`)、`name`(模糊)、`collectedFilter`(`all`/`only`)、`archivedFilter`(`all`/`exclude` 默认/`only`) |

★**字段名拼错不会报错**，会静默按默认分页返回——结果不对时先检查字段名。

**请求示例**（找名字含「订单」的三方应用）

```json
{
  "current": 1,
  "pageSize": 10,
  "queryFilter": { "name": "订单", "projectTypes": ["ThirdApp"] }
}
```

**响应示例**（`data` = [分页外壳](#数据结构分页外壳)，`records` 元素 = [应用列表条目](#数据结构应用列表条目)）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": {
    "records": [
      {
        "projectId": 812,
        "projectType": "ThirdApp",
        "name": "订单中心集成",
        "description": "订单系统接入女娲登录",
        "icon": null,
        "spaceId": 57,
        "creatorId": 21,
        "oauthClientId": "nwxa_0123456789abcdef0123456789abcdef",
        "homepageUrl": null,
        "redirectUri": null,
        "oauthEnabled": true,
        "publishStatus": "Developing",
        "pinned": false,
        "archived": false,
        "nameDefined": true,
        "collected": false,
        "owner": true,
        "created": "2026-10-01 10:00:00",
        "modified": "2026-10-01 10:00:00",
        "conversationId": null,
        "conversations": []
      }
    ],
    "total": 1,
    "size": 10,
    "current": 1,
    "columns": [],
    "pages": 1
  },
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- ★**可见性语义**：传 `spaceId` 则校验成员权限并保留 `creatorId`；**不传 `spaceId` 则 `creatorId` 被置 null、改按「用户所属全部空间」过滤** ⇒ 团队协作下**同一空间成员互相可见对方项目**，返回的不是严格的「我创建的」。
- ★`projectId` 用法分叉：三方应用（`ThirdApp`）可把它**原样**喂给端点 2 / 4；全栈应用（`UserApp`）必须**同时带 `projectType=UserApp`**，否则会定位到错的应用。
- `oauthClientId` 在列表里就有，但**密钥不在**——要用端点 4 单独取。

---

## scope 审核流程

★提交 scope 变更**不是立即生效**：会落一条待审核申请（认证设置对象的 `scopeApplyStatus` 变 `Pending`，`pendingScopes` 是申请的目标集），要管理员批准；**审核期间应用仍按原来「已生效」的 scope（`scopes` 字段）运行**，被拒会带 `scopeRejectReason`。没有待审核申请时 `scopeApplyStatus` 是 `null`（不是 `Approved`）。

---

## 凭据形态

令牌是**不透明随机串**，不是 JWT ⇒ ⛔ 不要自己解码取字段，要用户信息就调协议端点 `userinfo`：

| 凭据 | 前缀 | 随机部分 | 总长 |
|---|---|---|---|
| `clientId` | `nwxa_` | 32 位小写 hex（16 字节熵） | 37 |
| `clientSecret` | `nwxa_s_` | 48 位小写 hex（24 字节熵） | 55 |
| `access_token` | `oa-` | 43 位 url-safe base64（无 padding） | 46 |
| `refresh_token` | `or-` | 43 位 url-safe base64 | 46 |
| 授权码 | **无前缀** | 32 位 url-safe base64（24 字节熵） | 32 |

---

## 数据结构

### 认证设置对象

`data`（原 `ThirdAppOAuthSettingVo`），对应端点 1 / 2 / 5：

| 字段 | 类型 | 说明 |
|---|---|---|
| `projectId` | 数字 | 应用 id（`user_project` 主键） |
| `clientId` | 字符串 | OAuth2 Client ID |
| `hasClientSecret` | 布尔 | 是否已生成 Secret（★明文需另调端点 4） |
| `homepageUrl` | 字符串 | 应用主页地址 |
| `redirectUri` | 字符串 | **主**回调地址（沙箱冻结，见端点 5） |
| `redirectUris` | 数组 | **补充**回调地址列表，条目 `{uri, source, createdBy, createdAt}`（`source`：`Manual`/`Sandbox`）。★未配置返回**空数组而非 null** |
| `skippedRedirectUriRemovals` | 字符串数组 | 本次保存里被跳过的删除项（沙箱删人工条目）。★**只有端点 5 会填**，其余读接口恒 null——据此区分「没跳过」与「这不是保存的响应」 |
| `scopes` | 字符串数组 | **当前已生效**的 scope |
| `scopeApplyStatus` | 字符串 | 最近一条申请的状态：`Pending` / `Approved` / `Rejected`；★从未申请为 `null` |
| `pendingScopes` | 字符串数组 | 仅 `Pending` 时返回；★空数组表示「申请清空回落默认」 |
| `scopeRejectReason` | 字符串 | 仅 `Rejected` 时返回 |
| `enabled` | 布尔 | OAuth2 接入是否启用 |

### 应用信息对象

`data`（原 `ThirdAppInfoVo`），对应端点 3。在[认证设置对象](#数据结构认证设置对象)的基础上：

- **多**：`projectType`（`ThirdApp` = 三方应用 / `UserApp` = 全栈应用，后者也走 OAuth2 接入）、`name`、`description`、`icon`、`spaceId`、`creatorId`、`publishStatus`、`created`、`modified`；
- **不含**：scope 审核相关三字段（`scopeApplyStatus` / `pendingScopes` / `scopeRejectReason`），也不含 `skippedRedirectUriRemovals`（那是保存动作的回执）；
- `redirectUri` / `redirectUris` 口径与认证设置对象完全一致；密钥明文一律不下发，只给 `hasClientSecret` 布尔位。

### scope 条目

`data` 数组元素（原 `ThirdAppOAuthScopeVo`），对应端点 6：

| 字段 | 类型 | 说明 |
|---|---|---|
| `scope` | 字符串 | scope 值（授权请求 `scope` 参数用） |
| `description` | 字符串 | 能力说明 |
| `sensitive` | 布尔 | 是否敏感能力（敏感的通常需要更严格的申请理由） |

### 凭证对象

`data`（原 `ThirdAppOAuthCredentialVo`），对应端点 7：

| 字段 | 类型 | 说明 |
|---|---|---|
| `clientId` | 字符串 | Client ID |
| `clientSecret` | 字符串 | ★明文，仅此一次返回 |

### 分页外壳

`data`（原 `SuperPage`，继承平台通用分页），对应端点 8：

| 字段 | 类型 | 说明 |
|---|---|---|
| `records` | 数组 | 数据列表（元素 = 应用列表条目） |
| `total` | 数字 | 总条数 |
| `size` | 数字 | 每页条数 |
| `current` | 数字 | 当前页码 |
| `pages` | 数字 | 总页数（派生：total/size 向上取整） |
| `columns` | 数组 | 表格列定义（默认空数组） |
| `orders` / `optimizeCountSql` / `searchCount` / `countId` / `maxLimit` 等 | — | 分页元数据（继承来的，集成时可忽略） |

### 应用列表条目

`records` 元素（原 `UserProjectTabVo`，含其父类字段）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `projectId` | 数字 | ★应用 id（用法分叉见端点 8 的坑） |
| `projectType` | 字符串 | `ThirdApp` / `UserApp` / `NormalProject` 等 |
| `name` / `description` / `icon` | 字符串 | 基本信息 |
| `tenantId` / `spaceId` / `creatorId` | 数字 | 归属 |
| `oauthClientId` | 字符串 | Client ID（★密钥不在返回体里） |
| `homepageUrl` / `redirectUri` | 字符串 | 应用主页 / 主回调地址 |
| `redirectUris` | 数组 | 补充回调地址（条目同认证设置对象） |
| `oauthEnabled` | 布尔 | OAuth2 接入是否启用 |
| `publishStatus` | 字符串 | `Developing` / `Applying` / `Published` / `Rejected` |
| `pinned` / `archived` / `nameDefined` / `collected` / `owner` | 布尔 | 状态位（`owner` = 当前用户是否创建者） |
| `sandboxId` / `sandboxType` | 数字 / 字符串 | 沙箱信息（`Cloud` / `Personal`，罕用） |
| `devAgentId` | 数字 | 开发关联智能体（仅全栈应用，罕用） |
| `repoSlugId` / `planSlugId` | 字符串 | 资料库目录 / 项目计划表 ID（罕用） |
| `created` / `modified` | 字符串 | 时间 |
| `conversationId` | 数字 | 开发智能体会话 ID（罕用） |
| `conversations` | 数组 | 当前用户的开发会话列表（按 id 倒序，罕用） |

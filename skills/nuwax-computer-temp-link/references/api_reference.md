# 个人电脑临时链接 API Reference

端点级契约。操作纪律与用法见 [../SKILL.md](../SKILL.md)。

- 前缀：`/api/v1/4sandbox/proxy`（挂在 `PLATFORM_BASE_URL` 下）
- 方法：**三个端点全是 POST**，`Content-Type: application/json`
- 鉴权：`Authorization: Bearer <SANDBOX_ACCESS_KEY>`

响应信封（三个端点一致）：

```json
{ "code": "0000", "displayCode": "0000", "message": "成功", "data": ..., "tid": "trace-id-123", "success": true }
```

★**判据是响应体的 `code == "0000"`**：业务失败**也可能走 HTTP 200**，别只看 HTTP 状态码。

---

## 1. POST /create —— 创建临时代理配置

**功能说明**：把 `backendHost:backendPort` 注册成一条可对外访问的临时代理链接。

请求体：

```json
{ "sandboxId": "1234", "backendHost": "localhost", "backendPort": 8080 }
```

| 字段名 | 类型 | 必填 | 说明 |
|--------|------|:----:|------|
| `sandboxId` | string | ✅ | 目标环境的沙箱配置 ID —— 就是环境变量 `SANDBOX_ID` |
| `backendHost` | string | ✅ | 被代理服务监听的地址，**由目标机器上的客户端去连接**（个人电脑场景即用户本机） |
| `backendPort` | int32 | ✅ | 被代理服务监听的端口号 |

★`userId` **不在请求体里**：服务端从登录态取，请求体里根本没有这个字段。

成功响应 `data`（形态由部署配置决定，两种之一）：

```json
{ "tempLink": "e5974aa7e8ad4e47b26c37a3ff773863.sandbox-inner.nuwao.com", "id": 1 }
```

```json
{ "tempLink": "https://testagent.xspaceagi.com/api/proxy/sandbox/e5974aa7e8ad4e47b26c37a3ff773863", "id": 1 }
```

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `data.tempLink` | string | 外部可访问的临时代理链接地址；**域名与路径原样使用**（子域名形态不带协议头，交付时补实测可用的那个） |
| `data.id` | integer(int64) | 代理链接 ID，删除时用 |

★★**`create` 校验 `sandboxId`**：必须存在、启用、且属于当前用户；否则直接失败 ——
`code="4000"`、message「配置不存在」（「不存在」与「属于他人」返回同一错误，不泄露他人配置）。
所以 `sandboxId` 只从 `$SANDBOX_ID` 取，不要手填。

---

## 2. POST /list/{sandboxId} —— 查询临时代理配置列表

**功能说明**：查询该环境下已创建的临时代理配置。

★**`sandboxId` 在路径上**，不是请求体：

```
POST /api/v1/4sandbox/proxy/list/1234
```

请求体：无。成功响应 `data`：

```json
[
  { "id": 1, "tempLink": "https://testagent.xspaceagi.com/api/proxy/sandbox/e5974aa7e8ad4e47b26c37a3ff773863" }
]
```

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `data[]` | array | 代理配置列表 |
| `data[].id` | integer(int64) | 代理 ID |
| `data[].tempLink` | string | 临时代理链接完整地址 |

★列表范围 = 「该 `sandboxId` 下的链接」（叠加调用方身份）：查不到 ≠ 没建过，
先确认 `SANDBOX_ID` 是不是当前环境。

---

## 3. POST /delete/{id} —— 删除临时代理配置

```
POST /api/v1/4sandbox/proxy/delete/1
```

| 路径参数 | 类型 | 必填 | 说明 |
|--------|------|:----:|------|
| `id` | integer(int64) | ✅ | 代理 ID（通过查询接口获取） |

请求体：无。成功响应 `data` 为 `null`，看 `code`。

★语义纪律：

- **按「ID + 当前用户」匹配**，**不校验该 ID 属于哪台电脑** —— 别拿别的环境的 ID 来删。
- **不可逆**：没有「恢复」，要重来只能 `create` 一条新的（新 ID、新链接）。
- **服务端约 10 秒缓存**：删除后链接可能短暂仍能解析，之后彻底失效。这不是删除失败。

---

## 访问链接时的两种错误

链接本身不走上面三个管理端点，而是走平台的反向代理入口；两种错误含义完全不同：

| 返回 | 含义 | 处置 |
|---|---|---|
| `404 Invalid domain` | 这个 proxyKey 在服务端查不到：链接被改错 / 已被删除 / **路径形态下网关没把 `<proxyKey>` 回写成 Host** | 前两者核对链接后重新 `create`；若是第三种，重建无效，属部署配置问题 |
| `502 Claw is offline` | proxyKey 有效，但**目标机器的客户端当前没连到平台** | 让用户电脑上的客户端连上；**不用重建链接** |

★★**proxyKey 是从请求的 Host 第一段取的**（取第一个 `.` 之前的部分，无 `.` 则整个 Host）：
所以 `https://<proxyKey>.<base-domain>/...` 天然能查到，而 `https://<站点域名>/api/proxy/sandbox/<proxyKey>`
的 Host 里根本没有 proxyKey —— 它成立的前提是**网关把路径段回写成 Host**。

### 链接形态与协议头（实测，会随部署变）

子域名形态（`base-domain` 分支）返回的是**裸域名、不带协议头**。实测 2026-09-27，
站点 `testagent.xspaceagi.com`，`base-domain=sandbox-inner.nuwao.com`：

| 访问方式 | 结果 |
|---|---|
| DNS | 泛解析正常（解析到内网前置 IP） |
| `https://` | ✗ `curl: (60) no alternative certificate subject name matches` —— 证书 `CN=*.nuwao.com` 只覆盖一级，链接域名是两级 |
| `http://` | ✓ 通（探针路径与本机直连返回同形，`path` 原样回声） |

⇒ 交付时补 `http://`。**换环境（正式环境 / 换了 `base-domain`）要重新试**，别沿用这里的结论。

---

## 错误码

（下表是接口定义中的错误码，用于解读响应里的 `code` / `displayCode`；判定仍以 `code == "0000"` 为准。）

| 错误码 | HTTP状态码 | 说明 |
|--------|-----------|------|
| 40001 | 400 | 请求参数错误 |
| 40101 | 401 | 认证失败，ACCESS_KEY 无效或已过期 |
| 40301 | 403 | 沙盒ID 无效或无权限访问 |
| 40401 | 404 | 沙盒不存在 |
| 50001 | 500 | 服务器内部错误 |
| 50002 | 500 | 代理服务创建失败 |

错误响应示例：

```json
{ "code": "4001", "displayCode": "400", "message": "请求参数错误", "data": null, "tid": "trace-id-456", "success": false }
```

---

## 注意事项

1. **端口范围**：`backendPort` 取 1024–65535。
2. **主机地址**：本机服务用 `localhost` / `127.0.0.1`；该地址由**目标机器**解析，不是平台侧。
3. **链接有时效**：属临时链接，别当长期入口用。
4. **并发限制**：同一环境同时存在的代理链接数量可能有限制，不用了就删。
5. **安全提醒**：链接是公网可达的，请勿分享给不信任的第三方。

## 使用场景

- **本地开发调试**：把电脑上跑的服务分享给别人测试
- **Webhook 测试**：接收外部系统的 Webhook 回调
- **临时演示**：快速展示本机运行的应用
- **API 测试**：让外部服务能够调用本机 API

---

## 明确不存在的能力

- **无「修改」**：链接不能改指向，只能 `delete` + `create`。
- **无单条查询**：只有 `list/{sandboxId}`，没有按 ID 查一条。
- **无「个人电脑专用」端点**：不存在 `/api/v1/4computer/proxy/**` 之类的另一套接口 ——
  个人电脑与云沙箱共用本文件的三个端点，区别只在于「被代理的服务跑在哪台机器上」。
- **无租户参数**：不需要传租户，服务端按 AK 自行解析。

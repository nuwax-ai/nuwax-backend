# 模型与 API Key（5 个端点）

前缀 `{PLATFORM_BASE_URL}/api/oauth2/model`，请求头 `Authorization: Bearer <access_token>`（`oa-` 令牌）。

**本模块的 scope**：`model:read`（端点 1）、`model:key`（端点 2–5）。★两个都是**「仅授权码」scope——机器对机器（client_credentials）拿不到**：必须有真人在浏览器里登录授权一次。

★响应一律**平台信封** `{code, displayCode, message, data, tid}`：成败看 `code == "0000"`，业务结果在 `data` 里，HTTP 恒 200。

这一组是「**用女娲的模型额度跑你自己的应用**」：创建一把 API Key，然后按 OpenAI 兼容协议直连模型代理（`POST {baseUrl}/{modelId}/v1/chat/completions` + `Authorization: Bearer <accessKey>`）。

---

## 1. 可用模型列表 —— `GET /api/oauth2/model/list`

scope：`model:read`。无请求参数。

**响应示例**（`data` = [模型对象](#数据结构模型对象)数组）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": [
    {
      "id": 101,
      "name": "通用对话模型",
      "model": "qwen-max",
      "providerName": "DashScope",
      "type": "Chat",
      "apiProtocol": "OpenAI",
      "description": "适合通用问答与代码生成",
      "tag": null,
      "maxTokens": 8192,
      "maxContextTokens": 32768,
      "functionCall": "SUPPORT",
      "isReasonModel": 0
    }
  ],
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- ★本列表是**三重过滤**结果：用户有权 + 使用场景含 OpenApi + 已启用 ⇒ **可能少于平台界面所见的模型数**。
- 拿这里的 `id` 去创建 API Key、拿 `model` 值当上游协议里的模型名。

---

## 2. 创建 API Key —— `POST /api/oauth2/model/api-key/create`

scope：`model:key`。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `modelIds` | 数字数组 | **是** | 授权这把 Key 可用的模型（取自端点 1 的 `id`）。空数组 → `4000` `modelIds is required`；逐个校验，越界 → `model not available for API access: [...]` |

**请求示例**

```json
{ "modelIds": [101, 102] }
```

**响应示例**（`data` = [新建 Key 对象](#数据结构新建-key-对象)）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": {
    "id": 7,
    "accessKey": "sk-proxy-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "baseUrl": "https://platform.example.com/api/proxy/model",
    "modelIds": [101, 102],
    "usage": "POST {baseUrl}/{modelId}/v1/chat/completions + Authorization: Bearer <accessKey>"
  },
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- ★**`accessKey` 明文只在本响应里出现一次**，列表接口只给掩码（前 3 位 + `****` + 末 4 位）。丢了只能吊销重建，**务必当场存好**。
- ★`model not available for API access` 把「模型不存在 / 你无权 / 未开放 API 访问」**三种情况合并成同一句**，别据此推断某个模型 id 是否有效。
- ★`baseUrl` 给的是**你调模型代理用的地址**，不是在女娲上调用平台的地址：取部署配置 `model-api-proxy.base-api-url`，为空则回落租户 `siteUrl`，末尾补 `/api/proxy/model`。第三方接入时把它当作 `{baseUrl}/{modelId}/v1/chat/completions` 的前缀原样使用即可，不要自己拼平台域名。
- 单用户上限 **20 把**，超限 → `0001` `Too many API keys (limit 20), revoke unused ones first`。

---

## 3. Key 列表 —— `GET /api/oauth2/model/api-key/list`

scope：`model:key`。无请求参数。

**响应示例**（`data` = [Key 条目对象](#数据结构key-条目对象)数组）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": [
    {
      "id": 7,
      "maskedKey": "sk-****xxxx",
      "modelIds": [101, 102],
      "enabled": true,
      "created": "2026-10-01 10:00:00",
      "expire": "2027-10-01 10:00:00"
    }
  ],
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- ★**只列通过本门面签发的 Key**——你在平台界面上建的其他类型 Key 不会出现在这里。
- ★签发的是**模型专用 Key，打不进平台任何普通 HTTP 接口**（Spring MVC 全部拒绝），只有模型代理（Netty，端口默认 **18086**，对外走 `baseUrl`）接受它。

---

## 4. 修改 Key —— `POST /api/oauth2/model/api-key/update`

scope：`model:key`。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | 数字 | **是** | 缺失 → `0001` `id is required` |
| `modelIds` | 数字数组 | **是** | ★**整体替换，不是增量**——只想加一个模型也要把原有的全部带上 |
| `enabled` | 布尔 | 否 | ★模型代理**不校验**该字段，它只是个展示位，**不是安全开关** |

**请求示例**

```json
{ "id": 7, "modelIds": [101, 102, 103] }
```

**响应示例**：`data` = 修改后的 [Key 条目对象](#数据结构key-条目对象)（结构同端点 3 的数组元素）。

---

## 5. 吊销 Key —— `POST /api/oauth2/model/api-key/revoke`

scope：`model:key`。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | 数字 | **是** | 要吊销的 Key |

**请求示例**

```json
{ "id": 7 }
```

**响应示例**（`data` 为 null）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": null,
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- ★**吊销是唯一有效的关闭手段**：本端点是**物理删除、不可恢复**；`enabled=false` 和 `expire` 过期都**不被模型代理校验**，改它们不会真的挡住调用。想停用一把 Key 就必须 revoke。
- ★代理侧有约 60 秒缓存，吊销后极短时间内旧 Key 可能仍被放行；别把「吊销」当紧急止血的唯一动作还指望零延迟。

---

## 数据结构

### 模型对象

`data` 数组元素（原 `ModelItemVo`），对应端点 1：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | 数字 | 模型 ID（创建 Key 时用它） |
| `name` | 字符串 | 展示名 |
| `model` | 字符串 | 上游协议的 model 参数值 |
| `providerName` | 字符串 | 供应商 |
| `type` | 字符串 | 枚举名：`Completions` / `Chat` / `Embeddings` … |
| `apiProtocol` | 字符串 | `OpenAI` / `Ollama` … |
| `description` / `tag` | 字符串 | 描述 / 标签 |
| `maxTokens` / `maxContextTokens` | 数字 | 单次输出上限 / 上下文窗口 |
| `functionCall` | 字符串 | 函数调用能力 |
| `isReasonModel` | 数字 | 1 = 推理模型 |

### 新建 Key 对象

`data`（原 `ApiKeyCreatedVo`），对应端点 2：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | 数字 | Key ID |
| `accessKey` | 字符串 | ★**明文仅此一次**，当场存好 |
| `baseUrl` | 字符串 | 模型代理基址 = 站址 + `/api/proxy/model`。站址取值优先级：部署配置 `model-api-proxy.base-api-url`（环境变量 `MODEL_API_BASE_URL`）→ 租户配置 `siteUrl`；★两者都为空时本字段为 `null`（Key 仍可用，调用地址需自行知道） |
| `modelIds` | 数字数组 | 这把 Key 授权的模型 |
| `usage` | 字符串 | 调用范式：`POST {baseUrl}/{modelId}/v1/chat/completions` + `Authorization: Bearer <accessKey>` |

### Key 条目对象

`data` 数组元素（原 `ApiKeyItemVo`），对应端点 3 / 4：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | 数字 | Key ID |
| `maskedKey` | 字符串 | 掩码：前 3 位 + `****` + 末 4 位 |
| `modelIds` | 数字数组 | 授权的模型 |
| `enabled` | 布尔 | 展示位（★代理不校验） |
| `created` / `expire` | 字符串 | 创建 / 过期时间（★`expire` 代理同样不校验） |

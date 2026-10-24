# 用户与文件（3 个端点）

前缀 `{PLATFORM_BASE_URL}/api/oauth2`，请求头 `Authorization: Bearer <access_token>`（`oa-` 令牌）。

**本模块的 scope**：`user:space`（空间列表）、`file:write`（上传）、`file:read`（下载地址）。
（`profile` 的 `/userinfo` 与 `user:search` 的 `/user/search` 是裸对象端点，契约在《认证与令牌》文件里。）

★响应一律**平台信封** `{code, displayCode, message, data, tid}`：成败看 `code == "0000"`，业务结果在 `data` 里，HTTP 恒 200。下文「响应示例」都给完整信封。

---

## 1. 空间列表 —— `GET /api/oauth2/space/list`

scope：`user:space`。无请求参数。

返回授权用户可见的空间数组（**仅个人空间与团队空间**，租户二次收口）。

**响应示例**

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": [
    {
      "id": 57,
      "tenantId": 1,
      "name": "张三的个人空间",
      "description": null,
      "icon": null,
      "type": "Personal",
      "creatorId": 21,
      "creatorName": "张三",
      "modified": "2026-09-30 12:00:00",
      "created": "2026-01-15 09:30:00",
      "currentUserRole": "Owner",
      "receivePublish": 0,
      "allowDevelop": 1
    }
  ],
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

`data` 数组元素 = [空间对象](#数据结构空间对象)，字段表见文末。

---

## 2. 文件上传 —— `POST /api/oauth2/file/upload`

scope：`file:write`。**multipart/form-data**。

**请求参数**（form 字段）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `file` | 文件 | **是** | 上传的文件本体 |
| `targetType` | 字符串 | 否 | 默认 `"Default"`；`Agent` 时校验智能体可见权限 |
| `targetId` | 数字 | 否 | 默认 `-1`；配合 `targetType=Agent` 用 |
| `metadata` | 字符串 | 否 | 业务自定义元数据（JSON 字符串原样存） |

**请求示例**

```
POST /api/oauth2/file/upload
Authorization: Bearer oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
Content-Type: multipart/form-data; boundary=----formdata

（file=报告.pdf, targetType=Default）
```

**响应示例**（`data` = [文件记录对象](#数据结构文件记录对象)）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": {
    "id": 12345,
    "tenantId": 1,
    "userId": 21,
    "targetType": "Default",
    "targetId": -1,
    "fileName": "报告.pdf",
    "size": 204800,
    "mimeType": "application/pdf",
    "metadata": null,
    "key": "f/abcd1234",
    "storageType": "local",
    "url": "/api/f/abcd1234",
    "authRequired": true,
    "status": "normal",
    "created": "2026-10-01 10:00:00",
    "modified": "2026-10-01 10:00:00",
    "fileExtension": "pdf"
  },
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- **只有平台管理员能上传 SVG**，普通用户会被拒（错误码 `3340`）。
- `targetType=Agent` 且 `targetId>0` 时校验你对该智能体的可见权限，不通过返回业务失败 `No permission to upload file for this agent id`。

---

## 3. 文件下载地址 —— `GET /api/oauth2/file/url`

scope：`file:read`。为文件签发**平台外可直接打开**的临时地址。

**请求参数**（query）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `fileUrl` | 字符串 | **是** | 文件记录里的 `url`，**需 URL 编码** |
| `download` | 数字 | 否 | 1=强制下载；不传按 0 |

**请求示例**

```
GET /api/oauth2/file/url?fileUrl=%2Fapi%2Ff%2Fabcd1234&download=0
Authorization: Bearer oa-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

**响应示例**（`data` 是字符串：临时地址）

```json
{
  "code": "0000",
  "displayCode": "0000",
  "message": "请求成功",
  "data": "https://files.example.com/f/abcd1234?ak=yyyy&expire=1759999999",
  "tid": "0af7651916cd43dd8448eb211c80319c"
}
```

**坑**

- 地址 **24 小时内有效**（云存储场景给预签名直链、还会再 302 到 1h 预签名地址；本地存储给 `?ak=` 地址），**不要长期存下来**，用前现换。
- ★**不校验文件归属**——拿到 `fileUrl` 即可换出下载地址。⇒ **不要把内部文件 URL 暴露给不可信前端**。
- ★`/userinfo` 的 `avatar` 就是这个口径、且平台已经换好，**头像不需要调本端点**。

---

## 数据结构

### 空间对象

`data` 数组元素（原 `SpaceDto`），对应 `/space/list`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | 数字 | 空间 ID |
| `tenantId` | 数字 | 租户 ID |
| `name` | 字符串 | 空间名 |
| `description` | 字符串 | 描述，可为 null |
| `icon` | 字符串 | 图标地址，可为 null |
| `type` | 字符串 | 枚举：`Personal` / `Team` / `Class` / `Group`（本端点只会出现前两种） |
| `creatorId` | 数字 | 创建者用户 ID |
| `creatorName` | 字符串 | 创建者名 |
| `modified` / `created` | 字符串 | 时间 |
| `currentUserRole` | 字符串 | 枚举：`Owner` / `Admin` / `User`（授权用户在该空间的角色） |
| `receivePublish` | 数字 | 是否接收发布 |
| `allowDevelop` | 数字 | 是否允许开发 |

### 文件记录对象

`data`（原 `FileRecordVO`），对应 `/file/upload`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | 数字 | 文件记录 ID |
| `tenantId` | 数字 | 租户 ID |
| `userId` | 数字 | 上传者用户 ID |
| `targetType` | 字符串 | 上传时传的目标类型 |
| `targetId` | 数字 | 上传时传的目标 ID |
| `fileName` | 字符串 | 原始文件名 |
| `size` | 数字 | 字节数 |
| `mimeType` | 字符串 | MIME 类型 |
| `metadata` | 字符串 | 上传时传的元数据（JSON 字符串），可为 null |
| `key` | 字符串 | 存储键 |
| `storageType` | 字符串 | 存储类型（本地/云） |
| `url` | 字符串 | 平台内相对地址——换外网可开地址就用它调 `/file/url` |
| `authRequired` | 布尔 | 访问是否需要凭证 |
| `status` | 字符串 | 状态 |
| `created` / `modified` | 字符串 | 时间 |
| `fileExtension` | 字符串 | 扩展名 |

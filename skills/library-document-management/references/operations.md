# 资料库协作与管理操作（目录 / 评论 / 分享 / 版本 / 附件 / 权限）

本文是**协作与管理类**端点参考，与 [[api.md]]（导入/导出/增删改查 13 端点）互补。全部前缀 `/api/v1/4sandbox/repo`，鉴权同为 `Authorization: Bearer ${SANDBOX_ACCESS_KEY}`。

> 快速约定：
> - 前缀 `${BASE} = ${PLATFORM_BASE_URL}/api/v1/4sandbox/repo`。
> - 成败看响应 JSON 的 `code`，`"0000"` 成功；HTTP 常仍是 200。
> - **写操作全部 POST 化**：平台标准接口里的 `PUT`/`DELETE` 在此统一映射成 `POST`，用路径后缀（`/delete`、`/resolve`、`/unshare-all`）或 query 参数（`pageId`/`allowComment`）承载原语义。沙箱控制器只开放 GET/POST。
> - 身份来自 `$SANDBOX_ACCESS_KEY` 绑定的 user；权限门槛由后端按该 user 对空间/页面的关系裁决（`requireViewable`/`requireEditable`/`requireShareable`/`requireManageable` 等），越权返回 `code:"0001"`。

---

## 0. 链接格式（务必记住：相对路径 + 包成 Markdown 链接 = 可直接点开）

**链接一律用相对路径 `/repo/...`，不带域名、不写 `$PLATFORM_BASE_URL`**（那个 env 变量只在 curl 命令里当 API 基址用）。**并且，给用户看的链接必须渲染成 Markdown 超链接 `[标题](/repo/doc/{slugId})`，不能只给一坨 `/repo/doc/{slugId}` 裸文本**——裸文本点不动，用户也复制不了。包成 `[标题](相对路径)` 后在门户/聊天里点击即跳转，相对路径会被自动补全到当前环境域名。

- **文档（doc/sheet/pptx）链接**：`[标题](/repo/doc/{slugId})` —— 拿 `pageId` 后先用 `GET /pages/{pageId}` 或 `GET /pages/by-slug/{slugId}` 取 `slugId`，再拼 `/repo/doc/{slugId}`。
- **分享链接**：`[标题](/repo/share/{token})` —— `POST /pages/{pageId}/shares/links` 返回的 `data.token` 即 `{token}`。
- **空间主页**：`[空间主页](/repo/space/{spaceId})`；**门户首页**：`[门户](/repo/)`。
- **文档正文里引用其他文档（索引/目录/超链接等）用相对路径**：写 `[接口规范说明](/repo/doc/uJszfxZFdHE6weUJ)`，**不要带域名前缀**。分享链接同理用 `[标题](/repo/share/{token})`，空间用 `[标题](/repo/space/{spaceId})`。

---

## 1. 目录管理（含目录下的文档）

### 1.1 GET /pages/{pageId}/children — 读子页面/子文件夹

```bash
curl -s "$BASE/pages/201/children" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data`：`RepoPageVO[]`（该页面/文件夹的直接子节点；文件夹的 `pageType:"folder"`）。需 `requireViewable`。

### 1.2 POST /pages/{pageId}/move — 移动页面/文件夹

```bash
curl -s -X POST "$BASE/pages/201/move" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" \
  -H "Content-Type: application/json" \
  -d '{"targetParentId":188,"beforeId":null,"afterId":null}'
```

请求体：`targetParentId`（目标父文件夹 ID，`null`=移到空间根）、`beforeId`/`afterId`（同级定位，均空=追加末尾）。响应 `data` = `RepoPageVO`。需 `requireEditable`；后端防成环（不能移到自身/子孙下）。

### 1.3 POST /pages/{pageId}/copy — 复制文档（含副本）

```bash
curl -s -X POST "$BASE/pages/201/copy?targetSpaceId=57&targetParentId=188" \
  -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

query：`targetSpaceId`/`targetParentId` 均可选（缺省=源同级末尾）。响应 `data` = 副本 `RepoPageVO`（标题自动加「(副本)」）。需 `requireCopyable`。

### 1.4 新建文件夹

复用 [[api.md]] 的 `POST /pages`，把 `pageType` 传 `"folder"`（文件夹是纯容器节点，无正文、不进搜索）：

```bash
curl -s -X POST "$BASE/pages" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" \
  -H "Content-Type: application/json" \
  -d '{"spaceId":57,"parentId":null,"title":"产品资料","pageType":"folder"}'
```

响应 `data` = `RepoPageVO`（含新 `id`/`slugId`，`pageType:"folder"`）。在文件夹下建文档：`parentId` 填该文件夹 `id`。

---

## 2. 评论

### 2.1 GET /pages/{pageId}/comments — 评论线程列表

```bash
curl -s "$BASE/pages/201/comments" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data`：`RepoCommentThreadVO[]`，每个线程 `{comment: RepoCommentVO, replies: RepoCommentVO[]}`（一层回复）。

`RepoCommentVO` 字段：`id`、`pageId`、`userId`、`nickName`、`userName`、`avatar`、`parentId`（null=顶层）、`content`（富文本 JSON 字符串）、`mentions`（@提及 userId 数组）、`anchor`（局部文字评论锚点，**JSON 字符串**，非对象）、`resolved`（布尔）、`created`、`modified`。

### 2.2 POST /pages/{pageId}/comments — 发表评论/回复

```bash
curl -s -X POST "$BASE/pages/201/comments" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" \
  -H "Content-Type: application/json" \
  -d '{"parentId":null,"content":"{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"这条很好\"}]}]}","mentions":[12],"anchor":null}'
```

请求体：`parentId`（回复时填被回复评论 id，null=顶层）、`content`（富文本 JSON 字符串）、`mentions`（@提及 userId 列表，可选）、`anchor`（局部文字评论锚点，**JSON 字符串**——先把 `{from,to,text,ctxBefore,ctxAfter}` 序列化成字符串再传，和 `content` 一样；页面级评论传 null 或省略）。响应 `data` = `RepoCommentVO`。

**选中部分文字评论（锚点评论）示例**：

```bash
curl -s -X POST "$BASE/pages/489/comments" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" \
  -H "Content-Type: application/json" \
  -d '{"parentId":null,
       "content":"{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"这段表述要改\"}]}]}",
       "mentions":[],
       "anchor":"{\"from\":0,\"to\":4,\"text\":\"资料索引\",\"ctxBefore\":\"\",\"ctxAfter\":\"\\n本目录「阿飞测试」用于存放团队管理文档\"}"}'
```

> `anchor` 是**字符串字段**（后端 `private String anchor`），内容为 JSON：`from`/`to` = 选中片段在正文纯文本中的起止偏移、`text` = 选中原文、`ctxBefore`/`ctxAfter` = 前后上下文（定位高亮用）。后端**仅透传不校验**（与 `content` 同策略），所以偏移不必严格对齐也写得进；但前端按 `from`/`to` 高亮，若要精确定位需按正文纯文本偏移计算。**传对象（非字符串）会反序列化失败返回 `4000`**。

### 2.3 POST /comments/{commentId}/resolve — 标记已解决

```bash
curl -s -X POST "$BASE/comments/901/resolve" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data` = `RepoCommentVO`（`resolved:true`）。

### 2.4 POST /comments/{commentId}/delete — 删除评论及回复（软删）

```bash
curl -s -X POST "$BASE/comments/901/delete" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data` 为空。限作者或空间管理员/属主。

---

## 3. 分享

权限位 `permBits`（位掩码，查看恒含）：
`1` 查看、`2` 编辑、`4` 分享、`8` 导出、`16` 评论、`32` 复制。

### 3.1 GET /users/search?kw= — 按用户名/昵称搜用户（分享给个人选人用）

```bash
curl -s "$BASE/users/search?kw=张三" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

query：`kw`（必填）、`size`（默认 10，上限 20）。响应 `data`：`RepoUserSearchVO[]`（`id`/`userName`/`nickName`/`avatar`，已排除自己）。

### 3.2 GET /pages/{pageId}/shares — 查询分享状况

```bash
curl -s "$BASE/pages/201/shares" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data` = `RepoShareStatus`（空间分享列表 + 链接列表 + 管理标志等）。

### 3.3 POST /pages/{pageId}/shares/spaces — 分享到工作空间

```bash
curl -s -X POST "$BASE/pages/201/shares/spaces" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" \
  -H "Content-Type: application/json" -d '{"targetSpaceIds":[57,58],"permBits":3}'
```

请求体：`targetSpaceIds`（目标空间 ID 列表）、`permBits`（1查看|2编辑|4分享|8导出）。重复分享覆盖权限位。响应 `data` 空。

### 3.4 POST /pages/{pageId}/shares/users — 分享给个人

```bash
curl -s -X POST "$BASE/pages/201/shares/users" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" \
  -H "Content-Type: application/json" -d '{"targetUserIds":[12,13],"permBits":17}'
```

请求体：`targetUserIds`（被分享用户 ID 列表）、`permBits`（1查看|2编辑|4分享|8导出|16评论|32复制）。响应 `data` 空。

### 3.5 GET /spaces/{spaceId}/shares/received — 空间「接收分享」列表

```bash
curl -s "$BASE/spaces/57/shares/received" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

### 3.6 GET /spaces/{spaceId}/shares/sent — 空间「对外分享」列表

```bash
curl -s "$BASE/spaces/57/shares/sent" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data`：`RepoSpaceShareItemVO[]`（仅空间成员，无权静默过滤）。

### 3.7 POST /pages/{pageId}/shares/links — 新建分享链接

```bash
curl -s -X POST "$BASE/pages/201/shares/links" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" \
  -H "Content-Type: application/json" -d '{"accessType":"public","allowComment":1,"permBits":1}'
```

请求体：`accessType`（`login`=登录可访问 / `public`=任何人含匿名）、`allowComment`（1/0，缺省允许）、`permBits`（只读类：查看/评论/导出/复制，后端强制去编辑位）。响应 `data` = `RepoShareLinkVO`，其中 `token` 即分享链接凭据 → 分享页 `/repo/share/{token}`（相对路径，见 §0）。

### 3.8 POST /shares/links/{linkId}/comment — 更新链接评论权限

```bash
curl -s -X POST "$BASE/shares/links/77/comment?pageId=201&allowComment=0" \
  -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

### 3.9 POST /shares/links/{linkId}/delete — 删除分享链接

```bash
curl -s -X POST "$BASE/shares/links/77/delete?pageId=201" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

### 3.10 POST /shares/{shareId}/delete — 取消单条空间分享

```bash
curl -s -X POST "$BASE/shares/88/delete?pageId=201" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

### 3.11 POST /shares/users/{shareUserId}/delete — 取消单条个人分享

```bash
curl -s -X POST "$BASE/shares/users/99/delete?pageId=201" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

### 3.12 POST /pages/{pageId}/shares/unshare-all — 取消该文档全部分享与链接

```bash
curl -s -X POST "$BASE/pages/201/shares/unshare-all" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

### 3.13 GET /pages/{pageId}/share-links/{linkId}/stats — 链接访问统计

```bash
curl -s "$BASE/pages/201/share-links/77/stats" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data` = `RepoShareLinkStatsVO`（访问次数 + 去重访问用户列表及最近访问时间）。

---

## 4. 版本历史

### 4.1 GET /pages/{pageId}/versions?page=&size= — 版本列表

```bash
curl -s "$BASE/pages/201/versions?page=1&size=20" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data`：`{total, items: RepoPageVersionVO[]}`，`RepoPageVersionVO` 字段 `id`/`pageId`/`editorId`/`reason`/`created`（倒序，不含大字段）。

### 4.2 GET /pages/{pageId}/versions/{versionId}/preview — 版本 HTML 快照（预览）

```bash
curl -s "$BASE/pages/201/versions/301/preview" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data` = `RepoPageVersionPreviewVO`（含 `html` 字段）。

### 4.3 POST /pages/{pageId}/versions/{versionId}/restore — 恢复版本

```bash
curl -s -X POST "$BASE/pages/201/versions/301/restore" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

覆盖 `ydoc_state` 并追加一条 restore 版本记录。响应 `data` = `RepoPageVersionVO`。

### 4.4 POST /pages/{pageId}/versions/manual — 手动保存版本

```bash
curl -s -X POST "$BASE/pages/201/versions/manual" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

基于当前 `ydoc_state` 落一条手动版本。响应 `data` = `RepoPageVersionVO`。**空 sheet（未写值）无同步内容，会返回 `4000 page has no synced content yet`，须先写值再存版本。**

---

## 5. 附件

文件本体在 file 模块（先 `POST /api/v1/4sandbox/file/upload` 拿 `fileKey`，见 [[file-upload.md]]），这里只挂接「附件映射」。

### 5.1 GET /pages/{pageId}/attachments — 页面附件列表

```bash
curl -s "$BASE/pages/201/attachments" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

### 5.2 POST /pages/{pageId}/attachments — 挂接页面级附件

```bash
curl -s -X POST "$BASE/pages/201/attachments" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" \
  -H "Content-Type: application/json" \
  -d '{"fileKey":"file_xxx","fileName":"合同.pdf","mime":"application/pdf","size":20480}'
```

请求体：`fileKey`（file 模块 FileRecord.fileKey）、`fileName`、`mime`、`size`（字节）。响应 `data` = `RepoAttachmentVO`。

### 5.3 GET /spaces/{spaceId}/attachments — 空间全部附件（含页面级+空间级）

```bash
curl -s "$BASE/spaces/57/attachments" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

### 5.4 POST /spaces/{spaceId}/attachments — 挂接空间级附件

```bash
curl -s -X POST "$BASE/spaces/57/attachments" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" \
  -H "Content-Type: application/json" \
  -d '{"fileKey":"file_xxx","fileName":"说明.docx","mime":"application/vnd.openxmlformats-officedocument.wordprocessingml.document","size":10240}'
```

### 5.5 POST /attachments/{attachmentId}/delete — 删除附件（软删）

```bash
curl -s -X POST "$BASE/attachments/401/delete" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data` 空。限上传人或空间管理员/属主。

`RepoAttachmentVO` 字段：`id`、`spaceId`、`pageId`、`userId`、`fileKey`、`fileName`、`mime`、`size`、`created`。

---

## 6. 文档级权限（ACL，按人权限）

### 6.1 GET /pages/{pageId}/acl — 查询按人权限状况

```bash
curl -s "$BASE/pages/201/acl" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data` = `RepoAclStatus`（开关 + 条目）。仅可管理者。

### 6.2 POST /pages/{pageId}/acl — 整体覆盖按人权限

```bash
curl -s -X POST "$BASE/pages/201/acl" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" \
  -H "Content-Type: application/json" \
  -d '{"aclEnabled":1,"entries":[{"userId":12,"permBits":3},{"userId":13,"permBits":1}]}'
```

请求体：`aclEnabled`（**整数开关，`1`=开启 `0`=关闭**，开启后未配置成员降为仅阅读；传 `true`/`false` 会反序列化失败返回 `4000`）、`entries`（`{userId, permBits}` 数组，整体覆盖）。响应 `data` 空。仅可管理者。

---

## 7. 错误码与坑

- 沿用 [[api.md]] 的通用错误码：`0000` 成功、`0001` 业务错误（读端点资源不存在/越权，看 `message`）、`4030` 缺 Key 或无效 Key、`4000` 无效参数。**注意：写/操作端点目标不存在、id=0、重复删除/取消、移到自身、移入子孙等也返回 `4000`**，别当成「参数格式错」反复改 body。
- **越权即 `0001`**：评论/分享/版本/附件/ACL 各端点在后端按 Key 绑定 user 对空间/页面的权限位裁决，越权返回 `code:"0001"`（不是 403）。换绑定到有权限空间成员 user 的 AK。
- **只 GET/POST**：所有写操作用 POST；没有 PUT/DELETE。删/改统一走 `/delete`、`/resolve`、`/unshare-all` 后缀或 query 参数。
- **分享链接的 `permBits` 是只读类**：`POST /pages/{pageId}/shares/links` 后端会强制去掉编辑位（2），即使传了也不会生效。

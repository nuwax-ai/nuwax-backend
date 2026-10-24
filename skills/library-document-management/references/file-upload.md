# 文件上传（拿 fileKey）参考

> **何时读**：要把**本地二进制原始文件**（`.xlsx` / `.pptx` / `.docx` / `.pdf` 等）导入资料库时。`POST /import` 的 `fileKey` 参数要的是「平台已上传文件的 key」，而 `repo` 13 端点里没有上传接口——上传走平台文件模块的通用上传端点，先上传拿 `key`，再 import。

上传是 `multipart/form-data`，不需要 JSON body。核心就是两步：

1. `POST /api/v1/4sandbox/file/upload` 上传文件 → 拿 `data.key`。
2. `POST /api/v1/4sandbox/repo/import` 建页——**想深度解析（格式保真）就把文件 base64 填进 `content`**，`fileKey`（可选）只用于原件永存（「导出原件」下载）。**只传 `fileKey` 不传 `content`，二进制类型会得到 `parsed:false` 的占位页**（正文是占位文本、无深度解析内容，仅原件可下载）。

## 1. POST /api/v1/4sandbox/file/upload — 上传文件

```bash
curl -s -X POST "$PLATFORM_BASE_URL/api/v1/4sandbox/file/upload" \
  -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" \
  -F "file=@/path/to/季度报表.xlsx"
```

**表单字段**

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `file` | form-data | 二进制 | ✅ | 要上传的文件 |
| `targetType` | query | string | 否 | 来源对象类型（溯源用，如 `repo`；不传也能上传，不影响拿 key） |
| `targetId` | query | int64 | 否 | 来源对象 ID，默认 `-1` |
| `metadata` | query | string | 否 | 文件元数据 JSON 字符串 |

**响应**（看 `code`，`"0000"` 成功）：

```json
{"code":"0000","data":{"id":12345,"fileName":"季度报表.xlsx","size":102400,
  "mimeType":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "fileExtension":"xlsx","key":"s3-bucket-key-abc123","url":"https://...","storageType":"S3",
  "status":"ACTIVE","created":"...","modified":"..."}}
```

**`data.key` 就是 `/import` 要的 `fileKey`。**

## 2. GET /api/v1/4sandbox/file/ak/refresh — 换取免鉴权直访地址（可选）

上传返回的 `url` 若需要免登录直接访问，用这个换带 ak 的地址：

```bash
curl -s "$PLATFORM_BASE_URL/api/v1/4sandbox/file/ak/refresh" \
  -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" \
  --get --data-urlencode "url=$UPLOADED_URL"
```

响应 `data` = 带 ak 的直访 URL 字符串。**导入文档不需要这步**，仅当要给用户一个可点开的文件链接时才用。

## 3. 上传后导入：content（base64）触发深度解析 + fileKey 永存原件

```bash
# 1. 上传拿 key（原件永存用）
KEY=$(curl -s -X POST "$PLATFORM_BASE_URL/api/v1/4sandbox/file/upload" \
  -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" \
  -F "file=@季度报表.xlsx" | jq -r '.data.key')

# 2. import 导入：content 传 base64 触发深度解析，fileKey 让「导出原件」可用
B64=$(base64 < 季度报表.xlsx | tr -d '\n')
curl -s -X POST "$PLATFORM_BASE_URL/api/v1/4sandbox/repo/import" \
  -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" -H "Content-Type: application/json" \
  -d "{\"spaceId\":57,\"fileName\":\"季度报表.xlsx\",\"fileKey\":\"$KEY\",\"content\":\"$B64\"}"
# → data.pageId / data.slugId / data.parsed（parsed:true 才是深度解析成功；xlsx 导入得到 pageType:"sheet" 的表格页）
```

- **`content` 必传**：深度解析（pptx/docx/xlsx/xls 格式保真）只认 `content`（文件 base64 字节），不认 `fileKey`。省掉 `content` 只会得到 `parsed:false` 的占位页。
- **`fileKey` 可选**：只想「深度解析 + 可编辑」、不关心「导出原件」，`fileKey` 可以省略（Sidecar 会自行上传原件）；想保留原始文件下载入口就传。PDF 只做原样预览、无深度解析（不生成可编辑内容），但仍需传 `content`（base64）才能可靠预览，只传 `fileKey` 可能预览空白。
- Markdown/纯文本（`.md`/`.txt`/`.csv`）无需 content，走 `text` 字段即可；**HTML（`.html`）例外——虽是文本格式但按文件处理，走 `content`（base64）**，否则 HTML 结构丢失、`contentSize=0`、无法预览。字段选择矩阵见 [[api.md]]。

## 4. 一键脚本（skill 自带）

skill 根目录 `scripts/upload.py` 封装了上传 + 换直访地址，直接跑：

```bash
python scripts/upload.py /path/to/文件.xlsx
# 输出含 "Key :" 一行，就是 fileKey；也可加 --metadata '{"author":"AI"}'
```

脚本依赖 `pip install requests`，读环境变量 `PLATFORM_BASE_URL` / `SANDBOX_ACCESS_KEY`（与本文档其它端点一致）。

## 5. 常见问题

- **拿不到 key**：确认响应 `code` 是 `"0000"`；失败时 HTTP 常仍是 200，看 `code`/`message`。
- **import 后类型不对**：`fileName` 务必带正确扩展名（`.xlsx`/`.pptx`/`.docx`/`.pdf`/`.md` 等），服务端靠扩展名判定 sheet/pptx/doc。
- **要带复杂图表/图片的成品 Excel/PPT**：不要用 `setCellValues`/`replaceWorkbook` 手工拼，直接上传原始 `.xlsx`/`.pptx` import（**记得同时传 `content` base64**），保真度最高。

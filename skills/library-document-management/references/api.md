# 资料库文档管理 接口完整参考（端点级）

本文是端点级精确格式，写 curl / fetch / 代码前先查这里。接口前缀 `/api/v1/4sandbox/repo`。

> 内容编辑指令（ops）按文档类型拆到独立文档，按需加载：
> - 富文本 doc → [[ops-doc.md]]
> - 幻灯片 pptx → [[ops-pptx.md]]
> - 表格 sheet → [[ops-sheet.md]]
> - 多维表格 datatable → [[ops-datatable.md]]
> - 透视页（多维表格 HTML 看板）→ [[pivot-page.md]]

## 0. 通用约定

- **Base URL**：环境变量 `${PLATFORM_BASE_URL}`（当前 `https://agent.nuwax.com`）。完整前缀 `${PLATFORM_BASE_URL}/api/v1/4sandbox/repo`。下文 curl 用 `${BASE}` 占位，即 `BASE=${PLATFORM_BASE_URL}`。
- **线上可能 4040，本地联调用 8081**：`https://agent.nuwax.com` 的 Java 后端若未部署 repo 模块，`/api/v1/4sandbox/repo/*` 全部返回 `{"code":"4040","message":"No static resource ..."}`（HTTP 仍是 200）。本地调试改用 `BASE=http://127.0.0.1:8081`。
- **鉴权头**（所有请求都要带）：
  ```
  Authorization: Bearer ${SANDBOX_ACCESS_KEY}
  ```
  实际 curl 写法 `-H "Authorization: Bearer $SANDBOX_ACCESS_KEY"`（环境变量已存在，直接引用）。
- **响应格式**：非二进制端点返回
  ```json
  {"code":"0000","displayCode":"0000","message":"success","data":...,"tid":"...","success":true}
  ```
  成败看 `code`：`"0000"` 成功；`"0001"` 业务错误（message 是描述）；`"4030"` 缺 Key 或无效/过期 Key（两者同码）；`"4000"` 无效输入/参数（非法 pageType、写/操作端点目标不存在或重复操作等，看 message 区分）；`"4040"` 接口不存在（路径拼错）。**HTTP 状态码经常是 200，不要只靠 HTTP 状态判成败**。二进制导出端点（xlsx/pptx/docx/pdf/original）直接返回文件流，不包 JSON。
- **HTTP 状态码**（少数端点真的用）：`/export` 页面不存在 → 404、非法 format → 400。

## 1. 鉴权

- 每个请求带头 `Authorization: Bearer ${SANDBOX_ACCESS_KEY}` 即可，无需其它传参（租户/身份由 Key 自动识别）。
- **AK 绑定 user，user 必须是目标 space 成员**：文档/空间的可见性取决于该 AK 对应的 user 对 space 的成员关系。拿一个 AK 读某 space 的页面时，若返回 `{"code":"0001","message":"page not found"}` 或 `4030`，多半是「这个 AK 的 user 不在该 space 里」，不是页面真不存在——换一个绑定到该 space 成员 user 的 AK 再试。同一个页面，不同 AK 可能一个能读一个不能读。
- Key 缺失 → `code "4030"`；Key 无效/过期 → 也返回 `code "4030"`（与缺 Key 同码，别看到 4030 就只查「头有没有传」，也可能是 Key 值失效）。

## 2. 端点明细

### 2.1 GET /spaces — 空间列表

当前 sandbox 用户能访问的全部空间。

```bash
curl -s "$BASE/api/v1/4sandbox/repo/spaces" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data`：`SpaceDto[]`，每项有 `type` 字段（`"Personal"`=个人空间、`"Team"`=团队空间）。新建文档默认取 `type:"Personal"` 那项的 `id` 当 spaceId；列表里没有 `Personal` 项时才退回 `data[0].id`。

### 2.2 GET /spaces/{spaceId}/tree — 空间完整页面树

```bash
curl -s "$BASE/api/v1/4sandbox/repo/spaces/57/tree" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data`：`RepoPageTreeNodeVO[]`，每个节点 `{page: RepoPageVO, children: [...]}`。

### 2.3 GET /spaces/{spaceId}/pages — 空间根页面列表

```bash
curl -s "$BASE/api/v1/4sandbox/repo/spaces/57/pages" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data`：`RepoPageVO[]`。

### 2.4 GET /pages/{pageId} — 按 ID 读页面元数据

```bash
curl -s "$BASE/api/v1/4sandbox/repo/pages/201" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

- 成功 `data` = `RepoPageVO`；不存在 `{"code":"0001","message":"page not found"}`。

`RepoPageVO` 字段：`id`（数字）、`slugId`（短链）、`spaceId`、`parentId`、`title`、`pageType`（`doc`/`sheet`/`datatable`/`folder` 四值）、`sourceExt`（原始扩展名，如 `md`/`xlsx`/`pptx`/`docx`/`html`/`pdf`）、`originalFileKey`、`contentSize`、`creatorId`、`lastEditorId`、`editedAt`、`created`、`modified`。

> **注意**：导入 `.pptx` 得到的页面 `pageType` 是 `doc`（不是 `pptx`），靠 `sourceExt:"pptx"` 区分——判定「这是幻灯片页」应看 `sourceExt` 而非 `pageType`。

### 2.5 GET /pages/by-slug/{slugId} — 按短链 ID 读页面

```bash
curl -s "$BASE/api/v1/4sandbox/repo/pages/by-slug/BSbttVu3GLvMMLTm" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

同 2.4，用 `slugId` 定位（注意：只有 sandbox 用户有成员权限的空间内页面能查到）。

### 2.6 GET /pages/{pageId}/content — 读正文

按文档类型返回三选一，看 `data.kind`：`doc`（ProseMirror JSON，挂 `data.doc`）/ `pptx`（幻灯片模型，挂 `data.slides`）/ `sheet`（表格快照，挂 `data.snapshot`）。

```bash
curl -s "$BASE/api/v1/4sandbox/repo/pages/201/content" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data`：
```json
{"ok":true,"kind":"doc","applied":0,"skipped":0,"doc":{"type":"doc","content":[...]}}
```

- `kind` 取值 `doc`/`pptx`/`sheet`；结果挂在对应字段：`doc` → `data.doc`、`pptx` → `data.slides`、`sheet` → `data.snapshot`。
- 新建未播种的空页面读回空结构（doc 空文档 / sheet 空表），不报错。`kind` 由服务端按页面类型显式映射（`pageType=sheet` 或 `datatable`→sheet、`sourceExt=pptx`→pptx、其余 doc），所以 GET 读全新空 sheet/多维表格也直接返回 `kind:"sheet"` + 默认空表，不再误判成 doc。多维表格的字段/记录/视图语义解析看 [[ops-datatable.md]]。

### 2.7 GET /pages/{pageId}/export?format= — 导出

```bash
curl -s -o out.docx "$BASE/api/v1/4sandbox/repo/pages/201/export?format=docx" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

| format | 返回 | 数据源 | 备注 |
|---|---|---|---|
| `xlsx` | 二进制 .xlsx | Sidecar 实时 ydoc_state | 仅 sheet 页；须先写值（空表导出报 `document not found`） |
| `pptx` | 二进制 .pptx | Sidecar 实时 ydoc_state | 仅 pptx 页；AI 编辑已落库，导出为最新值 |
| `docx` | 二进制 .docx | 平台 `html_snapshot`（快照） | |
| `pdf` | 二进制 .pdf | 平台 `html_snapshot`（快照） | |
| `markdown` | JSON `{"code":"0000","data":{"title","markdown"}}` | `html_snapshot`（快照） | 非二进制 |
| `original` | 原始导入文件 | 原件（original_file_key） | html 无原件回退 .html 快照 |
| 其它 | HTTP 400 | — | 非法格式 |

- 页面不存在 → HTTP 404；无导出权限 → `{"code":"0001",...}`。
- **关键**：docx/pdf/markdown 走快照（导入时生成，AI 改内容后不刷新）；xlsx/pptx 走实时真源。见 SKILL.md「关键语义」。

### 2.8 GET /search — 搜索

```bash
curl -s "$BASE/api/v1/4sandbox/repo/search?keyword=报价&spaceId=57" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

参数：`spaceId?`、`keyword?`、`from?`、`size?`。响应 `data`：`RepoPageSearchVO[]`（`pageId`/`slugId`/`title`/`snippet`/`score`）。

### 2.9 POST /pages — 新建页面

```bash
curl -s -X POST "$BASE/api/v1/4sandbox/repo/pages" \
  -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" -H "Content-Type: application/json" \
  -d '{"spaceId":57,"title":"我的笔记","pageType":"doc"}'
```

请求体：`spaceId`（必填）、`parentId?`、`title?`、`pageType?`（`doc`/`sheet`/`datatable`/`folder`，传其它值如 `pptx` 会报 `4000 invalid pageType`）、`sourceExt?`、`originalFileKey?`。`pageType:"datatable"` 建多维表格（建完是空表，字段/记录播种看 [[ops-datatable.md]]）。

响应 `data` = `RepoPageVO`（含新 `id`、`slugId`）。**仅建元数据，不播种正文**——读回空结构，要内容再写（sheet 带 `kind:"sheet"`）或 `/import`。

### 2.10 POST /pages/{pageId}/rename — 重命名

```bash
curl -s -X POST "$BASE/api/v1/4sandbox/repo/pages/201/rename" \
  -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" -H "Content-Type: application/json" \
  -d '{"title":"新标题"}'
```

响应 `data` = `RepoPageVO`（title 已更新）。

### 2.11 POST /pages/{pageId}/delete — 删除（软删，不可恢复）

> ⛔ **调用前置条件（删除禁令，见 `SKILL.md`）**：**不得删除任何页面，除非用户明确说出要删哪个页面**。改版 / 清理 / 去重 / 整理 / 「我建的」/ 「重新导一个更干净的」**都不是理由**。删除是**软删、不可恢复**，并连带废掉侧边栏入口、页面互链、收藏、分享链接、评论锚点、版本历史——这些入口大多不在你的上下文里。**删前必须能引用用户原话里点名要删的那个页面；拿不准默认不删、去问。**
> 想「改」请改用非破坏性路径：改数据（动态读取，无需动页）/ `rename` / `move` / `copy` / **新建新页并排保留旧页**。

```bash
curl -s -X POST "$BASE/api/v1/4sandbox/repo/pages/242/delete" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

响应 `data` 为空。删页面及其子树（软删，yn=0）。**确认 pageId 正确再删，不可恢复。**

### 2.12 POST /pages/{pageId}/content — 修改正文

```bash
curl -s -X POST "$BASE/api/v1/4sandbox/repo/pages/201/content" \
  -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" -H "Content-Type: application/json" \
  -d '{"ops":[{"op":"appendBlocks","blocks":[{"type":"paragraph","content":[{"type":"text","text":"新段落"}]}]}]}'
```

请求体：`kind?`（缺省按页面类型映射 `doc`/`sheet`/`pptx`，也可显式指定覆盖）、`ops`（指令数组，按序执行）。服务端已按 `pageType`/`sourceExt` 自动映射 kind，全新空 sheet 不再误判成 doc；显式带 `kind:"sheet"` 仍是等价且更保险的写法。

响应 `data`：`{"ok":true,"kind":"doc","applied":1,"skipped":0,"doc":{...}}`——`applied` 是实际应用的写指令数，`skipped` 是未应用（未知/空）数（`skipped = 写指令数 − applied`）。若 ops 里带 `read`，会额外回填 `doc`/`slides`/`snapshot` 最新状态。

> ⛔ **判成败必须看 `applied`，不能只看 `code`**：`doc`/`pptx`/`html` 的**未知 op 名不报错也不写入**——服务端**静默跳过**，响应照样 `code:"0000"`，只有 `applied:0` + `skipped:N` 暴露它（`replaceDoc`/`setText` 这类不存在的名字、把 HTML 页的 `replaceText` 发到 doc 页，都长这样）。
> **`applied:0 && skipped>0` 一律判写失败**：回去核对 op 名在该 `kind` 下是否存在、`kind` 是否传对，改对后重发；**不许汇报「已改好」**。写指令建议同批带 `{"op":"read"}` 读回确认（见 SKILL.md「⛔ op 指令三条铁律」）。
> **例外：`sheet` / 多维表格（`kind:"sheet"`）**——未知 op 名现在直接返回 `code:"0001"`（message 列出 `setCellValues`/`replaceWorkbook`/`insertRow`/`removeRow`），不会静默跳过；同一批 ops 原子回滚。

指令集按文档类型见 [[ops-doc.md]] / [[ops-pptx.md]] / [[ops-sheet.md]]；**HTML 透视页见下方 2.12.1**。

### 2.12.1 POST /pages/{pageId}/content — 修改 **HTML 透视页** 正文（`kind:"html"`）

> 透视页（`sourceExt:"html"`）的正文真源是 Yjs 文档里的 `htmlRaw` 文本片段，**必须显式传 `kind:"html"`**——不传时服务端按页面类型推成 `doc`，会落错分支：内容 op 被 doc 执行器**整批静默吞掉**（`applied:0` + `skipped:1`，不报错、页面纹丝不动）。

```bash
AUTH="Authorization: Bearer $SANDBOX_ACCESS_KEY"
BASE="$PLATFORM_BASE_URL/api/v1/4sandbox/repo"

# 1. 先读回当前源码（改之前必做，find 要照抄这里的原文）
curl -s -X POST "$BASE/pages/1292/content" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"kind":"html","ops":[{"op":"read"}]}'
# → data.kind="html"、data.snapshot.html="<!doctype html>…"、applied=0（读无副作用）

# 2. 定向打补丁（首选：只改不满意的那一小处，其余逐字保留）
curl -s -X POST "$BASE/pages/1292/content" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"kind":"html","ops":[{"op":"replaceText","find":"共 12 条记录","replace":"共 15 条记录"},{"op":"read"}]}'
# → data.applied=1、data.snapshot.html 已是改后全文

# 3. 整份替换（大改版式时用）
curl -s -X POST "$BASE/pages/1292/content" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"kind\":\"html\",\"ops\":[{\"op\":\"replaceHtml\",\"html\":\"$HTML\"}]}"
```

| 指令 | 参数 | 说明 |
|---|---|---|
| `read` | — | 无副作用；回 `data.snapshot.html`。⚠️ **`GET /pages/{pageId}/content` 读不到 html 源码**（它按 `kindOf()` 报 `doc`，且 GET 没有 kind 入参），只能走 POST 的 `read` |
| `replaceText` | `find`、`replace`、`all?` | 定向补丁。`find` 为空 → 报错；**命中 0 次 → 报错**（`replaceText: find not found in current html (N chars); read the html first and retry with an exact snippet`）；命中多次且未给 `all:true` → 报错要求消歧；`all:true` 则全替换 |
| `replaceHtml` | `html` | 整份替换（`html` 为空/空白 → 报错） |

- **参数错误严格失败**：参数非法 / `find` 未命中一律明确报错，不会返回 `code:"0000"` 却什么都不改。**但 op 名写错仍会被静默跳过**——把 doc 类指令（`appendBlocks` 等）发到 html 页、或 `kind` 没传对，下场就是 `applied:0` + `skipped:1` 且不报错。所以判成败**永远看 `data.applied > 0`** ＋ `GET /pages/{pageId}` 的 `contentSize`/`editedAt` 变化；**`applied:0 && skipped>0` = 写失败**，回查指令名（html 只认 `read`/`replaceText`/`replaceHtml`）与 `kind` 后重发，别汇报成功。⚠️ `replaceText` 若是**同位替换**（等长），`contentSize` 本来就该不变——那不是没生效。
- **`pageId`/`slugId` 不变**：侧栏入口、页面互链、分享链接、评论锚点、版本历史全部保留；已打开该页的用户**无需刷新**即看到新内容（前端有 `htmlRaw` 观察器）。
- **写入是最小 diff**：只对变化中段做 delete+insert（10000 字符正文改 2 个字符只产生约 36 字节 update），不整篇重写、不毁正在协同编辑者的光标；emoji 等代理对不会被切碎。
- **快照回写**：改完自动重写 `html_snapshot` → `export?format=markdown/docx/pdf` **跟随最新正文**。⚠️ `export?format=original` 是「导出原件」，**始终是你导入时上传的那份原始文件字节**（不会被原地改影响）——这是设计如此，不是 bug。
- 2026-09-13 线上实测（pageId 1292 临时页）：读源码 ✅、`replaceText` `applied:1` ✅、`find` 不存在明确报错 ✅、`slugId` 未变 ✅、`editedAt` 刷新 ✅、`contentSize` 随长度变化 ✅（680→689）、markdown/docx/pdf 导出跟随 ✅、**已打开的页面不刷新自动更新** ✅。

### 2.13 POST /import — 导入文档

```bash
curl -s -X POST "$BASE/api/v1/4sandbox/repo/import" \
  -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" -H "Content-Type: application/json" \
  -d '{"spaceId":57,"fileName":"报告.md","text":"# 标题\n\n正文内容..."}'
```

请求体：`spaceId`（必填）、`parentId?`、`title?`、`fileName?`、`fileKey?`（平台已上传文件的 key，**如何上传拿 key 见 [[file-upload.md]]**）、`content?`、`text?`（Markdown/纯文本正文）。

- **深度解析靠 `content`，不靠 `fileKey`**：`content`（文件 base64 字节）才触发 Sidecar 深度解析（pptx/docx/xlsx/xls 的格式保真还原）；`fileKey` 只用于**原件永存**（`original_file_key`，供「导出原件」下载）。**只传 `fileKey` 不传 `content`，二进制类型会得到 `parsed:false` 的占位页**——正文是占位文本、没有深度解析内容，只有原件可下载。导入本地二进制原件务必**同时**给 `content`（base64）＋（可选）`fileKey`（传了就用已有 key，省一次上传；不传 Sidecar 会自行上传原件）。Markdown/纯文本走 `text` 即可，无需 content；**HTML 例外——虽是文本格式但按文件处理，走 `content`（base64）**，字段选择见下方矩阵。
**导入字段选择矩阵**（文件格式 → 用哪个字段）：

| 文件格式 | 使用的字段 | 原因 |
|---|---|---|
| Markdown（.md） | `text` | 纯文本，无需保留原件 |
| 纯文本（.txt） | `text` | 纯文本，无需保留原件 |
| HTML（.html） | `content`（base64） | 需保存原件 + 预览，`text` 只存纯文本、丢结构 |
| Word（.docx） | `content`（base64） | 二进制，必须 base64 传字节 |
| Excel（.xlsx） | `content`（base64） | 二进制，必须 base64 传字节 |
| PPT（.pptx） | `content`（base64） | 二进制，必须 base64 传字节 |
| PDF（.pdf） | `content`（base64） | 二进制，必须 base64 传字节 |
| 不确定格式 | `content`（base64） | 安全优先，解析失败再换 `text` |

> **一句话规则**：**HTML 虽然是文本格式，但要按文件处理——走 `content` base64，不走 `text`。**

- **ticket 由 Java 自动生成**（agent 无需关心）。
- 一步完成：创建页面 + 深度解析 + 落 ydoc_state + 回写 html_snapshot。这是「AI 创作 → 读 → 改 → 导出」最稳的入口。

响应 `data`：
```json
{"ok":true,"pageId":201,"slugId":"BSbttVu3GLvMMLTm","title":"报告","pageType":"doc","sourceExt":"md","originalFileKey":null,"parsed":true}
```

### 导入后校验（每次 /import 后必做）

判断是否解析成功看 `contentSize`：

```bash
curl -s "$BASE/api/v1/4sandbox/repo/pages/by-slug/{slugId}" -H "Authorization: Bearer $SANDBOX_ACCESS_KEY" | jq '.data.contentSize'
```

| 现象 | 结论 | 处理 |
|---|---|---|
| `contentSize > 0` | ✅ 解析成功 | 正常使用 |
| `contentSize = 0` 或（import 响应）`parsed:false` | ❌ 解析失败 | 换用 `content` base64 重新导入 |
| 导出 `format=original` 正常但预览空白 | ❌ sidecar 未解析 | 检查导入时是否传了 `content` |

> 一键校验脚本：`scripts/verify_import.py <slugId>`（跨平台 Python，读 `data.contentSize` 并打印结论，`--by-id` 用数字 pageId）。

## 3. 错误码速查

| code / HTTP | 含义 | 处理 |
|---|---|---|
| `"0000"` | 成功 | — |
| `"0001"` | 业务错误（message 描述） | 读 message |
| `"4030"` | 缺 Key 或无效/过期 Key（两者同码） | 补头 / 换有效 Key |
| `"4000"` | 无效输入/参数（非法 pageType、写/操作端点目标不存在或重复操作等，看 message） | 按 message 修正 |
| `"4040"` | 接口不存在（路径拼错 / 该端点未实现） | 核对路径与方法 |
| `"0001"` + `"page not found"` | 页面不存在/无成员权限 | 换 pageId / 换有权限空间 |

> **读/写端点「资源不存在」错误码不一致（实测）**：读端点 `GET /pages/{id}`、`GET /pages/{id}/content`、`GET /pages/by-slug/{slug}` 对不存在返回 `0001 page not found`；但写/操作端点（`move`/`copy`/`delete`/`comments`/`resolve`/`shares`/`links`/`versions`/`attachments`/`acl` 等）对「不存在 id / id=0 / 重复删除 / 重复取消 / 移到自身 / 移入子孙 / 目标不存在 / 删后查 stats」返回 `4000`（参数校验层拦截）。写操作遇 `4000` 先想「目标不存在 / 重复操作」，别只顾改参数格式。
| HTTP 404 | `/export` 页面不存在 | 换 pageId |
| HTTP 400 | `/export` 非法 format | 换合法 format |

> **读 sheet 偶发 HTTP 500（后端类加载器损坏，非接口问题）**：`GET /pages/{id}/content`（尤其 sheet 读大快照）偶发 500，后端日志是 `java.lang.ClassNotFoundException: com.fasterxml.jackson.core.JsonParser$NumberTypeFP`——这是 Spring Boot fat jar 长时间运行后类加载器损坏。**重启后端**（kill 旧进程 + 原命令重启，约 14 秒）即可恢复。判别：`unzip -l <fatjar> | grep NumberTypeFP` 能查到该类 = 打包完整、类加载器损坏而非缺类。

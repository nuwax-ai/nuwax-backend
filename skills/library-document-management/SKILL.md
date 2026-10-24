---
name: library-document-management
description: 通过平台文档接口（前缀 /api/v1/4sandbox/repo，AK 鉴权）对资料库做文档管理：上传、导入、导出、新建、读取、修改、删除、搜索，以及目录管理（建文件夹/移动/复制/读子级）、评论、分享（空间/个人/链接）、版本历史（列表/预览/恢复/手动保存）、附件、文档级权限、多维表格（数据表/Bitable：建表、字段、记录、视图筛选排序分组统计）、透视页（给多维表格生成 HTML 数据看板/可视化页面）、文档内思维导图/流程图。当用户要求 AI 创建/读取/修改/删除/上传/导入/导出文档、表格、幻灯片、多维表格（Word/Excel/PPT/Markdown/PDF/HTML/数据表）、操作或整理资料库页面、批量生成文档、把内容写成文档、导出成 docx/pdf/xlsx/pptx、给数据做看板/仪表盘/透视页（用户常说：生成可视化 HTML 页面、HTML 看板、把数据表做成网页、动态展示数据、数据透视页、动态读取数据表——这些说法同样触发本技能，且**写代码前必须先读 references/pivot-page.md**），或做资料库管理动作（建文件夹、移动/复制文档、发表/回复评论、分享给空间或个人、建/管理分享链接、查看/恢复历史版本、挂附件、设权限）时触发。给用户的文档链接用相对路径 /repo/doc/{slugId}，且必须包成 Markdown 超链接点击可打开（不带域名前缀，$PLATFORM_BASE_URL 仅作 curl API 基址）。
---

# 资料库文档管理

帮 AI 完成文档库的**上传 / 导入 / 导出 / 新建 / 读取 / 修改 / 删除 / 搜索**。文档分四类：`doc`（富文本）、`sheet`（表格）、`pptx`（幻灯片）、`datatable`（多维表格）。

## 快速开始

- 根地址：环境变量 `$PLATFORM_BASE_URL`
- 鉴权：每个请求带头 `Authorization: Bearer $SANDBOX_ACCESS_KEY`
- 成败判断：看响应 JSON 的 `code`，`"0000"` 才是成功——**错误时 HTTP 状态码常仍是 200，别被骗**
- **改正文（`ops`）时 `code:"0000"` 还不够**：必须再看 `data.applied > 0`。`applied:0` 且 `skipped>0` = 一条写指令都没落地，**判写失败**（op 名打错就长这样，不会报错）——见下方「⛔ op 指令三条铁律」
- 接口前缀：`/api/v1/4sandbox/repo`

```bash
curl -s "$PLATFORM_BASE_URL/api/v1/4sandbox/repo/spaces" \
  -H "Authorization: Bearer $SANDBOX_ACCESS_KEY"
```

## 导入格式决策（先看这个）

```
判断导入格式：
├─ Markdown / 纯文本 ──→ 用 text 字段
├─ HTML ──────────────→ 用 content(base64) —— ⚠️ 文本格式但按文件处理
├─ 二进制文件 (.docx/.xlsx/.pptx/.pdf) ──→ 用 content(base64)
└─ 不确定 ────────────→ 先用 content(base64)，安全优先
```

## 端点速查（13 个）

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/spaces` | 空间列表（`type:"Personal"` 是个人空间，新建文档默认用它当 spaceId） |
| GET | `/spaces/{spaceId}/tree` | 空间页面树 |
| GET | `/spaces/{spaceId}/pages` | 空间根页面列表 |
| GET | `/pages/{pageId}` | 按 ID 读页面元数据 |
| GET | `/pages/by-slug/{slugId}` | 按短链 ID 读页面 |
| GET | `/pages/{pageId}/content` | 读正文 |
| GET | `/pages/{pageId}/export?format=` | 导出（docx/pdf/markdown/xlsx/pptx 全实时：doc 编辑后自动回写快照，xlsx/pptx 走 Sidecar 实时内容） |
| GET | `/search?spaceId=&keyword=` | 搜索 |
| POST | `/pages` | 新建页面（只建元数据） |
| POST | `/pages/{pageId}/rename` | 重命名 |
| POST | `/pages/{pageId}/delete` | 删除（软删，不可恢复） |
| POST | `/pages/{pageId}/content` | 改正文 |
| POST | `/import` | 导入（一步建页+播种+快照） |

> 上表是 `repo` 前缀的 13 个端点。另有**文件上传**端点 `POST /api/v1/4sandbox/file/upload`（前缀 `/api/v1/4sandbox/file`，非 repo），上传本地二进制原件拿 `fileKey`，供 `/import` 导入原始 xlsx/pptx/docx/pdf 用——见 [[references/file-upload.md]]。

### ⛔ 删除禁令（最高优先级，对所有页面类型生效）

**不得删除任何页面，除非用户明确说出要删哪个页面。** 这条**不限于透视页**——`doc` / `sheet` / `pptx` / `datatable` / 透视页 / 文件夹，全部适用，**包括你自己先前创建的**。

**下列理由一律不成立**（全是删页事故的实际借口）：改版 / 重做、清理、去重、整理、优化、觉得多余、觉得重复、"这些都是我建的"、"我重新导一个更干净的"、"反正能再建一个"。

**为什么这么严**：`POST /pages/{pageId}/delete` 是**软删、不可恢复**；页面一旦删除，`pageId`/`slugId` 指向的一切同时作废——侧边栏入口、页面互链、收藏、分享链接、数据表的透视页挂载关系、评论锚点、版本历史。而这些入口**大多不在你能看到的上下文里**。

**要"改"时按这个顺序处理，全都不是删页**：

1. **改数据** → 什么都不用做（透视页/数据表动态读取，刷新即变）。
2. **改标题 / 换位置 / 复制** → `POST /pages/{id}/rename`、`/move`、`/copy`。
3. **改版式 / 改逻辑 / 想重做一份** → **新建新页面并排保留旧的**，打开验证，然后**问用户旧页怎么处理**。
4. **要整理一堆页面** → 列出清单**问用户**，由用户决定删哪些。

**执行前置自检**：在调用 `/delete` 之前，你必须能引用**用户原话**里点名要删的那个页面。做不到就**不删、去问**——**拿不准时默认不删**，多留一个页面没有任何损失，误删一个页面无法挽回。

## 文档类型与指令（按需加载）

`POST /content` 用 `ops` 指令数组改正文，指令按文档类型不同。**先确定文档类型**：读 `GET /pages/{pageId}` 看 `sourceExt`/`pageType`，或 `GET /pages/{pageId}/content` 看 `data.kind`。

| 文档类型 | 判定 | 指令/元素详情（按需读） |
|---|---|---|
| `doc` 富文本 | `pageType:"doc"` 且 `sourceExt` 非 `pptx`（Word/Markdown/HTML/纯文本） | [[references/ops-doc.md]]：6 指令 + 段落/标题/列表/表格/代码块/图片/附件/提及/流程图/思维导图节点 + 加粗/颜色/高亮等 marks |
| `pptx` 幻灯片 | `sourceExt:"pptx"`（注意其 `pageType` 是 `doc`） | [[references/ops-pptx.md]]：9 指令 + text/shape/image/media/formula 五类元素全字段 + 字体/图形/图片/公式样式 |
| `sheet` 表格 | `pageType:"sheet"`（Excel/CSV） | [[references/ops-sheet.md]]：setCellValues 定点写值/公式/样式（**已有内容的表，日常修改只许用这个**）+ read/导出随时可用；replaceWorkbook 仅限多表/合并等结构变更、结构修复，见下方「⛔ 覆盖写禁令」 |
| `datatable` 多维表格 | `pageType:"datatable"`（数据表/Bitable） | [[references/ops-datatable.md]]：底层=sheet（读写带 `kind:"sheet"`）+ 语义约定（row0 字段 schema、row1+ 记录、(0,0) 视图配置）；建表/字段/记录/筛选排序分组填色统计。**记录增删改只用 setCellValues/insertRow/removeRow，禁止整表 replaceWorkbook**（下方「⛔ 覆盖写禁令」） |

### ⛔ op 指令三条铁律（写正文前必读）

**服务端对「未知指令」分两类处理**：`doc`/`pptx`/`html` **静默跳过**——op 名写错既不报错也不写入，响应照样是 `code:"0000"`，只有 `applied`/`skipped` 两个数暴露它（`skipped = 写指令数 − applied`）；**`sheet`/多维表格直接报错 `code:"0001"`**，message 里列出允许的 op（`setCellValues`/`replaceWorkbook`/`insertRow`/`removeRow`）。前者是「以为改好了、其实一个字没动」的主要来源。

1. **op 名只能从该文档类型的清单里抄**——`doc` 是 `appendBlocks`/`replaceContent`/`replaceBlocks`/`insertBlocks`/`deleteBlocks`/`read`（[[references/ops-doc.md]]）；`pptx`、`sheet`、`datatable` 见各自 [[references/ops-*.md]]；`replaceHtml`/`replaceText` **只属于 HTML 页**（`kind:"html"`，[[references/api.md]] §2.12.1）。**`replaceDoc`/`setText`/`updateText` 这类名字任何类型都不存在**——发到 doc/pptx/html 是静默 `skipped`，发到 sheet/多维表格会直接 `code:"0001"`。
2. **判成败看 `applied`，不只看 `code`**：`applied > 0` 才算写成功；**`applied:0 && skipped>0` 一律判写失败**——停下来核对 op 名与该类型的 `kind`，改对后重发，**绝不许汇报「已改好」**。
3. **写完必读回**：写指令同批带 `{"op":"read"}`（或改完再 `GET /pages/{pageId}/content`），照回读的内容确认目标段落真的变了。只凭 `code:"0000"` 断言成功不算数。

**`kind` 要显式传**（`doc`/`sheet`/`pptx`/`html`）：HTML 页在平台侧缺省会被推成 `doc`，内容 op 会被 doc 执行器**整批吞掉**（同样是静默 `skipped`）；sheet/多维表格务必带 `kind:"sheet"`（见「关键语义」）。传其它值（如 `datatable`）会报错（`code:"0001"`，message 带原因），不会静默。

**参数非法会明确失败，不再静默**（2026-10-21 起）：未知 `sheetId`、未知样式 id、样式 key/值不合法、`mergeData` 非数组、未知 `kind`、**未知 sheet op 名**都返回 `code:"0001"`（HTTP 200、`success:false`，message 说明原因）；**同一请求的多个 op 是原子的**，任一失败整批回滚。所以拿到 `0001` 时文档没有被改，按提示修正后重发即可。

端点级精确格式、请求/响应体、错误码看 [[references/api.md]]。**透视页（HTML 数据页）另有一套铁律，见下。**

### ⛔ 覆盖写禁令（表格 / 多维表格，写前必读）

**`replaceWorkbook` 的语义不是「更新」，是「删掉整张表，再按你手里的快照重新插入」。** 从你 `read` 到发出这条指令之间的任何用户编辑（前端每改一格都是一条实时 op），都会被这份旧快照**静默覆盖**：接口照样 `code:"0000"`、`read` 读回也正常，没有报错，事后只能翻操作日志才能发现。

生产上真实发生过：AI 在整表快照上思考、整理了几分钟才写回，把用户两分钟前补的整段备注截掉、把状态改回旧值、把两条记录的标题写串成重复标题，原始标题在表里彻底消失。

**硬规则：**

1. **已有内容的表，日常增删改一律用增量指令**：`setCellValues` 改值/公式/**样式**，`insertRow` 加行，`removeRow` 删行。**禁止**用 `replaceWorkbook`。
   - **样式不再是整表写的理由**：`setCellValues` 的 `cells[]` 支持 `style`（Univer `IStyleData`，如日期/数字格式 `{ "n": { "pattern": "yyyy-mm-dd" } }`、底色 `{ "bg": { "rgb": "#FFFF00" } }`），只写样式时省略 `value` 即可。详见 [[references/ops-sheet.md]] §2。
   - 多维表格的 `date` 字段两种写法都行（实测）：直接写展示串 `"2026-09-30"`，或写 Excel 序列号 `46296` 并配 `style:{"n":{"pattern":"yyyy-mm-dd"}}`。**纯数字不带 `n` 会显示裸数字**。`bg`/字体/边框在 DataTableGrid 不渲染，数字格式 `n` 有效；详见 [[references/ops-datatable.md]] §1。
2. **禁止「`read` 全表 → 在快照上修改 → `replaceWorkbook` 写回」这个模式**——哪怕你只改了一格。这就是覆盖写，也是上述事故的唯一成因。
3. `replaceWorkbook` **只允许**这三种情况：
   - 页面**刚建、内容为空**，且需要**多工作表 / 合并单元格**等 setCellValues 表达不了的结构（单纯行列多**不需要**：setCellValues 会自动扩 `rowCount`/`columnCount`）；
   - 表结构已损坏，需要按完整骨架修复（见「必读的坑」第 6 条）；
   - 用户**明确要求**整表重建 / 重排 / 全量替换。
   即便属于这三种，也必须**在写入前重新 `read` 一次**（不许用任务开头读的副本），并把整个重建压到一次请求里。
4. **只改用户点名要改的内容。** 不要顺手做全表重排、去重、规范化、行序调整——用户没要求的「整理」本身就是覆盖写的高发场景。确有必要，先列出计划问用户。
5. **写前自检**：这次 payload 里若出现大量「用户没让改」的行/列，说明你正在整表覆盖——停下来改成增量。
6. **长任务先算后读**：分析、规划、生成内容都在前面做完；`read` 之后只做最小改动并立即写。读与写之间不要隔着几分钟的思考或多轮工具调用，窗口越短越安全。
7. **多维表格按稳定键定位**：先用记录 ID 列（或唯一标题）建 `行号 → 记录` 映射，再写。`insertRow`/`removeRow` 会让下方行号整体位移，按记忆中的行号写会写到别的记录上（这也是「ID 和标题串行」的直接原因）。

**写完仍要对账**：`read` 回来逐条确认目标记录的值就是你写的；多维表格可用 `scripts/dt_rows.js <pageId> --diff <基线>` 确认「改动只有预期的那些」，出现多余差异就是覆盖了别人的内容。

```text
❌ 错误（覆盖写）：read 整表 → 在 JSON 上改三格 → replaceWorkbook(整个 workbook)
✅ 正确（增量）：  read 定位 → setCellValues([{row:12,col:4,value:"已解决"}, ...])
❌ 错误（行号错位）：记住第 30 行是 NW-155 → 中间有人插了一行 → 还按第 30 行写
✅ 正确（稳定键）：  read → 用 ID 列建「行号 ↔ 记录 ID」映射 → 按 ID 当前所在行号写
```

### ⛔ 透视页铁律（给多维表格做 HTML 数据页前必读）

**先读 [[references/pivot-page.md]] 再动手。** 6 条硬红线：

1. **必须有 meta 开关**：`<meta name="repo-datatable-source" content="{数据表slugId}">`，content 填**数据表的 slugId**（不是 pageId）。没有它，页面只是无宿主能力的纯沙箱，数据一个也读不到。
2. **数据只能走 `window.repoDatatable.read()`**：禁止自己 `fetch()`/XHR（沙箱是 opaque origin，请求不带登录 cookie，打平台接口必 401）；禁止把记录内嵌进 HTML。
3. **绝不把 AK / ticket / 任何凭据写进 HTML**：页面内容任何能看该页的人都能读到；也不需要——宿主代理自动带凭据。
4. **动态读不到数据时停下来报告，不要降级**：绝不用「内嵌数据快照」冒充动态页面；**绝不在交付说明里声称未经验证的性质**（例：页面明明是静态快照，却说「无硬编码静态数据」）。
5. **不放 `<a href>` 跳转**（含站内 `/repo/doc/{slugId}`）：点击会把预览框自身导航走、用户回不去；`link` 字段渲染成纯文本标题 + slugId。
6. **JS 只能用 `static-resources.nuwax.com`**（ECharts），禁用 googleapis / `g-fonts.css`（国内不可达）。CSS 不受限（Tailwind CDN 可用），但别让 CDN 挂掉时页面裸奔。

**🅰️ 默认从内置模板起手（用户没指定风格时就这么干）**：`templates/pivot-dashboard.html` 是**完整可运行**的默认透视页模板——一份文件三视图（看板 / 表格 / 图表）+ 顶部指标条 + 搜索 + 分组轴手选并跨会话记忆；**字段角色靠「字段类型 + 字段名语义」自动识别，不写死任何字段名、列号、选项 id**。用户没说风格或变体时，**复制这个文件起手**，通常只做两件事：① 把 head 里 `repo-datatable-source` 的 `content` 换成目标数据表 slugId；② 按需改标题与文案。**别重写它的字段识别、空值桶、图表适配逻辑**——那些是在线上真数据上跑过的，真要改就重跑 `pivot_check.sh`。要「杂志 / 报告式」观感才另起 §5F 那套。详见 [[references/pivot-page.md]] §5.0。

**平台自己也播种这份模板**：用户在多维表格里点「＋ → 新建透视页面」时，前端就用这份模板起页（只把数据表 slugId 与标题替换进去），所以**用户已有的透视页多半就是它**——遇到「帮我改改我那个透视页」，先按这份模板的结构去读页面，而不是另起一版。

**🔴 导入前铁律：写码 → `pivot_check.sh`（机械门禁 + 本地真渲染**断言**）→ 导入 → 线上复核。禁止跳过本地渲染。**
**「渲染对不对」由脚本断言，不靠肉眼**——`pivot_check.sh` 退 0 才继续；断言报错就按输出逐条改。**人眼只需看「好不好看」（视觉/配色），那部分断言管不了。**

```bash
# ① 一条命令跑「机械门禁 → 本地真渲染断言」，任一步失败即停（导入不可回退：/import 只新建页、
#    不覆盖，看走眼就得再导一次、白烧一个 slugId）
bash scripts/pivot_check.sh <page.html> <数据表slugId> <数据表pageId>
#    退出码 0 = 门禁过了 + 断言全过（可以导入）
#           1 = 被拦下（门禁 ERROR，或断言失败 —— 输出里有具体是哪条断言、实测值多少）
#           4 = 门禁过了但**渲染没验证**（没给数据源，或显式 --no-assert）—— 未完整验证，别当通过
#           5 = 环境不具备（本机没 Chrome，断言跑不了）—— 不假装通过，装 Chrome 或 PIVOT_CHROME=<路径>

# 分开跑也行（带更多开关）：
#   node scripts/preview_stub.js <数据表pageId> page.html --assert     # 单视口断言（导入前那一关）
#     分享访客视角加 --readonly；排查「页面读到了什么」加 --print-payload
#     想「录一次、之后离线回放」（无凭据也能跑）：联网时加 --save-snapshot snap.json 录制，
#     之后用 --from-file snap.json 回放（不联网、不需要 AK，注入结果与联网逐字节一致）
#     → 用 pivot_check.sh 时对应 --from-file：bash scripts/pivot_check.sh page.html <slugId> --from-file snap.json
#     截图存档（如确需人眼复核视觉）：加 --shots /tmp/shots
#   node scripts/preview_stub.js <数据表pageId> page.html                  # 不加 --assert = 只出预览文件（退 4 口径）
#   python3 scripts/pivot_lint.py <page.html> <数据表slugId> <数据表pageId>
#   node scripts/e2e_pivot.mjs --page <页面.html> --from-file <快照.json> --corrupt --fail-writes
#     改版收尾 / 交付前跑：多视口（1440x900 / 390x844 / 320x375）+ 写失败演练 + 坏数据演练
```

**断言查什么**（都是机器可读、退出码可判，不需要人盯）：页面非白屏 → 数据桥上屏 → `read()` 成功且**快照里的真实数据值出现在页面可见文本里**（模板无关的判据，专抓「字段名对不上 / 结构不匹配 / 数据没喂进去」）→ 无「加载中…」残留 → 无 `NaN`/`undefined`/`[object Object]` → 无未捕获异常、无 console error → 无横向溢出（多视口）→ canvas 图表没坍缩成 0 高。**环境不具备的能力（如外链 CDN 不可达导致图表渲染不了）如实报「未验证」warn，不会伪装成通过。**

| 附带脚本 | 干什么 | 何时用 |
|---|---|---|
| `scripts/pivot_check.sh` | **一条命令**跑「机械门禁 → 本地真渲染断言」，失败即停 | 透视页**导入前/原地改后**（首选入口） |
| `scripts/pivot_lint.py` | 透视页静态门禁（红线 + 宿主解析模拟 + 内联 JS 语法） | 需要看逐条 ERROR/WARN 时 |
| `scripts/preview_stub.js` | 用真实快照在本地渲染页面（`--assert` 出机器断言；可 `--readonly`/离线回放/`--shots` 截图） | 导入前那一关；或需要预览文件/截图时 |
| `scripts/e2e_pivot.mjs` | 端到端：**多视口 + 写失败 + 坏数据演练**（共用同一断言内核） | 改版收尾 / 交付前；已知局限：`--corrupt` 只覆盖页面**默认视图** |
| `scripts/assert_engine.js` | 断言内核（被上面两个脚本共用，一般不用直接调） | 排查「断言为什么这么判」时 |
| `scripts/dt_rows.js` | 数据表**读回 / 落基线 / 逐行比对**（`--save` / `--diff` → `NET_ZERO`） | 写操作后**对账**、验残留 |
| `scripts/verify_import.py` | 校验导入成功（`contentSize > 0`） | 导入后立即 |

**内置模板**：`templates/pivot-dashboard.html` —— 默认透视页起手件（见上方 🅰️）。**用户没指定风格就用它**，改 meta 的 `content` + 标题文案即可；不要从 §5 骨架拼。它不是片段而是完整可运行的一整页（三视图 + 指标条 + 分组轴手选），字段角色自动识别。

**⚠️ 宿主解析 HTML 的两处裸正则，会让「本地绿、线上炸」**：宿主取**第一个** `repo-datatable-source` meta 当目标数据表、把 SDK 注入到**第一个** `<head>` 之后——两处都是拿正则在**原始文本**上取首个匹配，**不剥注释**。所以注释里写带尖括号的示例标签（如说明文字里出现 meta 或 head 标签）会**抢走真标签**：要么 `read()` 报 `forbidden`，要么 SDK 注入进注释变惰性、页面永远走兜底面板。**最坑的是本地预览看不出来**（数据是桩喂的、不读 meta，页面渲染得好好的）。因此 `pivot_lint.py` 与 `preview_stub.js` 都按宿主规则模拟并**硬失败**；写注释时别出现带尖括号的示例标签。详见 [[references/pivot-page.md]] §1.0。

**一律走 `pivot_lint.py`，不要手敲 `grep` 自查。** 脚本已覆盖全部机检项（AK/`Bearer`/ticket 泄漏、`fetch`/XHR、`<a href>`、googleapis、外链 JS 白名单、`prompt/confirm/alert`、`document.cookie`、缺 SDK、内嵌真实记录反查、**内联 JS 语法错误**、**CSS 类名重复定义**、**ECharts 未做 resize 补偿**…）。手敲的简化版 grep 会**误报**（如 `grep 'ak-'` 命中 `word-break:break-word`、`grep 'resize'` 命中 CSS 的 `resize:both`），一旦被误报误导，人就会开始「忽略自查结果」——那比不查更危险。（脚本的 AK 正则是 `ak-[0-9a-fA-F]{8,}`，本就躲开了这类误报。）WARN 仍须逐条人工确认。

**验收（必做，「能打开、有数据」不算数）**——写死数据的页面同样「能打开有数据」，能**对上数**才算通过。三层，**前两层脚本断言，第三层必须上线**：

1. **本地渲染（导入前，机器判）**：`bash scripts/pivot_check.sh <page.html> <slugId> <dataPageId>` → 退 **0** 才算过。退 1 会指名道姓报出哪条断言失败、实测值多少；退 4 = 没验证（没给数据源 / `--no-assert`）；退 5 = 本机没 Chrome。**这一层现在不靠肉眼**——「数据上了屏、字段名对得上、没 NaN、没横向溢出、图表没坍缩」都是脚本在判。
2. **写操作要对账**：写之前 `node scripts/dt_rows.js <数据表pageId> --save /tmp/base.json` 落基线，写之后 `--diff /tmp/base.json`——打印 `NET_ZERO` 才叫「改动就是我想要的那些」；出现多余的差异就是**残留**（退 3 是「有差异，逐条认领」不是工具报错）。
3. **线上断言清单（必须上线做，桩替代不了）**：`slugId` 未变（原地改时）→ 分组轴名与字段名逐字一致 → 桶数 == 去重取值数 + 1（**含空值桶**）→ **Σ各桶 == 记录总数** → 页面汇总数与数据表实际值一致 → 图表标题对得上。
4. **活体测试**：改数据表一条记录 → 页面刷新 → 数字必须跟着变。**不变 = 数据写死了，回炉。**

**人眼还剩什么活**：只剩「好不好看」——配色、留白、字体层次这类主观项（断言只管「对不对」）。需要时用 `preview_stub.js --assert --shots /tmp/shots` 落图再看，别把「肉眼看一遍」当成渲染正确性的证据。

手法提醒：线上核查**快照落盘后局部 grep**（别把整页 a11y 树灌进上下文）；**别记 uid 跨步骤复用**（reload 后前缀全变）；透视页在**沙箱 iframe** 里，宿主侧执行 JS 碰不到页面内部 DOM——要在页面里跑 JS 就用本地那份预览文件。详见 [[references/pivot-page.md]] §1.1。

**⛔ 改版不要靠删旧页（重申上方全局删除禁令）**

**正文现在可以原地改**（`POST /pages/{pageId}/content` 带 `kind:"html"` + `replaceText` 定向补丁 / `replaceHtml` 整份替换），`slugId` 不变、已打开的页面自动刷新——所以**改版没有任何理由去删旧页**。仍然最容易犯的错就是「删掉旧的、重新导一个新的」——**禁止**。改版需求一律按上方分流处理：改数据不用动页、改标题/位置用 `rename`/`move`、改版式**先原地改**（改不动再新建新页并排保留旧页）、**删页须用户明确点名**。透视页通常还挂着数据表的下级关系，删掉会一并打断。

**透视页（数据看板 / 报告式页面）**：给多维表格生成动态读写数据的 HTML 页面（meta 标记 + 宿主注入 SDK）。含**样式规范（黑白灰基线 + 网页设计要求）+ 六类模板**（表格/卡片/看板/仪表盘/筛选列表/**杂志报告式**）+ **默认起手件 `templates/pivot-dashboard.html`**（用户没指定风格就用它，见上方 🅰️ 与 [[references/pivot-page.md]] §5.0）；用户要「报告 / 日报 / 杂志 / 高级感可视化」走 **F 杂志/报告式**模板，优先用 `frontend-design` 技能打磨视觉。页面跑在**沙箱 iframe**（opaque origin）里：**CSS 怎么写都行**（内联/外链/Tailwind/动态注入都生效，不漏宿主；`position:fixed`、`100vh` = 预览框视口），默认内联只为稳。⚠️ 其余沙箱边界（`localStorage` 仅当次有效→跨会话用 `repoHost.getPref/setPref`；图片用附件裸相对 `/api/f/{key}` 会自动换免登录地址；不用 `prompt/confirm` 用自绘弹层；主标题 `document.title`+`repoHost.onTitle` 跟随改名、**记得剥掉「站点名 - 」前缀**）详见 [[references/pivot-page.md]] §0.5。

## 关键语义（务必记住）

- **判定幻灯片页看 `sourceExt`，不看 `pageType`**：导入 `.pptx` 的页面 `pageType` 是 `doc`。
- **导出全实时**：docx/pdf/markdown 读 `html_snapshot`，AI 编辑 doc 后 Sidecar 会自动回写该快照，导出即最新；xlsx/pptx 走 Sidecar 实时内容（pptx 编辑会落库、xlsx 写值后即可导出）。
- **从零创作成品文档**用 `/import`（一步建页+播种快照+真源）；已有文档用 `POST /content` 定点修改后直接导出即可，无需重新 import。
- **sheet 读写务必带 `kind:"sheet"`**：全新空表按内容探测会误判成 doc，带 kind 后任何状态的表都能正确读写。
- **多维表格 = sheet 存储 + 语义约定**：`pageType:"datatable"` 的页读写同样带 `kind:"sheet"`；字段 schema 在 row0 表头格 `custom.dtField`、记录在 row1+、视图配置在 `(0,0)` 格 `custom.dtMeta`——写 (0,0) 时只带 custom 不带 value（value:null 会清掉首字段名）。select 字段记录值写**选项 id** 不是选项名，但视图筛选/填色条件的 value 反过来写**选项名**（前端按翻译后的展示名比较）。详见 [[references/ops-datatable.md]]。
- **导入二进制原件：深度解析靠 `content`，`fileKey` 只永存原件**：`/import` 的 `content`（文件 base64 字节）才触发 Sidecar 深度解析（pptx/docx/xlsx/xls 格式保真），`fileKey` 只用于原件永存（「导出原件」下载）。**只传 `fileKey` 不传 `content`，二进制类型会得到 `parsed:false` 的占位页**（正文是占位文本、无深度解析内容）。导入本地二进制原件务必同时给 `content`（base64）＋可选 `fileKey`（想保留原件下载入口就先 `POST /api/v1/4sandbox/file/upload` 拿 `data.key` 填进去，repo 13 端点没有上传接口）。纯文本/Markdown 走 `text` 字段即可，无需上传、无需 content。
- **新建文档默认进个人空间**：用户新建/导入文档、表格、幻灯片（Word/Excel/PPT/Markdown/PDF/HTML）时，若用户没指定空间、上下文里也没关联到某个空间，默认放**用户个人空间**——`GET /spaces` 找 `type:"Personal"` 的项，取其 `id` 当 `spaceId`（列表里没有 `Personal` 项才退回 `data[0].id`）。
- **HTML 导入用 `content` 不用 `text`**：HTML 虽然是文本格式，但用 `text` 字段导入只会保存剥离了标签的纯文本，导致 `contentSize=0`、无法预览。要保留 HTML 完整结构并支持预览，必须用 `content` 字段传 base64 编码的 HTML 字节。导入后可通过 `contentSize` 是否 > 0 判断是否解析成功。

## 推荐工作流

0. **做透视页（HTML 数据看板）**：先读 [[references/pivot-page.md]]（铁律见上）→ 生成 HTML → 跑 **`bash scripts/pivot_check.sh <page.html> <数据表slugId> <数据表pageId>`**（一条命令 = 机械门禁 + 本地真渲染断言，**退 0 才继续**；**没有 AK 就 `--save-snapshot` 录一次 + `--from-file` 离线回放**）→ `/import`（`parentId` 必须 = 数据表 pageId，`content` 传 base64）→ 打开验收（**本地断言已过 → `dt_rows.js` 对账 + 线上断言清单 + 活体测试**）。**「渲染对不对」不许用肉眼下结论**：退 4/5 都算**没验证**，要么补齐数据源/装 Chrome 重跑，要么如实说「未验证」。（视觉好不好看仍可另存截图人眼过一遍。）交付前建议再跑 `node scripts/e2e_pivot.mjs --page <页面.html> --from-file <快照.json> --corrupt --fail-writes`（多视口 + 坏数据演练）。⚠️ **要改已有透视页时**：**原地改**——先 `POST /content {"kind":"html","ops":[{"op":"read"}]}` 读回源码，再 `{"op":"replaceText","find":"原文","replace":"新文"}` 打补丁（大改版式用 `replaceHtml`）；`slugId` 不变、已打开页面自动刷新、无须删页重建。**绝不允许为了改版删掉旧页**——删页须用户明确点名，见 [[references/api.md]] §2.12.1、[[references/pivot-page.md]] §1 与上方「⛔ 禁止删页」。
1. **从零创作成品文档**：直接 `POST /import` 传入 Markdown/文本（一步建页 + 解析 + 播种正文 + 生成快照），拿回 `pageId`/`slugId`。**导入本地文件原件**（HTML/xlsx/pptx/docx/pdf）时用 `content`（base64）——**HTML 虽是文本格式也走 `content`，不走 `text`**，否则 HTML 结构丢失、无法预览；`fileKey`（可选）永存原件，见 [[references/file-upload.md]]。导入后立即校验 `GET /pages/by-slug/{slugId}` 的 `data.contentSize > 0`（或直接跑 `scripts/verify_import.py <slugId>`）确认解析成功。
2. **读**：`GET /pages/{pageId}/content`。
3. **改**：`POST /pages/{pageId}/content` 发 `ops`（按类型看对应 [[references/ops-*.md]]），同批带 `{"op":"read"}` 读回校验。**表格/多维表格已有内容时**：先 `read` 定位，紧接着用 `setCellValues`/`insertRow`/`removeRow` 写最小改动；**绝不把 `read` 的快照整份 `replaceWorkbook` 写回**（见「⛔ 覆盖写禁令」）。
4. **导出**：`GET /pages/{pageId}/export?format=docx|pdf|markdown|xlsx|pptx|original`。

## 必读的坑

1. **新建的空页面读回的是空内容**——`POST /pages` 只建元数据没内容，读回空结构（doc 空文档 / sheet 空表）。要内容，先用 `/import` 导入，或直接写（sheet 带 `kind:"sheet"` 写值、doc 用 appendBlocks 播种）。
2. **sheet 公式分两层看**——写公式 `f` 时把结果写进 `v`（否则 `read` 快照/导出缓存是旧值或空值）；改明细格后，前端/Excel 打开会**自动重算出正确合计**，但 `read` 快照的 `v` 是旧缓存、不自动刷新，别拿它当最新值（见 [[references/ops-sheet.md]]）。
3. **判成败看 code，改正文还要看 `applied`**——HTTP 200 也可能是失败（`code` 非 `"0000"`）。op 名打错时：**sheet/多维表格直接 `code:"0001"`**；`doc`/`pptx`/`html` 则是 `code:"0000"` + `applied:0` + `skipped:1`（静默跳过、一个字没改），**同样判写失败**。判据与处置见「⛔ op 指令三条铁律」。
4. **AK 绑定 user，须是 space 成员**——拿某 AK 读页面报 `page not found`/`4030`，多半是该 AK 的 user 不在目标 space，换绑定到该 space 成员 user 的 AK。
5. **二进制导入只传 fileKey 不解析**——`/import` 深度解析（pptx/docx/xlsx/xls）只看 `content`（base64 字节），`fileKey` 仅原件永存。只传 `fileKey` 会返回 `parsed:true` 之外的 `parsed:false` + 占位正文，读回 slides/snapshot 为空。要保真导入务必同时传 `content`。
6. **replaceWorkbook（sheet/多维表格）每张 sheet 必须给完整结构**——缺 `freeze`/`rowHeader`/`columnHeader`/`rowData`/`columnData` 任一，API 写入和 `read` 读回**全都正常**，但前端打开时协同重放失败：轻则静默空表/「没有数据表」，重则弹**「该范围已被保护，目前无编辑权限。如需编辑，请联系创建者。」**（表插不进去 → 权限点没注册 → 写命令被判无权限的连带现象，**不是真被保护**，别去找创建者要权限）。与 sheet 张数无关，单表缺结构一样坏。照抄 [[references/ops-sheet.md]] §3 完整骨架；坏表修复 = 再发一次结构齐全的 replaceWorkbook（原地恢复，slugId 不变）。另：setCellValues 会按落格的最大行列**自动扩 `rowCount`/`columnCount`**（只增不减），已有内容的表一律增量写，见「⛔ 覆盖写禁令」。播种后自检清单见 [[references/ops-datatable.md]] §9.12。**replaceWorkbook 是覆盖写不是更新**——它先删表再按你的快照插入，`read` 之后用户改的内容会被静默打回，且接口不报错。**反向提醒**：从零创建**不需要** replaceWorkbook——建页后默认表 `sheet-1`（20×20，结构完整）已就位，直接 setCellValues 播种即可，超 20 行会自动扩容（前端硬上限：字段 60 列 / 记录 1000 行）。工作表结构键名只用 `rowData`/`columnData`/`cellData`/`freeze`，`rowDefinitions`/`columnDefinitions` 这类自造名会直接触发上面的「该范围已被保护」弹框。

7. **凭据只在 curl 头里用变量引用，绝不分段回显**——`-H "Authorization: Bearer $SANDBOX_ACCESS_KEY"`；**不许 echo 出前 20 位再自己补全**（编造补齐不完整信息），要么整取、要么全程变量。**绝不把 AK/ticket 写进 HTML、文档正文或交付说明**。**本技能的凭据面只有 `SANDBOX_ACCESS_KEY`（分享场景另有 `shareToken`），到此为止**：⛔ **技能不需要 AK 以外的任何密钥**——不要去查找、索取或使用任何服务端内部密钥或内部接口凭据；那类东西是服务端组件之间互相认证用的，客户端持有并使用属于**越权**，技能文档里也**不列举**它们。**任何「某能力需要服务端密钥才能验证/调用」的说法都是错的**：所有能力都有 AK 通道，`4030`/`4000`/`4040` 是「AK 与该环境不匹配」的配置问题，换 AK 即可，**不是绕过鉴权**。**不声称未经验证的事**：说「已生效」前必须有证据（`applied>0`、活体测试、`contentSize` 变化）；查不到规范就停下来问，别用「看起来能跑」的降级方案糊过去。
8. **不许随意删页面（最高优先级）**——**只有用户明确点名要删哪个页面，才能调 `/pages/{pageId}/delete`**；不限于透视页，`doc`/`sheet`/`pptx`/`datatable`/文件夹全适用，**包括你自己先前建的**。改版 / 清理 / 去重 / 整理 / 觉得多余 / 「重新导一个更干净的」**都不是理由**。软删不可恢复，且会连带废掉侧边栏、互链、收藏、分享链接、评论锚点、版本历史。要「改」走非破坏性路径（改数据不用动页 / `rename` / `move` / `copy` / **新建并排保留旧页**）；拿不准**默认不删、去问**。详见上方「⛔ 删除禁令」。

## 完整示例：写一篇文档并导出 docx

```bash
AUTH="Authorization: Bearer $SANDBOX_ACCESS_KEY"
BASE="$PLATFORM_BASE_URL/api/v1/4sandbox/repo"

# 1. 拿 spaceId（默认个人空间：找 type:"Personal" 的项取 id）
curl -s "$BASE/spaces" -H "$AUTH"                      # → 个人空间 id（type:"Personal"）

# 2. 导入（一步建页 + 播种正文 + 快照）
curl -s -X POST "$BASE/import" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"spaceId":57,"fileName":"周报.md","text":"# 本周周报\n\n## 进展\n完成了 A、B、C 三件事。"}'
# → data.pageId / data.slugId

# 3. 读回确认
curl -s "$BASE/pages/<pageId>/content" -H "$AUTH"

# 4. 追加一段
curl -s -X POST "$BASE/pages/<pageId>/content" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"ops":[{"op":"appendBlocks","blocks":[{"type":"paragraph","content":[{"type":"text","text":"补充：还有 D。"}]}]}]}'

# 5. 导出
curl -s -o 周报.docx "$BASE/pages/<pageId>/export?format=docx" -H "$AUTH"
```

端点级精确格式、全量字段、完整错误码见 [[references/api.md]]；各文档类型的元素编辑参数见 [[references/ops-doc.md]] / [[references/ops-pptx.md]] / [[references/ops-sheet.md]] / [[references/ops-datatable.md]]；透视页（多维表格 HTML 看板）生成见 [[references/pivot-page.md]]；文件上传拿 fileKey 见 [[references/file-upload.md]]。

## 协作与管理操作（目录 / 评论 / 分享 / 版本 / 附件 / 权限）

除上面 13 个「上传/导入/导出/增删改查」端点外，沙箱 API 还开放**协作与管理类**端点（前缀同为 `/api/v1/4sandbox/repo`，全部 GET/POST，标准接口的 PUT/DELETE 已 POST 化）。做这些操作时读 [[references/operations.md]]：

| 分类 | 端点要点 |
|---|---|
| 目录管理 | `GET /pages/{id}/children`、`POST /pages/{id}/move`、`POST /pages/{id}/copy`；建文件夹用 `POST /pages` 传 `pageType:"folder"` |
| 评论 | `GET/POST /pages/{id}/comments`、`POST /comments/{cid}/resolve`、`POST /comments/{cid}/delete` |
| 分享 | `GET /pages/{id}/shares`、`POST /pages/{id}/shares/spaces` / `shares/users` / `shares/links`、`POST /pages/{id}/shares/unshare-all`、`GET /spaces/{sid}/shares/received` / `shares/sent`、`GET /pages/{id}/share-links/{lid}/stats` |
| 版本 | `GET /pages/{id}/versions`、`GET /pages/{id}/versions/{vid}/preview`、`POST /pages/{id}/versions/{vid}/restore`、`POST /pages/{id}/versions/manual` |
| 附件 | `GET/POST /pages/{id}/attachments`、`GET/POST /spaces/{sid}/attachments`、`POST /attachments/{aid}/delete` |
| 权限 | `GET/POST /pages/{id}/acl`（文档级按人权限） |

**给用户的链接：相对路径 + 包成 Markdown 链接，别写 `$PLATFORM_BASE_URL`**（那个 env 变量只在 curl 命令里当 API 基址用）。关键：链接必须渲染成 `[标题](/repo/doc/{slugId})` 的超链接形态，**不要只给 `/repo/doc/{slugId}` 裸文本**——裸文本点不开、复制不动；包成 Markdown 链接后在门户/聊天里点击即跳转（相对路径自动补全到当前环境域名）。

- 文档地址：`[标题](/repo/doc/{slugId})`（拿 `slugId` 后拼）；分享链接：`[标题](/repo/share/{token})`（token 由 `POST /pages/{id}/shares/links` 返回）；空间主页：`[标题](/repo/space/{spaceId})`；门户：`[门户](/repo/)`。
- **多维表格交付/嵌入**：链接就是干净的 `[标题](/repo/doc/{slugId})`，**页面各区域照常展示，不要为「隐藏某块界面」往链接上挂参数**。除用户或宿主页面明确要求的展示参数外，交付链接一律不加参数——它是用户长期持有、到处转发的入口，多一个参数就多一处「对方看到的和我看到的不一样」。
- 正文内引用其他文档（索引/目录/超链接）也用相对路径 `/repo/doc/{slugId}`，**不要带域名前缀**——例如 `[接口规范说明](/repo/doc/uJszfxZFdHE6weUJ)`。相对路径在任何环境打开都能正确跳转；带域名的绝对路径换环境就失效。

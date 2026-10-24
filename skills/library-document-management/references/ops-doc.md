# doc 富文本编辑参考（指令 + 节点/mark JSON）

> **何时读**：给 `doc` 类型文档（`pageType:"doc"` 且 `sourceExt` 非 `pptx`；即 Word/Markdown/HTML/纯文本/导入的 docx 页）做内容读写时。pptx 幻灯片看 [[ops-pptx.md]]，表格看 [[ops-sheet.md]]。

> ⛔ **改 doc 正文就用下面这 6 个指令，别拿 HTML 页的 `replaceText` 当偏方。**
> `replaceText`/`replaceHtml` 是 **HTML 页（`kind:"html"`）专有**；`replaceDoc`/`setText` 这类名字**任何类型都不存在**。
> **op 名打错不会有任何报错**——服务端对未知指令**静默跳过**，响应仍是 `code:"0000"`，只有 `applied:0` + `skipped:1` 暴露它，正文一个字节都不变。
> 所以「改错字 / 改原段落」的做法是 **`read` 读回数下标 → `replaceBlocks` 替换原块**（或 `insertBlocks` 在原句后就地补更正），**改得回原段落**——「只能追加『更正上文』」是误判，别据此放弃原地改。

doc 正文是 **ProseMirror JSON**：顶层 `{"type":"doc","content":[块...]}`。块是顶层 `content` 数组的元素，行内节点/文字嵌在块的 `content` 里，行内样式用 `marks` 标记。

## 1. 指令总览（6 个）

| op | 参数（`?` 可选） | 语义 |
|---|---|---|
| `appendBlocks` | `blocks`：块数组 | 追加到文档末尾（增量，最常用） |
| `replaceContent` | `content`：完整 doc 节点 | 整体替换全文（先清空再写，会丢并发） |
| `replaceBlocks` | `index`、`blocks`、`count?`(默认=blocks 长度) | 把 `[index, index+count)` 替换为 blocks |
| `insertBlocks` | `index`、`blocks` | 在 index 处插入（index 可 = 块总数=末尾） |
| `deleteBlocks` | `index`、`count?`(默认 1) | 删除 `[index, index+count)` |
| `read` | 无 | 读回最新 doc（无副作用，可与写指令同批） |

- 块下标是顶层 `content` 数组位置（0 起）。
- **定点改**：先 `{"op":"read"}` 读回全文数清下标，再 `replaceBlocks`/`insertBlocks`/`deleteBlocks`。
- 未知节点类型/非法结构 → 整批报错，返回 `code "0001"`（HTTP 状态仍是 200），message 带节点名，如 `appendBlocks: invalid blocks (Unknown node type: xxx)`。
- **判成败看 `applied`**：`applied > 0` 才算写成功；**`applied:0` 且 `skipped>0` = 整批被跳过（op 名不存在 / 结构非法）= 写失败**，核对 op 名与 `kind` 后重发，**别当成功汇报**。
- **写完必 `read` 读回**（可与写指令同批下发）确认目标块真的变了；只凭 `code:"0000"` 就断言「已生效」不算数。

## 2. 块级节点 JSON

### 2.1 段落 paragraph

```json
{"type":"paragraph","content":[{"type":"text","text":"一段文字"}]}
```

### 2.2 标题 heading（1-6 级）

```json
{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"一级标题"}]}
```

`attrs.level` 取 1~6，对应 H1~H6。

### 2.3 无序列表 bulletList / 有序列表 orderedList

```json
{"type":"bulletList","content":[
  {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"项一"}]}]},
  {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"项二"}]}]}
]}
```

有序列表把 `bulletList` 换成 `orderedList`，可带起始值 `{"type":"orderedList","attrs":{"start":1},...}`。每个 `listItem` 内是段落（可嵌多段/嵌套列表）。

### 2.4 引用 blockquote

```json
{"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","text":"引用内容"}]}]}
```

### 2.5 代码块 codeBlock（带语言高亮）

```json
{"type":"codeBlock","attrs":{"language":"java"},"content":[{"type":"text","text":"System.out.println(1);"}]}
```

`attrs.language` 传语言 id（`java`/`json`/`xml`/`javascript`/`python`/`sql`/`bash` 等），省略或 `null` 为纯文本块。

### 2.6 分割线 horizontalRule

```json
{"type":"horizontalRule"}
```

### 2.7 表格 table（含表头/单元格）

```json
{"type":"table","content":[
  {"type":"tableRow","content":[
    {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"姓名"}]}]},
    {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"年龄"}]}]}
  ]},
  {"type":"tableRow","content":[
    {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"张三"}]}]},
    {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"30"}]}]}
  ]}
]}
```

- 首行用 `tableHeader`（加粗表头），后续行用 `tableCell`。每格 `content` 是段落数组。
- `tableHeader`/`tableCell` 可选 `attrs`：`colspan`（列合并）、`rowspan`（行合并）、`colwidth`（列宽 px 数组，如 `[200,300]`）。

### 2.8 流程图 flowDiagram

```json
{"type":"flowDiagram","attrs":{"data":{"nodes":[{"id":"n1","type":"rect","x":100,"y":100,"text":{"value":"开始"}}],"edges":[]}}}
```

`attrs.data` 是 LogicFlow 的 graphData（`nodes`/`edges`）。块级原子节点，双击进流程图编辑器修改；AI 场景通常是读出来后整块替换或删除。

### 2.9 思维导图 mindMap

```json
{"type":"mindMap","attrs":{"data":{
  "nodeData":{"id":"mm_root","topic":"产品规划","children":[
    {"id":"mm_a","topic":"目标","children":[
      {"id":"mm_a1","topic":"GMV 翻倍"},
      {"id":"mm_a2","topic":"用户 10 万"}
    ]},
    {"id":"mm_b","topic":"打法","children":[
      {"id":"mm_b1","topic":"渠道扩张"}
    ]}
  ]},
  "direction":2,
  "compact":false
}}}
```

`attrs.data` 是 mind-elixir 同构的整图数据，两部分：

| 键 | 类型 | 说明 |
|---|---|---|
| `nodeData` | 节点树（根） | 递归结构，见下表 |
| `direction` | `0`/`1`/`2`/`3` | 整图布局方向：0 左 / 1 右 / 2 两侧（默认推荐）/ 3 下 |
| `compact` | boolean? | 紧凑模式（节点间距更小），缺省 false |

节点树每个节点：

| 键 | 类型 | 说明 |
|---|---|---|
| `topic` | string（必填） | 节点标题文字 |
| `id` | string（**必填**） | 全图唯一字符串 id（如 `mm_a1`）；前端只兜底根节点 id，**子节点缺 id 会导致折叠/编辑异常，务必逐个给** |
| `children` | 节点数组? | 子节点（递归同构）；叶子可省略 |
| `expanded` | boolean? | `false` = 初始收起该节点的子级（前端显示「+N」折叠角标，N=全部子孙数） |
| `direction` | `0`/`1`? | 该节点子级展开方向（0 左 / 1 右），缺省随整图 |
| `hyperLink` | string? | 节点挂链接 |
| `style` | object? | 节点文字样式（颜色/字号等，mind-elixir 风格），一般不需要 |

与流程图同为**块级原子节点**：`read` 读回 `attrs.data` 整树；修改用 `replaceBlocks` 整块替换（先 read 数清下标）；根节点若无子节点，前端会补一个「双击编辑」占位子节点——AI 播种时应直接给全真实子树，别留空根。

**沙箱 API 导入 Markdown 不会自动转图形节点**：用户在前端页面上传 md 时，`mermaid` 围栏代码块（`mindmap`/`flowchart`）会自动转成可编辑思维导图/流程图；但沙箱 `POST /import` 走 Sidecar 解析，**没有这两个转换**——mermaid 代码块只会呈现为普通代码块。所以 AI 要在文档里放思维导图/流程图，用 ops 指令直接写 `mindMap`/`flowDiagram` 节点 JSON（本节与 2.8），别指望 `/import` 转。

## 3. 行内节点 JSON（必须嵌在块内）

### 3.1 图片 image

```json
{"type":"paragraph","content":[{"type":"image","attrs":{"src":"https://example.com/a.png","alt":"描述","width":300}}]}
```

- `src`：远程 URL 或 base64 data URL；`alt` 无障碍描述；`title` 悬停提示；`width` 显示宽度（px，缺省原图宽，前端另有 `max-width:100%` 硬上限）。
- **给正文配图**：本地图片先用 `POST /api/v1/4sandbox/file/upload` 上传，取返回 `data.url`（带 `?ak=` 直访地址）填 `src`，前端即可直接加载；**不要 base64 内嵌大图**（会把请求体撑爆）。示例见 [[file-upload.md]]。
- image 是**行内**节点，必须包在块（如 `paragraph`）内，不能直接放顶层 `content`。

### 3.2 附件 attachment

```json
{"type":"paragraph","content":[{"type":"attachment","attrs":{"fileKey":"","fileName":"报告.pdf","size":102400,"mime":"application/pdf","url":"https://example.com/报告.pdf"}}]}
```

- `fileName`（展示名，必填）、`url`（下载地址）、`mime`（MIME 类型）、`size`（字节数）、`fileKey`（平台文件 key，无则留空串）。
- 也是行内节点，嵌在段落内。

### 3.3 @提及 mention

```json
{"type":"paragraph","content":[{"type":"text","text":"请 "},{"type":"mention","attrs":{"id":"694515","label":"杨鹏"}},"确认"}]}
```

- `id` 是用户 accountId（数字字符串），`label` 是显示名。用户列表查 `GET /api/space/user/list`（前端页面内能力，sandbox API 未直接暴露）。

### 3.4 硬换行 hardBreak

```json
{"type":"paragraph","content":[{"type":"text","text":"第一行"},{"type":"hardBreak"},{"type":"text","text":"第二行"}]}
```

## 4. 行内样式 marks

marks 挂在 text 节点上，`text` 拆成多段各自带 mark。**doc 富文本没有字号/字体族 mark**（那些是 pptx 元素级样式），可用的只有下面这些：

| mark | 写法 | 说明 |
|---|---|---|
| 加粗 | `{"type":"text","text":"x","marks":[{"type":"bold"}]}` | |
| 斜体 | `[{"type":"italic"}]` | |
| 删除线 | `[{"type":"strike"}]` | |
| 行内代码 | `[{"type":"code"}]` | 等宽字体 |
| 链接 | `[{"type":"link","attrs":{"href":"https://...","target":"_blank"}}]` | `target` 省略默认当前页 |
| 文字颜色 | `[{"type":"textStyle","attrs":{"color":"#FF0000"}}]` | color 为 CSS 颜色 |
| 高亮 | `[{"type":"highlight","attrs":{"color":"#FFFF00"}}]` | color 为背景色 |

组合示例（加粗 + 红色）：

```json
{"type":"text","text":"重点","marks":[{"type":"bold"},{"type":"textStyle","attrs":{"color":"#FF0000"}}]}
```

## 5. 工作流

**批量写一篇结构化文档**（推荐）：直接用 `/import` 一步导入 Markdown（自动解析成标题/列表/段落/代码块等），或对空页用 `replaceContent` 一次性给整篇 doc JSON。

**定点修改**：

1. `POST /content` 发 `{"op":"read"}` 拿回 `data.doc`（顶层 `content` 数组）。
2. 数清目标块在 `content` 里的下标（0 起）。
3. `replaceBlocks`（改/替换）、`insertBlocks`（插入）、`deleteBlocks`（删除）定位操作。
4. 同批带 `read` 可立即拿回修改后的 doc 校验。

```bash
# 在第 0 块前插入一个标题
curl -s -X POST "$BASE/pages/<pageId>/content" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"ops":[{"op":"insertBlocks","index":0,"blocks":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"新标题"}]}]},{"op":"read"}]}'
```

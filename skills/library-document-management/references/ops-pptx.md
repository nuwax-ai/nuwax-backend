# pptx 幻灯片编辑参考（指令 + 元素字段全集）

> **何时读**：给 `pptx` 类型文档（`sourceExt:"pptx"`，注意其 `pageType` 是 `doc`）做幻灯片读写时。富文本看 [[ops-doc.md]]，表格看 [[ops-sheet.md]]。

> ⛔ **写指令通用铁律**：op 名只能从下面这 9 个里抄；**名字打错不会报错**——未知指令被服务端**静默跳过**（`code:"0000"` + `applied:0` + `skipped:1`，内容一个字不改）。**判成败看 `applied > 0`**，写完同批带 `read` 读回。详见 SKILL.md「⛔ op 指令三条铁律」。

幻灯片真源是 `slidesModel`：每页 `{id,width,height,background,note,_v,elements:[...]}`，元素 `elements` 数组内每个元素是一个对象。`index`（第几页）、`elementIndex`（页内第几个元素）均 **0 起**；`left/top/width/height` 是**画布占比 0~1**（相对页宽高，不是像素）。

## 1. 指令总览（9 个）

| op | 参数（`?` 可选） | 语义 |
|---|---|---|
| `addSlide` | `index?`(默认末尾)、`title?`、`background?`、`width?`、`height?`(默认 960×540) | 新增一页（`title` 非空会自动加居中标题框） |
| `setSlideTitle` | `index`、`text` | 改页标题（首个 text 元素；无则新建 32pt 加粗居中标题框） |
| `addTextElement` | `index`、`text`、`left?/top?/width?/height?`(.1/.1/.8/.2)、`fontSizePt?`(默认 18)、`color?`、`bold?`、`italic?`、`align?`、`rotation?`、`background?` | 页内追加文本框 |
| `deleteSlide` | `index` | 删除一页 |
| `setElementText` | `index`、`elementIndex`、`text` | 改某元素文字 |
| `setElementStyle` | `index`、`elementIndex` + 样式字段（见 §3） | 改元素几何/样式 |
| `deleteElement` | `index`、`elementIndex` | 删除元素 |
| `insertElement` | `index`、`element`(完整元素 JSON)、`elementIndex?`(默认页末尾) | 插入元素（`element.type` 限 5 类，见 §2） |
| `read` | 无 | 读回整份 slides 模型 |

## 2. 元素类型与字段

`insertElement` 的 `element.type` **只支持 5 类**：`text`（文本框）、`shape`（图形）、`image`（图片）、`media`（音视频）、`formula`（公式）。`table`（表格）/`chart`（图表）/`draw`（绘图）/`board`（白板）结构复杂，AI 编辑端点**不支持**，传了会返回 `code "0001"`（HTTP 仍 200），message 为 `insertElement: element.type "table" not supported (use text/shape/image/media/formula)`——这些元素需在前端画布里手动创建。

> 通用技巧：`read` 读出的元素 JSON 改几字段后，可原样回传 `insertElement` 重建；也可以 `deleteElement` 删旧 + `insertElement` 插新来「换形状/换类型」。

### 2.1 通用字段（5 类元素都有）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `id` | str | 自动生成 | 元素唯一 id（可省略） |
| `type` | str | 必填 | `text/shape/image/media/formula` |
| `left` `top` `width` `height` | num | .1/.1/.4/.2 | 位置尺寸，画布占比 0~1 |
| `rotation` | num | 0 | 旋转角（度） |
| `opacity` | num | 1 | 不透明度 0~1 |
| `background` | str | `""` | 填充色（CSS 颜色，`""`=透明） |
| `radius` | num | 0 | 圆角（roundRect 用，0~0.5） |
| `shape` | str | `rect` | 几何形状（枚举见下） |
| `pathD` | str | `""` | `shape:"path"` 时的路径 |
| `autofit` | str | `none` | `spAutoFit/normAutofit/none` |
| `fontScalePct` | num | 0 | normAutofit 缩放比 |
| `borderColor` | str | `""` | 边框颜色（`""`=无边框） |
| `borderWidth` | num | 0 | 边框粗细（0=无边框） |
| `lineType` | str | `seg` | 仅 `shape:"line"` 用（枚举见下） |
| `flipH` `flipV` | bool | false | 水平/垂直翻转 |
| `textWrap` | bool | true | 文字换行 |
| `highlight` | str | `""` | 文字背景高亮色（CSS 颜色，`""`=无） |
| `reflection` | bool | false | 倒影 |
| `shadow` | obj/null | null | 阴影 `{x,y,blur,color,opacity}` |

**`shape` 枚举**：`rect` `roundRect` `ellipse` `line` `triangle` `diamond` `parallelogram` `trapezoid` `pentagon` `hexagon` `star` `heart` `cloud` `cylinder` `cube` `arrowLeft` `arrowRight` `arrowUp` `arrowDown` `arrowLeftRight` `bracketLeft` `bracketRight` `flowProcess` `flowDecision` `flowTerminator` `flowData` `path`。

**`lineType` 枚举**（`shape:"line"` 时）：`seg`（线段）/ `arrow`（箭头）/ `elbow`（直角拐角）/ `callout`（标注）/ `arrow1`（单箭头标注）/ `arrow2`（双箭头标注）。

### 2.2 文本/形状专属（text、shape）

在通用字段基础上，`text` 和 `shape` 额外支持文字样式：

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `text` | str | `""` | 正文（`\n` 分段） |
| `fontSizePt` | num | 18 | 字号（pt） |
| `color` | str | `""` | 文字颜色 |
| `bold` `italic` | bool | false | 加粗/斜体 |
| `fontFamily` | str | `""` | 字体族（如「思源黑体」「微软雅黑」） |
| `align` | str | text=`l` / shape=`ctr` | `l/ctr/r/j`（左/中/右/两端） |
| `vAlign` | str | `""` | 垂直对齐 `t/ctr/b`（顶/中/底） |
| `lineHeight` | num | 0 | 行距倍数（0=默认） |
| `spaceBefore` `spaceAfter` | num | 0 | 段前/段后间距（pt） |

`shape` 与 `text` 的区别仅在默认对齐（形状默认居中）与可配 `shape`/`lineType` 几何；要「形状里带文字」就用 `type:"shape"` + `text`。

### 2.3 图片专属（image）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `src` | str | `""` | 图片地址（远程 URL 或 base64 data URL） |
| `fit` | str | `""` | `fill`（拉伸铺满）/ `contain`（等比缩放） |

```json
{"op":"insertElement","index":0,"element":{"type":"image","left":0.6,"top":0.6,"width":0.3,"height":0.3,"src":"https://example.com/a.png","fit":"contain"}}
```

### 2.4 音视频专属（media）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `src` | str | `""` | 媒体地址 |
| `mediaKind` | str | `video` | `video` / `audio` |

### 2.5 公式专属（formula）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `formula` | str | `""` | LaTeX 源码 |
| `fontSizePt` `color` `bold` `italic` `fontFamily` | — | — | 公式外观，同文本框 |

```json
{"op":"insertElement","index":0,"element":{"type":"formula","left":0.3,"top":0.4,"width":0.4,"height":0.2,"formula":"E=mc^2","fontSizePt":24}}
```

## 3. `setElementStyle` 字段白名单（定点改样式）

只传要改的字段，不传的字段不动。字段按类型分三组：

**数字**：`left` `top` `width` `height` `rotation` `opacity` `radius` `borderWidth` `textMargin` `fontSizePt` `lineHeight` `spaceBefore` `spaceAfter`

**布尔**：`bold` `italic` `flipH` `flipV` `reflection` `textWrap`

**字符串**：`background` `borderColor` `lineType` `color` `fontFamily` `align` `vAlign` `highlight`

- `align` 必须是 `l/ctr/r/j`；`vAlign` 是 `t/ctr/b`。
- `highlight` 是**字符串**（高亮背景色），不是布尔。
- **不在白名单的字段**（`shape`、`fit`、`mediaKind`、`src`、`formula`、`shadow`、`autofit` 等）不能通过 `setElementStyle` 改——换形状/类型用 `deleteElement`+`insertElement` 重建。混合传时非白名单字段被**静默忽略**、白名单字段照常生效；**只传非白名单字段（一个白名单字段都没传）会返回 `code "0001"` `setElementStyle: no style field provided`**。

```bash
# 第 0 页第 1 个元素：改字号 28 + 加粗 + 红色
curl -s -X POST "$BASE/pages/<pageId>/content" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"ops":[{"op":"setElementStyle","index":0,"elementIndex":1,"fontSizePt":28,"bold":true,"color":"#FF0000"}]}'
```

## 4. 工作流

**从零造一页 PPT**：

```bash
# 1. 加封面页（带标题）
# 2. 加正文页 + 文本框
curl -s -X POST "$BASE/pages/<pageId>/content" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"ops":[
    {"op":"addSlide","title":"封面标题","background":"#FFFFFF"},
    {"op":"addSlide","title":"第二页"},
    {"op":"addTextElement","index":1,"text":"正文内容","top":0.4,"fontSizePt":20}
  ]}'
```

**精修某元素**：

1. `{"op":"read"}` 读回 `data.slides`，定位页 `index` 和元素 `elementIndex`。
2. `setElementText` 改文字、`setElementStyle` 改样式、`insertElement` 加元素、`deleteElement` 删元素。
3. 同批带 `read` 校验结果。

> **持久化**：pptx 的 AI 编辑会正常落库（早期 Sidecar 语义去重 bug 已修复），改完换个请求 `read` 或 `export?format=pptx` 都能拿到新值。

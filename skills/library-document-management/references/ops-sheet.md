# sheet 表格编辑参考（指令 + 读/导出）

> **何时读**：给 `sheet` 类型文档（`pageType:"sheet"`；Excel/CSV 导入或新建的表格页）做读写时。富文本看 [[ops-doc.md]]，幻灯片看 [[ops-pptx.md]]。

> ⛔ **写指令通用铁律**：op 名只能从 `setCellValues` / `replaceWorkbook` / `insertRow` / `removeRow` / `read` 里抄；**名字打错会直接报 `code:"0001"`**（message 列出允许的 op），不会再静默跳过。**判成败看 `applied > 0`**，写完必读回（`read` 或 `dt_rows.js` 对账）。详见 SKILL.md「⛔ op 指令三条铁律」。

表格内容纯 API 随时可读可写可导出，**不依赖前端**。读走「操作日志重放」实时还原当前表，写走「mutation 追加」，写后立即可读、可导出。

## 0. 关键约定：带 `kind` 显式声明类型

读写 sheet 用 `POST /pages/{pageId}/content`。服务端已按页面类型显式映射 kind（`pageType:"sheet"` → `kind:"sheet"`），所以**不传 `kind` 也不会误判**；但 body 里**显式带 `kind:"sheet"`** 仍是等价且更保险的写法（不依赖页面元数据、语义自明）。全新空表（从未写过内容）同样能正确读写。

```bash
AUTH="Authorization: Bearer $SANDBOX_ACCESS_KEY"
BASE="$PLATFORM_BASE_URL/api/v1/4sandbox/repo"

# 写值（带 kind）
curl -s -X POST "$BASE/pages/<pageId>/content" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"kind":"sheet","ops":[{"op":"setCellValues","cells":[{"row":0,"col":0,"value":"姓名"}]}]}'

# 读回（带 kind）
curl -s -X POST "$BASE/pages/<pageId>/content" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"kind":"sheet","ops":[{"op":"read"}]}'
```

## 1. 指令总览（5 个）

| op | 参数（`?` 可选） | 语义 |
|---|---|---|
| `setCellValues` | `sheetId?`(默认第一个 sheet)、`cells:[{row,col,value?,custom?,style?}]` | 定点批量写单元格值/公式/样式 |
| `replaceWorkbook` | `workbook`(完整表格 JSON) | ⚠️ **覆盖写**：删掉现有 sheet 后按快照重插。**仅多工作表/合并单元格等结构变更 / 结构修复 / 用户明确要求整表重建**时用（样式、扩行扩列都不需要）；已有内容的表日常改值一律 `setCellValues`（见 SKILL.md「⛔ 覆盖写禁令」） |
| `insertRow` | `sheetId?`、`row`、`rowCount?`(默认1) | 在第 `row` 行之前插入空行，原行起整体下移 |
| `removeRow` | `sheetId?`、`row`、`rowCount?`(默认1) | 删除第 `row` 行，下方整体上移 |
| `read` | 无 | 读回当前表格快照 `snapshot`（JSON，随时可用） |

- **op 名打错会直接报 `code:"0001"`**（message 列出允许的 op），不会静默跳过。
- `row`/`col` 是 **0 起非负整数**（A1 = row 0, col 0）。
- 读回结果在 `data.snapshot`，结构：`snapshot.sheetOrder` 是工作表 id 顺序，`snapshot.sheets[id].name` 表名，`snapshot.sheets[id].cellData[row][col]` 是单元格 `{v:值, f:公式, s:样式id, t:值类型}`（`t` 只有**经前端编辑器改过**的格才带，纯 API 写入读回不带，详见 §2）。

## 2. setCellValues：定点写值 / 公式

| 字段 | 类型 | 说明 |
|---|---|---|
| `sheetId` | string? | 目标工作表 id，缺省用第一个 sheet（`snapshot.sheetOrder[0]`）；**显式指定时必须存在**，否则返回 `code:"0001"` 并回显可用 id（不会静默成功） |
| `cells` | array | 单元格数组，每项 `{row, col, value?, custom?, style?}` |
| `cells[].row` | number | 行号，0 起非负整数 |
| `cells[].col` | number | 列号，0 起非负整数 |
| `cells[].value` | string/number/boolean/null / `{v,f,style?}` | 写入值；`null` 清空该格；对象形态可写/清公式与样式（见下） |
| `cells[].style` / `cells[].s` | object / string / null | 单元格样式；inline 对象或 workbook 样式 id；`null` 只清样式。与 `value` 平级，二者可同给 |

`cells[].value` 的形态：

| value 形态 | 示例 | 效果 |
|---|---|---|
| 纯值 `string/number/boolean` | `"张三"`、`150` | 写值，**不动该格原有公式与样式**（改明细格不会误伤公式格） |
| `null` | `null` | 清空整格（值/公式/样式） |
| 对象 `{v, f}` | `{"v":450,"f":"=A2*B2"}` | 显式控制：`v`=结果值，`f`=公式字符串（带 `=` 前缀）；`f:null` 清公式留纯值 |
| 缺省（只给 `style`/`custom`） | `{"row":1,"col":2,"style":{"n":{"pattern":"yyyy-mm-dd"}}}` | 只改样式/custom，**保留该格原有 v/f** |

### 写样式（日期/数字格式、底色、字体等）

`style` 直接写 Univer `IStyleData` 的键，**按 key 合并进该格原有样式**（不会冲掉其它样式）。白名单：
`ff`(字体) / `fs`(字号) / `it`(斜体) / `bl`(加粗) / `ul`·`bbl`·`st`·`ol`(下划线/删除线等) / `bg`(底色) / `bd`(边框) / `cl`(字色) / `va`(上下标) / `n`(数字格式) / `tr`(旋转) / `ht`(水平对齐) / `vt`(垂直对齐) / `tb`(换行) / `pd`(内边距)。

常用写法：

```jsonc
// 日期：value 是 Excel 日期序列号（如 46350），靠格式显示成日期
{ "row": 1, "col": 2, "value": 46350, "style": { "n": { "pattern": "yyyy-mm-dd" } } }
// 数字格式
{ "row": 1, "col": 3, "value": 1234.5, "style": { "n": { "pattern": "#,##0.00" } } }
// 只改样式、不动值（把已有的日期格补上格式）
{ "row": 1, "col": 2, "style": { "n": { "pattern": "yyyy-mm-dd" } } }
// 底色 / 加粗
{ "row": 1, "col": 0, "style": { "bg": { "rgb": "#FFFF00" }, "bl": 1 } }
// 清样式（保留值）
{ "row": 1, "col": 2, "style": null }
```

- `style` 与 `value.style` 二选一，同时给会报错（`code:"0001"`）；`cells[].style` 与 `cells[].s` 也只能二选一。
- 样式 key 写错会报错（`code:"0001"`，不会静默忽略）——比如写成 `numFmt` 会提示 allowed 列表，应改成 `n: { pattern }`。
- **样式值也会做结构校验**，类型不符/越界枚举/未知子键一律报错（`code:"0001"`）：`ff` 非空 ≤256 字节；`fs` `0<fs≤1000`；`it`/`bl` 为 `0|1`；`bg`/`cl` 为 `{rgb:"#RRGGBB"|rgb()|rgba(), th?:0..11}`；`n:{pattern:string}`；`bd` 边键仅 `t/r/b/l/tl_br/tl_bc/tl_mr/bl_tr/ml_tr/bc_tr`，边值 `{s:0..13,cl?}`；`ht 0..6`、`vt 0..3`、`tb 0..3`、`td 0..2`、`va 1..3`、`tr {a:-360..360}`、`pd` 四边 `0..1000`。
- **样式 id 必须存在**：传字符串时必须是 workbook `styles` 里已有的 id，悬空引用会报错（`code:"0001"`）。
- **一批 ops 原子生效**：同一请求任一 op 失败，整批回滚，不会留半批写入。
- 写 **date/createdTime/modifiedTime 等多维表格字段**时，展示串（`"2026-09-30"`）与「数字 + `n.pattern`」两种写法都行（DataTableGrid 实测都能正确显示日期，详见 [[ops-datatable.md]] §1）。

```bash
curl -s -X POST "$BASE/pages/<pageId>/content" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"kind":"sheet","ops":[{"op":"setCellValues","cells":[
    {"row":0,"col":0,"value":"姓名"},{"row":0,"col":1,"value":"年龄"},
    {"row":1,"col":0,"value":"张三"},{"row":1,"col":1,"value":30},
    {"row":1,"col":2,"value":{"v":90,"f":"=A2*B2"}}
  ]}]}'
```

「把某格改成 X」的流程：先 `read` 拿到 `snapshot`，定位目标单元格的 `row`/`col`（非第一个 sheet 记得带 `sheetId`），再 `setCellValues` 覆盖。**只写这一格（或这一批目标格）；不要把整份 snapshot 用 `replaceWorkbook` 写回**——那是覆盖写（见 §3 警告）。

**setCellValues 会自动扩存储范围**：写值前会按本次落格的最大 `row`/`col` 追加 `set-worksheet-row-count` / `set-worksheet-column-count`（绝对值、只增不减），所以写第 48 行、第 25 列不会再「存得进、看不见」。**不需要为了扩行扩列去 `replaceWorkbook`**；已有内容的表一律增量写（§3、SKILL.md「⛔ 覆盖写禁令」）。只有多工作表、合并单元格、行列结构等 `setCellValues` 表达不了的结构变更才考虑整表重建。

### 改带公式的表格：展示层自动重算，但 API 读回快照的 `v` 不刷新

表里若有公式列（如金额 `=E8*F8`、小计 `=SUM(...)`、合计），分两层看，别混：

1. **展示层会自动重算**：前端打开着（Univer 公式引擎）、或用 Excel/WPS 打开导出的 xlsx，公式格会按公式重算出正确值。所以「只改明细格、合计自动跟着变」在**给用户看的最终结果上成立**——这正是公式化的意义（价格变了合计同步变，不写死数值）。
2. **API 读回快照的 `v` 不自动刷新**：改明细后 `read` 回来的公式格 `v` 仍是旧缓存值；导出 xlsx 的缓存值 `<v>` 也是旧的（但公式 `f` 是对的，Excel 打开即重算成新值）。所以 agent 若要通过 `read` 拿「最新合计数值」，别信旧 `v`，自己按公式重算。

实操结论：

- **想让用户看到正确合计：改明细格即可**，不用动合计/小计公式格（展示层自动重算兜底）。
- **想让 `read` 快照里公式格 `v` 也准确**（或导出缓存值正确）：写公式时把结果写进 `v`；改明细后若需读回精确值，自己重算后 `{"v":新值,"f":"=公式"}` 重写一次公式格。

- 想让公式格的结果与公式一起更新：`value` 传对象 `{"v":新结果,"f":"=公式"}`。
- 想把公式格改成纯值：`value` 传 `{"v":值,"f":null}`。
- 想把「假公式」字符串（无 `f`、`v` 以 `=` 开头，如 `"=E9*F9"`）转成真公式：重写该格 `{"v":结果,"f":"=E9*F9"}`。

### 读回公式的两种形态（写前先认准）

读 `data.snapshot` 时，单元格的公式可能以两种形态出现，别被第一种骗到：

| 形态 | 样子 | 含义 |
|---|---|---|
| 真公式 | `{"v":450,"f":"=A2*B2","t":2}` | `f` 字段存公式，`v` 存结果，`t` 是结果类型（2=数字）；Excel/WPS 会重算 |
| 「假公式」字符串值 | `{"v":"=A2*B2","t":1}` | 内容以 `=` 开头但**是纯文本**（`t:1`=字符串），没有 `f` 字段，**不重算**，导出也是文本 |

- **判公式看有没有 `f` 字段**，不要看 `v` 是否以 `=` 开头。
- `t` 是值类型（CellValueType）：`1`=字符串、`2`=数字、`3`=布尔、`4`=强制字符串。**你永远不需要自己提供 `t`**：纯 API 写入路径不产生也不保存 `t`，读回同样不带；`t` 只是前端编辑器里用户手改单元格后自动补的标记，渲染/导出只认 `v`/`f`，不依赖 `t`。所以上表里的 `t:1`/`t:2` 只会出现在「经前端编辑过」的格子上，纯 API 读回没有——判假公式的唯一标准是**有没有 `f` 字段**。
- 想写真公式：`setCellValues` 的 `value` 传 `{"v":结果,"f":"=公式"}`；`replaceWorkbook` 写 `{v:结果, f:"=公式"}`。
- 想把「假公式」字符串转成真公式：重写该格 `{"v":结果,"f":"=公式"}`。

> **读回与导出的公式可能不一致**：给「金额=单价×数量」这类格写纯值后，`read` 回来的 cellData `f` 字段常是 `None`（看起来是纯值），但 `export?format=xlsx` 出来的文件里该格却自动带了 `=E8*F8` 公式——这是 Univer 编辑器在导出/重算层的补全行为，不是 `setCellValues` 写的。结论不变：**服务端仍不替你算 `v`**（公式格结果缓存靠你自己写）；判单元格是否有公式**以读回 cellData 的 `f` 字段为准**，导出文件里的公式是 Univer 层自动补的，别用它反推 cellData。

## 3. replaceWorkbook：整表重建（⚠️ 覆盖写，默认禁用）

> ⛔ **先读 SKILL.md「⛔ 覆盖写禁令」。** 这条指令的语义是**删除现有所有 sheet，再按你传入的快照重新插入**——不是更新。`read` 之后用户在页面上做的任何修改都会被静默覆盖：接口照样 `code:"0000"`、`read` 读回也正常，没有任何报错。
>
> - **已有内容的表，日常增删改一律用 `setCellValues`**（改值/公式/样式），不要为了「改一格」「补个日期格式」或「顺手整理」而整表写回。
> - **禁止「`read` 全表 → 修改 JSON → `replaceWorkbook` 写回」这个模式。**
> - 只有三种情况可以用：① 页面刚建、内容为空，且需要**多工作表/合并单元格**等 `setCellValues` 表达不了的结构（单纯数据播种、扩行扩列、加样式都不需要）；② 表结构已损坏，需要按完整骨架修复；③ 用户**明确要求**整表重建/重排。
> - 即便是这三种，也必须**写入前重新 `read`**（不许用任务开始时读的副本），把数据合并到最新状态，并压到一次请求里发完。
> - 写前自检：payload 里若出现大量「用户没让改」的行/列，说明你正在整表覆盖——停下来改增量。

当需要**多工作表、合并单元格、行列结构**等 `setCellValues` 覆盖不了的内容时，才考虑用 `replaceWorkbook` 传入完整表格 JSON（结构 = `read` 返回的 `snapshot` 同款），一次性重建整本工作簿。**样式不在其列**——`setCellValues` 已支持 `style`（§2），单格/整列样式都用增量写。

| 字段 | 类型 | 说明 |
|---|---|---|
| `workbook` | object | 完整表格快照，必含非空的 `sheets` 对象 |

`workbook` 的骨架（与 `read` 返回的 `snapshot` 同构）：

```json
{
  "id": "wb", "name": "", "appVersion": "0.1.0", "locale": "zhCN",
  "styles": { "s1": { "bg": { "rgb": "#FFFF00" } } },
  "sheetOrder": ["data", "summary"],
  "sheets": {
    "data": {
      "id": "data", "name": "数据",
      "rowCount": 5, "columnCount": 5,
      "cellData": {
        "0": { "0": { "v": "单价" }, "1": { "v": "数量" }, "2": { "v": "小计" } },
        "1": { "0": { "v": 10 }, "1": { "v": 3 }, "2": { "v": 30, "f": "=A2*B2" } }
      },
      "mergeData": [], "rowData": {}, "columnData": {},
      "rowHeader": { "width": 46 }, "columnHeader": { "height": 20 },
      "freeze": { "xSplit": 0, "ySplit": 0, "startRow": -1, "startColumn": -1 },
      "hidden": 0, "zoomRatio": 1, "scrollTop": 0, "scrollLeft": 0,
      "defaultColumnWidth": 73, "defaultRowHeight": 23
    },
    "summary": {
      "id": "summary", "name": "汇总",
      "rowCount": 5, "columnCount": 5,
      "cellData": { "1": { "0": { "v": 110, "f": "=SUM(data!C2:C3)" } } },
      "mergeData": [], "rowData": {}, "columnData": {},
      "rowHeader": { "width": 46 }, "columnHeader": { "height": 20 },
      "freeze": { "xSplit": 0, "ySplit": 0, "startRow": -1, "startColumn": -1 },
      "hidden": 0, "zoomRatio": 1, "scrollTop": 0, "scrollLeft": 0,
      "defaultColumnWidth": 73, "defaultRowHeight": 23
    }
  }
}
```

> ⚠️ **每张 sheet 都必须给完整结构，`freeze`、`rowHeader`、`columnHeader`、`rowData`、`columnData`、`rowCount`、`columnCount` 一个都不能少**。服务端不校验这些字段——缺了照样写入成功、`read` 照样读回齐全（纯 API 自检**发现不了**这个问题）；但前端打开页面时，协同重放会访问 `freeze.xSplit`、`rowHeader.width`、`columnHeader.height`，并把 `cellData`/`rowData`/`columnData` 当对象遍历、`mergeData` 当数组用，缺任何一个就抛异常（`rowData`/`columnData` 缺失时报 `Cannot convert undefined or null to object`）。后果看缺的是哪条：
> - 一般情况：失败**完全静默**（页面无任何报错提示）——表现为空表 / 「没有工作表」，刷新多少次都不恢复；
> - 缺的若是 `insert-sheet` 的关键字段：表插不进来、该页一张表都没有，前端反而弹**「该范围已被保护，目前无编辑权限。如需编辑，请联系创建者。」**——这是「表没插进去 → 权限点没注册 → 后续写命令被判无权限」的连带现象，**不是真的被保护，别去找创建者要权限**，按本条补全结构重播即可。
>
> **与 sheet 数量无关**：单表缺结构一样坏，多表结构齐全完全正常（分组对照实测坐实）。
> **坏表修复**：对同一页再发一次结构齐全的 `replaceWorkbook` 即可原地恢复，slugId 与已分发链接不变。

要点：
- **单元格值**：`{v:值}` 纯值，`{v:值, f:公式}` 带公式，`{s:样式id}` 引用 `styles`。**不要提供 `t`**——纯 API 路径不产生 `t`，前端渲染也不依赖它（§2）。
- **sheet 骨架**：每张 sheet 都照抄上面示例的全套字段（`freeze`/`rowHeader`/`columnHeader`/`rowData`/`columnData` 尤其别省）。禁止只给 `id/name/cellData` 的「极简表」写法，也别自造 `rowDefinitions`/`columnDefinitions` 之类的字段名去代替 `rowData`/`columnData`（前端不认自造名，等同没给）——API 读写全正常，前端打开必坏（见上方警告）。
- **公式**：写 `f` 字段（如 `"=A2*B2"`、`"=SUM(data!C2:C3)"`）。
- **多表**：`sheetOrder` 是表 id 顺序，`sheets` 按 id 存各表；每张表都要完整骨架。
- **样式**：`styles` 顶层对象，单元格 `s` 字段引用其 key。也支持直接写 inline 样式对象（`s: {bg:{rgb:"#FFFF00"}}`）。
- **`mergeData`**：每张 sheet 若给 `mergeData`，**必须是数组**（无合并写 `[]`）。写成 `{}` 会报错（`code:"0001"`）；旧版本还会导致导出 xlsx 报 `object is not iterable`。
- **行列数**：`rowCount`/`columnCount` 给足实际数据范围并留余量——前端只在配置范围内扫描/渲染，写超出范围的数据「存得进、看不见」（§2）。

**公式结果要自己写 `v`**：`replaceWorkbook` 写入公式定义 `f` 后不会重算数值，所以带公式的格子请同时把当前结果写进 `v`（如上面 `v:30, f:"=A2*B2"`）。写好了 `f`，用 Excel/WPS 打开时会被自动重算。

## 4. 读与导出

- **读**：`{"kind":"sheet","ops":[{"op":"read"}]}` → `data.snapshot` 完整表格 JSON，**纯 API 随时可用**（不依赖前端打开/落盘）。
- **导出**：`GET /pages/{pageId}/export?format=xlsx` → 返回 `.xlsx` 二进制文件。写了内容（setCellValues / replaceWorkbook）后即可导出，多表/公式/样式都会进文件；`setCellValues` 写入的 **inline 样式**（数字格式、底色、字体、边框）同样会进 xlsx。

## 5. 边界（不支持的）

- **公式不自动重算**：写 `f` 后数值结果需你自行写进 `v`（结果缓存不自动算），Excel/WPS 打开时会重算。改明细格后，公式格的结果缓存同样不会自动刷新——若需最新结果，用 `setCellValues` 传 `{"v":新结果,"f":"=公式"}` 一并更新。
- **图片/图表/形状**：这类浮动对象 `replaceWorkbook` 的 `cellData` 不覆盖，需前端编辑器插入，或 `/import` 上传原始 xlsx。
  - 落到「饼图/柱状图」这类具体图表：API 只能把图表的**数据源**写进表格（例：`replaceWorkbook` 新增一个「XX分布」工作表，含 分类 + 数值 + 占比公式 `=B2/$B$5` + 合计公式 `=SUM(B2:B4)`）；**图表图形对象 API 生成不了**，需前端「插入 → 图表 → 饼图」选中该数据区一键生成。改明细值后数据区（公式）会联动，但图形对象始终由前端维护。
- 要「带复杂图表/图片的成品 Excel」，直接 `POST /import` 上传原始 xlsx 文件。

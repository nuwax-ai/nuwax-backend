# 多维表格（datatable）操作参考（建表 + 字段 + 记录 + 视图）

> **何时读**：用户要「多维表格 / 数据表 / Bitable」——新建、写数据、配字段、配视图（筛选/排序/分组/填色/统计）时。普通 Excel 表格看 [[ops-sheet.md]]；给多维表格配「透视页」（HTML 数据看板）看 [[pivot-page.md]]。

> ⛔ **写指令通用铁律**：写指令就是 sheet 那几个（`setCellValues` / `replaceWorkbook` / `insertRow` / `removeRow` / `read`，带 `kind:"sheet"`）。**op 名打错现在会直接报 `code:"0001"`**（message 列出允许的 op），不再静默跳过；**判成败仍看 `applied > 0`**，写完必读回对账（见 §9.12 播种自检）。详见 SKILL.md「⛔ op 指令三条铁律」。

## 0. 底层模型：datatable = sheet 存储 + 语义约定

多维表格**底层复用 Univer sheet**（一张数据表 = 一个 sheet），但按约定赋予语义：

| 数据 | 落点 |
|---|---|
| **字段**（列定义） | row 0 表头行：第 col 列单元格 `v` = 字段名，`custom.dtField` = 字段 schema |
| **记录**（行数据） | row 1 起每行一条，值按 §6 编码写进 `v` |
| **视图配置**（筛选/排序/分组/填色/行高/统计） | `(0,0)` 单元格 `custom.dtMeta` = `JSON.stringify(viewMeta)`（§5） |
| 多张数据表 | 一个 workbook 多个 sheet，每个 sheet 一张数据表（**多表完全支持**；但 replaceWorkbook 重建时每张 sheet 必须给完整结构，见 §9.10） |

- 读写端点与 sheet 完全相同：`POST /pages/{pageId}/content`，**body 显式带 `kind:"sheet"`**。
- 页面判定：`GET /pages/{pageId}` 看 `pageType:"datatable"`。
- 扫描规则：**字段从 col 0 连续扫描，遇空字段名即截断**（字段之间不能有空列）；记录扫描跳过整行为空的行。**写入会自动扩表配置范围**（见 §2），前端另有两个硬上限：字段最多扫到 `min(columnCount, 60)` 列、记录最多扫到 `min(rowCount, 1000)` 行。

## 1. 指令总览（5 个，与 sheet 同一套）

| op | 参数（`?` 可选） | 多维表格场景用途 |
|---|---|---|
| `setCellValues` | `sheetId?`、`cells:[{row,col,value?,custom?,style?}]` | 写字段（row0 带 custom.dtField）、写记录值、写视图（(0,0) 只带 custom.dtMeta）、补单元格样式 |
| `insertRow` | `sheetId?`、`row`、`rowCount?`(默认1) | 在第 row 行之前插入空行（原 row 起整体下移），用于插记录 |
| `removeRow` | `sheetId?`、`row`、`rowCount?`(默认1) | 删除第 row 行（下方整体上移），用于删记录 |
| `replaceWorkbook` | `workbook` | ⚠️ **覆盖写，默认禁用**：删掉现有数据表后按快照重插，会覆盖 `read` 之后用户的修改。**仅多工作表/合并单元格等结构变更 / 结构修复 / 用户明确要求整表重建**时用（样式、扩行扩列都不需要）；每张 sheet 必须给含 `freeze` 的完整结构（§9.10）。已有数据的表增删改只用 `setCellValues`/`insertRow`/`removeRow` |
| `read` | 无 | 读回 `data.snapshot`，自行解析 cellData（含 custom） |

`cells[]` 项的关键能力：
- `value`：写值（string/number/boolean/null，或 `{v,f}` 公式对象）。
- `custom`：object，**整对象替换**单元格 `custom`（实测：不是按键合并！）。重写已有 custom 的格子时必须把要保留的键一起带上——(0,0) 同时有 dtField+dtMeta，只想改 dtMeta 也要两个键都给，否则 dtField 被抹、字段扫描从 col0 直接截断。
- `style`（或 `s`）：单元格样式，Univer `IStyleData` inline 对象（如 `{"n":{"pattern":"yyyy-mm-dd"}}`、`{"bg":{"rgb":"#FFFF00"}}`），**按键合并**、不冲掉其它样式；只写样式时省略 `value`。详见 [[ops-sheet.md]] §2「写样式」。
- **`value` 缺省、只给 `custom`/`style` 时：只更新 custom/样式，保留该格原有 v/f**——写 (0,0) 的 dtMeta 就靠这个（不碰首字段名）。
- **`value:null` 是整格清空**（实测）：v/f/custom/样式全被清掉，同请求里带的 custom/style 也会被忽略。没有「清值但保留 custom」的裸 op，需要就重写整格。
- **日期字段两种写法都行（2026-10-21 实测）**：
  - **展示串**（推荐，最省事）：写 `"2026-09-30"`，DataTableGrid 原样显示，支持筛选排序。
  - **Excel 序列号 + 日期格式**：写 `46296` 并带 `style:{"n":{"pattern":"yyyy-mm-dd"}}`，DataTableGrid 会渲染成 `2026-09-30`；**不带 `n` 时会显示裸数字 `46296`**。已有数字日期或与普通 sheet 复用时用这种。
  - `createdTime`/`modifiedTime` 仍由宿主填，勿手写。
- **DataTableGrid 不渲染的样式**：`bg`（底色）、字体（`ff`/`fs`/`bl`/`cl`）、边框（`bd`）在普通 sheet 视图正常，但多维表格网格不显示——别指望用背景色标记记录。数字格式 `n` 是有效的（且会**覆盖**字段自身的货币格式，比如给 currency 格写 `#,##0.00` 后不再显示 `¥`）。
- **参数校验（2026-10-21 起）**：未知 `sheetId`、未知样式 id、样式 key/值非法、未知 `kind`、**未知 op 名**均返回 **`code:"0001"`**（HTTP 200、`success:false`，message 带原因）；同一请求的 ops 是**原子**的，任一失败整批回滚。
- **`kind` 只能是 `sheet`**：多维表格页底层就是 sheet，传 `kind:"datatable"` 现在会报 `code:"0001"` 并提示改用 `"sheet"`（旧版会静默返回 `applied:0`）。

## 2. 从零创建多维表格

```bash
AUTH="Authorization: Bearer $SANDBOX_ACCESS_KEY"
BASE="$PLATFORM_BASE_URL/api/v1/4sandbox/repo"

# 1. 建页（只建元数据，内容是空的，须自己播种）
curl -s -X POST "$BASE/pages" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"spaceId":57,"parentId":<父页面ID，可选>,"title":"项目跟进表","pageType":"datatable"}'
# → data.id（pageId）/ data.slugId

# 2. 播种字段（row0：v=字段名 + custom.dtField=schema）+ 记录（row1+），一批写完
curl -s -X POST "$BASE/pages/<pageId>/content" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"kind":"sheet","ops":[{"op":"setCellValues","cells":[
    {"row":0,"col":0,"value":"任务","custom":{"dtField":{"type":"text"}}},
    {"row":0,"col":1,"value":"状态","custom":{"dtField":{"type":"select","options":[
      {"id":"opt-todo","name":"待办","color":"#8c8c8c"},
      {"id":"opt-doing","name":"进行中","color":"#fa8c16"},
      {"id":"opt-done","name":"已完成","color":"#52c41a"}]}}},
    {"row":0,"col":2,"value":"截止日","custom":{"dtField":{"type":"date"}}},
    {"row":0,"col":3,"value":"预算","custom":{"dtField":{"type":"currency","currency":"¥","decimals":2}}},
    {"row":1,"col":0,"value":"官网改版"},
    {"row":1,"col":1,"value":"opt-doing"},
    {"row":1,"col":2,"value":"2026-09-30"},
    {"row":1,"col":3,"value":50000},
    {"row":2,"col":0,"value":"数据看板"},
    {"row":2,"col":1,"value":"opt-todo"}
  ]},{"op":"read"}]}'
```

要点：
- 字段**必须从 col 0 连续**排（中间空一列，后面字段全部扫不到）。
- select 字段的记录值写**选项 id**（如 `opt-doing`），不是选项名——见 §6。
- 同批带 `{"op":"read"}` 立即读回校验。
- **追加/修改记录直接 `setCellValues`，不需要先扩行**：服务端会按本次落格的最大 `row`/`col` 自动追加 `set-worksheet-row-count` / `set-worksheet-column-count`（绝对值、只增不减），写第 48 行也能显示。`insertRow` 只用于「插到中间某条记录之前 / 保持行序」，不是扩容量。前端硬上限是字段 60 列、记录 1000 行（`min(columnCount,60)` / `min(rowCount,1000)`），超过这个量级才需要考虑拆表。
- **从零创建不需要 `replaceWorkbook`**（实测；已有内容的表更是禁止用它做日常写入）：API 建页后默认表 `sheet-1` **已自动就位且结构完整**——`rowCount:20`、`columnCount:20`、`rowData`/`columnData`/`mergeData` 齐全（不是 `null`，服务端读回与前端显示都正常），直接 `setCellValues` 播种即可；写更多行/列时服务端自动扩容。**不要**因为「新建空表结构是 null / setCellValues 在空表上静默失败 / 超 20 行会截断」就改用 `replaceWorkbook`——这几条都不成立。
- **工作表结构键名只用 Univer 的**：`rowData` / `columnData` / `cellData` / `freeze` / `rowHeader` / `columnHeader`。`rowDefinitions` / `columnDefinitions` 这类自造名 Univer 不认，会直接导致表插不进来、弹「该范围已被保护」（§9.10）。
- **读回校验 ≠ 前端校验**：`read` 返回正常、`contentSize>0` 只证明数据写入了；结构性问题（如 sheet 缺 `freeze`）API 侧完全无感。replaceWorkbook 播种后务必打开一次前端页面确认字段数/记录数正确（自检清单见 §9.12）。

## 3. 字段操作

### 3.1 新增 / 修改字段

写 row 0 对应列：`{"row":0,"col":N,"value":"字段名","custom":{"dtField":{...schema}}}`。

- schema 缺省（不带 custom）等价纯文本列，但**规范写法始终带 dtField**。
- 修改字段类型/选项 = 重写该格（value + custom 一起给），会覆盖旧 schema。
- **重写 col 0 时 custom 必须同时带 `dtField` 和 `dtMeta`**——custom 是整对象替换（§1），(0,0) 上挂着的视图配置 dtMeta 不带就会被抹掉。
- **改 select 选项列表时**：新选项的 id 若与记录里已写的 id 对不上，旧记录显示为灰色纯文本 pill——增删选项前先 `read`，保留仍在用的旧 id。

### 3.2 删除字段

沙箱没有「一键删列」语义，两步手工做：
1. 清表头：`{"row":0,"col":N,"value":null}`（整格清空，字段扫描到此截断——**删中间列会把后面字段全断掉**，所以删中间列要先把它右侧所有列整体左移重写）。
2. 清该列数据：对该列 row1..最后记录行逐行 `value:null`。

安全做法：优先只删**最后一列**（清表头 + 清该列记录值即可）。一定要删中间列时，**不要整表 `replaceWorkbook`**——按「左移重写」增量做：`read` 后按记录 ID 建立行映射，用 `setCellValues` 把右侧各列逐格左移覆盖（表头与记录一起做），最后清空尾列。整表重建只留给「结构确实坏掉」或用户明确要求的情况，且必须当场重新 `read`（见 SKILL.md「⛔ 覆盖写禁令」）。

### 3.3 字段 schema 键表（`custom.dtField`）

| 键 | 类型 | 适用 type | 说明 |
|---|---|---|---|
| `type` | string（必填） | 全部 | 见下方 30 类 |
| `options` | `[{id,name,color?}]` | select / multiSelect | 选项列表；`id` 自定义唯一字符串（惯例 `opt-` 前缀），`color` 用 §5.3 色板 |
| `format` | string | number / date | 显示格式 |
| `max` | number | progress / rating | 进度上限（默认 100）/ 评分星数（默认 5） |
| `currency` | string | currency | 货币符号（¥ $ € £ ₩，默认 ¥） |
| `decimals` | number | currency | 小数位（默认 2） |
| `prefix` | string | autoNumber | 自动编号前缀 |
| `seq` | number | autoNumber | 已发号计数（宿主发号时递增回写，播种可给 0） |
| `formula` | string | formula | 公式表达式（`[字段名]` 引用） |
| `linkTable` | `{slugId,title}` | link | 关联目标表 |
| `lookupLink` / `lookupCol` / `lookupColName` / `lookupAgg` | number/number/string/`'value'\|'sum'\|'count'\|'avg'\|'max'\|'min'` | lookup | 本表 link 列 / 目标列号 / 目标列名 / 聚合方式 |
| `buttonAction` | `{kind,label?,url?,targetCol?,value?}` | button | openUrl / setValue |

### 3.4 type 全量 30 类（含使用场景）

共 30 类，前端按「常规 / 业务 / 高级」三组展示（[[note]]：`group`/`barcode`/`signature`/`location`/`button`/`link`/`lookup` 本期前端隐藏新建入口，但存量照常展示编辑，API 仍可建）。用哪个类型，先想这列要让用户**输入什么、怎么展示**，再看下表。

#### 常规组（14 类）

| type | 展示名 | 值形态（§6） | 使用场景 |
|---|---|---|---|
| `text` | 单行文本 | string | 最常用：名称、标题、备注等短文本 |
| `multiLineText` | 多行文本 | string | 长描述、说明、评论性长文，单元格可输入换行 |
| `number` | 数字 | number | 数量、金额数值、统计口径，右上/右下对齐 |
| `select` | 单选 | 选项 **id** | 状态、优先级等「从固定集合选一个」；选项在 schema.`options` |
| `multiSelect` | 多选 | 选项 id 逗号连 | 标签集合、多选属性；options 同上 |
| `date` | 日期 | 展示串 或 数字+日期格式 | 截止日、开始日等日期；`"2026-09-30"` 或 `46296` + `n:{pattern:"yyyy-mm-dd"}`，均能正确显示 |
| `user` | 人员 | 逗号连名 | 负责人、参与者等**平台成员**，输入时按用户名匹配 |
| `checkbox` | 复选框 | boolean | 完成态、开关（是否/已读/启用） |
| `url` | 超链接 | string | 外链地址，渲染成可点击链接 |
| `phone` | 电话号码 | string | 手机/座机，可点击拨号 |
| `email` | Email | string | 邮箱，可点击发信 |
| `attachment` | 附件 | JSON 数组串 | 挂文件（图外任意文件）；url 来自 file/upload |
| `image` | 图片 | JSON 数组串 | 单图/多图，单元格直接预览缩略图 |
| `media` | 多媒体 | JSON 数组串 | 音视频/图片混合，按 MIME 渲染播放器 |

#### 业务组（5 类）

| type | 展示名 | 值形态 | 使用场景 |
|---|---|---|---|
| `currency` | 货币 | number | 金额，schema 带 `currency`（¥/$/€…）与 `decimals` |
| `rating` | 评分 | number | 星级评价（schema.`max` 默认 5） |
| `progress` | 进度 | number | 百分比进度条（schema.`max` 默认 100） |
| `autoNumber` | 自动编号 | string（宿主发号） | 序号/流水号，schema.`prefix`+`seq`，**建字段即自动递增，勿手写** |
| `formula` | 公式 | string（宿主回写） | 由表达式计算（`[字段名]` 引用），**只读** |

#### 高级组（自动族 + 跨表）

| type | 展示名 | 值形态 | 使用场景 |
|---|---|---|---|
| `createdTime` | 创建时间 | 展示串（宿主填） | **新建记录时自动写当前时间**，勿手写 |
| `modifiedTime` | 最后更新时间 | 展示串（宿主填） | **记录被修改时自动刷成为当前时间**，勿手写 |
| `createdBy` | 创建人 | 逗号连名（宿主填） | **新建记录时自动写当前操作用户名**，勿手写 |
| `modifiedBy` | 修改人 | 逗号连名（宿主填） | **记录被修改时自动刷成当前操作用户名**，勿手写 |
| `button` | 按钮 | string（宿主处理） | 整列按钮（跳链接 openUrl / 写值 setValue），见 `buttonAction`，点击触发 |
| `link` | 单向关联 | JSON 串 | 关联**另一张多维表格**的记录（`linkTable` 指目标）；两表独立性，改一处不互写 |
| `lookup` | 查找引用 | string（宿主回写） | 经本表 link 列**取目标表某列**的值回来展示（`lookupLink`/`lookupCol`），只读 |
| `group` | 群组 | — | 组织架构/团队（本期前端隐藏，API 可建） |
| `barcode` | 条码 | string | 条形码展示（本期隐藏） |
| `signature` | 签字 | JSON 串 | 手写签名（本期隐藏） |
| `location` | 地理位置 | string | 定位地址（本期隐藏） |

**自动族/只读字段别当普通列写值**：`autoNumber`/`createdTime`/`modifiedTime`/`createdBy`/`modifiedBy`/`formula`/`lookup`/`button` 的值由宿主计算写入——你**播种记录时跳过这些列**（建字段可以，写值没意义且会被宿主覆盖）。其中 `createdTime`/`createdBy` 在**新增记录**时填，`modifiedTime`/`modifiedBy` 在**每次改行**时刷成当前时间/当前用户。

**字段值跟记录走，改字段类型会波及存量**：把列从 A 类型改成 B 类型，已有记录的旧值可能显示异常（如 number 改 select，旧数字对不上选项变灰色 pill）。改类型前先 `read` 看这列都存了什么值。

## 4. 记录操作

> **先建映射再写**：记录行号会随 `insertRow`/`removeRow` 整体位移，**不要凭记忆里的行号写**。批量改多条记录前先 `read`，用记录 ID 列（或唯一标题）建 `行号 → ID` 映射，只改用户点名的那几条；**不要顺手全表重排、去重、规范化**——那等于整表覆盖。

| 动作 | 做法 |
|---|---|
| **追加** | 找第一个整行为空的行号 R（read 后看 cellData），`setCellValues` 写 `{row:R,col:...}`；没有空行先 `{"op":"insertRow","row":<rowCount>}` 再写 |
| **修改** | `setCellValues` 定点覆盖（row≥1）；写前先按 ID 确认该行仍是目标记录 |
| **插入到指定位置** | `{"op":"insertRow","row":R}`（在 R 之前插空行，原 R 起下移）再写值 |
| **删除** | `{"op":"removeRow","row":R}`（下方整体上移；引用该行的 link 值不会自动修）；删多条从大到小删 |
| **清空某格** | `value:null` |
| **整表重排/批量替换** | ⛔ 默认不做。用户明确要求时才能用 `replaceWorkbook`，且写前重新 `read` |

## 5. 视图配置（`custom.dtMeta`）

写 `(0,0)` 单元格，**只带 custom、不带 value**（(0,0) 的 `v` 是首字段名，带 `value:null` 会把它清掉）：

```json
{"op":"setCellValues","cells":[{"row":0,"col":0,"custom":{"dtMeta":"{\"v\":1,\"views\":[...]}}"}}]}
```

`dtMeta` 的值是 **JSON 字符串**（stringify 后的 `DtViewMeta`）：

```jsonc
{
  "v": 1,
  "views": [
    {
      "id": "view-0",            // 首视图必须是 view-0（名「表格」），恒存在、不可删
      "name": "表格",
      "filter": null,            // 筛选，见 5.1
      "sort": null,              // 排序，见 5.2
      "groupBy": null,           // 分组字段列号（number|null）
      "color": null,             // 填色规则，见 5.3
      "rowHeight": "medium",     // compact | medium | loose
      "footStats": {}            // 底部统计：{"列号": "统计类型"}，见 5.4
    }
  ]
}
```

可加多个视图（`id` 用 `view-` + 随机短串，`name` 自定义），前端按视图 tab 切换。

### 5.1 filter（筛选）

```json
{"conjunction":"and","enabled":true,"conditions":[
  {"col":1,"op":"equals","value":"进行中"},
  {"col":3,"op":"gt","value":"1000"}
]}
```

- `conjunction`：`and` 满足所有 / `or` 满足任一。
- `op` 取值：`contains`、`equals`、`notEquals`、`gt`、`gte`、`lt`、`lte`、`empty`、`notEmpty`。
- **数学比较（gt/gte/lt/lte）仅数字/日期族字段可用**：number、currency、rating、progress、autoNumber、date、createdTime、modifiedTime；其余类型只有 contains/equals/notEquals/empty/notEmpty。
- **select 字段筛选 `value` 写选项名不是选项 id**（实测）：前端比较前会把单元格里的 id 翻译成选项名（multiSelect 译成逗号连名、link 译成目标标题），写 `equals "opt-doing"` 永远匹配不上、视图直接空。记录值才用 id（§6），两处口径相反，别搞混。
- 数字/日期比较值也写字符串（`"1000"`、`"2026-09-30"`，日期按 YYYY-MM-DD 字典序=时间序）。

### 5.2 sort（排序）

```json
{"col":3,"dir":"desc","enabled":true}
```

单字段排序；`dir`: `asc`/`desc`。

### 5.3 color（填色，单元格级）

```json
{"enabled":true,"rules":[
  {"col":1,"op":"equals","value":"已完成","color":"#52c41a"},
  {"col":3,"op":"gt","value":"80000","color":"#fa8c16"}
]}
```

填色条件复用筛选的匹配逻辑——**select 字段的 value 同样写选项名**（§5.1）。多条规则按顺序匹配、命中第一条即着色。色板 12 色（与选项色同源）：
`#1677ff` `#2f54eb` `#722ed1` `#c41d7f` `#eb2f96` `#fa8c16` `#fadb14` `#a0d911` `#52c41a` `#13c2c2` `#14a7b8` `#8c8c8c`

### 5.4 footStats（底部统计行）

`{"列号(字符串)": "统计类型"}`，如 `{"3":"sum","0":"count"}`。统计类型 8 种：

`count`（总数）、`filled`（已填）、`empty`（空）、`distinct`（去重数）、`sum`（求和）、`avg`（平均）、`max`（最大）、`min`（最小）——sum/avg/max/min 仅数字类字段有意义（前端菜单按类型门控，API 写了非法组合不报错但不展示）。

## 6. 值编码表（记录单元格 `v`）

| 字段类型 | 值形态 | 示例 |
|---|---|---|
| text / multiLineText / url / phone / email / barcode / signature / location | string | `"官网改版"` |
| number / currency / rating / progress | number | `50000`、`4.5` |
| checkbox | boolean | `true` |
| **select** | string = **选项 id** | `"opt-doing"` |
| **multiSelect** | string = **选项 id 英文逗号连接** | `"opt-a,opt-b"` |
| date / createdTime / modifiedTime | string 展示串，或 number + `style.n.pattern` 日期格式 | `"2026-09-30"` 或 `46296` + `{"n":{"pattern":"yyyy-mm-dd"}}`（纯数字不带 `n` 会显示裸数字） |
| user / group / createdBy / modifiedBy | string，多人**逗号连名** | `"张三,李四"` |
| attachment / image / media | JSON 数组字符串 | `"[{\"url\":\"/api/f/{key}\",\"name\":\"a.png\",\"size\":123}]"` |
| link | JSON 字符串 | `"{\"s\":\"目标slugId\",\"r\":行号,\"t\":\"标题\"}"` |
| autoNumber | string | 宿主发号，勿写 |
| formula / lookup / button | string | 宿主计算回写，勿写 |

**select/multiSelect 口径（易错）**：**记录值**底层存**选项 id 不是选项名**。前端渲染按 schema.options 把 id 翻译成彩色 pill；id 匹配不到时原样显示灰色 pill（不崩但难看）；导出 xlsx 会自动翻译成选项名。所以：写记录值用 id；要新增选项，先重写 row0 的 dtField（options 里加 `{id,name,color}`），再用新 id 写记录。**但视图筛选/填色条件的 value 反过来用选项名**（§5.1/§5.3，前端按翻译后的展示名比较）。

附件的 `url` 用 `POST /api/v1/4sandbox/file/upload` 返回的 `data.url`（见 [[file-upload.md]]）。

## 7. 读取与解析

`{"kind":"sheet","ops":[{"op":"read"}]}` → `data.snapshot`，按 §0 落点自行解析：

- `snapshot.sheets[sheetId].cellData[0][col]`：`.v` 字段名、`.custom.dtField` schema。
- `cellData[row][col].v`（row≥1）：记录值。
- `cellData[0][0].custom.dtMeta`：JSON.parse 得视图配置。
- 多张数据表：`snapshot.sheetOrder` 逐 sheet 解析。

## 8. 导出

`GET /pages/{pageId}/export?format=xlsx` → 多维表格值**自动翻译成人类可读**：select/multiSelect 的选项 id 译为选项名，link/附件/图片/媒体 JSON 译为可读文本，无需预处理。

## 9. 坑清单

1. **(0,0) 写 dtMeta 千万别带 `value`**——该格 `v` 是首字段名，`value:null` 是**整格清空**（v/f/custom 全没，同请求里带的 custom 也被忽略），字段扫描直接截断为 0 列。只给 `custom`。
2. **字段必须从 col 0 连续**——中间空名/空格列会截断后续所有字段。
3. **select 记录值写选项 id 不写选项名**——写名不会报错，但渲染成灰色纯文本、编辑器里匹配不到选项。反过来，**筛选/填色条件的 value 写选项名**（§5.1）。
4. **只读字段（autoNumber/时间/人/formula/lookup/button）别手写值**——宿主会覆盖。
5. **删中间列会断字段扫描**——优先只删尾列；确需删中间列时按 §3.2 用 `setCellValues` 左移重写，**不要整表 replaceWorkbook**。
6. **`kind:"sheet"` 显式带**——新建的 datatable 页内容为空，不带 kind 可能被探测成 doc。
7. **新建页是空的**——`POST /pages` 只建元数据，字段+记录都要自己播种（§2）。
8. **insertRow/removeRow 的行号是物理行**——记录行号 = sheet 行号（row≥1），删行后下方记录行号整体上移，连续删多行要从大到小删。
9. **裸 setCellValues 的 `custom` 是整对象替换不是合并**（实测）——重写 (0,0) 或任何已有 custom 的格，必须把 `dtField`+`dtMeta` 等要保留的键全部带上，漏了就被抹掉。（透视页 SDK / Sidecar 语义层的 `setField`/`setViewMeta` 内部先读后合并，无此坑。）
10. **replaceWorkbook 的 sheet 结构缺字段 → 前端静默空表 / 弹「范围已被保护」**（最难排查，分组对照实测坐实）——服务端不校验 sheet 结构：缺 `freeze`/`rowHeader`/`columnHeader`/`rowData`/`columnData` 时写入照样成功、`read` 读回照样齐全；但前端打开时协同重放要访问 `freeze.xSplit`、`rowHeader.width`、`columnHeader.height`，并把 `cellData`/`rowData`/`columnData` 当对象遍历（缺 `rowData`/`columnData` 报 `Cannot convert undefined or null to object`），缺任一即抛异常且**失败完全静默**（页面无任何报错）——表现为空表/「没有数据表」，刷新不恢复。**缺的若是 `insert-sheet` 的关键字段**：表插不进来、该页 0 张表，前端反而弹**「该范围已被保护，目前无编辑权限。如需编辑，请联系创建者。」**——那是「无表 → 权限点未注册 → 写命令被判无权限」的连带现象，**不是真被保护，别去找创建者要权限**。**与数据表张数无关**：单表缺结构一样坏，多表结构齐全完全正常；「必须单 sheet」是错误归因。每张 sheet 必须给完整骨架（照抄 [[ops-sheet.md]] §3 示例，`freeze` 固定给 `{"xSplit":0,"ySplit":0,"startRow":-1,"startColumn":-1}`，`rowData`/`columnData` 固定给 `{}`）。**坏表修复 = 对同一页再发一次结构齐全的 replaceWorkbook**，原地恢复、slugId 与已分发链接不变。
11. **写入会自动扩 rowCount/columnCount**——`setCellValues` 会按落格的最大行列追加扩容（只增不减），直接写第 N 行/第 M 列即可，不需要 `insertRow` 扩容量、更不需要 replaceWorkbook。前端硬上限：字段 60 列、记录 1000 行（`min(columnCount,60)` / `min(rowCount,1000)`），超过才需拆表。
12. **播种后自检清单**（读回正常 ≠ 前端正常，逐项过）：
    1. `read`：`sheetOrder`、各表 `rowCount`/`columnCount`、row0 各列 `custom.dtField`、记录行数与预期一致；
    2. replaceWorkbook 播种的，确认每张 sheet 都带 `freeze`、`rowHeader`、`columnHeader`、`rowData`、`columnData`、`rowCount`、`columnCount`（前端重放失败 API 侧查不出来，只能结构上不给漏的机会）；
    3. `GET /pages/by-slug/{slugId}` 的 `contentSize > 0`（必要非充分——只证明写入了内容）；
    4. **最终判据：打开前端页面**确认字段数、记录数、各数据表 tab 都正确（无法开浏览器时，可用透视页 SDK `read()` 或让宿主前端接口代查，与 API `read` 对比行数）。
13. **整表 `replaceWorkbook` 会覆盖并发修改（数据回退事故）**——它的语义是「删表 + 按快照重插」，不是更新。AI 常见错误：任务开始时 `read` 整表 → 思考/整理几分钟 → 把整份快照写回；这期间用户在页面上的修改全部丢失，而且 `code:"0000"`、`read` 回读正常、**没有任何报错**。**已有数据的表一律增量写**（`setCellValues`/`insertRow`/`removeRow`）；只有新建空表播种、结构修复、用户明确要求整表重建才用，且写前必须重新 `read`。同理，按行号批量写前要先按记录 ID 建映射（§4）——行号位移会把内容写到别的记录上，造成「ID 与标题串行」和重复标题。写后对账：`node scripts/dt_rows.js <pageId> --diff <基线>` 出现多余差异就是覆盖了别人的内容。

## 10. 交付/嵌入：链接不要加额外参数

**交付多维表格时，链接就是干净的 `[标题](/repo/doc/{slugId})`**（分享页 `[标题](/repo/share/{token})`）。页面**各区域照常展示**（含左侧「数据表」列表栏），使用者可自由切换查看其中的数据表。

除 `just_show_content` / `hide_menu` / `toc` / `nav` 这类**由用户或宿主页面明确要求**的展示参数外，**不要自作主张往交付链接上挂参数**：交付链接是用户长期持有、到处转发的入口，多一个参数就多一处「对方看到的和我看到的不一样」。**更不要为了「藏起某块界面」而给页面加参数**——那属于改变使用者看到的界面，是用户的决定，不是交付时可以顺手做的默认动作。

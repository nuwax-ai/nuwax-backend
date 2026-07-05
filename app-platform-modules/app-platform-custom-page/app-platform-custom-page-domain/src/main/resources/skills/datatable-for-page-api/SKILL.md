---
name: datatable-for-page-api
description: "Custom Data Table management API for creating and managing user-defined data tables. Supports two groups: 1) Table Schema Management — create tables, rename tables, update table descriptions, define/modify columns and field types, delete tables, list/search tables, get table detail, copy table structure, check if table has data; 2) Table SQL API Management — create new SQL-based table operation APIs, update existing SQL-based table operation APIs. Use this skill whenever the user wants to work with custom data tables, create a new table, manage table columns or fields, or create/update SQL-based data table APIs. Keywords: data table, custom table, table creation, table schema, columns, fields, SQL API, table operation API. 支持中文：自定义数据表、表结构、字段定义、数据表SQL操作API、创建API、更新API."
license: MIT
---

# Data Table for Page API Skill

## Overview

This skill provides a Python client script to access the platform data table REST APIs in sandbox.

Use this skill (`@datatable-for-page-api`) when the user wants to perform any of the following:

- **创建数据表**: create a new custom table with a name and optional description
- **查看/搜索数据表**: list existing tables, search by name, get table definition detail
- **修改表结构**: rename table, change description, update field/column definitions
- **删除数据表**: remove a table definition
- **复制表**: copy an existing table structure to a new table
- **创建数据表SQL操作API**: create a new SQL-based table operation API for page components
- **更新数据表SQL操作API**: update an existing SQL-based table operation API

## Agent workflow

### 创建表并定义SQL操作API（推荐标准流程）

1. 调用 **`add-table`**（`POST .../compose/db/table/add`）创建表，获得表 ID。
2. **立刻调用 `get-table`** 读出建表时自动生成的 8 个系统字段（`id`/`uid`/`user_name`/`nick_name`/`agent_id`/`agent_name`/`created`/`modified`），它们必须原样回传。
3. 调用 **`update-table-definition`**（`POST .../compose/db/table/updateTableDefinition`）定义字段结构——**必须传"系统字段(原样)+自定义字段"的完整 fieldList**，不能只传新字段。详见下方「⚠️ update-table-definition 关键约束」。
4. 再次调用 **`get-table`** 读取 **`dorisTable`**（物理表名，形如 `custom_table_{表ID}`），SQL API 的 SQL 语句里必须用这个物理表名，**不能用建表时的中文表名（如"用户表"）**。
5. 调用 **`table-sql-new`**（`POST .../table/sql/new`）创建数据表SQL操作API，用于页面组件调用。
6. 如需修改已创建的SQL操作API，调用 **`table-sql-update`**（`POST .../table/sql/update`）。
7. **【最易遗漏】前端对接 + 登记进 `.project.md`**：建表/建 API 只是脚手架。建好后必须：①获取每个 SQL 操作 API 的端点 token；②按下方「⚠️ 前端对接规范（必读）」落地前端 lib；③把表 ID、物理表名、token、SQL、入出参全部登记进项目根目录的 `.project.md`（见下方「项目记忆 `.project.md`」）。前端真正能调用才算闭环。

> ⚠️ **不要用 `copy-table` 来"绕过"建表/加字段**。`update-table-definition` 用对格式后完全能自建自定义字段（见下方约束）。复制别人的表会把无关字段带进来，造成字段语义错乱。

### 查询表信息

1. 若不知道表 ID，先调用 **`list-tables`** 获取表列表。
2. 调用 **`get-table`**（`GET .../compose/db/table/detailById`）获取表定义详情。

脚本封装：`add_table` / `list_tables` / `update_table_definition` / `table_sql_new` / `table_sql_update` 等；CLI：`add-table`、`list-tables`、`table-sql-new`、`table-sql-update` 等。

## ⚠️ update-table-definition 关键约束（必读，否则必然报错）

这是本技能最容易踩坑的接口。以下任一条件不满足，后端会返回 `4000 JSON parse error` 或 `5000 系统开小差啦`：

1. **`fieldType` 必须是数字枚举，绝对不能传类型名字符串**（如 `"VARCHAR"` / `"INT"`）。合法枚举：

   | fieldType | 类型 |
   |-----------|------|
   | `1` | VARCHAR |
   | `2` | INT |
   | `5` | DATETIME |
   | `6` | BIGINT |
   | `7` | MEDIUMTEXT / 长文本 |

2. **必须传完整的 fieldList（系统字段 + 自定义字段）**，只传自定义字段会被拒。
   - 先 `get-table` 读出系统字段，原样保留其 `fieldType` / `defaultValue`（时间字段是 `CURRENT_TIMESTAMP`）/ `sortIndex` / `systemFieldFlag:true`，再追加自定义字段。
   - VARCHAR 字段需带 **`fieldStrLen`**（如 255），数值/时间字段 `fieldStrLen` 传 `null`。

3. **字段注释的 key 是 `fieldDescription` **。

4. 每个字段需带完整属性：`fieldName` / `fieldDescription` / `fieldType`(数字) / `fieldStrLen` / `nullableFlag` / `defaultValue` / `uniqueFlag` / `enabledFlag` / `sortIndex` / `systemFieldFlag`。

### 可直接复制的正确 fieldList 模板

下面是已验证成功的完整请求体（系统字段原样 + 自定义字段），替换 `id` 为你的表 ID、自定义字段按需修改即可：

```bash
python scripts/datatable_for_page_api.py update-table-definition --id 398 --field-list '[
  {"fieldName":"id","fieldDescription":"主键ID","fieldType":6,"fieldStrLen":null,"nullableFlag":false,"defaultValue":null,"uniqueFlag":false,"enabledFlag":true,"sortIndex":0,"systemFieldFlag":true},
  {"fieldName":"uid","fieldDescription":"用户唯一标识","fieldType":1,"fieldStrLen":255,"nullableFlag":false,"defaultValue":null,"uniqueFlag":false,"enabledFlag":true,"sortIndex":1,"systemFieldFlag":true},
  {"fieldName":"user_name","fieldDescription":"用户名","fieldType":1,"fieldStrLen":255,"nullableFlag":true,"defaultValue":null,"uniqueFlag":false,"enabledFlag":true,"sortIndex":2,"systemFieldFlag":true},
  {"fieldName":"nick_name","fieldDescription":"用户昵称","fieldType":1,"fieldStrLen":255,"nullableFlag":true,"defaultValue":null,"uniqueFlag":false,"enabledFlag":true,"sortIndex":3,"systemFieldFlag":true},
  {"fieldName":"agent_id","fieldDescription":"智能体唯一标识","fieldType":1,"fieldStrLen":255,"nullableFlag":true,"defaultValue":null,"uniqueFlag":false,"enabledFlag":true,"sortIndex":4,"systemFieldFlag":true},
  {"fieldName":"agent_name","fieldDescription":"智能体名称","fieldType":1,"fieldStrLen":255,"nullableFlag":true,"defaultValue":null,"uniqueFlag":false,"enabledFlag":true,"sortIndex":5,"systemFieldFlag":true},
  {"fieldName":"created","fieldDescription":"数据创建时间","fieldType":5,"fieldStrLen":null,"nullableFlag":false,"defaultValue":"CURRENT_TIMESTAMP","uniqueFlag":false,"enabledFlag":true,"sortIndex":6,"systemFieldFlag":true},
  {"fieldName":"modified","fieldDescription":"数据修改时间","fieldType":5,"fieldStrLen":null,"nullableFlag":false,"defaultValue":"CURRENT_TIMESTAMP","uniqueFlag":false,"enabledFlag":true,"sortIndex":7,"systemFieldFlag":true},
  {"fieldName":"title","fieldDescription":"标题","fieldType":1,"fieldStrLen":255,"nullableFlag":true,"defaultValue":null,"uniqueFlag":false,"enabledFlag":true,"sortIndex":8,"systemFieldFlag":false},
]'
```

> 排错提示：若仍报 `5000`，逐项自查——①是否误用了字符串 fieldType ②是否漏传系统字段 ③是否漏了 fieldStrLen。

## ⚠️ 前端对接规范（必读，建完 SQL API 后立刻照做）

`table-sql-new` / `table-sql-update` 只是把 SQL 操作 API 注册到平台；**前端页面真正调用它时走的是另一条路径**。这一步没有文档支撑是前后对接反复踩坑的根因，逐条核对：

### 0. 关键真相：SQL 操作 API 本质是工作流（Workflow）

通过本技能创建的「数据表 SQL 操作 API」，**平台会把它实例化为一个 Workflow（工作流）**，工作流内部含一个 SQL 节点，真正去读写底层物理表（形如 `custom_table_{表ID}`）。

- 前端页面调用时，调用的就是这个工作流。
- 每个这样的工作流都有一个唯一的**端点 token**（一串数字），前端 lib 靠「功能名 → token」映射表来调用。
- ⚠️ 该 token 通常**不直接出现在创建接口的返回体里**，需要从平台「SQL 操作 API / 工作流列表」中获取。**取到后必须立即登记进 `.project.md`，下次无需再查找。**
- ⚠️ 实际上 `table-sql-new` / `table-sql-update` 的返回体（`data.apiSchema`）里**会**带调用路径 `.../api/v1/4sandbox/page/w/{token}`，末尾那串数字就是当前生效的 token —— 直接从这里抓即可，不必再去别处找。

### 0.1 ⚠️ 更新 SQL API 后，端点 token 会变（高频踩坑）

- **`table-sql-update` 成功后，平台经常会重新生成端点 token**，旧 token 立即失效。
- 现象：只改了 SQL（或 args），前端却开始 404 / 调用失败 / 返回非预期结果，且 `.project.md` 里登记的还是旧 token。
- **每次 new/update 后的强制闭环（少一步都会断链）**：
  1. 从返回体 `data.apiSchema` 里抓最新 token（正则 `page/w/(\d+)`）；
  2. 立刻用新 token 覆盖 `.project.md` 对应条目；
  3. 立刻同步前端 lib 的「功能名 → token」映射；
  4. 用新 token 实际调一次验证通不通。
- **排错第一反应**：前端报「调用失败 / 404 / 调不通」时，**先查 token 是否在最近一次 update 后没刷新**，再去查 SQL 语法。

### 1. 调用路径与端点 token

- 页面调用路径（**唯一**正确路径，同源调用，浏览器自带登录态）：

  ```text
  POST {PLATFORM_BASE_URL}/api/v1/4sandbox/page/w/{token}
  Content-Type: application/json
  ```

  - 前端代码里直接用**相对路径** `/api/page/w/{token}` 即可（同源，登录态由浏览器自动带），**不要**在前端请求头里塞 `Authorization` / `SANDBOX_ACCESS_KEY`——那是 Python 脚本侧管理端鉴权用的，前端拿不到也不该出现。
  - `/api/v1/4sandbox/...` 前缀仅用于 Python 脚本侧内部调试，**前端不要用**。

### 2. 响应外壳结构（查询类）

所有查询类 API 的返回都包在统一外壳里，业务数据在 `data.outputList`，**不是 `data` 本身**：

```json
{
  "success": true,
  "message": "success",
  "data": {
    "outputList": [ { "id": 1, "...": "..." } ],
    "rowNum": 1
  }
}
```

前端解析：`const rows = result.data?.outputList ?? []`。务必先判 `result.success`，失败时抛 `result.message`。

### 3. 写操作（INSERT / UPDATE / DELETE）语义

- 写操作通常只返回 `{ success: true }`，**不回填新主键 id**。
- 因此前端「新增」后**不要**用假 id 渲染单条，应直接整表/按条件**重新拉取列表**刷新。
- 批量删除/更新同理，按调用次数计数成功条数即可。

### 4. LIKE 模糊查询：用普通参数 `{{var}}` + 前端自拼 `%`

- **模糊查询统一用 `{{var}}` 普通参数占位符，不要用 `${{var}}`。**
- SQL 写法：`WHERE name LIKE {{keyword}}`（和精确匹配 `=` 用同一种占位符）。
- **平台不会自动包 `%`，前端必须自己拼**：传 `%关键词%`（前一个 `%`、后一个 `%`），例如搜「小美」就传 `%小美%`。
- 普通参数走参数化通道，值一定带引号，不会把关键词当成列名。
- ⚠️ **不要用 `${{var}}`**：它在本平台展开后值常丢引号，生成 `WHERE name LIKE 小美`，MySQL 把关键词当列名，报 **`Unknown column 'xxx' in 'where clause'`**。
- 精确匹配用 `={{var}}`，模糊匹配用 `LIKE {{keyword}}`（前端拼 `%`），二者不要混用。

### 5. 字段名对齐

- 建表字段名（`fieldName`）= SQL 列名 = 响应 JSON 字段名，三者必须一致。
- 当后端列名与前端业务概念不同时（很常见），**在前端 `lib` 层集中写一个 `toXxx(raw)` 映射函数**做转换，组件层只消费映射后的统一类型，不要把映射逻辑散落到各个组件。
- `id` 在后端是数值（bigint），前端建议统一 `String(id)` 处理，避免大整数精度问题。
- 系统字段 `created`/`modified` 是平台自动维护的 `CURRENT_TIMESTAMP`，建表时保留其 `defaultValue`，前端只读不写。

### 6. referer

页面同源调用时浏览器会自动带上 `referer`，一般无需手动处理。若在非页面环境（如脚本/SSR）调试页面端点，需手动带 `referer: {PLATFORM_BASE_URL}/page/{PROJECT_ID}-/dev/`，否则可能被拦。

## 项目记忆 `.project.md`（强制执行）

项目根目录维护一份 `.project.md`，作为 AI 的「项目记忆中枢」，集中沉淀项目设计、数据表结构、SQL 操作 API（工作流）及其端点 token 等关键信息。解决「端点 token 从哪来」反复踩坑的问题。

- **进入项目做任何开发前，必须先阅读 `.project.md`**（不存在则创建）。
- **建表 / 建（或更新）SQL 操作 API 后，立即把表 ID、物理表名、端点 token、SQL、入出参登记进 `.project.md`，再开始写前端代码**。
- 前端开发时，端点 token、字段名一律**从 `.project.md` 取**，不要凭记忆或猜测。
- 开发过程中只要产生了应长期保留的信息（改了字段、定了调用约定等），同步更新 `.project.md`。
- 它是唯一事实来源（single source of truth）；只存「项目级、跨会话需保留」的信息，临时调试信息不要塞进来。

### `.project.md` 建议登记模板

```markdown
# 项目记忆（.project.md）

## 1. 项目概述
- 用途 / 技术栈 / 模版类型（react-vite / vue3-vite 等）

## 2. 数据表清单
### 表：<表名>
- 表 ID：<如 398>
- 物理表名（dorisTable）：<如 custom_table_398>
- 字段：
  | 字段 | 类型 | 系统/自定义 | 说明 |
  |------|------|------------|------|

## 3. SQL 操作 API（工作流）清单
### <功能名，如 getAll / add / update / delete / search>
- 工作流 ID：<如平台分配的 workflow id>
- 端点 token：<前端 /api/page/w/{token} 用的那串数字>
- 绑定物理表：<如 custom_table_398>
- SQL 节点文本：
  ```sql
  SELECT ... FROM custom_table_398 WHERE ...
  ```
- 入参：<参数名 / 是否必填 / 用途；LIKE 用 {{var}}，前端自拼 `%关键词%`>
- 出参：查询类在 data.outputList；写操作仅回 success，不回填 id

## 4. 前端调用约定
- lib 文件位置：<如 src/lib/xxxTableApi.ts>
- 字段映射：<后端列名 ↔ 前端模型名>
- 写后刷新 / 跨表级联顺序等约定

## 5. 变更记录
- 日期 + 改动摘要
```

## 跨表关联与级联更新（常见范式）

业务里常出现「主表某个字段引用另一张字典表」的情况（例如：业务表 A 有一个分类字段，值来自字典表 B 的名称）。改字典表名称时，必须**级联更新**业务表里所有引用它的行，否则出现脏数据。

### 范式

1. 为级联操作单独建一个 **UPDATE 类 SQL 操作 API**（不要复用普通更新 API），形如：

   ```sql
   UPDATE custom_table_{A的表ID}
   SET {关联字段} = {{newName}}
   WHERE {关联字段} = {{oldName}}
   ```

2. 前端「改名」流程要保证**先级联、再改字典表**的顺序：

   ```
   1) 调用级联 UPDATE API，把业务表里 oldName 全部改成 newName
   2) 调用字典表自身的 update API，改字典记录
   3) 刷新业务列表（级联生效）
   ```

3. 删除字典项同理：先级联把引用置为一个约定的默认值（如 `"default"` 或「未指定」），再删字典记录。

> 跨表没有外键约束，一致性完全靠应用层按上述顺序保证。把级联 API 和调用顺序登记进 `.project.md`，避免下次遗漏。

## 前端 lib 层最小模板（TypeScript）

新建业务时，前端 `src/lib/xxxTableApi.ts` 按下面骨架填写即可，已内含：端点 token 映射、统一外壳解析、`outputList` 取值、写后刷新约定、原始行→前端模型映射。**字段全部替换为你的实际表结构**。

```ts
/**
 * 业务表 API 封装（基于平台数据表 SQL 操作 API，本质是工作流）
 * 调用路径：POST /api/page/w/{token}（同源，浏览器自带登录态）
 */

// 每个建好的 SQL 操作 API（工作流）一个 token，从 .project.md 取，不要凭记忆
const API_ENDPOINTS = {
  getAll: '<<token>>',
  getById: '<<token>>',
  add: '<<token>>',
  update: '<<token>>',
  remove: '<<token>>',
  // 模糊查询用 LIKE 占位符 ${query} 的那个 API
  search: '<<token>>',
} as const;

/** 前端统一模型（字段名按你的业务命名，与后端列名解耦） */
export interface YourItem {
  id: string;
  // ... 你的业务字段
  createdAt: string;
  updatedAt: string;
}

/** 后端原始行（字段名 = SQL 列名 = 建表 fieldName） */
interface RawRow {
  id: number;
  // ... 与 SQL SELECT 列一一对应
  created?: string;
  modified?: string;
}

/** 统一外壳 */
interface ApiEnvelope<T> {
  success: boolean;
  message: string;
  data?: { outputList: T[]; rowNum: number };
}

const API_PATH_PREFIX = '/api/page/w';

/** 统一调用：解析外壳，失败抛错，成功返回 data */
async function callAPI<T>(
  token: string,
  params: Record<string, unknown> = {}
): Promise<T> {
  const res = await fetch(`${API_PATH_PREFIX}/${token}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const result: ApiEnvelope<T> = await res.json();
  if (!result.success) throw new Error(result.message || 'API 调用失败');
  return (result.data ?? ({} as T));
}

/** 后端原始行 -> 前端模型（字段映射集中在此，勿散落到组件） */
function toItem(r: RawRow): YourItem {
  return {
    id: String(r.id),
    // ... 字段映射
    createdAt: r.created ?? '',
    updatedAt: r.modified ?? '',
  };
}

// ===== 查询类：读 data.outputList =====
export async function getAll(): Promise<YourItem[]> {
  const data = await callAPI<{ outputList: RawRow[] }>(API_ENDPOINTS.getAll);
  return (data.outputList ?? []).map(toItem);
}

export async function search(query: string): Promise<YourItem[]> {
  // 后端用普通参数占位符 LIKE {{query}}，平台不自动包 %，前端自拼 %query%
  const data = await callAPI<{ outputList: RawRow[] }>(API_ENDPOINTS.search, { query: `%${query}%` });
  return (data.outputList ?? []).map(toItem);
}

// ===== 写操作类：通常只回 success，新增后整表刷新 =====
export async function add(input: Partial<YourItem>): Promise<void> {
  await callAPI(API_ENDPOINTS.add, {
    /* 把 input 映射成后端列名参数 */
  });
  // 注意：写操作不回填新 id，调用方 add 后应重新 getAll() 刷新
}

export async function update(id: string, input: Partial<YourItem>): Promise<void> {
  await callAPI(API_ENDPOINTS.update, { id, /* ...列名参数 */ });
}

export async function remove(id: string): Promise<void> {
  await callAPI(API_ENDPOINTS.remove, { id });
}
```

> 调用方约定：页面组件 `await add(...)` 之后立即 `await getAll()` 重新拉取列表，不要依赖写操作的返回值渲染单条。

## Authentication

The script reads authentication from environment variables:

| Variable | Description |
|----------|-------------|
| `PLATFORM_BASE_URL` | Platform API base URL (e.g. `https://xxx`) |
| `SANDBOX_ACCESS_KEY` | Sandbox access key for API calls |
| `SANDBOX_ID` | Sandbox identifier (optional; sent as `X-Sandbox-Id` when available) |
| `DEV_PROJECT_ID` | **当前项目 ID（projectId）**，`table-sql-new` / `table-sql-update` 的 `--project-id` 参数从这里取值 |
| `DEV_SPACE_ID` | **当前项目所在空间 ID（spaceId）**，`add-table` / `list-tables` 的 `spaceId` 由脚本从此变量自动读取并注入请求体，确保表建在/查在项目空间而非个人空间 |

Requests use:

- `Authorization: Bearer $SANDBOX_ACCESS_KEY`
- `X-Sandbox-Id: $SANDBOX_ID` (when set)

## ⚠️ 获取 projectId（禁止创建测试工作流）

`table-sql-new` 和 `table-sql-update` 需要 `projectId` 参数。**直接从环境变量 `DEV_PROJECT_ID` 读取**，不要用其他方式获取。

> 🚫 **绝对禁止创建测试 SQL 工作流（如 `SELECT 1`）来探测 projectId 或验证 API 连通性。**
> - 每个通过 `table-sql-new` 创建的 SQL API 都会实例化为一个**永久工作流**，平台**没有删除接口**，测试工作流会永久残留在项目中。
> - 正确做法：`projectId = os.environ.get('DEV_PROJECT_ID')`，直接使用。

## ⚠️ 获取 spaceId（禁止使用个人空间）

`add-table` 和 `list-tables` 需要 `spaceId` 参数。**脚本已自动从环境变量 `DEV_SPACE_ID` 读取并注入请求体，无需也禁止手动指定。**

> 🚫 **绝对禁止把表建到/查到用户个人空间。**
> - 平台后端在 `spaceId` 为空时，会回退到**调用者的个人空间 ID**，而个人空间**不是**项目所在空间——建到个人空间的表不会出现在项目里，前端 SQL API 也读不到。
> - 正确做法：保证沙箱环境注入了 `DEV_SPACE_ID`（项目空间 ID），脚本会自动带上；**不要**通过修改脚本、手动构造请求体等方式绕过。
> - 排错第一反应：表建完后在前端/列表里看不到，先确认 `DEV_SPACE_ID` 是否正确指向项目空间，而不是个人空间。

## Python Script

The client script is located here:

```text
datatable-for-page-api/scripts/datatable_for_page_api.py
```

## Base URL

Table Definition endpoints are called under:

```text
$PLATFORM_BASE_URL/api/v1/4sandbox/compose/db/table/...
```

Table SQL API endpoints are called under:

```text
$PLATFORM_BASE_URL/api/v1/4sandbox/table/sql/...
```

For sandbox calls, `spaceId` in table-add and table-list requests is sourced automatically from the `DEV_SPACE_ID` environment variable by the script (see below). **Never omit or override it** — if `spaceId` is missing the backend falls back to the caller's personal space, which is NOT the project space and will create/query tables in the wrong place.

## Supported Endpoints

### Table Definition（表结构管理）

| CLI Command | Method | Endpoint | Description |
|-------------|--------|----------|-------------|
| `add-table` | POST | `/add` | Create a new table definition（新增表定义） |
| `update-table-name` | POST | `/updateTableName` | Update table name / description / icon（更新表名称和描述） |
| `update-table-definition` | POST | `/updateTableDefinition` | Update table field definitions（更新表字段定义） |
| `delete-table` | POST | `/delete/{id}` | Delete a table definition（删除表定义） |
| `list-tables` | POST | `/list` | Query table definitions paginated（查询表定义列表） |
| `get-table` | GET | `/detailById?id=` | Get table definition detail（查询表定义详情） |
| `exist-table-data` | GET | `/existTableData?tableId=` | Check if table has business data（查询表是否有数据） |
| `copy-table` | POST | `/copyTableDefinition?tableId=` | Copy a table structure（复制表结构） |

### Table SQL API（数据表SQL操作API管理）

| CLI Command | Method | Endpoint | Description |
|-------------|--------|----------|-------------|
| `table-sql-new` | POST | `/api/v1/4sandbox/table/sql/new` | Create a new SQL-based table operation API（创建数据表SQL操作API） |
| `table-sql-update` | POST | `/api/v1/4sandbox/table/sql/update` | Update an existing SQL-based table operation API（更新数据表SQL操作API） |

## Usage

```bash
python scripts/datatable_for_page_api.py --help
```

### Table Definition Examples

```bash
# Create a new table（创建表）
python scripts/datatable_for_page_api.py add-table --name "Users" --description "User information table"

# List tables（查询表列表）
python scripts/datatable_for_page_api.py list-tables --page-no 1 --page-size 20

# Get table definition detail（查看表结构详情）
python scripts/datatable_for_page_api.py get-table --table-id 123

# Update table name and description（修改表名称和描述）
python scripts/datatable_for_page_api.py update-table-name --id 123 --name "Users_v2" --description "Updated user table"

# Update table field definitions（修改表字段定义）
# ⚠️ fieldType 必须是数字枚举(1=VARCHAR,2=INT,5=DATETIME,6=BIGINT,7=TEXT)，不能传 "VARCHAR"/"INT" 字符串
# ⚠️ 注释字段 key 是 fieldDescription
# ⚠️ 必须传完整 fieldList(系统字段+自定义字段)，详见上文「⚠️ update-table-definition 关键约束」
python scripts/datatable_for_page_api.py update-table-definition --id 123 --field-list '[{"fieldName":"name","fieldType":1,"fieldStrLen":255,"fieldDescription":"user name","nullableFlag":true,"defaultValue":null,"uniqueFlag":false,"enabledFlag":true,"sortIndex":8,"systemFieldFlag":false},{"fieldName":"age","fieldType":2,"fieldStrLen":null,"fieldDescription":"user age","nullableFlag":true,"defaultValue":null,"uniqueFlag":false,"enabledFlag":true,"sortIndex":9,"systemFieldFlag":false}]'

# Copy a table（复制表）
python scripts/datatable_for_page_api.py copy-table --table-id 123

# Check if table has data（检查表是否有数据）
python scripts/datatable_for_page_api.py exist-table-data --table-id 123

# Delete a table（删除表）
python scripts/datatable_for_page_api.py delete-table --id 123
```

### Table SQL API Examples

> ⚠️ SQL 语句里的表名必须用 `get-table` 返回的 **`dorisTable`**（物理表名，形如 `custom_table_123`），**不能用建表时的表名（如"用户表"）**。
> 占位符：`{{var}}` 普通参数（LIKE 也用它，前端自拼 `%关键词%`）。

```bash
# Create a new SQL table operation API（创建数据表SQL操作API）
# 注意：FROM 后面是 dorisTable 物理表名 custom_table_123，不是表名"用户表"
python scripts/datatable_for_page_api.py table-sql-new \
  --project-id "100" \
  --table-id 123 \
  --api-name "queryUsers" \
  --description "Query users by name" \
  --sql "SELECT * FROM custom_table_123 WHERE name = {{name}}" \
  --args '[{"name":"name","description":"user name","require":true}]'

# Update an existing SQL table operation API（更新数据表SQL操作API）
python scripts/datatable_for_page_api.py table-sql-update \
  --project-id "100" \
  --api-id 456 \
  --api-name "queryUsersV2" \
  --description "Query users by name and age" \
  --sql "SELECT * FROM custom_table_123 WHERE name = {{name}} AND age > {{age}}" \
  --args '[{"name":"name","description":"user name","require":true},{"name":"age","description":"min age","require":false}]'
```

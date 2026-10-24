# 透视页（HTML 数据页）生成参考

> **何时读**：用户要给多维表格配「透视页 / 数据看板 / 可视化页面 / HTML 数据页 / **报告页 / 日报 / 杂志式页面 / 高级感动态网页**」——即一个挂在多维表格下、动态读写该数据表的 HTML 页面。数据表本体的字段/记录/视图操作看 [[ops-datatable.md]]。

## 0. 原理（为什么 HTML 能读到数据表）

- 透视页是一个普通 HTML 导入页，但 `<head>` 里带一个标记：
  ```html
  <meta name="repo-datatable-source" content="{数据表slugId}">
  ```
- 前端检测到该 meta 后把页面放进 `<iframe sandbox="allow-scripts allow-forms allow-modals">` 里执行（`HtmlRuntime`）：页面是 **opaque origin 的沙箱文档**，够不到宿主的 DOM / cookie / localStorage；并在脚本执行前注入 SDK：`window.repoDatatable`（已绑定 meta 指定的数据表，方法调用经 postMessage 桥由宿主**带登录凭据**执行）。
- 页面脚本用 `window.repoDatatable` 动态读写字段/记录——数据表一更新，页面刷新即最新值；页面里写回的数据，数据表里也实时可见。**页面自己的 `fetch()` 不携带登录 cookie（见 §0.5），一切数据读写只能走 SDK。**
- 没有该 meta 的 HTML 走纯沙箱 iframe（无任何宿主能力）——**meta 是透视页的开关，必须带**。
- 预览窗口高度 = 可视区高度（内容超出时 **iframe 内部滚动**），与页面自身内容高度无关；`100vh` 等视口单位因此等于「预览框可视区」，可自由使用（见 §0.5）。

### 0.5 沙箱能力边界（写模板前先看这张表）

| 能力 | 沙箱内行为 |
|---|---|
| `window.repoDatatable.*`（9 个方法） | ✅ 正常，宿主代理带凭据 |
| `document.title`、`repoHost.getTitle()/onTitle(fn)` | ✅ 读到**宿主推送的文档标题**（文档重命名时回调 `onTitle`）。⚠️ 注意：三者取到的是**宿主整页标题**（`document.title = 站点名 + " - " + 文档名`，同值），渲染前先剥掉首个 `" - "` 前缀段（§2.1） |
| `window.localStorage` | ✅ 不抛错（bootstrap 内存兜底）：**仅本次打开内有效，刷新即空**；写会异步备份到宿主但读不回 |
| `window.sessionStorage` | ✅ 不抛错（bootstrap 内存兜底），纯内存态 |
| `repoHost.getPref(key)/setPref(key,v)` | ✅ 真持久（落宿主 localStorage、按页面隔离），返回 Promise |
| 外链 CSS（`<link rel=stylesheet>`、Tailwind CDN）、JS 动态注入 `<style>` | ✅ **生效**（沙箱是独立文档，样式不会漏到宿主；跨域 CSS 无需 CORS 头，2026-09-13 实测计算样式命中）。建议仍以内联为主，只为 CDN 挂掉时页面不裸奔 |
| `position:fixed` | ✅ 可用，相对**预览框视口**（不再是宿主整屏） |
| 视口单位 `100vh` / `100dvh` | ✅ 可自由使用 = 预览框可视区高度；做「整屏一版」式排版没问题，超出部分 iframe 内滚动 |
| CDN `<script src>`（ECharts 等） | ✅（不受 CORS 约束）；平台规范只允许 `static-resources.nuwax.com` 的 JS |
| `fetch()` / XHR 任意 URL | ✅ 发得出，但**不带登录 cookie**（`SameSite=Lax`）→ 打平台接口一律 401；跨域读响应还要求对方回 CORS 头（`static-resources.nuwax.com` 没配，读不到） |
| `document.cookie` | ❌ 访问即抛 SecurityError |
| `window.open` / `target="_blank"` | ❌ 静默无效（无 `allow-popups`），返回 null |
| 顶层导航（`target="_top"`、`top.location`） | ❌ 被拦（console: `Unsafe attempt to initiate navigation…`） |
| **页内 `<a href="…">` 跳转**（含站内 `/repo/doc/{slugId}`） | ⚠️ **在预览框内导航**：透视页当场被目标页替换、用户回不去（顶层不变）。**不要放跳转链接**；`link` 字段渲染成纯文本（§3） |
| `<form>` 提交 | ⚠️ submit 事件正常派发（`allow-forms`），但真实提交被 CSP `form-action 'none'` 拦 → 必须 `preventDefault()` 自处理 |
| `<base href>` / `<object>` / `<embed>` | ❌ 被 CSP `base-uri 'none'` / `object-src 'none'` 拦 |
| `prompt()` / `confirm()` / `alert()` | ⚠️ 能弹（`allow-modals`）但会阻塞页面、且 Chrome 会记住「阻止此页面再创建对话框」导致后续静默失效。**规范禁用，用自绘弹层**（§5 骨架自带） |
| `<img src="/api/f/{key}">`（含 `<video poster/src>`、`<audio src>`、`<source src>`） | ✅ 运行时自动换址：bootstrap 观察 DOM，把裸相对 `/api/f/{key}` 换成免登录直访地址再写回。**异步**（先裂后好，给图加占位底色）；只认裸相对无查询串，绝对地址/带 `?` 的原样不动 |

## 1. 创建流程（沙箱 API）

```bash
AUTH="Authorization: Bearer $SANDBOX_ACCESS_KEY"
BASE="$PLATFORM_BASE_URL/api/v1/4sandbox/repo"

# 1. 生成完整 HTML 字符串（含 meta + style + script，全内联），base64 编码
B64=$(printf '%s' "$HTML" | base64)   # 或 python3 -c "import base64,sys;print(base64.b64encode(open(sys.argv[1],'rb').read()).decode())" page.html

# 2. 🔴 导入前自检：一条命令跑「静态门禁 → 本地真渲染断言」（别拿生产当调试器，见 §1.0）
bash scripts/pivot_check.sh page.html <数据表slugId> <数据表pageId>
#    退出码 0 = 门禁过了 + 断言全过（可以导入）  1 = 被拦下（门禁 ERROR 或断言失败，输出会指名道姓）
#            4 = 门禁过了但**渲染没被验证**（没给数据表 pageId / 用了 --no-assert）—— 未完整验证，别当通过
#            5 = 环境不具备（本机没 Chrome，断言跑不了）—— 不假装通过
#    「渲染对不对」由这条命令判，不需要人眼；只在想看视觉（配色/留白）时再截图
#    没有 AK / 暂时不在网：先录一次快照，再离线回放（见 §1.0）
#      bash scripts/pivot_check.sh page.html <slugId> --from-file <快照.json>

# 3. 导入，挂在多维表格页下（parentId 必须 = 多维表格的 pageId）
curl -s -X POST "$BASE/import" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"spaceId\":57,\"parentId\":<多维表格pageId>,\"title\":\"数据看板\",\"fileName\":\"dashboard.html\",\"content\":\"$B64\"}"
# → data.pageId / data.slugId

# 4. 校验解析成功
curl -s "$BASE/pages/by-slug/<slugId>" -H "$AUTH"   # data.contentSize > 0（必要非充分，见下）

# 5. 验收（必做，见 §1.1）：断言清单 + 活体测试
```

**🔴 铁律：写码 → `pivot_check.sh`（静态门禁 + 本地真渲染**断言**）→ 导入 → 线上复核。禁止跳过本地渲染。**

**「渲染对不对」交给断言，不交给眼睛**：看一眼只能发现「明显崩了」，发现不了「少一个分组桶」「某列全是 NaN」「窄预览框里横向溢出」——而这些恰恰是要靠人复述、且下次回归时没人会记得的。所以渲染验证做成**机器可读的断言**：退出码 + 每条断言的实测值。人眼只剩「好不好看」（配色/留白/字体层次），需要时用 `--shots` 落图再看。

**为什么把两步合成一条命令**：分开跑时，最省事的路径是「只跑 lint 就导入」——而 lint 是**静态**的，它查不出「字段名对不上、真数据下渲染成空态、图表拿不到字段」这类只有真数据才暴露的问题，这些恰恰最贵（要再导一次、白烧一个 `slugId`）。合成一条命令后，**跳过渲染验证变成需要主动做的事**（`--no-assert`），且没给数据源时会**响亮地报「未完整验证」并退 4**，不会静默放行。

**为什么这条是铁律**：`/import` **只会新建一个页面，不会覆盖旧的**——渲染问题（图表整行坍缩、分组漏桶、配色糊成一片）只有在真渲染里才看得见，而一旦导进去才发现，就得**再导一次，白烧一个 `pageId`/`slugId`**。而 `slugId` 会变，恰恰是改版最贵的代价：侧栏入口、页面互链、分享链接、评论锚点全部作废（详见下文「⛔ 不要为了改版去删已有透视页」）。本地跑一遍的成本几乎是零，**跳过它没有任何理由**。
> 实测教训：先导了带 bug 的版本，在浏览器里才发现 CSS 类名撞车（`.g2`）导致图表整行坍缩，只好再导一次——**两个 pageId 换来一个本可以 10 秒本地发现的问题**。根因不是「不会验」，而是「部署比本地看一眼更顺手」——所以这一步必须**固化成脚本**，不能靠自觉。

要点：
- **HTML 用 `content`（base64）不用 `text`**——text 会剥标签，页面直接废掉。
- **`parentId` 必须传目标多维表格的 pageId**——透视页一定创建在数据表页的下级（与前端「＋新建 HTML 页面」同位置），便于统一管理、一目了然。**不传则落在空间根、和多维表格散开，禁止这么做。** 若 context 里没有明确的父页面 id，先 `GET /pages/{pageId}`（拿数据表页）取它的 `data.id` 当 parentId。
- `meta content` 填**数据表的 slugId**（`GET /pages/{pageId}` 返回里的 `slugId`），不是 pageId。
- **✅ 更新已有透视页：可以原地改（2026-09-13 起支持，勿再删页重建）** —— `POST /pages/{pageId}/content`，body 显式带 `kind:"html"`（**必须显式传**：不传时服务端按页面类型推成 `doc`，会落错分支）。两条指令：

  | 指令 | 参数 | 用途 |
  |---|---|---|
  | `replaceText` | `find`、`replace`、`all?` | **定向打补丁（首选）**：只对不满意的那一小处下手，其余逐字保留 |
  | `replaceHtml` | `html` | 整份替换（大改版式时用） |

  **读回源码**：`{"kind":"html","ops":[{"op":"read"}]}` → `data.snapshot.html`（可与其他写指令同批下发，回的是**写完之后**的最新源码）。⚠️ `GET /pages/{pageId}/content` **读不到**——它按 `kindOf()` 给 html 页报 `doc`，且 GET 没有 kind 入参，必须走 POST 的 `read`。
  **严格失败，不再有静默假成功**：`find` 为空 / 在源码里**命中 0 次** / 命中多次但未给 `all:true` → **明确报错**（不会再出现「返回 `0000` 却一个字没改」）。所以**先 `read` 拿准确原文，再 `replaceText`**，别凭记忆猜 `find`。
  ⚠️ **但 op 名写错仍会被静默跳过**：html 只认 `read`/`replaceText`/`replaceHtml`，发了别的名字（如 doc 类的 `appendBlocks`、或 `replaceDoc`/`setText` 这类不存在的名）→ `applied:0` + `skipped:1`、**不报错**、页面纹丝不动。
  **判改没改**：看 `data.applied > 0` ＋ `GET /pages/{pageId}` 的 `contentSize`/`editedAt` 有无变化，**别只看 `code`**。（注意 `replaceText` 若是同位替换，`contentSize` 本来就该不变——那是正常的，不是没生效。）
  **原地改的好处**：`pageId`/`slugId` 不变 → 侧栏入口、页面互链、分享链接、评论锚点、版本历史**全部保留**；已打开该页的用户**无需刷新**即看到新内容。正文按**最小 diff** 写入，不整篇重写、不会毁掉正在协同编辑者的光标。
  **⛔ 不要为了改版去删已有透视页。** 正文既然能原地改，「删掉旧的、重新导一个新的」就更加**没有任何理由**——**禁止**。删页会换掉 pageId + slugId，**旧链接全部失效**（页面互链、外部收藏、分享链接、别人侧边栏里的入口一并作废），而它出现在用户的侧边栏里，就是**用户的可见资产，不是你的临时产物**。先按需求分流：
  1. **改数据**（记录增删改）→ **什么都不用做**。透视页是动态读数据表的，用户刷新页面数字自己就变了——这正是透视页的设计目的。
  2. **改标题 / 换位置** → `POST /pages/{pageId}/rename`、`POST /pages/{pageId}/move`，**不删页**。
  3. **改版式 / 改样式 / 改逻辑** → **先试原地改**（`replaceText` 打小补丁；改动大就用 `replaceHtml` 整份换，`slugId` 不变），见上。只有「推倒重做、旧页再无保留价值」时才**新建一个新页并排保留旧页**，交给用户确认新版没问题后自己决定旧页去留。**不要替用户删。**
  4. 只有用户**明确说「删掉这个页面」**时，才执行 `delete`。
  因此工作方式：**新建页时**本地改好 → 跑门禁 + 浏览器验证通过 → 一次 import 到位；**上线后的小改动直接原地 `replaceText`**（先 `read` 拿原文），**不必也不许删页重导**。
  ⚠️ 改 html 页**只用 `kind:"html"`**，别拿 doc 类 ops（`appendBlocks` 等）当偏方：它改的不是正文真源（`htmlRaw`），顶多触发 Sidecar 快照回写，页面渲染仍是旧的。**别自己发明绕路。**
- `contentSize > 0` 只证明内容写入了（必要非充分条件）；最终渲染效果**以前端打开为准**——发布后自己打开一次页面，确认数据读得到、版式正确、console 无报错。

### 1.0 本地渲染 + 断言（导入前必做）

`scripts/preview_stub.js` 把数据表的**真实快照**按宿主 SDK 的真实解析语义，灌进一个本地桩里，产出一份可打开的页面；加 `--assert` 时还会**用系统 Chrome 真渲染它并逐条断言**（零 npm 依赖，走 CDP）。

```bash
# 联网模式（拉真实快照）
PLATFORM_BASE_URL="$PLATFORM_BASE_URL" SANDBOX_ACCESS_KEY="$SANDBOX_ACCESS_KEY" \
  node scripts/preview_stub.js <数据表pageId> page.html [输出.html] [选项]

# 离线模式（回放一份快照文件，不需要任何凭据、不联网）
node scripts/preview_stub.js --from-file <快照.json> page.html [输出.html] [选项]
```

| 参数 | 说明 |
|---|---|
| `<数据表pageId>` | 数据表**页**的 pageId（不是 slugId）——脚本要拉它的快照 |
| `<页面.html>` | 待预览的页面文件 |
| `[输出.html]` | 缺省 `/tmp/pivot-preview-<页面名>.html`（**不写工作目录**） |
| `--from-file <json>` | **离线回放**：直接读快照文件，不联网、不需要 AK。接受平台 API 原始响应 / `{snapshot}` / workbook 本身三种形态 |
| `--save-snapshot <json>` | 联网模式下顺手把拉到的**原始**快照存成文件，供日后 `--from-file` 回放 |
| `--readonly` | 以**分享页访客**身份预览：`read()` 正常，写方法按真实语义 reject——验只读门控用这个 |
| `--print-payload` | 额外打印注入的 `read()` 数据结构（排查「页面读到的是什么」时用） |
| `--assert` | **开真渲染断言**（见下）。不加则只出预览文件，退出码 4，表示「渲染未验证」 |
| `--expect '<json>'` | 追加自定义期望，如 `'{"containsText":["本月支出"]}'`、`'{"maxWriteCalls":0}'`、`'{"probes":["张三"]}'` |
| `--fail-writes` | 让写方法一律 reject：验「写失败时页面不崩、不假装成功」 |
| `--shots <dir>` | 落每个视口的截图（**只在要看视觉时用**，断言本身不依赖截图） |
| `--json-out <file>` | 把完整断言结果写成 JSON（给 CI / 后续脚本消费） |

**期望值从哪来**：默认**自动推导**（记录数、字段数、是否应为空态，探针取快照里的真实数据值），所以多数页面**不用写任何期望**。要加语义级期望（「本页必须出现『本月支出』」「渲染期不许有任何写调用」）有两种写法，**`--expect` 覆盖 meta 的同名键**（命令行更靠后）：

```html
<!-- 页面自带期望：谁跑这个页面都会按这套细则验 -->
<meta name="pivot-assert" content='{"containsText":["本月支出"],"maxWriteCalls":0}'>
```
```bash
node scripts/preview_stub.js <数据表pageId> page.html --assert --expect '{"containsText":["本月支出"]}'
```
⚠️ `content` 是 JSON，**外层用单引号包**（JSON 里的双引号就不会冲突）；写坏成非法 JSON 会**退 2 并指出**，不静默忽略。

**没装 Chrome 怎么办**：`--assert` 会退 **5**（不是 0），并提示装 Chrome 或 `PIVOT_CHROME=<可执行文件路径>`。**不假装通过**——脚本化验证需要真浏览器；实在没有就显式 `--no-assert` 走退 4 口径，并如实说「渲染未验证」。

**断言查什么**（每条都带实测值，失败时直接告诉你是哪条、差多少）：

| 断言 | 抓的是 |
|---|---|
| `page.not-blank` | 白屏 / 整页没渲染出来 |
| `sdk.injected` + `sdk.read-ok` | 数据桥没注入、`read()` 报错（典型：slugId 对不上） |
| `render.has-data` | **用快照里的真实数据值当探针**去页面可见文本里找——找不到任何一条 = 「字段名对不上 / 结构不匹配 / 数据没喂进去」（模板无关，不依赖任何 class 名或空态文案） |
| `render.no-loading` | 卡在「加载中…」 |
| `text.no-anomalies` | 页面出现 `NaN` / `undefined` / `[object Object]` |
| `runtime.no-page-errors` / `console.no-errors` | 未捕获异常 / console error |
| `layout.no-horizontal-overflow` | 横向溢出（可在多个视口宽度下判） |
| `charts.no-collapse` | canvas 图表坍缩成 0 高 |

**环境不具备就报「未验证」**：本机连不上外链 CDN 时 ECharts 加载不了，脚本会主动阻断外链、页面走「图表库缺失」兜底，并把 `charts.not-verified` 报成 **warn**——是「没验证到」，不是「通过」。**别把 warn 读成绿。**

#### 一条命令：`scripts/pivot_check.sh`

```bash
bash scripts/pivot_check.sh <页面.html> <数据表slugId> [数据表pageId]
bash scripts/pivot_check.sh <页面.html> <数据表slugId> --from-file <快照.json>
bash scripts/pivot_check.sh <页面.html> <数据表slugId> --from-file <快照.json> --no-assert   # 只出预览文件
```

串起「静态门禁 → 本地真渲染断言」两步，**任一步失败即停**（门禁不过就不进渲染，断言不过就点名报错）：

| 退出码 | 含义 | 怎么办 |
|---|---|---|
| `0` | 门禁过了 **+ 断言全过** | 可以导入 |
| `1` | 被拦下（门禁有 ERROR，或断言失败） | 按输出逐条改，**别导入** |
| `2` | 参数错（文件不存在 / 缺 slugId） | 看提示补参数 |
| `4` | 门禁过了，但**渲染没被验证**（没给数据源，或显式 `--no-assert`） | **未完整验证**，别当通过——补 pageId / `--from-file`，或去掉 `--no-assert` |
| `5` | **环境不具备**（本机没 Chrome，断言跑不了） | 装 Chrome，或 `PIVOT_CHROME=<路径>`；不想装就显式 `--no-assert` 但那是退 4 |

⚠️ **退 4 / 退 5 都不是「通过」**：静态门禁查不出「数据桥读不到数据、字段名对不上、图表取不到字段」这类只有真数据才暴露的问题；而没跑断言就等于这些问题**根本没被检查过**。这两种情况脚本都会打印醒目横幅，**别把它们当成可以导入的信号**。

#### 多视口 + 坏数据演练：`scripts/e2e_pivot.mjs`（改版收尾 / 交付前）

```bash
node scripts/e2e_pivot.mjs --page family.html --from-file snap.json \
  --viewports 1440x900,390x844,320x375 --fail-writes --corrupt --writes-threshold 20 --shot-dir /tmp/shots
```

与 `preview_stub.js --assert` 的分工：**导入前跑后者就够**（单视口、快），改版收尾/交付前跑前者（更全）。两者**共用**同一套桩、注入与判定内核，不会出现「一个绿一个红」。

- **多视口**：透视页可能只有 400px 宽（预览框），也可能全屏 1440px。默认 `1440x900,390x844,320x375`。窄屏下的横向溢出、指标条挤成一列、看板列塌成 0 宽，都是只在桌面宽度验证过的页面才会漏掉的问题。
- **`--fail-writes`**：写方法一律 reject，验「写失败时页面不崩、不假装成功」。
- **`--corrupt`**：四个**真实发生得出来**的坏数据形态——记录清空 / 表头清空 / 类型错乱 / **网格维度非法（线上真出过「负数 rowCount → 界面整片空白」的事故）**，每个变体都要求「不白屏、无报错、不吐 NaN」。其中「清空」类变体另带**自证断言**（原数据值一个都不许再出现）——若毒没投到页面上，演练就得报红，不许假绿。
- **已知局限（如实记）**：`--corrupt` 只演练页面的**默认视图**。模板若在默认视图里不显示数字/日期、也不读 `rowCount`，那「类型错乱」「维度非法」对它就是 no-op——**这不是通过，是没覆盖到**。要覆盖就切到会用到这些的视图再测。

#### 宿主怎么读你的 HTML（两处裸正则，务必知道）

宿主加载透视页时做两件事，**两件都是拿正则跑在原始 HTML 文本上、取第一个匹配**，既不剥注释也不建 DOM：

| 宿主在找 | 取法 | 你写错位置的后果 |
|---|---|---|
| **目标数据表 slugId** | 第一个 `repo-datatable-source` meta 的 `content` | 页面 `read()` 报 `forbidden`（读到的是被误当 slugId 的那串字） |
| **SDK / CSP 的注入点** | 第一个 `<head>` 之后 | 注入落在注释里变成**惰性的**：页面永远走兜底面板，一条数据都读不到 |

**所以下面这些写法是地雷**（它们会让「注释里的示例标签」抢走「正文里的真标签」）：

- `<!-- 用法：<meta name="repo-datatable-source" content="这里填slugId"> -->` 写在真 meta **之前** —— 宿主取到 `"这里填slugId"`；
- `<!-- 结构：<head> 放 meta -->` 类说明文字写在真 `<head>` **之前** —— 注入点被抢；
- `<script>document.write('<meta ...>')</script>` 之类的动态生成标签排在真标签之前。

**最坑的地方是它只在线上炸**：本地预览的数据是**桩喂的、压根不读 meta**，所以这类页面本地渲染得好好的，上线却一条数据读不到。因此：

- `pivot_lint.py` 与 `preview_stub.js` **都会**按宿主的裸正则规则模拟一遍，命中即**硬失败**并指出行号（lint 这一步刻意放在**剥注释之前**跑）；
- 写注释时**不要出现带尖括号的示例标签**——想说明就写成不含尖括号的文字（如「meta 里填 slugId」「head 里放 meta」）。

#### 「需要凭据 / 登录态」的东西怎么自检

**核心：把「需要登录态」当成本地可复现的前置条件来消除，而不是每次去借一个登录态。** 分三层看，绝大多数自检根本不需要登录：

1. **契约层（不需要凭据）**——端点路径、响应外壳（`code`/`data.snapshot.sheets[].cellData`）、字段结构与类型。用离线 fixture 就能断言。**「本地预览桩」验的就是这一层**：它断言的是「桩按真实语义解析快照」，不是「线上数据对不对」。
2. **数据层（需要一次真访问）**——记录值、字段选项、真实行数。用 **`--save-snapshot` 录一次**（那一次用真凭据），之后**永久离线回放**：`--from-file` 走的是与联网完全相同的解析路径，实测注入结果**逐字节一致**，所以离线跑绿等价于联网跑绿。录制只需一次登录，之后 CI/回归零凭据。
3. **端到端层（必须真登录）**——写权限、分享访客只读、协同实时刷新。这层**替代不了**，只能上线做（§1.1 活体测试 + 只读门控实测）。

⚠️ **不要把「没凭据」当成「跳过自检」的理由**——那是把成本推给了线上。能录就录，能离线就离线；确实只剩端到端那层做不了的，**如实说明「未验证」**，不要含糊成「已验证」。

#### 凭据从哪来：只用 SANDBOX_ACCESS_KEY，不要别的

本技能做任何事（含本地预览）**只需要 `SANDBOX_ACCESS_KEY`（AK）**——它是技能本来就持有的凭据。具体是：

- **透视页运行时**：**不需要任何凭据**。页面在沙箱 iframe 里靠宿主注入的 `window.repoDatatable` 读数据，凭据由宿主代理携带（这正是铁律 3「绝不把 AK/ticket 写进 HTML」的原因）。所以「沙箱里没有密钥」是**设计如此**，不是缺陷。
- **本地预览 `preview_stub.js`（导入前自检）**：用 AK 打 `GET /pages/{pageId}/content` 拿原始 workbook 快照——**这条路径本来就走 AK，技能天然可用**。要的是**原始** workbook（脚本自己按 Sidecar 语义解析，这正是「桩与线上一致」的关键），而该端点返回的恰好就是原始快照。

⛔ **技能不需要 AK 以外的任何密钥**——**不要去查找、索取或使用任何服务端内部密钥或内部接口凭据**。那类东西是服务端组件之间互相认证用的，客户端持有并使用属于**越权**；技能里也**不列举它们**（列举本身就等于指路）。技能的正确凭据面**只有 AK**（分享场景另有 `shareToken`），到此为止。

> **任何「这个能力需要服务端密钥才能验证 / 才能调」的说法都是错的。** 所有能力都有 AK 通道；遇到 `4030`/`4000`/`4040` 是「AK 与该环境不匹配」的**配置问题**，解法是换一个对该环境有效的 AK，**不是绕过鉴权**。

**AK 报错怎么读**（`preview_stub.js` 已内建这些诊断）：

| 报错 | 含义 | 怎么办 |
|---|---|---|
| `4040` | **该 base 没部署 repo 模块**——`PLATFORM_BASE_URL` 指错了环境 | 换到实际部署了资料库的环境域名，重试 |
| `4000 无效的 API Key` | 端点存在，但这个 AK 对**该环境**无效 | 换一个绑定到该环境成员 user 的 AK |
| `4030` | AK 缺失 / 无效 / 过期 | 补头，或 `POST /api/v1/4sandbox/file/ak/refresh` |
| `0001 page not found` | AK 有效但无权读该页 | 换绑定到该 space 成员 user 的 AK；或确认 pageId 是数据表页 |

**AK 暂时不可用时**：用 `--save-snapshot` 在 AK 可用的那一刻录一次，之后 `--from-file` 离线回放——**回放不联网、不需要任何凭据**。这也是把「一次真访问」摊薄成「永久离线回归」的办法。

**为什么能本地跑**：透视页是纯静态单文件，**唯一的外部依赖就是 `window.repoDatatable`**。把它桩掉，页面其余部分（渲染、图表、交互）与线上**逐字相同**——所以本地看得见的渲染问题，线上一定也会发生；本地看不见，线上也不会凭空冒出来。

**它替代不了什么**（桩的边界，务必知道）：
- 数据是**拉取那一刻的快照**，不是实时的 → 「活体测试」（改一条记录看数字跟不跟着变）仍**必须上线做**（§1.1）。
- 写操作**只打印不落库** → 写完后的回显逻辑能验，真实写权限不能验。
- 图片 `/api/f/{key}` **不做换址**（本地没有宿主换址能力）→ **裂图是预期现象**，别当 bug 修。
- ⚠️ **桩的解析语义逐行对齐 sidecar 的真实实现**，其中两处反直觉但线上就是如此，**不要按「直觉更合理」去改桩**（本地与线上不一致的预览，比不预览更糟）：
  1. `rowCount`/`columnCount` 是**网格维度**（`sheet.rowCount`，缺省 20），**不是「记录数 / 字段数」**；
  2. `records[i].values` 里**每个字段列都有 key**（空值写空串），不是「空值就不出现」——模板可能靠 key 的存在性判断「这列有没有值」。

### 1.1 验收（必做，「能打开、有数据」不算数）

**写死数据的页面同样「能打开有数据」**——所以「打开了、图表出来了」不是验收，**能对上数**才是。分三层：**第一层机器断言、第二层对账、第三层必须上线**。

#### 第一层：静态 + 本地真渲染断言（导入前，机器判）

```bash
bash scripts/pivot_check.sh page.html <数据表slugId> <数据表pageId>   # 必须 0；4 = 没验证；5 = 没 Chrome
```

**这一层不靠肉眼**——退 0 的含义就是「上面的断言逐条都过了」（数据上了屏、字段名对得上、没 NaN、没横向溢出、图表没坍缩）；退 1 会指名道姓报出哪条失败、实测值多少。**退 4 / 退 5 都等于「没验证」，不是通过**。**这一层过不了的页面不要导。**

**人眼只剩「好不好看」**：配色、留白、字体层次这类主观项断言管不了——需要时 `node scripts/preview_stub.js <数据表pageId> page.html --assert --shots /tmp/shots` 落图再看。**别把「肉眼看了一遍，看着还行」当成渲染正确性的证据**：那是这一层之前的老做法，漏掉的恰恰是「少一个分组桶」「窄屏横向溢出」这类。

#### 第二层：写操作要对账（导入后 / 每次原地改后）

**别用「看上去是对的」当验收**——写操作（导入、AI 编辑、批量写单元格）之后要用**基线 + 逐行比对**证明「改动就是你想要的那些，没有残留」：

```bash
# ① 写之前：落基线
node scripts/dt_rows.js <数据表pageId> --save /tmp/base.json
# ② 执行写操作（导入 / AI 编辑 / 改单元格）
# ③ 写之后：与基线比
node scripts/dt_rows.js <数据表pageId> --diff /tmp/base.json
#    → 打印 NET_ZERO = 逐行逐字段一致；有差异则逐条列出并退 3
```

看输出时的判断标准：**每一处差异都应该是你这次故意改的**；多出来的任何一处（尤其「新加的列在每一行都冒出一个值」「本该保留的行被清了」）就是**残留**。退 `3` 不是「工具报错」，是「有差异，请逐条认领」。

其他有用的用法：

```bash
node scripts/dt_rows.js <数据表pageId> --list                # 先看清有几张表、各多少行
node scripts/dt_rows.js <数据表pageId> --sheet 备注表         # 只看某一表（名字或从 1 起的序号）
node scripts/dt_rows.js <数据表pageId> --diff /tmp/base.json --all   # 所有表都比（默认只比首表，并会提示哪张没比）
node scripts/dt_rows.js --from-file <快照.json>              # 离线读回（不联网、不需要 AK）
```

⚠️ `--save` 落的**基线**与 `--from-file` 要的**原始快照**是两种文件，别混（混了脚本会明确告诉你换哪个，不会崩）。

#### 第三层：线上断言清单（必须真登录，替代不了）

逐条核对，**任何一条对不上就是没通过**：

| # | 断言 | 为什么 |
|---|---|---|
| 1 | **URL 里的 `slugId` 与导入前一致**（原地改的场景） | 变了说明变成了新建页，侧栏入口/互链/分享链接全废 |
| 2 | `GET /pages/by-slug/{slugId}` 的 `contentSize > 0` | 必要非充分：只证明内容写进去了 |
| 3 | **分组轴名与数据表字段名逐字一致** | 差一个字（含空格/全半角）就会静默渲染成空桶 |
| 4 | **桶数 == 该字段去重取值数 + 1**（含「空值」桶） | 漏空值桶是最常见的错，且会让数字自相矛盾 |
| 5 | **Σ(各桶数字) == 记录总数** | 与数据表实际记录数对齐（不是「筛过的数」） |
| 6 | **页面上的「N / M」等汇总数与数据表实际值一致** | 写死数据的页面这里必然对不上 |
| 7 | 图表标题 / 轴标题与字段名一致 | 复制粘贴改字段时最容易漏 |
| 8 | 改数据表一条记录 → 页面刷新 → 数字**跟着变** | **不变 = 数据写死了，回炉**（活体测试） |

#### 线上核查的手法（省上下文、且不做无用功）

- **快照一律落盘再局部查**：`take_snapshot(filePath=...)` 写到临时文件，然后只 `grep` 你关心的那几个数（分组名、总数、标题）。把整页 a11y 树灌进上下文既贵又更容易看漏。
- **不要记 uid 跨步骤复用**：页面一 reload，uid 前缀**全变**，上一轮的 uid 一定失效——每次交互前**重新取快照**。
- **跨沙箱 iframe 拿不到 JS 句柄**：透视页在沙箱 iframe 里渲染（无 `allow-same-origin`），所以**宿主侧执行 JS 碰不到页面内部 DOM**。要断言渲染结果只能用 a11y 树 / 截图；要在页面里直接跑 JS，就用**本地那份预览文件**（`file://` 打开，无沙箱限制）——这也是「导入前本地预览」值得当铁律的原因之一。
- **验证前端是否已生效必须带 `?cb=` 之类的破缓存参数**（线上 `index.html` 没有 `cache-control`，直接刷新可能拿到旧壳）。

⚠️ **这一层替代不了，也编不了**：做不了就**如实说「未验证」**，不要把「第一层过了」说成「验证通过」。

**⛔ 删除禁令（与 `SKILL.md` 全局禁令同一条，此处重申）**：**不得删除任何页面，除非用户明确说出要删哪个页面**——不限于透视页，`doc`/`sheet`/`pptx`/`datatable`/文件夹全部适用，**包括你自己先前建的**。「改版 / 重做 / 清理 / 去重 / 整理 / 觉得多余 / 我建的 / 重新导一个更干净」**一律不构成删除理由**；`/delete` 是软删**不可恢复**，且会连带废掉侧边栏入口、互链、收藏、分享链接、评论锚点、版本历史——这些入口大多不在你的上下文里。

**标准动作是「新建新版 → 打开验证 → 保留旧版 → 由用户自己决定旧版去留**」，**不是**「建新版 → 删旧版」。改版需求先按 §1 的 1/2/3/4 分流；**执行 `/delete` 前必须能引用用户原话里点名要删的那个页面，做不到就不删、去问——拿不准时默认不删。**

## 2. SDK：`window.repoDatatable`

全部方法返回 Promise，失败 reject（`Error.message` 是服务端原因）。多张数据表时方法都接受可选尾参 `sheetId`（缺省操作首表）。

| 方法 | 参数 | 说明 |
|---|---|---|
| `read()` | — | 读结构快照（见下） |
| `setCellValue(row, col, value, sheetId?)` | row≥1，value: string/number/boolean/null | 写记录单元格，null 清空 |
| `appendRecord(values, sheetId?)` | values: `{列号: 值}` | 追加记录（落首个空行，无空行自动插行）；values 不能为空对象 |
| `insertRecordAt(row, sheetId?)` | row≥1 | 在第 row 行之前插空记录 |
| `removeRecordAt(row, sheetId?)` | row≥1 | 删除第 row 行 |
| `setField(col, name, schema?, sheetId?)` | col≥0 | 设置字段名+schema（schema 结构见 [[ops-datatable.md]] §3.3） |
| `addField(col, name, schema?, sheetId?)` | 同上 | setField 的语义别名 |
| `removeField(col, sheetId?)` | col≥0 | 删字段（清表头+清该列） |
| `setViewMeta(viewMeta, sheetId?)` | 任意可序列化值 | 写视图配置（结构见 [[ops-datatable.md]] §5） |

`read()` 返回：

```jsonc
{
  "sheetId": "sheet-1",
  "rowCount": 21, "columnCount": 22,
  "fields": [ { "col": 0, "name": "任务", "schema": { "type": "text" } } ],
  "records": [ { "row": 1, "title": "官网改版", "values": { "0": "官网改版", "1": "opt-doing" } } ],
  "viewMeta": null,
  "editable": true,          // 当前会话可否写：分享访客为 false
  "tables": [ { "sheetId": "...", "name": "数据表1", "fields": [...], "records": [...] } ]  // 多数据表；旧后端可能缺失
}
```

- 顶层 `sheetId`/`fields`/`records` 永远是**第一张数据表**（`sheetOrder[0]`）的；其余数据表在 `tables[]` 数组里，写方法用尾参 `sheetId` 定位目标表（单表场景两者一致）。
- `records[i].values` 的 key 是**列号**，值一律字符串（复合值是 JSON 字符串，见 §3）。
- `records[i].row` 是物理行号，写回（setCellValue/removeRecordAt）就用它。
- **`editable === false` 时隐藏所有写入口**（新增/删除/编辑按钮），只做只读展示——分享页访客、只读协作者都会拿到 false；不调门控直接调写方法会 403。
- 写操作成功后要回显，**重新 `read()` 一次**再渲染（本地数据不会自动更新）。
- `read()` resolve 的**就是快照对象本身**（`{sheetId, fields, records, …}`），**没有 `ok` 字段**；失败是 reject。别写 `if (d.ok)` 这类判断（会恒为假、页面永远「加载失败」）。

### 2.1 宿主能力：`window.repoHost`

数据之外的少量宿主能力（bootstrap 注入，全部页面可用）：

| 方法 | 返回 | 用途 |
|---|---|---|
| `repoHost.getTitle()` | string | 当前文档标题。⚠️ **与沙箱内 `document.title` 同值，都是宿主整页标题**（形如 `站点名 - 文档名`）——**用前先剥前缀**（下方 `stripSite()`），别直接显示 |
| `repoHost.onTitle(fn)` | — | 标题变化回调（文档重命名时触发），回调参数**同样带站点前缀**；配合 `document.title` 让页面主标题跟随改名 |
| `repoHost.getPref(key)` | Promise\<string\|null> | 读跨会话偏好（宿主 localStorage，按页面隔离） |
| `repoHost.setPref(key, value)` | Promise | 写跨会话偏好；「记住我选的视图/折叠态」用它，别用 localStorage（刷新即空） |
| `repoHost.resolveFileUrl(path)` | Promise\<string\|null> | 把 `/api/f/{key}` 换成免登录直访地址；**一般不用手调**——`<img>/<video>/<audio>/<source>` 的裸相对 src 已由 bootstrap 自动换址（§0.5），只有要拿地址做 canvas/下载时才调 |

标题跟随的标准写法（放进每个模板的 `load()` 之前）。**关键：宿主标题带站点名前缀**（宿主侧是 `document.title = site ? site + ' - ' + title : title`），剥掉首个 `" - "` 段才是文档真名：

```js
function stripSite(t) {                                   // "站点名 - 文档名" → "文档名"
  t = String(t == null ? document.title || '' : t);
  var i = t.indexOf(' - ');
  return i > 0 ? t.slice(i + 3) : t;
}
var titleEl = document.querySelector('.hd h1');           // 换成你的主标题节点
function syncTitle(t) { titleEl.textContent = stripSite(t); }
syncTitle(document.title);                                // 沙箱内 document.title 就是宿主整页标题
window.repoHost && window.repoHost.onTitle(syncTitle);    // 回调参数同样带前缀，故再 strip 一次
```

## 3. 复合值解析（渲染前必做）

| 字段类型 | values 里的形态 | 页面解析 |
|---|---|---|
| select / multiSelect | **选项 id**（多选逗号连接，如 `"opt-a,opt-b"`） | 用 `field.schema.options` 建 id→{name,color} 映射，渲染彩色 pill；映射不到原样显示 |
| checkbox | `"true"` / `"false"` / 布尔 | 渲染 ✓ 或开关 |
| attachment / image / media | JSON 数组字符串 `[{"url","name","size"}]` | `JSON.parse` 后渲染：图片直接 `<img src="url">`（url 是 `/api/f/{key}` 裸相对路径，bootstrap 会**自动换成免登录地址**，异步、先裂后好，建议给占位底色）；文件类渲染成**文件名纯文本**（见下） |
| link | JSON 字符串 `{"s":slugId,"r":行号,"t":标题}` | 渲染**纯文本**标题 + 灰字 slugId 提示：沙箱内 `<a href>` 点击会把预览框自身导航走（§0.5），跳转链接会毁掉透视页 |
| user / group | 逗号连名（`"张三,李四"`） | split(',') 渲染头像/名单 |
| date | 展示串 `"2026-09-30"` | 直接用 |
| number / currency / progress / rating | 数字串 | 按 schema 的 currency/decimals/max 格式化 |

解析一律 try/catch：JSON.parse 失败按纯文本兜底显示，别让整个页面崩。

## 4. 样式规范（默认偏好 + 可发挥）

**总原则：风格由用户要求驱动，偏好是默认基线而非死规则。** 用户没指定风格时，走下面的黑白灰高级感基线；用户提出了明确风格诉求（「报告风 / 杂志风 / 编辑部风 / 现代极简 / 暗色」…）**以用户要求为准**，基线规则可作为骨架但允许灵活发挥，不必逐条死守。生成「报告 / 网页 / 可视化界面」这类重点产出时，**优先用 `frontend-design` 技能**（见 §4.2）托底高质量设计。

### 4.1 默认基线（用户未指定风格时）

整体基调：**黑白灰为主、简洁大方、高级感**；整体观感更像一张**精致的网页 / 出版物**，而不是一个表格工具界面。缺省遵守：

1. **页面不加背景颜色**——白底，不用灰底/渐变底/纹理。（用户要求氛围背景时可破例，见 §4.2）
2. **按钮不用红色等过重颜色**——主按钮黑/深灰（#1f2329 系）或淡蓝，禁止大红大紫。
3. **标题前不加竖线**——纯文字标题，不用 `border-left` 装饰条。
4. **数据表 tab 前不要序号**——多表切换 tab 只显示名称。
5. **选中 tab 不要下划线，选中字体加粗 + 淡蓝色 `#6b8cff`**。
6. **每行数据需要横线分隔**——表格/列表行用 `border-bottom: 1px solid #f0f0f0` 级别的分隔线。
7. **卡片模式不需要专门标题区，第一行直接显示字段内容**；**卡片和表格不要同时出现**——右上角一个切换按钮，二选一显示。

排版细节建议：字体用系统栈（`-apple-system, "PingFang SC", "Microsoft YaHei", sans-serif`）；主文字 #1f2329、次要 #646a73、弱化 #8f959e；分隔线 #e5e6eb/#f0f0f0；强调色只用淡蓝 #6b8cff；圆角 6-8px；行高 1.5-1.6；留白充足。选项 pill 颜色用字段 schema 里的 option.color（低饱和底色 + 深色文字更高级）。

### 4.2 网页设计要求（生成报告/网页/可视化时遵守）

> 用户强调：**§4.1 基线规则不用写得那么死，可按用户要求灵活发挥。** 开发网页或生成报告/数据可视化界面时，按下述要求执行；这些是「如何产出高质量可交互页面」的约定，不替代用户明确指定的风格。

1. **技术底座**：页面跑在沙箱 iframe 里（独立文档），**样式怎么写都不会漏到宿主**——内联 `<style>`、外链 `<link rel="stylesheet">`（含 Tailwind CDN）、JS 动态注入 `<style>` **全部生效**（2026-09-13 实测：跨域 CSS 无需 CORS 头即命中计算样式）。`position:fixed`、`100vh` 也可自由使用（相对预览框视口，§0.5）。**默认仍建议内联 + 系统字体**：只为外链 CDN 挂掉时页面不裸奔，不再是安全硬约束。图表/趋势用 ECharts：`https://static-resources.nuwax.com/echarts.min.js`——**平台规范只允许这一个 JS 白名单域名**（技术上沙箱不拦其它 script，但第三方 CDN 不可控）；引了 CDN 仍要保证核心结构与信息不依赖它也能读（fallback）。
2. **输入交互用自绘弹层，不用 `prompt()`**：沙箱里 `prompt/confirm` 能弹（`allow-modals`）但会阻塞页面，且 Chrome 会记住「阻止此页面再创建对话框」使后续调用静默失效。§5 骨架自带一个 12 行的 `ask()` 自绘模态，复制即用。
3. **主标题跟随文档改名**：用 `document.title` 渲染 + `repoHost.onTitle` 重绘（§2.1 标准写法，**先 `stripSite()` 剥掉「站点名 - 」前缀**）。
4. **字体用系统字体栈**：**不得使用 googleapis.com 相关地址**（含 `g-fonts.css`）——不是沙箱拦它，而是国内不可达，页面会卡在网络等待上。三字体系直接写系统栈——衬线 `"Songti SC",serif`、无衬线 `"PingFang SC",sans-serif`、等宽 `"SFMono-Regular",monospace`。
5. **优先用 frontend-design 技能**：开发网页 / 生成报告时，先按 §5 选型并套模板，视觉质感交给 `frontend-design` 技能打磨（设计师级排版、衬线/无衬线/等宽三字体系、高级灰阶、克制强调色、杂志刊头等），避免通用的「AI 模板脸」。
6. **完整性**：页面必须包含任务要求的所有重要内容信息；多字段/多记录不能只渲染局部。
7. **逻辑性**：各部分保持逻辑联系（如 KPI → 分布图 → 按分组正文），让读者能跟上整体思路。
8. **可交互**：输出页面应是可交互的，允许用户查看与探索数据（图表 hover/tooltip、分组导航、列表卡片切换、筛选搜索等至少一项）。
9. **单 HTML 页面**：结果尽量合并为一个 HTML 文件（内联 CSS/JS）交付，便于导入与预览。
10. **长内容分片**：内容很长时**分片输出后合并**，避免一次写太大失败——可多次「追加/拼接」到同一 html 文件，最终一份完整页面。
11. **数据绑定**：页面数据一律经 `window.repoDatatable.read()` 动态读（见 §2），不在 HTML 里硬编码记录数据；写入口按 `editable` 门控（见 §6）。
12. **看板列的来源是数据，不是措辞**：看板/卡片墙/分组视图的**列 = 该字段已定义的选项 + 空值桶**，**禁止硬编码列名**。分组维度一律由 `schema.options` 现算，这样用户改了数据表的选项，页面不用重导就跟着变。
    - **用户嘴里的列名，常常在数据表里根本不存在**（用户说「按待规划分组」，但表里那个字段的选项只有「进行中/已完成/已上线」——「待规划」是用户**口头描述**，不是字段选项）。**⛔ 不要为了对上用户的措辞去 `addField`/加选项造数据**——那是在污染用户的数据表，改的是用户资产而不是页面。
    - 正确做法：把「这个字段没填」这个事实，**映射到用户熟悉的那个标签**上，并把它抽成**可配置常量**（如 `var NONE_LABEL = '待规划'`），一处改、全页生效，用户看着也是他要的词。
    - **空值桶永远排在最前**（`[NONE_LABEL, ...options]`）：无状态的卡片/记录默认堆在最显眼处，最需要被看见。
    - 同理适用于任何「按字段分流」的视图（表格分组、筛选 tab、图例），一律现算，不写死。

### 4.3 图表与配色（浅色主题，实测配方）

#### ⛔ 分组统计必须带「空值桶」（最容易出错、且数字会自相矛盾）

**数据表里往往没有「待规划」「未指派」「暂无」这类选项**——没有归属的记录，对应的是**该字段值为空的记录**，而不是某个选项。如果分组时只遍历 `schema.options` 去数，这些记录会**整批消失**，而百分比又按「命中的那几条」算，于是**图上的数字自己跟自己打架**。

> 实测事故：玫瑰图统计一个 16 条记录的表，按 `options` 遍历只统计到 **4 条**，占比还按 4 条算——剩下 12 条凭空蒸发，用户一眼就看出总数不对。

**规则**：
- **分组维度 = 已定义选项 + 空值桶**（同 §4.2 规则 12），顺序 `[空值桶, ...options]`。
- 空值桶**统一命名**（用可配置常量，如 `NONE_LABEL`），**统一配色 `#94A3B8`**（中性灰，一眼可辨「这类是没填的」）。
- 空值桶的判定：字段值为**空串 / `undefined` / 值为选项列表里不存在的 id**（脏数据也归进来，别丢）。
- **自检（必做）：各桶之和 === 记录总数。** 渲染前先算一遍，不等就别往下渲染——这是唯一能拦住「数字自相矛盾」的机械手段。

```js
var NONE_LABEL = '待规划'          // 按用户措辞定，但只在页面里用，别去改数据表
var opts = (field.schema.options || [])
var buckets = [{ key: '', name: NONE_LABEL, color: '#94A3B8', count: 0 }]
  .concat(opts.map(function (o) { return { key: o.id, name: o.name, color: o.color, count: 0 } }))
var index = {}; buckets.forEach(function (b) { index[b.key] = b })
records.forEach(function (r) {
  var b = index[r.values[field.col]] || index['']   // 空值 / 未知 id 一律进空值桶
  b.count++
})
var sum = buckets.reduce(function (a, b) { return a + b.count }, 0)
if (sum !== records.length) console.warn('分组数字不自洽', sum, records.length)  // 上线前必须消除
```

#### 浅色主题 ECharts 配方

深色默认在浅色底上「发糊」：轴文字太浅、tooltip 半透明黑、系列色过饱和。浅色页面直接用下面这套（图表容器背景保持透明，**页面与图表都不要加深色底**）：

```js
var C = {
  tip:   { bg: '#fff', border: '#E4EAF4', text: '#16203A' },
  axis:  { label: '#5A6785', split: '#EDF1F8' },     // 轴文字 / 分割线
  series: ['#0EA5A0', '#6D4AFF', '#F59E0B', '#F43F5E', '#0EA5E9', '#65A30D'],
}
// tooltip：必须自己给背景，否则是半透明黑
tooltip: { backgroundColor: C.tip.bg, borderColor: C.tip.border, borderWidth: 1,
           textStyle: { color: C.tip.text, fontSize: 12 }, extraCssText: 'box-shadow:0 6px 20px rgba(20,34,64,.10);border-radius:8px;' }
// 坐标轴
axisLabel: { color: C.axis.label }
splitLine: { lineStyle: { color: C.axis.split } }
```

**状态色不能直接当文字色用**：状态色通常取自数据表选项自身（如 `#52c41a` 这种亮绿），**在浅色底上做文字对比度不足**（尤其小字号），必须**加深后再用于文字**，色块/边框仍用原色——这样既保住「一眼认出是那个状态」，又读得清。

```js
/** 把 hex 按比例加深。状态文字色 = darken(选项色, 0.35)：色块/边框用原色，文字用加深色 */
function darken(hex, k) {
  var n = parseInt(hex.slice(1), 16)
  var f = function (v) { return Math.max(0, Math.round(v * (1 - k))) }
  return '#' + [f(n >> 16), f((n >> 8) & 255), f(n & 255)]
    .map(function (v) { return ('0' + v.toString(16)).slice(-2) }).join('')
}
```

**加深幅度用 `k = 0.35`**（实测白底对比度，WCAG AA 正文线是 4.5:1）：

| 原色 | 原色对比度 | `k=0.3` | `k=0.35` ✅ |
|---|---|---|---|
| `#52c41a` 绿 | 2.27 ✗ | `#398912` → 4.41 ✗（差一点） | `#357f11` → **5.01** |
| `#F59E0B` 橙 | 2.15 ✗ | `#ac6f08` → 4.17 ✗ | `#9f6707` → **4.75** |
| `#0EA5E0` 蓝 | 2.81 ✗ | `#0a739d` → 5.31 | `#096b92` → 5.95 |
| `#F43F5E` 红 | 3.67 ✗ | `#ab2c42` → 6.62 | `#9f293d` → 7.35 |

⚠️ **`k=0.3` 不够**——绿、橙这类亮色在 0.3 时只有 4.4 / 4.2，仍不达标，而它们恰恰是最常用的状态色。用 **0.35** 一次兜住。原色直接当文字色**一律不合格**（最高才 3.67），色块和边框可以照用。

#### 渲染后必须补 resize

入场动画、字体加载、侧栏展开都会改变图表容器宽度，**ECharts 只在 init 时量一次尺寸**——不补 `resize()` 就会被压窄变形（圆变椭圆、标签重叠）。**用了 `echarts.init` 就必须有** `requestAnimationFrame` + `setTimeout` 各调一次 + 监听窗口 resize（`pivot_lint.py` 会拦这一条）。

```js
function fix() { charts.forEach(function (c) { c.resize() }) }
requestAnimationFrame(fix); setTimeout(fix, 200)
window.addEventListener('resize', fix)
```

## 5. 六类页面模板（按需求选型）

> **先看 §5.0**：用户没指定风格 / 变体时**不要选型、不要拼骨架**——直接用内置的默认起手件 `templates/pivot-dashboard.html`（完整可运行的一整页）。下面这张表只在用户**明确说出**某种形态时才用来选。

| 用户说法 | 选型 |
|---|---|
| 「把数据展示出来 / 列表页」 | A 基础表格 |
| 「表格和卡片都要 / 灵活看」 | B 表格+卡片切换 |
| 「按状态/分类看 / 看板」 | C 看板（按 select 字段分组列） |
| 「统计 / 汇总 / 仪表盘 / KPI」 | D 统计仪表盘 |
| 「可搜索可筛选的清单」 | E 列表+筛选搜索 |
| 「报告 / 杂志 / 日报 / 有高级感的网页 / 动态可视化」 | **F 杂志/报告式**（引 ECharts/Tailwind，见 §5F） |

### 5.0 默认起手件 `templates/pivot-dashboard.html`（没指定风格就用它）

**用户没说风格 / 变体**（只说「做个透视页 / 数据看板 / 把这些数据展示出来」）时，**不要从零写码，也不要拿 §5A–§5F 的骨架拼**——复制 `templates/pivot-dashboard.html` 起手。它**不是片段，是一整页完整可运行的透视页**（自线上一个真实透视页参数化而来，2026-09-13）。**平台自己也用它**：多维表格里点「＋ → 新建透视页面」由前端以同一份模板起页（只替换数据表 slugId 与标题），所以用户已有的透视页多半就是它——「帮我改我那个透视页」时先照这个结构读页面。

| 它自带什么 | 说明 |
|---|---|
| 三视图 | **看板**（按分组轴分列，拖卡片即改状态）/ **表格** / **图表**，三视图共用一份数据与同一个搜索词 |
| 顶部指标条 | 记录总数 + 各桶计数与占比 +（语义词典认出「完成」类字段时）完成率 |
| 分组轴选择器 | 用户可在页面上换分组轴，选择经 `repoHost.getPref/setPref` **跨会话记住**（存字段名，不是硬编码索引） |
| 字段角色自动识别 | 靠 `custom.dtField.type` + 字段名语义词典（分组 / 进行中 / 完成 / 否定词）判断，**不写死字段名、列号、选项 id** |
| 图表按字段族自适应 | `select`→分布、`user`→人员任务量、`multiSelect`→标签分布、`date` 族→趋势、`number` 族→按分组求和/均值 |
| 空值桶 | 空值**单独成桶**（桶数 = 去重取值数 + 1）——别「顺手」删掉它，**Σ各桶 == 记录总数**是验收断言之一 |
| 多数据表切换 | 挂的多维表格里若有多张数据表（一个 workbook 多个 sheet），页头下方自动出现切换标签：切表只换数据源，**写指令按当前那张表定位**，分组轴 / 图形偏好按表分别记住（「上次看哪张表」也记住） |

**改两处就能用**：① `<head>` 里 `repo-datatable-source` 的 `content` 换成**目标数据表的 slugId**（不是 pageId）；② 标题与文案——`<title>`、`<h1 id="pageTitle">`、页脚里那句兜底文案。**②可以不改**：运行时宿主会用真实文档标题覆盖它们（`repoHost.onTitle`），静态值只是没拿到标题时的兜底。

**不要改**：字段识别、空值桶、图表适配、`getPref/setPref` 这段逻辑。它们是线上真数据上逐条对过数的；真要改，改完必须重跑 `pivot_check.sh` 再谈导入。

**已知观感（不是 bug，别去「修」）**：人员/分类取值较多（≥6）而图表卡高度固定时，ECharts 会**自动隐藏重叠的坐标轴标签**——数据、tooltip、图例都不受影响，只是个别轴标签看不见。这是渲染密度行为，不是数据丢失；这段代码已验证，别为了「把标签全显示出来」动它。

**何时不用它**：用户点名要「杂志 / 报告 / 日报 / 有高级感的长页」→ 走 §5F；只要一张纯表格或纯筛选列表 → §5A / §5E（默认模板的表格视图也能顶，但页面重心不同）。

### 5.1 动手前先定四件事（这三分钟能省掉一次重导）

选完模板、写码之前，先把下面四件定下来。**别边写边猜**——猜错的代价是「本地看着对、上线全是空桶」，而重导要白烧一个 `slugId`。

**① 先看有哪些字段、各是什么类型**：`node scripts/dt_rows.js <数据表pageId> --json`（需要先 `--list` 看有几张表、`--sheet` 选表）。**字段名从这里逐字复制**，别凭印象手打（差一个空格就静默渲染成空桶）——写代码前就把这份字段清单摆在眼前，比写完再回来对要省事得多。

**② 定「用哪个字段当分组轴」——按角色的优先级挑**：

| 优先级 | 字段角色 | 适合当 | 怎么认出来 |
|---|---|---|---|
| 最高 | `select` / `multiSelect` | **分组轴、看板列、图例** | `custom.dtField.type` 是 `select`/`multiSelect`；取值是**选项 id**（渲染前需按 schema 的选项表翻译成展示名） |
| 高 | `user` / `createdUser` 等人员类 | 负责人维度 | 取值是人员对象（批量查名字见 §3 复合值解析） |
| 中 | `date` / `createdTime` | 趋势轴（按日/周/月分桶） | 注意空日期要单独成桶，别直接 `new Date('')` |
| 中 | `number` / `formula`(数值) | 度量（求和/均值） | 求和时**先判空**，`''` 参与加法会得到字符串拼接 |
| 低 | `text` | 明细列、搜索关键字 | 自由文本，**别当分组轴**（取值几乎不重复，会炸出上百个桶） |

**③ 语义词典：把用户的词映射到字段名。** 用户说「按状态看」「谁的活最多」「哪个月涨得快」，对应的可能是 `状态` / `负责人` / `完成时间`——**先读回表头再对号**，对不上就**问用户**，别自己挑一个凑（挑错字段做出的图，数字全对但意思全错，比报错更难发现）。

**④ 无表 / 无数据时兜底：报告，不要编。** 表不存在、读回 0 条记录、目标字段不在表头——**一律如实说明（哪个环节、实际读到了什么），不许用硬编码示例数据把页面填满**。这是上面的铁律在选型阶段的具体化：写死数据的页面「能打开有数据」，但它骗人。

**⑤ 拿不准分组轴就手选，不要自动挑。** 语义不明确时（比如「按分类」而表里有 `分类` 和 `类型` 两个字段），**把候选列出来问用户**——一次澄清的成本远低于一次重导。

### 5.2 通用骨架（完整可运行，§5A–§5F 共用）

> 若走 §5.0 的默认起手件，本节及以下的骨架都不用看——那些是「用户点名了其他形态」时才用的素材。

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="repo-datatable-source" content="这里填数据表slugId">
<title>数据看板</title>
<style>
  * { margin:0; padding:0; box-sizing:border-box; }
  body { font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif; color:#1f2329; background:#fff; padding:24px; }
  .hd { display:flex; align-items:center; justify-content:space-between; margin-bottom:20px; }
  .hd h1 { font-size:18px; font-weight:600; }
  .btn { border:1px solid #e5e6eb; background:#fff; color:#1f2329; border-radius:6px; padding:6px 14px; font-size:13px; cursor:pointer; }
  .btn:hover { border-color:#6b8cff; color:#6b8cff; }
  .btn--primary { background:#1f2329; border-color:#1f2329; color:#fff; }
  .btn--primary:hover { opacity:.85; color:#fff; }
  table { width:100%; border-collapse:collapse; }
  th { text-align:left; font-size:12px; color:#8f959e; font-weight:500; padding:10px 12px; border-bottom:1px solid #e5e6eb; }
  td { font-size:13px; padding:12px; border-bottom:1px solid #f0f0f0; }
  .pill { display:inline-block; padding:2px 10px; border-radius:4px; font-size:12px; color:#fff; }
  .empty { text-align:center; color:#8f959e; font-size:13px; padding:48px 0; }
</style>
</head>
<body>
<div class="hd"><h1 id="pageTitle">数据看板</h1><div id="actions"></div></div>
<div id="app"><div class="empty">加载中…</div></div>

<!-- 自绘输入弹层：替代 prompt()（沙箱里 prompt 能弹但会阻塞、且易被浏览器永久禁掉，见 §0.5）。
     ⚠️ 显隐只走 style.display：别用 hidden 属性 + 内联 display:flex（内联样式压过 [hidden] 的 UA 规则，弹层会常显） -->
<div id="mask" style="display:none;position:fixed;inset:0;background:rgba(0,0,0,.35);align-items:center;justify-content:center;z-index:50">
  <div style="background:#fff;border-radius:8px;padding:20px;width:320px;box-shadow:0 8px 30px rgba(0,0,0,.18)">
    <div id="maskTitle" style="font-size:14px;font-weight:600;margin-bottom:12px"></div>
    <input id="maskInput" style="width:100%;border:1px solid #e5e6eb;border-radius:6px;padding:8px 10px;font-size:13px;outline:none">
    <div style="display:flex;justify-content:flex-end;gap:8px;margin-top:16px">
      <button class="btn" id="maskCancel">取消</button>
      <button class="btn btn--primary" id="maskOk">确定</button>
    </div>
  </div>
</div>

<script>
(function () {
  var api = window.repoDatatable;
  var snap = null, fields = [], records = [];

  function esc(s) { return String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }

  /* 标题跟随文档改名（§2.1）：沙箱内 document.title 就是宿主推送的标题 */
  var titleEl = document.getElementById('pageTitle');
  titleEl.textContent = document.title || titleEl.textContent;
  window.repoHost && window.repoHost.onTitle(function (t) { titleEl.textContent = t; });

  /* 自绘输入弹层：返回 Promise<string|null>，取消/空值返回 null */
  function ask(title) {
    return new Promise(function (res) {
      var mask = document.getElementById('mask'), input = document.getElementById('maskInput');
      var ok = document.getElementById('maskOk'), cancel = document.getElementById('maskCancel');
      document.getElementById('maskTitle').textContent = title;
      input.value = ''; mask.style.display = 'flex'; input.focus();
      function done(v) { mask.style.display = 'none'; ok.onclick = cancel.onclick = null; input.onkeydown = null; res(v); }
      ok.onclick = function () { done(input.value.trim() || null); };
      cancel.onclick = function () { done(null); };
      input.onkeydown = function (e) { if (e.key === 'Enter') done(input.value.trim() || null); };
    });
  }

  // select 选项 id → {name,color} 映射
  function optionMap(f) {
    var m = {}; ((f.schema && f.schema.options) || []).forEach(function (o) { m[o.id] = o; });
    return m;
  }
  function cellHtml(f, v) {
    if (v == null || v === '') return '<span style="color:#8f959e">—</span>';
    var t = f.schema && f.schema.type;
    if (t === 'select') { var o = optionMap(f)[v]; return '<span class="pill" style="background:' + esc(o && o.color || '#8c8c8c') + '">' + esc(o ? o.name : v) + '</span>'; }
    if (t === 'multiSelect') { var mm = optionMap(f); return String(v).split(',').map(function (id) { var o = mm[id]; return '<span class="pill" style="margin-right:4px;background:' + esc(o && o.color || '#8c8c8c') + '">' + esc(o ? o.name : id) + '</span>'; }).join(''); }
    if (t === 'checkbox') return v === true || v === 'true' ? '✓' : '—';
    if (t === 'attachment' || t === 'image' || t === 'media') {
      /* 附件/图片/媒体：渲染文件名纯文本（⚠️ 不要用 <a href>：沙箱内点击会把预览框导航走，且带 cookie 的下载地址也点不开，见 §3）。图片要显示就 <img src="a.url">，bootstrap 会自动换免登录地址 */
      try { return JSON.parse(v).map(function (a) { return esc(a.name) || '附件'; }).join('、'); } catch (e) { return esc(v); }
    }
    /* link 字段：纯文本标题 + slugId 提示（⚠️ 不做成链接：页内 <a href> 会把预览框导航走，见 §3、§6.9） */
    if (t === 'link') { try { var l = JSON.parse(v); return esc(l.t) + ' <span style="color:#8f959e;font-size:12px">(' + esc(l.s) + ')</span>'; } catch (e) { return esc(v); } }
    return esc(v);
  }

  function renderTable() {
    if (!records.length) return '<div class="empty">暂无数据</div>';
    return '<table><thead><tr>' + fields.map(function (f) { return '<th>' + esc(f.name) + '</th>'; }).join('') + '</tr></thead><tbody>'
      + records.map(function (r) { return '<tr>' + fields.map(function (f) { return '<td>' + cellHtml(f, r.values[f.col]) + '</td>'; }).join('') + '</tr>'; }).join('')
      + '</tbody></table>';
  }

  async function load() {
    snap = await api.read();
    fields = snap.fields; records = snap.records;
    document.getElementById('app').innerHTML = renderTable();
    // 写入口按 editable 门控
    document.getElementById('actions').innerHTML = snap.editable ? '<button class="btn btn--primary" id="add">新增记录</button>' : '';
    if (snap.editable) {
      document.getElementById('add').addEventListener('click', async function () {
        var v = await ask('「' + fields[0].name + '」的值');
        if (!v) return;
        var values = {}; values[fields[0].col] = v;
        await api.appendRecord(values);          // 写回数据表
        snap = await api.read();                 // 写后重读（§2）
        fields = snap.fields; records = snap.records;
        document.getElementById('app').innerHTML = renderTable();
      });
    }
  }
  load().catch(function (e) { document.getElementById('app').innerHTML = '<div class="empty">加载失败：' + esc(e.message) + '</div>'; });
})();
</script>
</body>
</html>
```

### A 基础表格
上面骨架即是。行横线分隔、表头灰字、select 彩 pill、空态/错误兜底齐全。

### B 表格 + 卡片切换
在 A 基础上：右上加切换按钮（`表格 | 卡片` 两个文字按钮，选中者加粗 + `#6b8cff`，无下划线），同一时刻只渲染一种。卡片 = 每条记录一个 `border:1px solid #e5e6eb; border-radius:8px` 的块，**没有标题区，第一行直接是首字段的值**（加粗 15px），其余字段按「字段名: 值」灰字小行排列；卡片间 `grid-template-columns: repeat(auto-fill,minmax(280px,1fr)); gap:16px`。

### C 看板（按 select 分组）
取一个 select 字段作分组轴：每个选项一列（列头 = 选项名 + 该列记录数灰字角标），列内该选项的记录做小卡片（首字段加粗 + 2-3 个关键字段）。记录按 `values[分组列] === 选项id` 归列，空值归「未分类」列。列容器横向 flex、可横滚；卡片行间横线分隔或独立小卡均可，保持黑白灰 + 选项色点缀。

### D 统计仪表盘
顶部一排 KPI 卡（记录总数、数字字段 sum/avg、某选项计数、checkbox 完成率等），下方可加纯 CSS 条形图（`div` 宽度按占比 %，条色用 #1f2329 或 #6b8cff 单色，不引图表库）。统计全部从 `records` 现算（Number() 转换失败按 0）。KPI 卡：白底细边框圆角，数值 24px 加粗，标签 12px 灰字。

### E 列表 + 筛选搜索
顶部一行：搜索框（`border:1px solid #e5e6eb` 圆角，focus 变 #6b8cff）+ select 字段筛选下拉 + 「仅未完成」类 checkbox 快捷筛选。过滤在内存做（`records.filter(...)`），不写回数据表；搜索按「任一字段值 includes 关键词」。列表行用横线分隔，行内直接展示关键字段。

### F 杂志 / 报告式页面（高级感动态报告）

**何时用**：用户要的是「报告网页 / 杂志风 / 日报 / 有设计感的深度内容页」，而不只是普通表格。视觉与信息层次是核心诉求。**强烈建议用 `frontend-design` 技能打磨视觉**（或其风格精神），配合 ECharts 做趋势图。

**与 A–E 的区别**：A–E 偏「数据工具视图」（信息优先、界面从简）；F 偏「**高级感网页 / 内容型出版物**」——把数据编排成一篇可阅读、可沉浸浏览的报告页，排版与视觉质感是同等甚至更重的诉求。可以突破 §4.1 的「白底-系统字体-纯表格」基线（用户要求风格时），靠 **ECharts（CDN JS）+ 任意 CSS 手法** 做质感（沙箱是独立文档，外链 CSS / Tailwind / 动态注入样式都生效，§0.5），但**仍必须遵守**：数据经 `window.repoDatatable.read()` 动态读、`editable===false` 隐藏写入口、所有值 `esc()`、无 googleapis、不用 `prompt()`、主标题跟随 `document.title`。

**设计要点**（黑编编辑部 / 深度报告方向是用户偏好的默认高端样式，也可按用户新需求发挥）：

- **三字体系**：衬线（Noto Serif SC / Songti SC）做大标题与章节标题 → 杂志感；无衬线（Noto Sans SC / PingFang）做正文与导语 → 舒适；等宽（JetBrains Mono / SF Mono）做刊头期号、meta、序号、KPI 数字 → 编辑部印刷感。
- **配色**：近黑 `#141414` 主色 + 白底 `#fbfbf9` + 灰阶 `#646a73/#8f959e/#ececec` + 单一克制强调色（默认淡蓝 `#6b8cff`，用户指定其它色则从其要求）。**不用大红/重彩大面积铺**。
- **版式结构**（自上而下）：
  1. **刊头**：刊名小字大写字距 + 期号/日期/收录数 meta 行（等宽）。
  2. **大标题 + 导语**：衬线大标题，一句概括本期核心。
  3. **KPI 速览**：横向一排卡（总数/覆盖方向/收录日期/最热方向），等宽大数字 + 灰字标签。
  4. **方向分布图**：ECharts 横向条形图 + 至多一个进度条区，展示分类占比。
  5. **分组正文**：按某个 select 字段（或手动分类）分章节，章节头 = 序号 + 标题 + 该分组条数；条目 = 序号 + 加粗标题 + 亮点副行 + 右侧日期，行间细线分隔。
  6. **页脚**：数据源说明、生成时间、「数据表更新后刷新即同步」。
- **交互**（至少一项）：章节锚点导航 / 卡片列表切换 / ECharts tooltip / 回到顶部按钮；hover 行线动效。
- **响应式**：≥820px 两栏图表、单栏分组；移动端图表区堆叠、条目序号简化。

**骨架**（含 ECharts + SDK 动态读，可直接改字段名复用；核心是 `read()` → 现算统计 → 组 HTML → `echarts.init`）：

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="repo-datatable-source" content="这里填数据表slugId">
<title>报告标题</title>
<!-- 样式可以外链也可以内联（沙箱独立文档，样式不会漏到宿主，§0.5）；
     这里保持内联只为不依赖外部 CSS CDN。只有 JS 走白名单 CDN。 -->
<script src="https://static-resources.nuwax.com/echarts.min.js"></script>
<style>
  /* 三字体系 + 黑白灰 + 淡蓝强调；示例变量 */
  :root{--ink:#141414;--gray:#646a73;--gray2:#8f959e;--rule:#ececec;--paper:#fbfbf9;--accent:#6b8cff;
        --rain:"Noto Serif SC","Songti SC",serif;--sans:"Noto Sans SC","PingFang SC",sans-serif;--mono:"JetBrains Mono","SFMono-Regular",monospace;}
  body{margin:0;background:var(--paper);color:var(--ink);font-family:var(--sans);line-height:1.65}
  .masthead{padding:56px 6vw 28px;border-bottom:1px solid var(--ink)}
  .mast-meta{font-family:var(--mono);font-size:12.5px;letter-spacing:.02em;color:var(--gray2)}
  .mast-title{font-family:var(--rain);font-weight:700;font-size:clamp(38px,7vw,62px);margin:22px 0 0}
  .mast-title em{font-style:normal;color:var(--accent)}   /* 标题中可加一个强调字 */
  .section{padding:34px 6vw}
  .sec-no{font-family:var(--mono);color:var(--accent);letter-spacing:.1em}
  .item{display:grid;grid-template-columns:52px 1fr 96px;gap:18px;padding:20px 0;border-bottom:1px solid var(--rule)}
  .item .t{font-size:17px;font-weight:600}
  .item .hl{color:var(--gray);font-size:13.5px;margin-top:8px}
  .item .date{font-family:var(--mono);font-size:12px;color:var(--gray2);text-align:right;white-space:nowrap}
  #chart{width:100%;height:300px}
  @media(max-width:820px){.item{grid-template-columns:40px 1fr}.item .date{grid-column:2;text-align:left}}
</style>
</head>
<body>
<div id="app">加载中…</div>
<script>
(function(){
  var mount=document.getElementById('app');
  function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
  function optName(vid,opts){for(var i=0;i<opts.length;i++)if(opts[i].id===vid)return opts[i].name;return vid;}
  function fmtDate(iso){var p=String(iso||'').split('-');if(p.length<3)return esc(iso);return p[1]+'/'+p[2]+'/'+p[0];}

  function render(d){
    var fields=d.fields||[],records=d.records||[];
    var opts=[];fields.forEach(function(f){if(f.schema&&f.schema.type==='select')opts=f.schema.options||[];});
    // 现算统计：分类计数 + 日期分布
    var cat={},date={};
    records.forEach(function(r){var c=optName(r.values['1'],opts)||'其他';cat[c]=(cat[c]||0)+1;var dt=(r.values['2']||'').slice(0,10);if(dt)date[dt]=(date[dt]||0)+1;});
    var catArr=Object.keys(cat).map(function(k){return{name:k,count:cat[k]};}).sort(function(a,b){return b.count-a.count;});
    var maxCat=catArr.length?catArr[0].count:1;

    var html=''
      +'<header class="masthead">'
      +'<div class="mast-meta" id="mastMeta"></div>'
      +'<h1 class="mast-title">主标题<em>点睛字</em></h1>'
      +'</header>'
      +'<section class="section" style="background:#fff;border-bottom:1px solid var(--rule)">'
      +'<div id="chart"></div></section>'
      +'<main class="section">'
      + catArr.map(function(c,si){
          var items=records.filter(function(r){return (optName(r.values['1'],opts)||'其他')===c.name;});
          return '<div style="margin-bottom:48px">'
            +'<div style="display:flex;align-items:baseline;gap:14px;padding-bottom:10px;border-bottom:1px solid var(--ink)">'
            +'<span class="sec-no">'+String(si+1).padStart(2,'0')+'</span>'
            +'<span style="font-family:var(--rain);font-size:24px;font-weight:700">'+esc(c.name)+'</span>'
            +'<span style="margin-left:auto;color:var(--gray2);font-size:12px">'+c.count+' 条</span></div>'
            + items.map(function(r){return '<div class="item" data-row="'+r.row+'">'
                +'<div class="sec-no">'+esc(optName(r.values['1'],opts))+'</div>'
                +'<div><div class="t">'+esc(r.values['0'])+'</div><div class="hl">'+esc(r.values['3'])+'</div></div>'
                +'<div class="date">'+fmtDate(r.values['2'])+'</div></div>';}).join('')
            +'</div>';
        }).join('')
      +'</main>'
      +'<footer class="section" style="color:var(--gray2);font-size:12.5px;border-top:1px solid var(--ink)">数据源：报告标题 · 数据表更新后刷新本页即同步</footer>';
    mount.innerHTML=html;

    // 标题跟随（§2.1）：沙箱内 document.title 是宿主推送值，文档重命名时 onTitle 再推一次；
    // 注意宿主标题带「站点名 - 」前缀，先 stripSite() 剥掉再显示。
    var meta=document.getElementById('mastMeta');
    function stripSite(t){t=String(t==null?document.title||'':t);var i=t.indexOf(' - ');return i>0?t.slice(i+3):t;}
    function setMeta(t){meta.innerHTML='<b>数据源</b> · '+esc(stripSite(t))+' · <b>'+records.length+'</b> 条 · <b>'+catArr.length+'</b> 个方向';}
    setMeta(document.title);
    window.repoHost&&window.repoHost.onTitle(setMeta);

    // 图表（ECharts，若无 ECharts 则纯 CSS 条形兜底）
    if(typeof echarts!=='undefined'){
      var ch=echarts.init(document.getElementById('chart'));
      ch.setOption({tooltip:{trigger:'axis'},grid:{left:20,right:30,top:20,bottom:10,containLabel:true},
        xAxis:{type:'value',splitLine:{lineStyle:{color:'#f2f3f5'}},axisLabel:{color:'#8f959e'}},
        yAxis:{type:'category',data:catArr.map(function(c){return c.name;}),axisLabel:{color:'#2b2f33'}},
        series:[{type:'bar',data:catArr.map(function(c){return c.count;}),barWidth:14,
          itemStyle:{color:'#6b8cff'},label:{show:true,position:'right',color:'#2b2f33'}}]});
      window.addEventListener('resize',function(){ch.resize();});
    }
  }
  // read() resolve 的就是快照对象（无 ok 字段），失败走 catch（§2）
  window.repoDatatable.read().then(render).catch(function(e){mount.innerHTML='加载失败：'+esc(e.message);});
})();
</script>
</body>
</html>
```

> 说明：字段列号是本节示例（`0`=标题、`1`=分类、`2`=日期、`3`=亮点），**务必先读 `snap.fields` 确认每个 `col` 的实际含义再绑定**，不要照抄列号。F 模板同样可用纯 CSS 画图表（无 ECharts 时），保持零依赖兜底。

## 6. 注意事项

1. **依赖策略**——页面在**沙箱 iframe 里独立渲染**：**样式怎么写都不会漏到宿主**——内联 `<style>`、外链 `<link rel=stylesheet>`（含 Tailwind CDN）、JS 动态注入 `<style>` **全部生效**（2026-09-13 实测，跨域 CSS 无需 CORS 头）；`position:fixed`、`100vh` 也可自由使用（相对预览框视口，§0.5）。**默认仍以内联 + 系统字体为基线**，只为外链 CDN 挂掉时页面不裸奔，不再是安全硬约束。**JS 只允许 `static-resources.nuwax.com` 白名单**（`echarts.min.js`），**绝不引 googleapis / 其它第三方 CDN**（国内可达性 + 不可控）。引了 CDN 仍要保证核心结构与信息不依赖它也能读（fallback）。图片用数据表附件的 `/api/f/{key}` 相对地址：`<img>/<video>/<audio>/<source>` 的裸相对 src 会被 bootstrap **自动换成免登录直访地址**（异步，先裂后好，给占位底色）；⚠️ **带查询串或绝对地址不会被换**，`<a href>` 指向 `/api/f/` 的下载链接也点不开（沙箱请求不带 cookie，且点击会把预览框导航走）——文件类附件一律渲染成文件名纯文本。
2. **长内容分片合并**——页面 HTML 很长时**分片写入后合并**成一个文件（同一 html 分多次拼接：《分片 A 写 head+样式》《分片 B 写正文骨架》《分片 C 写脚本》再拼接），避免一次输出过大失败；最终仍是单 HTML 交付。
3. **所有用户数据渲染前 `esc()` 转义**——字段名/记录值都可能含 HTML 特殊字符。
4. **不要自己 fetch 数据、不要自己写鉴权**——一律用注入的 `window.repoDatatable`（鉴权由宿主代理自动带上）；**绝对不要把 AK/ticket 硬编码进 HTML**（页面内容任何能看该页的人都能读到）。
5. **空态与错误兜底**——`records.length===0` 给「暂无数据」；`load()` 的 catch 给「加载失败:原因」；JSON.parse 全部 try/catch。
6. **写后重读**——appendRecord/setCellValue 成功后 `await api.read()` 再整页重渲染，别手改本地数组（物理行号会变）。
7. **多数据表**——`snap.tables` 存在时可做数据表切换 tab（tab 只显示名称、无序号，选中加粗 #6b8cff）；写方法带 `sheetId` 参数定位目标表。`tables` 缺失（旧后端）回退用顶层 fields/records 单表渲染。
8. **meta 的 content 必须是 slugId**——填错页面会退化成 iframe 静态渲染或读不到数据；生成 HTML 前先 `GET /pages/{pageId}` 拿数据表的 `slugId`。
9. **不要放跳转链接 / `window.open` / 真提交表单**——页内 `<a href>`（含站内 `/repo/doc/{slugId}`）会把**预览框自身导航走**，透视页当场被目标页替换、用户回不去；`window.open` 与 `target="_blank"` 静默无效；顶层导航被拦。`<form>` 只 `preventDefault()` 自处理（真实提交被 `form-action 'none'` 阻断）。需要指向某文档时渲染纯文本标题 + slugId 提示。
10. **存储三档别用错**——`localStorage` / `sessionStorage` 在沙箱里是 bootstrap 的**内存兜底**：不抛错、当次打开内有效、**刷新即空**；要跨会话记住（默认视图、折叠态、上次筛选）用 `await repoHost.setPref(key, value)` / `repoHost.getPref(key)`（落宿主 localStorage、按页面隔离）。`document.cookie` 访问即抛 SecurityError，别碰。
11. **输入一律自绘弹层**——`prompt()` / `confirm()` 虽能弹（`allow-modals`）但会阻塞页面，且 Chrome 记住「阻止此页面再创建对话框」后**静默失效**（按钮点了毫无反应、无从排查）。§5 骨架自带 `ask()` 模态，复制即用。
12. **标题跟随文档改名**——主标题/刊头用 `document.title`（或同值的 `repoHost.getTitle()`）渲染 + `repoHost.onTitle(fn)` 重绘（§2.1 标准写法）；⚠️ **三者取到的都是宿主整页标题 `站点名 - 文档名`，必须先剥掉首个 `" - "` 前缀段**再显示。
13. **预览窗口 = 可视区高度 + 内部滚动**——iframe 高度由宿主按可视区设定、与页面内容高度无关；`100vh` 即预览框可视区。想做「整屏一版」的排版直接用 `100vh`，内容更长时页面内部滚动即可，**不要试图让页面撑高宿主容器**（没有这个能力，历史上「按内容自适应高度」与 `100vh` 构成正反馈回路已删除）。

### 6.14 反模式清单（实测踩坑，逐条对照）

| 反模式 | 为什么会发生 | 正确做法 |
|---|---|---|
| 页面用 `fetch()` 调平台接口 → `Failed to fetch` / 401 | 沙箱 opaque origin，`fetch` 不带登录 cookie（`SameSite=Lax`） | 一律 `window.repoDatatable.*`（铁律 2） |
| `fetch` 失败后改成「把数据嵌进 HTML」 | 把「页面能显示」当成了目标 | **停下来报告偏差**，回去查 meta/SDK 用法；不得降级（铁律 4） |
| 交付说明写「无硬编码静态数据」，实际是快照 | 只验证了「渲染正常」，没验证「数据是活的」 | 跑 §1.1 **活体测试**；未验证的性质不许写进交付说明 |
| 把 AK / ticket 硬编码进 HTML 供页面鉴权 | 凭「页面要调 API 就得带 token」的推理行事，没读文档 | 宿主代理已带凭据；HTML 里出现任何 `ak-` / `Bearer` / `ticket` 都是错误（铁律 3） |
| echo 环境变量只截前 20 位、后半段自己补 | 想省事，用编造补齐不完整信息 | **绝不部分回显密钥**：要么整取，要么全程 `-H "Authorization: Bearer $SANDBOX_ACCESS_KEY"` |
| 页内放 `<a href="/repo/doc/...">` | 以为是普通站内链接 | 会把预览框导航走（页面当场被目标页替换、用户回不去）；渲染纯文本标题 + slugId（铁律 5） |
| 用 `prompt()` / `confirm()` / `alert()` | 图省事 | 用 §5 骨架自带的 `ask()` 自绘弹层 |
| 日期显示成 `46276` | 表格/数据表日期可能是 Excel 序列号（20000–80000 的整数） | 先判区间再归一化：`new Date(Math.round((n-25569)*86400000)).toISOString().slice(0,10)`；不在区间就按原串显示（§3 的日期正常已是 `2026-09-30`） |
| 主标题显示成「站点名 - 文档名」 | `document.title` 与 `repoHost.getTitle()` **同值**，都是宿主整页标题（`site + " - " + title`） | 两者都要**剥掉首个 `" - "` 前缀段**（§2.1 `stripSite()`）；`onTitle(fn)` 回调参数同样带前缀 |
| 改完只看 `code:"0000"` 就宣布「改好了」 | `code` 只说明请求被受理，不等于写进去了 | 认 `data.applied > 0` ＋ `contentSize`/`editedAt` 有变化（§1）。html 页的错 `find` 现在会**明确报错**，不再静默 |
| 改 html 页忘了传 `kind:"html"` | 服务端按页面类型推成 `doc`，落错分支（对 html 页写 doc 内容 = 白忙） | **显式传 `kind:"html"`**（§1） |
| 凭记忆猜 `find` 字符串 → 报 `find not found` | html 页 `replaceText` 是**严格失败**：命中 0 次直接报错，不静默跳过 | 先 `POST /content {"kind":"html","ops":[{"op":"read"}]}` 拿准确原文，再据此写 `find`（§1） |
| **「改不动正文 → 那就删掉重建」** | 把「页面」当成自己的临时产物，忽略了它是用户的可见资产 | **禁止**。正文现在**可以原地改**（`replaceText`/`replaceHtml`）、改数据不用动页、改标题/位置用 `rename`/`move`，删页须用户明确同意（§1 分流表） |
| 交付时顺手「清理」掉自己先前建的旧页 | 自认为「都是我建的，我清楚」 | 用户侧边栏 / 收藏 / 分享链接都是入口，**同一条禁令**；清理与否由用户决定 |
| 字段按列号写死（col 0/1/2/3） | 图快 | 按**语义角色**识别（第一个 select / user / date），或先读 `snap.fields` 确认每个 `col` 的含义（§3、§5 说明）；用户增删字段页面不崩 |

**一句总结**：遇到平台能力边界，先查规范再动手；查不到就停下来问，**不要用「看起来能跑」的降级方案糊过去**。导入**前**跑 `scripts/pivot_check.sh`（机械门禁 + 本地**真渲染断言**，退 0 才算过），导入**后**跑 §1.1 对账 + 线上断言清单 + 活体测试兜住「假动态」。**「渲染对不对」一律机器判**（退出码 + 逐条实测值），人眼只负责「好不好看」。

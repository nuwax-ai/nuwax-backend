#!/usr/bin/env node
/**
 * 透视页「导入前」本地预览桩 —— 花 10 秒先看一眼，别拿生产当调试器。
 *
 * 用法:
 *   PLATFORM_BASE_URL=https://testagent.xspaceagi.com \
 *   SANDBOX_ACCESS_KEY=ak-xxxx \
 *     node scripts/preview_stub.js <数据表pageId> <页面.html> [输出.html] [--readonly] [--print-payload]
 *
 *   输出默认落在 /tmp/pivot-preview-<页面名>.html，用浏览器直接打开即可（不写工作目录）。
 *
 * 干什么:
 *   拉数据表真实快照 → 按宿主 SDK 的真实解析语义转成 read() 的返回结构 →
 *   在页面最前面注入 window.repoDatatable / window.repoHost 桩 → 写出一份本地 HTML。
 *   页面照旧动态 read()，只是数据来自那一瞬的快照、写方法不落库。
 *
 * 为什么非做不可:
 *   透视页是纯静态单文件，唯一外部依赖就是 window.repoDatatable —— 完全能本地跑。
 *   而「新建/大改版」仍要走 /import，导入后才发现渲染问题（图表坍缩、分组漏桶、
 *   类名冲突）就得再来一次，**每次白烧一个 pageId/slugId**，正是改版最贵的代价。
 *   本地看一眼的成本几乎为零，跳过它没有任何理由。
 *
 * ⚠️ 桩的解析语义逐行对齐线上数据表解析实现（sidecar 的 `parseSingleSheet`，2026-09-13 核对）。
 *    不要按「直觉更合理」去改它：本地与线上不一致的预览，比不预览更糟。
 *    已知两处反直觉、但**线上就是这样**，故照抄：
 *      1) rowCount/columnCount 是**网格维度**（sheet.rowCount，缺省 20），不是「记录数/字段数」；
 *      2) records[i].values 里**每个字段列都有 key**（空值写空串），不是「空值就不出现」。
 *
 * 桩的边界（必须知道）:
 *   - 数据是拉取那一刻的快照，不是实时的；要验「活体」仍得上线改一条记录再刷新。
 *   - 写方法只打印不落库；--readonly 时按真实语义 reject（验只读门控用这个）。
 *   - 图片 /api/f/{key} 不做换址（本地无宿主换址能力），裂图属预期。
 *
 * --assert（把「打开看一眼」变成可判定的结论）:
 *   加 --assert 后，生成预览文件的同时用系统 Chrome 真渲染并逐项断言，输出机器可读结论。
 *   退出码 0 = 断言全过 / 1 = 有断言失败 / 4 = 没启用断言 / 5 = 环境不具备（没装 Chrome）。
 *   期望值默认从快照自动推导（记录数、字段数、空态与否…），也可用 --expect 或页面里的
 *   <meta name="pivot-assert" content='{"containsText":["支出"]}'> 显式补充。
 *   「浏览器打开肉眼确认」从此是可选的人眼复核，不再是唯一验收手段。
 */
'use strict'

const fs = require('fs')
const path = require('path')

// —— 与 sidecar src/datatable.ts 对齐的扫描上限 ——
const MAX_SCAN_COLS = 60
const MAX_SCAN_ROWS = 1000

/** 对齐 sidecar cellText()：cell.v 空值一律当空串 */
function cellText(cell) {
  if (cell == null) return ''
  const v = cell.v
  return v == null ? '' : String(v)
}

/**
 * 解析单张数据表 —— 对齐 sidecar parseSingleSheet()。
 * row0 表头（v + custom.dtField）→ fields；row1+ → records；(0,0) custom.dtMeta → viewMeta。
 */
function parseSingleSheet(wb, sheetId) {
  const sheet = (wb && wb.sheets && wb.sheets[sheetId]) || undefined
  const cellData = (sheet && sheet.cellData) || {}
  const rowCount =
    typeof sheet?.rowCount === 'number' && sheet.rowCount > 0 ? sheet.rowCount : 20
  const columnCount =
    typeof sheet?.columnCount === 'number' && sheet.columnCount > 0 ? sheet.columnCount : 20
  const name = typeof sheet?.name === 'string' && sheet.name !== '' ? sheet.name : sheetId

  const fields = []
  const headerRow = cellData[0] || {}
  for (let col = 0; col < MAX_SCAN_COLS; col++) {
    const cell = headerRow[col]
    const fieldName = cellText(cell)
    // ⚠️ 线上是 break 不是 continue：表头中间有空列，其后字段会被整个吞掉。
    //    本地预览会如实复现这个现象；真遇到了请去补齐表头，别在桩里「修」它。
    if (fieldName.length === 0) break
    const dtField = cell && cell.custom && cell.custom.dtField
    const schema =
      dtField != null && typeof dtField === 'object' && !Array.isArray(dtField)
        ? dtField
        : { type: 'text' }
    fields.push({ col, name: fieldName, schema })
  }

  const records = []
  const rowKeys = Object.keys(cellData)
    .map(Number)
    .filter((r) => Number.isInteger(r) && r >= 1)
    .sort((a, b) => a - b)
  for (const row of rowKeys) {
    if (row > MAX_SCAN_ROWS) break
    const values = {}
    let hasAny = false
    for (const f of fields) {
      const t = cellText(cellData[row] && cellData[row][f.col])
      values[f.col] = t // 空值也留 key（对齐线上），模板可能靠 key 存在性判断「这列有没有值」
      if (t.length > 0) hasAny = true
    }
    if (!hasAny) continue // 整行都空 → 不算记录
    const firstCol = fields.length > 0 ? fields[0].col : 0
    records.push({ row, title: values[firstCol] || `记录 ${row}`, values })
  }

  let viewMeta = null
  const c00 = headerRow[0]
  const dtMetaRaw = c00 && c00.custom && c00.custom.dtMeta
  if (typeof dtMetaRaw === 'string' && dtMetaRaw.length > 0) {
    try {
      viewMeta = JSON.parse(dtMetaRaw)
    } catch {
      viewMeta = null
    }
  }

  return { sheetId, name, rowCount, columnCount, fields, records, viewMeta }
}

/**
 * 把平台的 code 翻译成「下一步该干什么」。
 * 这几个码成因完全不同，光报数字会让人瞎猜，甚至误判成「脚本有 bug」。
 */
function diagnoseCode(code, message) {
  switch (String(code)) {
    case '4040':
      return '该 base 上没部署资料库（repo）模块 —— PLATFORM_BASE_URL 指错环境了，换到实际部署了资料库的域名。'
    case '4000':
      return /api\s*key|api密钥|密钥/i.test(message || '')
        ? '端点存在，但这个 AK 对「该环境」无效 —— 换一个绑定到该环境成员 user 的 AK。'
        : '请求不合法 —— 核对 pageId 是数字、路径是 /pages/{pageId}/content。'
    case '4030':
      return 'AK 缺失 / 无效 / 已过期 —— 补 Authorization 头，或 POST /api/v1/4sandbox/file/ak/refresh 刷新。'
    case '0001':
      return 'AK 有效但读不到该页 —— 换绑定到该 space 成员 user 的 AK；并确认 pageId 是「多维表格」页。'
    default:
      return '确认 pageId 是「多维表格」页、且 AK 对它有读权限。'
  }
}

/**
 * 从「任意一种读到的 JSON」里取出 workbook。
 *
 * 兼容三种形态，方便把真环境的一次响应直接存成 fixture 回放：
 *   1) 平台 API 原始响应  { code, data: { snapshot } }
 *   2) 只含 snapshot      { snapshot: {...} }
 *   3) workbook 本身      { sheetOrder, sheets }
 */
function extractWorkbook(json) {
  if (json == null || typeof json !== 'object') return null
  const isWb = (v) => v != null && typeof v === 'object' && v.sheets != null
  const cands = [
    json.data && json.data.snapshot, // ① 平台 API 原始响应
    json.snapshot, // ② 只含 snapshot
    json.data, // ③ 有的实现把 workbook 直接放 data
    json, // ④ workbook 本身
  ]
  for (const c of cands) {
    if (isWb(c)) return c
  }
  return null
}

/** 解析整个 workbook —— 对齐 sidecar parseDataTableWorkbook()，顶层单表字段取 tables[0] */
function parseWorkbook(wb) {
  const sheetOrder = Array.isArray(wb && wb.sheetOrder) ? wb.sheetOrder : []
  const tables = sheetOrder
    .filter((sid) => typeof sid === 'string' && sid !== '')
    .map((sid) => parseSingleSheet(wb, sid))
  const first = tables[0]
  if (first == null) {
    return {
      sheetId: null,
      rowCount: 20,
      columnCount: 20,
      fields: [],
      records: [],
      viewMeta: null,
      tables: [],
    }
  }
  return {
    sheetId: first.sheetId,
    rowCount: first.rowCount,
    columnCount: first.columnCount,
    fields: first.fields,
    records: first.records,
    viewMeta: first.viewMeta,
    tables,
  }
}

// U+2028/U+2029 是 JS 行终止符，JSON.stringify 不会转义它们，直接内嵌 <script> 会截断脚本。
// 不用正则字面量写（源码里出现该字符本身就会被解析器当换行），改用 fromCharCode 构造。
const LS = String.fromCharCode(0x2028)
const PS = String.fromCharCode(0x2029)

/** JSON → 可安全内嵌进 <script> 的字面量（转义 </ 防提前闭合，以及 U+2028/2029） */
function toScriptLiteral(value) {
  return JSON.stringify(value)
    .replace(/<\//g, '<\\/')
    .split(LS)
    .join('\\u2028')
    .split(PS)
    .join('\\u2029')
}

/** 生成注入桩：window.repoDatatable + window.repoHost（模板会用到后者，缺了标题/偏好会空） */
function buildStub(snapshot, opts) {
  const payload = toScriptLiteral(snapshot)
  const docTitle = toScriptLiteral(opts.title)
  const readonly = opts.readonly ? 'true' : 'false'
  return (
    '<script>(function(){\n' +
    '  var SNAPSHOT = ' + payload + ';\n' +
    '  var READONLY = ' + readonly + ';\n' +
    '  var DOC_TITLE = ' + docTitle + ';\n' +
    '  function deny(m){ return Promise.reject(new Error("forbidden: " + m + " —— 本地桩为只读预览（真实环境 403）")); }\n' +
    '  window.repoDatatable = {\n' +
    '    read: function(){ return Promise.resolve(SNAPSHOT); },\n' +
    '    setCellValue: function(row,col,v){ if(READONLY) return deny("setCellValue"); console.log("[桩] setCellValue", row, col, v); return Promise.resolve(); },\n' +
    '    appendRecord: function(v){ if(READONLY) return deny("appendRecord"); console.log("[桩] appendRecord", v); return Promise.resolve(); },\n' +
    '    insertRecordAt: function(row){ if(READONLY) return deny("insertRecordAt"); console.log("[桩] insertRecordAt", row); return Promise.resolve(); },\n' +
    '    removeRecordAt: function(row){ if(READONLY) return deny("removeRecordAt"); console.log("[桩] removeRecordAt", row); return Promise.resolve(); },\n' +
    '    setField: function(col,name,schema){ console.log("[桩] setField", col, name, schema); return Promise.resolve(); },\n' +
    '    addField: function(col,name,schema){ console.log("[桩] addField", col, name, schema); return Promise.resolve(); },\n' +
    '    removeField: function(col){ console.log("[桩] removeField", col); return Promise.resolve(); },\n' +
    '    setViewMeta: function(v){ console.log("[桩] setViewMeta", v); return Promise.resolve(); }\n' +
    '  };\n' +
    '  var titleHandlers = [];\n' +
    '  window.repoHost = {\n' +
    '    getTitle: function(){ return DOC_TITLE; },\n' +
    '    onTitle: function(fn){ if(typeof fn === "function") titleHandlers.push(fn); },\n' +
    '    getPref: function(){ return Promise.resolve(null); },\n' +
    '    setPref: function(k,v){ console.log("[桩] setPref", k, v); return Promise.resolve(); },\n' +
    '    resolveFileUrl: function(){ return Promise.resolve(null); }\n' +
    '  };\n' +
    '  window.__previewSetTitle = function(t){ DOC_TITLE = t; titleHandlers.forEach(function(f){ f(t); }); };\n' +
    '})();</script>\n'
  )
}

// —— 宿主解析模拟（与 pivot_lint.py 的 lint_host_parse 同款规则）——
// 宿主对透视页 HTML 的两处解析都是**裸正则跑在原始文本上、取首个匹配**，既不剥注释也不建 DOM：
//   ① 目标数据表 slugId ← 首个 repo-datatable-source meta 的 content
//   ② CSP/SDK 注入点     ← 首个 <head>
// 所以注释/脚本里放个示例标签就会抢走真标签，而**本地预览看不出**：
// 数据是桩喂的、压根不读 meta，页面会渲染得完美无缺，上线却一条数据都读不到。
// 这就是「本地绿、线上炸」最贵的形态，故这里按宿主规则如实模拟并**直接报错**。
const HOST_META_RE = /<meta[^>]+name=["']repo-datatable-source["'][^>]*>/i
const HOST_META_CONTENT_RE = /content=["']([^"']*)["']/i
const TEXT_BLOCK_RE = /<(script|style|title|textarea)\b[^>]*>([\s\S]*?)<\/\1\s*>/gi

/** 注释与 script/style/title/textarea **正文**的区间：落在里面的「标签」是纯文本，宿主却照样命中 */
function textRegions(html) {
  const regions = []
  const cmt = /<!--[\s\S]*?-->/g
  let m
  while ((m = cmt.exec(html)) != null) {
    regions.push({ start: m.index, end: m.index + m[0].length, kind: '注释' })
  }
  TEXT_BLOCK_RE.lastIndex = 0
  while ((m = TEXT_BLOCK_RE.exec(html)) != null) {
    if (m[2] === '') continue
    const start = m.index + m[0].indexOf(m[2])
    regions.push({ start, end: start + m[2].length, kind: `${m[1].toLowerCase()} 正文` })
  }
  return regions
}

const enclosingRegion = (regions, pos) =>
  regions.find((r) => r.start <= pos && pos < r.end) || null

const lineOf = (html, pos) => html.slice(0, pos).split('\n').length

/**
 * 按宿主规则检查 meta 开关 —— 不通过就**不能出预览**。
 *
 * 关键：桩不读 meta 也能渲染出数据，所以「meta 缺失 / 被注释顶掉」这类页面
 * 在这里会显得完全正常，上线却彻底读不到数据。宁可这里报错，也不要给一个骗人的绿。
 */
function checkHostMeta(html) {
  const meta = HOST_META_RE.exec(html)
  if (meta == null) {
    console.error('✘ 页面里没有 repo-datatable-source meta —— 线上不会注入数据桥，页面一条数据都读不到。')
    console.error('   （本地之所以能渲染，是因为数据是本桩喂的；别把这份预览当通过。）')
    console.error('   加上：<meta name="repo-datatable-source" content="数据表slugId">')
    return false
  }
  const region = enclosingRegion(textRegions(html), meta.index)
  if (region != null) {
    const got = HOST_META_CONTENT_RE.exec(meta[0])
    console.error(
      `✘ 首个 repo-datatable-source meta 落在${region.kind}里（第 ${lineOf(html, meta.index)} 行）。`,
    )
    console.error(
      `   宿主取的是**首个文本匹配**且不剥注释 → 会把 "${got ? got[1] : ''}" 当成目标数据表 slugId，`,
    )
    console.error('   真 meta 被挤掉，线上 read() 报 forbidden（本地同样看不出来）。')
    console.error(`   修法：把示例标签从${region.kind}里删掉，或写成不含尖括号的纯文字。`)
    return false
  }
  return true
}

/**
 * 注入桩，位置与宿主**完全一致**（否则本地能跑、线上跑不了 —— 正是要消灭的失败模式）。
 *
 * 宿主 buildPivotFrameHtml 的三分支（裸正则、原始文本、取首个匹配）：
 *   ① 有 <head> → 插到该 <head> 之后
 *   ② 无 <head> 有 <html> → 在 <html> 后包一个 <head> 塞进去
 *   ③ 都没有 → 整个前置
 * 首个 <head>/<html> 落在注释或脚本正文里时，宿主注入的内容是**惰性的**
 * （window.repoDatatable 不存在 → 页面永远走兜底面板），这里直接报错。
 *
 * 宿主还会注入一个 CSP meta，本地**刻意不注入**：它那三条限制
 * （form-action / base-uri / object-src 'none'）都由 pivot_lint 单独拦下，
 * 本地注入不会多抓到问题，只会给排错添一层干扰。
 *
 * @returns {{html: string, anchor: string} | null} null = 注入点不可用（已打印原因）
 */
function inject(html, stub) {
  const regions = textRegions(html)

  const head = /<head[^>]*>/i.exec(html)
  if (head != null) {
    const region = enclosingRegion(regions, head.index)
    if (region != null) return injectBlocked(html, head.index, region, '<head>')
    return { html: html.replace(/<head[^>]*>/i, (m) => `${m}\n${stub}`), anchor: '<head> 之后' }
  }

  const htmlTag = /<html[^>]*>/i.exec(html)
  if (htmlTag != null) {
    const region = enclosingRegion(regions, htmlTag.index)
    if (region != null) return injectBlocked(html, htmlTag.index, region, '<html>')
    return {
      html: html.replace(/<html[^>]*>/i, (m) => `${m}<head>${stub}</head>`),
      anchor: '<html> 后补的 <head> 里',
    }
  }

  // 残缺片段：宿主整体前置（浏览器会自行纠正结构，脚本仍先于 body 执行）
  return { html: stub + html, anchor: '整体前置（无 html/head）' }
}

function injectBlocked(html, pos, region, tag) {
  console.error(`✘ 首个 ${tag} 落在${region.kind}里（第 ${lineOf(html, pos)} 行）。`)
  console.error('   宿主按裸正则取首个文本匹配注入 SDK，注入的内容落进去是**惰性的** ——')
  console.error('   window.repoDatatable 不会存在，线上永远走兜底面板。')
  console.error(`   修法：把 ${tag} 字样从${region.kind}里移出去（写成不含尖括号的文字）。`)
  return null
}

function usage() {
  console.log(
    [
      '透视页本地预览桩',
      '',
      '用法: node scripts/preview_stub.js <数据表pageId> <页面.html> [输出.html] [选项]',
      '      node scripts/preview_stub.js --from-file <快照.json> <页面.html> [输出.html] [选项]',
      '',
      '环境变量（联网模式需要）: PLATFORM_BASE_URL、SANDBOX_ACCESS_KEY',
      '',
      '  --from-file <json>   离线模式：不从网络拉数据，直接读一份快照文件。',
      '                       接受平台 API 原始响应 / {snapshot} / workbook 本身三种形态。',
      '                       有了它，「需要登录态的真数据」可以「录一次、之后永久离线回放」。',
      '  --save-snapshot <json>  联网模式下顺手把拉到的原始快照存成文件，供日后 --from-file 回放。',
      '  --readonly        以「分享页访客」身份预览：read() 正常，写方法按真实语义 reject',
      '  --print-payload   额外打印注入的 read() 数据结构（排查用）',
      '',
      '断言（需本机装 Chrome；缺 Chrome 一律退 5，绝不假装通过）:',
      '  --assert          生成后立刻真渲染并断言。退 0 全过 / 1 有失败 / 5 环境不具备',
      '  --expect <json>   补充/覆盖期望，如 \'{"containsText":["支出"],"records":31}\'',
      '                    也可写进页面：<meta name="pivot-assert" content=\'{...}\'>',
      '  --fail-writes     让写方法一律 reject，验「写失败时页面不崩」（只读/无权限门控）',
      '  --shots <dir>     落每个视口的截图（视觉问题排查用）',
      '  --json-out <file> 把断言结果写成 JSON（机器可读）',
      '',
      '输出默认 /tmp/pivot-preview-<页面名>.html，浏览器直接打开。',
    ].join('\n'),
  )
}

/** 读页面里的 <meta name="pivot-assert" content='{...}'>：显式断言期望。 */
function readAssertMeta(html) {
  const tag = /<meta[^>]+name=["']pivot-assert["'][^>]*>/i.exec(html)
  if (tag == null) return null
  const c = /content=["']([^"']*)["']/i.exec(tag[0])
  if (c == null || c[1].trim() === '') return null
  try {
    const spec = JSON.parse(c[1])
    if (spec == null || typeof spec !== 'object' || Array.isArray(spec)) {
      return { __parseError: 'content 必须是 JSON 对象' }
    }
    return spec
  } catch (exc) {
    return { __parseError: exc.message }
  }
}

async function main() {
  const argv = process.argv.slice(2)
  if (argv.includes('-h') || argv.includes('--help') || argv.length === 0) {
    usage()
    return argv.length === 0 ? 2 : 0
  }
  // 选项解析：这几个要吃掉后一个 token 当值，不能当位置参数
  const VALUE_OPTS = {
    '--from-file': 'fromFile',
    '--save-snapshot': 'saveSnapshot',
    '--expect': 'expect',
    '--shots': 'shots',
    '--json-out': 'jsonOut',
  }
  const flags = new Set()
  const opts = {}
  const args = []
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (VALUE_OPTS[a]) {
      const v = argv[++i]
      if (v == null || v.startsWith('--')) {
        console.error(`✘ ${a} 需要一个值`)
        return 2
      }
      opts[VALUE_OPTS[a]] = v
      continue
    }
    if (a.startsWith('--')) {
      flags.add(a)
      continue
    }
    args.push(a)
  }
  const readonly = flags.has('--readonly')
  const assertOn = flags.has('--assert')
  const { fromFile, saveSnapshot } = opts
  if (opts.expect != null) {
    let parsed
    try {
      parsed = JSON.parse(opts.expect)
    } catch (exc) {
      console.error(`✘ --expect 不是合法 JSON：${exc.message}`)
      return 2
    }
    if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      console.error('✘ --expect 必须是 JSON 对象，如 \'{"containsText":["支出"]}\'')
      return 2
    }
    opts.expectSpec = parsed
  }

  // 位置参数：联网模式 [数据表pageId, 页面.html, 输出.html]；离线模式 [页面.html, 输出.html]
  let dtPageId, htmlPath, outArg
  if (fromFile) {
    ;[htmlPath, outArg] = args
  } else {
    ;[dtPageId, htmlPath, outArg] = args
  }
  if (!htmlPath || (!fromFile && !dtPageId)) {
    usage()
    return 2
  }
  if (!fs.existsSync(htmlPath)) {
    console.error(`✘ 页面文件不存在：${htmlPath}`)
    return 2
  }

  // 1) 取得数据表 workbook：离线读文件，联网拉接口
  let wb
  if (fromFile) {
    try {
      wb = extractWorkbook(JSON.parse(fs.readFileSync(fromFile, 'utf8')))
    } catch (exc) {
      console.error(`✘ 读快照文件失败：${exc.message}`)
      return 1
    }
    if (!wb) {
      console.error(`✘ ${fromFile} 里找不到 workbook（需要含 sheets 的对象，或平台 API 原始响应）`)
      return 1
    }
    console.log(`· 离线模式：快照来自 ${fromFile}`)
  } else {
    const base = (process.env.PLATFORM_BASE_URL || '').replace(/\/+$/, '')
    const ak = process.env.SANDBOX_ACCESS_KEY || ''
    if (!base || !ak) {
      console.error('✘ 缺 PLATFORM_BASE_URL / SANDBOX_ACCESS_KEY（联网读数据表要用；只想离线回放就加 --from-file）')
      return 2
    }
    try {
      const res = await fetch(`${base}/api/v1/4sandbox/repo/pages/${dtPageId}/content`, {
        headers: { Authorization: `Bearer ${ak}` },
      })
      const body = await res.json()
      if (body.code !== '0000') {
        console.error(`✘ 拉数据表快照失败：${body.code} ${body.message || ''}`)
        console.error(`  ${diagnoseCode(body.code, body.message)}`)
        console.error('  若 AK 一时不可用：在能读的时刻加 --save-snapshot 录一次，之后用 --from-file 离线回放。')
        return 1
      }
      wb = body.data && body.data.snapshot
      if (!wb) {
        console.error('✘ 快照为空 —— 确认这个 pageId 是多维表格页（不是普通文档/sheet）')
        return 1
      }
    } catch (exc) {
      console.error(`✘ 请求异常：${exc.message}`)
      return 1
    }
    // 录 fixture：把原始响应整体存下来，日后 --from-file 可原样回放（走同一条解析路径）
    if (saveSnapshot) {
      try {
        fs.writeFileSync(saveSnapshot, JSON.stringify({ code: '0000', data: { snapshot: wb } }, null, 2))
        console.log(`· 已录制快照：${saveSnapshot}（日后 --from-file 回放，无需再联网/登录）`)
      } catch (exc) {
        console.error(`⚠️  快照录制失败（不影响本次预览）：${exc.message}`)
      }
    }
  }

  const snapshot = parseWorkbook(wb)
  if (snapshot.records.length === 0) {
    console.error('⚠️  解析出 0 条记录 —— 页面会渲染成空态。确认数据表里有数据、且表头行不为空。')
  }

  // 2) 宿主解析模拟 + 注入桩并写出
  const raw = fs.readFileSync(htmlPath, 'utf8')
  if (!checkHostMeta(raw)) {
    console.error('   （这条与 pivot_lint.py 第 0 步同款；两边都拦，因为这条缝最贵：本地全绿、线上全黑。）')
    return 1
  }
  const docTitle = process.env.PREVIEW_TITLE || `预览站点 - ${path.basename(htmlPath)}`
  const out = outArg || path.join('/tmp', `pivot-preview-${path.basename(htmlPath)}`)
  const stub = buildStub(snapshot, { readonly, title: docTitle })
  const injected = inject(raw, stub)
  if (injected == null) return 1
  fs.writeFileSync(out, injected.html, 'utf8')

  if (flags.has('--print-payload')) {
    console.log('--- read() 注入结构 ---')
    console.log(JSON.stringify(snapshot, null, 2))
  }

  const t = snapshot.tables
  console.log(`✔ 本地预览已生成：${out}`)
  console.log(
    `  ${fromFile ? '快照源 ' + fromFile : '数据表页 ' + dtPageId}：${t.length} 张表 / ` +
      `${snapshot.records.length} 条记录 / ${snapshot.fields.length} 个字段` +
      `（table[0] 网格 ${snapshot.rowCount}×${snapshot.columnCount}）`,
  )
  console.log(`  身份：${readonly ? '只读（写方法会 reject）' : '可编辑'}`)
  console.log(`  宿主解析：meta ✓ / 注入点 ${injected.anchor}（与宿主规则一致，故注入位置可信）`)

  // 3) --assert：真渲染 + 逐项断言，把「打开看一眼」变成可判定的结论
  if (!assertOn) {
    // 不加 --assert 就只是「生成了文件」，渲染正确性**从未被检查**——所以退 4，不退 0。
    // 退 0 会被读成「这一步过了」，而下一次调用的人只会看到 0，不会看到这行字。
    console.log('')
    console.log('  ⚠️  渲染未验证（退出码 4）——只生成了文件，没人判过它对不对。')
    console.log('     「打开看一眼」不算验证：看不到少一个分组桶、窄屏横向溢出、某列全是 NaN。')
    console.log('  推荐：加 --assert 让渲染变成机器可判的断言；或直接跑一条命令')
    console.log('    bash scripts/pivot_check.sh <页面.html> <数据表slugId> [数据表pageId]')
    console.log('  （静态红线另由 python3 scripts/pivot_lint.py 兜）')
    return 4
  }
  // 断言期望：页面里的 pivot-assert meta 打底，--expect 覆盖同名键（命令行更靠后 → 优先级更高）
  const metaSpec = readAssertMeta(raw)
  if (metaSpec && metaSpec.__parseError) {
    console.error(`✘ 页面里的 pivot-assert meta 不是合法 JSON：${metaSpec.__parseError}`)
    console.error("   写成 <meta name=\"pivot-assert\" content='{\"containsText\":[\"支出\"]}'>（content 用单引号包）")
    return 2
  }
  const expectSpec =
    metaSpec || opts.expectSpec
      ? Object.assign({}, metaSpec || {}, opts.expectSpec || {})
      : null

  const { runAssertions, formatReport } = require('./assert_engine.js')
  console.log('· 真渲染断言中（系统 Chrome + 零依赖 CDP）…')
  const res = await runAssertions({
    htmlPath: out,
    snapshot,
    expect: expectSpec,
    failWrites: flags.has('--fail-writes'),
    shotDir: opts.shots || null,
    onLog: (m) => console.log(`  ${m}`),
  })
  console.log('')
  console.log(formatReport(res))
  if (opts.jsonOut) {
    try {
      fs.writeFileSync(opts.jsonOut, JSON.stringify(res, null, 2))
      console.log(`  JSON 结果：${opts.jsonOut}`)
    } catch (exc) {
      console.error(`  ⚠️  JSON 写出失败（不影响断言结论）：${exc.message}`)
    }
  }
  if (res.envUnavailable) return 5
  if (!res.ok) {
    console.error('  （逐项 detail 见上方；要看注入的数据加 --print-payload，要截图加 --shots <dir>）')
    return 1
  }
  console.log('  下一步：跑 pivot_lint.py → /import（人眼复核可选，但推荐扫一眼视觉）')
  return 0
}

// 被当作模块 require 时不执行 CLI（dt_rows.js 复用这里的解析，避免两份解析语义漂移）
if (require.main === module) {
  main()
    .then((code) => process.exit(code))
    .catch((exc) => {
      console.error('✘', exc && exc.message ? exc.message : exc)
      process.exit(1)
    })
}

// buildStub / inject / checkHostMeta 一并导出：e2e_pivot.mjs 要用同一套桩与注入逻辑，
// 两条入口共用一份实现，避免「预览看到的」与「e2e 断言的」不是同一个页面。
module.exports = {
  parseWorkbook,
  parseSingleSheet,
  extractWorkbook,
  diagnoseCode,
  buildStub,
  inject,
  checkHostMeta,
}

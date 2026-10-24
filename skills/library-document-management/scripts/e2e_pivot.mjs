#!/usr/bin/env node
/**
 * 透视页端到端断言 —— 一份快照，跑多视口 + 坏数据演练，一次给结论。
 *
 * 用法:
 *   node scripts/e2e_pivot.mjs --page family.html --from-file snap.json \
 *     [--viewports 1440x900,390x844,320x375] [--fail-writes] [--corrupt] \
 *     [--writes-threshold 20] [--shot-dir /tmp/shots] [--expect '<json>'] [--json-out r.json]
 *
 * 退出码: 0 断言全过 / 1 有断言失败 / 2 参数错 / 4 未启用断言（不会出现，留作契约对齐） / 5 环境不具备（没 Chrome）
 *
 * 与 preview_stub.js --assert 的分工:
 *   - preview_stub.js --assert  单视口、快速、导入前的那一关（默认跑这个就够）
 *   - e2e_pivot.mjs             多视口 + 写失败演练 + 坏数据演练，改版收尾 / 交付前跑
 *   两者**共用** preview_stub 的桩与注入、以及 assert_engine 的判定内核 —— 不会出现
 *   「预览绿、e2e 红」这种两套标准互相打架的情况。
 *
 * 为什么要多视口: 透视页在预览框里可能只有 400px 宽，也可能全屏 1440px。
 *   只在桌面宽度验证过的页面，在窄预览框里横向溢出 / 指标条挤成一列 / 看板列塌成 0 宽，
 *   都是上线后才被发现的老问题（横向溢出尤其常见）。
 *
 * 为什么要 --corrupt: 线上出过「网格维度是负数 → 界面整片空白」的事故。
 *   页面在拿到**形状不对**的数据时，至少要做到「不白屏、不报错、不吐 NaN」，
 *   而不是把异常直接甩到屏幕上。--corrupt 就是把这个要求变成可判定的断言。
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const require_ = createRequire(import.meta.url)
const stubMod = require_(path.join(HERE, 'preview_stub.js'))
const { runAssertions, formatReport } = require_(path.join(HERE, 'assert_engine.js'))

const DEFAULT_VIEWPORTS = ['1440x900', '390x844', '320x375']

function usage() {
  console.log(
    [
      '透视页端到端断言（多视口 + 假写 + 坏数据演练）',
      '',
      '用法: node scripts/e2e_pivot.mjs --page <页面.html> --from-file <快照.json> [选项]',
      '',
      '  --page <html>           要测的页面（原始页面文件，不用先跑 preview_stub）',
      '  --from-file <json>      数据快照。多视口要比对同一份数据，所以固定用快照回放。',
      '                          没有快照时：先联网跑一次 preview_stub.js --save-snapshot snap.json',
      '  --viewports <列表>      如 1440x900,390x844,320x375（默认就是这三个；窄的那个是预览框宽度）',
      '  --fail-writes          让写方法一律 reject：验「写失败时页面不崩、不假装成功」',
      '  --corrupt              坏数据演练：记录清空 / 表头清空 / 类型错乱 / 网格维度非法，',
      '                          每个变体都要求「不白屏、无报错、不吐 NaN」',
      '  --writes-threshold <n> 渲染期允许的最大写调用数（默认不判；页面只读时建议 0）',
      "  --expect '<json>'      额外期望，如 '{\"containsText\":[\"本月支出\"]}'",
      '  --shot-dir <dir>       落每个视口的截图',
      '  --json-out <file>      把完整结果写成 JSON',
      '',
      '退出码: 0 全过 / 1 断言失败 / 2 参数错 / 5 环境不具备（没 Chrome，不假装通过）',
    ].join('\n'),
  )
}

/** '1440x900,390x844' → [{width,height,name,mobile}]；窄屏自动标 mobile（让宿主按手机视口排版） */
function parseViewports(spec) {
  const out = []
  for (const raw of String(spec).split(',')) {
    const s = raw.trim()
    if (s === '') continue
    const m = /^(\d+)\s*[x×]\s*(\d+)$/i.exec(s)
    if (m == null) return { error: `视口写法不对：「${s}」（应为 1440x900 这种）` }
    const width = Number(m[1])
    const height = Number(m[2])
    if (width < 200 || height < 200 || width > 8000 || height > 8000) {
      return { error: `视口尺寸不合理：${width}x${height}` }
    }
    out.push({ width, height, name: `${width}x${height}`, mobile: width <= 500 })
  }
  if (out.length === 0) return { error: '--viewports 是空的' }
  return { viewports: out }
}

/**
 * 把毒施加到「页面真正读的那一层」—— 顶层字段 **以及 tables[] 的每一项**。
 *
 * 为什么必须两层都改（实测踩过）：read() 返回的既有顶层 records/fields（旧单表形态），
 * 也有 tables[]（多表形态），页面取用哪一层取决于模板。第一版只改了顶层，
 * 结果 4 个坏数据变体渲染出来和正常数据**一模一样**（元素 519 / 文本 765 全等）——
 * 演练等于没做，还一路报绿。毒不到页面上的演练比不做更危险。
 */
function poison(snapshot, mutate) {
  const out = JSON.parse(JSON.stringify(snapshot))
  mutate(out)
  if (Array.isArray(out.tables)) for (const t of out.tables) mutate(t)
  return out
}

/**
 * 四个坏数据变体 —— 全部是**真实发生得出来**的形态，不是凭空造的：
 *   记录清空 / 表头清空 / 类型错乱 / 网格维度非法（2026-10-08 线上事故形态）。
 * 每个变体只改数据、不改页面，回答同一个问题：页面拿到坏数据会不会把异常甩给用户。
 *
 * @param {string[]} baseProbes 正常快照的「数据特征值」探针，用于自证毒已生效
 */
function corruptVariants(snapshot, baseProbes) {
  const noRecords = poison(snapshot, (t) => {
    t.records = []
  })

  const emptyHeader = poison(snapshot, (t) => {
    t.fields = []
    t.records = (t.records || []).map((r) => ({ ...r, values: {} }))
  })

  const badTypes = poison(snapshot, (t) => {
    t.records = (t.records || []).map((r) => {
      const values = {}
      for (const k of Object.keys(r.values || {})) {
        const v = String(r.values[k] ?? '')
        if (/^\d+(\.\d+)?$/.test(v)) values[k] = '这不是数字' // 数值列塞字符串 → 页面求和/均值最容易出 NaN
        else if (/^\d{4}[-/.]\d{1,2}/.test(v)) values[k] = '不是日期'
        else values[k] = v === '' ? '…' : v
      }
      return { ...r, values }
    })
  })

  const negDims = poison(snapshot, (t) => {
    t.rowCount = -23 // 网格维度非法：行数为负 ⇒ 按行渲染的页面会一行都画不出来
    t.columnCount = -4
  })

  // 「数据清空」类变体额外断言：原来的数据值一个都不许再出现。
  // 这是**自证**——如果页面还显示着老数据，说明毒没到页面上，这个演练是假的。
  const gone = { excludesText: (baseProbes || []).slice(0, 4) }

  return [
    { key: '记录清空', suffix: 'no-records', snap: noRecords, why: '数据表一条记录都没有', expect: gone },
    { key: '表头清空', suffix: 'empty-header', snap: emptyHeader, why: '字段全没了（表头被空列吞掉）', expect: gone },
    { key: '类型错乱', suffix: 'bad-types', snap: badTypes, why: '数值列给字符串、日期列给乱码', expect: {} },
    { key: '维度非法', suffix: 'neg-dims', snap: negDims, why: '网格行/列为负数', expect: {} },
  ]
}

/** 用 preview_stub 的桩 + 宿主注入语义，把某份快照注入页面，写成一个临时 HTML */
function injectSnapshot(pageHtml, snapshot, tmpDir, tag) {
  const raw = fs.readFileSync(pageHtml, 'utf8')
  if (!stubMod.checkHostMeta(raw)) {
    return { error: '宿主解析模拟未通过（meta 开关缺失，或注释/脚本里的假 meta·假 head 抢走了真标签）——先跑 pivot_lint.py' }
  }
  const stub = stubMod.buildStub(snapshot, { readonly: false, title: `e2e ${tag}` })
  const injected = stubMod.inject(raw, stub)
  if (injected == null) return { error: '注入失败（宿主注入点落在注释/脚本里，见上）' }
  const out = path.join(tmpDir, `e2e-${tag}.html`)
  fs.writeFileSync(out, injected.html, 'utf8')
  return { htmlPath: out, anchor: injected.anchor }
}

async function main() {
  const argv = process.argv.slice(2)
  if (argv.length === 0 || argv.includes('-h') || argv.includes('--help')) {
    usage()
    return argv.length === 0 ? 2 : 0
  }

  const VALUE_OPTS = {
    '--page': 'page',
    '--from-file': 'fromFile',
    '--viewports': 'viewports',
    '--writes-threshold': 'writesThreshold',
    '--shot-dir': 'shotDir',
    '--expect': 'expect',
    '--json-out': 'jsonOut',
  }
  const flags = new Set()
  const opts = {}
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
    console.error(`✘ 不认识的位置参数：${a}`)
    usage()
    return 2
  }

  const { page, fromFile, shotDir, jsonOut } = opts
  if (!page || !fromFile) {
    console.error('✘ --page 与 --from-file 都是必填')
    usage()
    return 2
  }
  if (!fs.existsSync(page)) {
    console.error(`✘ 页面文件不存在：${page}`)
    return 2
  }
  if (!fs.existsSync(fromFile)) {
    console.error(`✘ 快照文件不存在：${fromFile}`)
    console.error('  没有快照就联网跑一次：node scripts/preview_stub.js <数据表pageId> <页面.html> --save-snapshot snap.json')
    return 2
  }

  const vpParsed = parseViewports(opts.viewports || DEFAULT_VIEWPORTS.join(','))
  if (vpParsed.error) {
    console.error(`✘ ${vpParsed.error}`)
    return 2
  }
  const viewports = vpParsed.viewports

  let expectSpec = null
  if (opts.expect != null) {
    try {
      expectSpec = JSON.parse(opts.expect)
    } catch (exc) {
      console.error(`✘ --expect 不是合法 JSON：${exc.message}`)
      return 2
    }
    if (expectSpec == null || typeof expectSpec !== 'object' || Array.isArray(expectSpec)) {
      console.error("✘ --expect 必须是 JSON 对象，如 '{\"containsText\":[\"本月支出\"]}'")
      return 2
    }
  }
  const maxWriteCalls = opts.writesThreshold != null ? Number(opts.writesThreshold) : null
  if (maxWriteCalls != null && (!Number.isInteger(maxWriteCalls) || maxWriteCalls < 0)) {
    console.error(`✘ --writes-threshold 需要非负整数，收到：${opts.writesThreshold}`)
    return 2
  }

  // 快照 → 解析出 read() 的返回结构（与页面运行时看到的完全一致）
  let wb
  try {
    wb = stubMod.extractWorkbook(JSON.parse(fs.readFileSync(fromFile, 'utf8')))
  } catch (exc) {
    console.error(`✘ 读快照失败：${exc.message}`)
    return 2
  }
  if (!wb) {
    console.error(`✘ ${fromFile} 里找不到 workbook（需要含 sheets 的对象，或平台 API 原始响应）`)
    return 2
  }
  const snapshot = stubMod.parseWorkbook(wb)
  if (snapshot.records.length === 0) {
    console.error('⚠️  快照解析出 0 条记录 —— 正向用例会退化成空态检查（--corrupt 仍然有效）')
  }
  if (shotDir) {
    try {
      fs.mkdirSync(shotDir, { recursive: true })
    } catch (exc) {
      console.error(`✘ 截图目录建不出来：${exc.message}`)
      return 2
    }
  }

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pivot-e2e-'))
  const runs = []

  // ① 正向：多视口
  const base = injectSnapshot(page, snapshot, tmpDir, 'base')
  if (base.error) {
    console.error(`✘ ${base.error}`)
    return 2
  }
  console.log(`· 正向：${viewports.map((v) => v.name).join(' / ')}（快照 ${fromFile}）`)
  const baseRes = await runAssertions({
    htmlPath: base.htmlPath,
    viewports,
    snapshot,
    expect: Object.assign({}, expectSpec || {}, maxWriteCalls != null ? { maxWriteCalls } : {}),
    failWrites: flags.has('--fail-writes'),
    shotDir: shotDir || null,
    onLog: (m) => console.log(`  ${m}`),
  })
  if (baseRes.envUnavailable) {
    console.log('')
    console.log(formatReport(baseRes))
    console.error('✘ 环境不具备 —— 已停止，未假装通过。')
    return 5
  }
  runs.push({ title: `正向 ${viewports.map((v) => v.name).join('/')}`, res: baseRes })
  console.log('')
  console.log(formatReport(baseRes))

  // ② 坏数据演练：每个变体只看「不白屏 / 无报错 / 不吐 NaN」这组健壮性断言
  if (flags.has('--corrupt')) {
    const baseProbes = (baseRes.expected && baseRes.expected.probeValues) || []
    if (baseProbes.length === 0) {
      console.error('⚠️  正常快照里没有可用作探针的数据值 —— 「数据清空」变体无法自证毒已生效，只做健壮性检查')
    }
    for (const variant of corruptVariants(snapshot, baseProbes)) {
      const inj = injectSnapshot(page, variant.snap, tmpDir, variant.suffix)
      if (inj.error) {
        console.error(`✘ 坏数据变体「${variant.key}」注入失败：${inj.error}`)
        return 2
      }
      console.log('')
      console.log(`· 坏数据演练「${variant.key}」（${variant.why}）`)
      const res = await runAssertions({
        htmlPath: inj.htmlPath,
        viewports,
        snapshot: variant.snap,
        expect: Object.assign(
          {
            tolerateEmpty: true, // 空态是允许的；不许的是白屏 / 报错 / 吐 NaN
            probeMinHits: 0,
            maxWriteCalls,
          },
          variant.expect || {},
        ),
        failWrites: flags.has('--fail-writes'),
        shotDir: shotDir || null, // 截图文件名带变体后缀（e2e-no-records-1440x900.png），不会互相覆盖
        onLog: (m) => console.log(`  ${m}`),
      })
      runs.push({ title: `坏数据「${variant.key}」`, res })
      console.log(formatReport(res))
    }
  }

  // ③ 汇总
  const failed = runs.filter((r) => !r.res.ok)
  const errorCount = runs.reduce((n, r) => n + r.res.summary.errorCount, 0)
  const warnCount = runs.reduce((n, r) => n + r.res.summary.warnCount, 0)
  const checks = runs.reduce((n, r) => n + r.res.summary.counts, 0)
  console.log('')
  console.log('─'.repeat(60))
  if (failed.length === 0) {
    console.log(
      `✔ 端到端全过：${runs.length} 组 × ${viewports.length} 视口，共 ${checks} 项检查` +
        (warnCount ? `（${warnCount} 条提醒，见上）` : ''),
    )
  } else {
    console.log(`✘ 端到端失败：${errorCount} 项 ERROR / ${warnCount} 条提醒`)
    for (const r of failed) {
      console.log(`  ✘ ${r.title}：${r.res.errors.map((e) => e.name).join(' , ')}`)
    }
  }
  if (jsonOut) {
    try {
      fs.writeFileSync(jsonOut, JSON.stringify(runs, null, 2))
      console.log(`  JSON 结果：${jsonOut}`)
    } catch (exc) {
      console.error(`  ⚠️  JSON 写出失败：${exc.message}`)
    }
  }
  console.log(`  （临时注入页在 ${tmpDir}，跑完可删）`)
  return failed.length === 0 ? 0 : 1
}

main()
  .then((code) => process.exit(code))
  .catch((exc) => {
    console.error('✘', exc && exc.message ? exc.message : exc)
    process.exit(1)
  })

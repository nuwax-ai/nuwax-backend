#!/usr/bin/env node
/**
 * 透视页断言内核 —— 把「浏览器打开、肉眼确认」变成「真渲染 + 机器可读断言」。
 *
 * 为什么要有它:
 *   preview_stub.js 只把桩注进去、写一份 HTML 就结束了，剩下的「看一眼」对 agent
 *   不成立 —— 页面到底渲染成什么样，agent 无从得知。于是一类「只有真数据+真渲染
 *   才暴露」的问题（图表整行坍缩、字段名对不上渲染成 NaN、结构对不上走空态、
 *   长文本溢出、图表库没加载）在导入前**完全没有拦截点**，本地全绿、线上才炸。
 *
 * 依赖策略（重要，别改成 npm 包）:
 *   技能是要分发到多环境的包，目录里没有 package.json / node_modules。
 *   playwright 要拉 ~150MB 浏览器二进制；jsdom 没有布局引擎（clientWidth 恒 0）
 *   ⇒ 溢出与图表坍缩这两类**恰恰测不了**。所以这里用 **系统已装的 Chrome +
 *   手写 CDP 客户端**：node 22+ 自带 WebSocket，零 npm 依赖，能力不打折。
 *   技能铁律本来就要求「浏览器打开预览确认」—— 浏览器是既有前提，这不是新增
 *   依赖，只是把既有前提自动化。
 *
 * 环境不具备时（找不到 Chrome）如实报跳过，**绝不假装通过**。
 *
 * ⚠️ 本文件被 preview_stub.js（--assert）与 e2e_pivot.mjs 共用。改渲染/采集逻辑
 *    只改这里，别在两个入口各写一份 —— 两份必然漂移（技能在解析语义上已吃过
 *    一次亏，见 preview_stub.js 顶部注释）。
 */
'use strict'

const fs = require('fs')
const os = require('os')
const path = require('path')
const { spawn, execFileSync } = require('child_process')

// ── 常量 ──
const DEFAULT_VIEWPORT = { width: 1440, height: 900, name: 'desktop' }
const DEFAULT_SETTLE_QUIET_MS = 500 // 连续多久没有 DOM 变化就算渲染稳定
const DEFAULT_SETTLE_MAX_MS = 6000 // 稳定等待上限（ECharts 首帧 + 异步 read → 渲染）
const CHROME_BOOT_MS = 15000 // DevToolsActivePort 出现的最长等待
const NAV_TIMEOUT_MS = 20000

/** 外链 JS 白名单（与 pivot_lint.py 同源：只有 ECharts 的 CDN 允许） */
const JS_WHITELIST = ['static-resources.nuwax.com']

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ────────────────────────────── Chrome 发现 ──────────────────────────────

/**
 * 找系统 Chrome。找不到返回 null（调用方必须如实报「跳过」，不要继续跑）。
 * PIVOT_CHROME 环境变量可显式指定（CI / 非常规安装位置用）。
 */
function findChrome(explicit) {
  const cands = []
  // 显式指定（参数或 PIVOT_CHROME）时**不做兜底**：指定的路径不存在就返回 null。
  // 悄悄回落到别的浏览器，会让人以为「我指定的路径生效了」，也让「本机没有 Chrome」这一分支
  // 永远无法被验证（实测踩过：PIVOT_CHROME=/nonexistent 仍然跑绿了）。
  const forced = explicit || process.env.PIVOT_CHROME || ''
  if (forced) {
    try {
      return fs.existsSync(forced) ? forced : null
    } catch {
      return null
    }
  }
  if (process.platform === 'darwin') {
    cands.push(
      '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      '/Applications/Chromium.app/Contents/MacOS/Chromium',
      '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
      '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser',
    )
  } else if (process.platform === 'win32') {
    cands.push(
      'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
      'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
      path.join(process.env.LOCALAPPDATA || '', 'Google\\Chrome\\Application\\chrome.exe'),
    )
  } else {
    cands.push(
      '/usr/bin/google-chrome',
      '/usr/bin/google-chrome-stable',
      '/usr/bin/chromium',
      '/usr/bin/chromium-browser',
      '/snap/bin/chromium',
      '/opt/google/chrome/chrome',
    )
  }
  for (const c of cands) {
    if (!c) continue
    try {
      if (fs.existsSync(c)) return c
    } catch {
      /* ignore */
    }
  }
  // PATH 兜底（linux 容器里常见只装了命令名）
  for (const name of ['google-chrome', 'google-chrome-stable', 'chromium', 'chromium-browser', 'chrome']) {
    try {
      const p = execFileSync('which', [name], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
      if (p) return p
    } catch {
      /* 没这个命令，继续 */
    }
  }
  return null
}

// ───────────────────────── 极简 CDP 客户端 ─────────────────────────
// 只做需要的事：id 关联的请求/响应、按 sessionId 过滤的事件分发。
// 用 node 内置 WebSocket（node 22+ 全局可用）——这是「零依赖」的关键。

class CDP {
  constructor(wsUrl) {
    this._wsUrl = wsUrl
    this._ws = null
    this._id = 0
    this._pending = new Map()
    this._listeners = new Map()
    this._closed = false
  }

  connect(timeoutMs = CHROME_BOOT_MS) {
    return new Promise((resolve, reject) => {
      let ws
      try {
        ws = new WebSocket(this._wsUrl)
      } catch (e) {
        reject(new Error(`DevTools WebSocket 建连失败：${e.message}`))
        return
      }
      this._ws = ws
      const timer = setTimeout(() => reject(new Error('DevTools WebSocket 连接超时')), timeoutMs)
      ws.addEventListener('open', () => {
        clearTimeout(timer)
        resolve()
      })
      ws.addEventListener('error', () => {
        clearTimeout(timer)
        reject(new Error(`DevTools WebSocket 出错（${this._wsUrl}）`))
      })
      ws.addEventListener('message', (ev) => this._onMessage(ev))
      ws.addEventListener('close', () => {
        this._closed = true
        for (const [, p] of this._pending) p.reject(new Error('DevTools 连接已关闭'))
        this._pending.clear()
      })
    })
  }

  _onMessage(ev) {
    let msg
    try {
      msg = JSON.parse(typeof ev.data === 'string' ? ev.data : String(ev.data))
    } catch {
      return
    }
    if (msg.id != null) {
      const p = this._pending.get(msg.id)
      if (p == null) return
      this._pending.delete(msg.id)
      if (msg.error) p.reject(new Error(`${p.method}: ${msg.error.message || JSON.stringify(msg.error)}`))
      else p.resolve(msg.result || {})
      return
    }
    if (msg.method) {
      const set = this._listeners.get(msg.method)
      if (set == null) return
      for (const h of set) {
        try {
          h(msg.params || {}, msg.sessionId)
        } catch {
          /* 监听器自身出错不该拖垮采集 */
        }
      }
    }
  }

  on(method, handler) {
    if (!this._listeners.has(method)) this._listeners.set(method, new Set())
    this._listeners.get(method).add(handler)
    return () => this._listeners.get(method)?.delete(handler)
  }

  send(method, params = {}, sessionId = undefined, timeoutMs = 30000) {
    if (this._closed) return Promise.reject(new Error('DevTools 连接已关闭'))
    const id = ++this._id
    const payload = { id, method, params }
    if (sessionId) payload.sessionId = sessionId
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this._pending.delete(id)
        reject(new Error(`CDP 调用超时：${method}`))
      }, timeoutMs)
      this._pending.set(id, {
        method,
        resolve: (v) => {
          clearTimeout(timer)
          resolve(v)
        },
        reject: (e) => {
          clearTimeout(timer)
          reject(e)
        },
      })
      try {
        this._ws.send(JSON.stringify(payload))
      } catch (e) {
        clearTimeout(timer)
        this._pending.delete(id)
        reject(e)
      }
    })
  }

  close() {
    try {
      if (this._ws) this._ws.close()
    } catch {
      /* ignore */
    }
  }
}

// ─────────────────── 页面注入方式：data: URL（零网络、零端口、更像真实沙箱） ───────────────────
// 为什么不用本地 http server（试过，会挂）：
//   ① 有些环境里 Chrome 的 outbound 连接被整体阻断，连 127.0.0.1 都是**挂起**而不是快速失败 ——
//      表现是 Page.navigate 超时、连 Runtime.evaluate 都不再响应，整台渲染进程像死了一样；
//   ② 透视页在线上跑在**沙箱 iframe（opaque origin）**里，data: URL 的 origin 恰好也是 null，
//      比 http://127.0.0.1 更接近真实环境。
// 代价：opaque origin 下 localStorage 不可用 —— 但真实环境同样不可用（技能已要求改用
// repoHost.getPref/setPref），内置模板零 localStorage 依赖。
const DATA_URL_WARN_LENGTH = 1_500_000

function buildDataUrl(htmlPath) {
  const html = fs.readFileSync(htmlPath)
  const url = 'data:text/html;charset=utf-8;base64,' + html.toString('base64')
  return { url, htmlBytes: html.length, urlLength: url.length }
}

/**
 * 探测外链资源是否可达。
 *
 * 为什么必须探：页面里的 `<script src="...echarts...">` 是**解析阻塞**的。网络不可达时它
 * **不快速失败，而是把整个页面挂住**（后面所有脚本一行都不执行）—— 于是「页面空白/仍在加载中」
 * 这类断言全部假红，把「环境没网」误报成「页面写坏了」。
 * 探到不可达就主动阻断外部请求（让它快速失败），页面会走自己「图表库缺失」的兜底分支，
 * 此时图表相关断言如实标记为**未验证**而不是通过。
 */
async function probeExternal(cdp, probeUrl, timeoutMs = 4000) {
  let targetId = null
  try {
    const created = await cdp.send('Target.createTarget', { url: 'about:blank' })
    targetId = created.targetId
    const attached = await cdp.send('Target.attachToTarget', { targetId, flatten: true })
    const sid = attached.sessionId
    await cdp.send('Runtime.enable', {}, sid)
    const r = await cdp.send(
      'Runtime.evaluate',
      {
        expression: `(async () => {
          try {
            const c = new AbortController();
            const t = setTimeout(() => c.abort(), ${timeoutMs});
            await fetch(${JSON.stringify(probeUrl)}, { mode: 'no-cors', signal: c.signal, cache: 'no-store' });
            clearTimeout(t);
            return 'ok';
          } catch (e) { return 'fail:' + (e && e.name ? e.name : String(e)); }
        })()`,
        awaitPromise: true,
        returnByValue: true,
      },
      sid,
      timeoutMs + 4000,
    )
    return { reachable: r.result != null && r.result.value === 'ok', detail: r.result ? String(r.result.value) : 'no-result' }
  } catch (e) {
    return { reachable: false, detail: `probe-error: ${e.message}` }
  } finally {
    if (targetId != null) {
      try {
        await cdp.send('Target.closeTarget', { targetId }, undefined, 5000)
      } catch {
        /* 关页失败不影响探测结论 */
      }
    }
  }
}

// ───────────────────── 注入记录器（页面脚本执行前挂上钩子） ─────────────────────
// 桩（preview_stub.js 的内联 <script>）用 `window.repoDatatable = {...}` 赋值，
// 所以这里用访问器属性拦截赋值即可完整记录页面**实际调了什么、拿到了什么**。

function instrumentSource(opts) {
  const failWrites = opts.failWrites ? 'true' : 'false'
  return `(function () {
  'use strict';
  var REC = { calls: [], hostCalls: [], titles: [], hookState: 'pending', instrumentedAt: Date.now() };
  window.__pivotAssert = REC;
  var WRITE_METHODS = ['setCellValue','appendRecord','insertRecordAt','removeRecordAt','setField','addField','removeField','setViewMeta'];

  function brief(v) {
    try {
      if (v === null || v === undefined) return null;
      var t = typeof v;
      if (t === 'string') return v.length > 160 ? v.slice(0, 160) + '…' : v;
      if (t === 'number' || t === 'boolean') return v;
      if (Array.isArray(v)) return { array: v.length };
      if (t === 'object') {
        var o = {};
        if (Array.isArray(v.records)) o.records = v.records.length;
        if (Array.isArray(v.fields)) {
          o.fields = v.fields.length;
          o.fieldNames = v.fields.slice(0, 40).map(function (f) { return f && f.name; });
        }
        if (Array.isArray(v.tables)) o.tables = v.tables.length;
        if (v.sheetId !== undefined) o.sheetId = v.sheetId;
        if (v.rowCount !== undefined) o.rowCount = v.rowCount;
        if (v.columnCount !== undefined) o.columnCount = v.columnCount;
        if (v.viewMeta !== undefined) o.hasViewMeta = v.viewMeta != null;
        if (Object.keys(o).length === 0) o.keys = Object.keys(v).slice(0, 20);
        return o;
      }
      return String(v);
    } catch (e) { return '<unserializable>'; }
  }

  function wrapDatatable(api) {
    if (api === null || (typeof api !== 'object' && typeof api !== 'function')) return api;
    var cache = new Map();
    return new Proxy(api, {
      get: function (target, prop, receiver) {
        if (prop === '__pivotWrapped') return true;
        var orig = Reflect.get(target, prop, receiver);
        if (typeof orig !== 'function') return orig;
        var name = String(prop);
        if (cache.has(name)) return cache.get(name);
        var kind = WRITE_METHODS.indexOf(name) >= 0 ? 'write' : 'read';
        var fn = function () {
          var args = Array.prototype.slice.call(arguments).map(brief);
          var rec = { method: name, kind: kind, args: args };
          if (kind === 'write' && ${failWrites}) {
            rec.ok = false; rec.rejected = true;
            REC.calls.push(rec);
            return Promise.reject(new Error('forbidden: ' + name + ' —— --fail-writes 模拟写失败（真实环境为 403）'));
          }
          try {
            var r = orig.apply(target, arguments);
            if (r && typeof r.then === 'function') {
              return r.then(function (v) {
                rec.ok = true; rec.result = brief(v); REC.calls.push(rec); return v;
              }, function (e) {
                rec.ok = false; rec.error = String(e && e.message ? e.message : e); REC.calls.push(rec); throw e;
              });
            }
            rec.ok = true; rec.result = brief(r); REC.calls.push(rec); return r;
          } catch (e) {
            rec.ok = false; rec.error = String(e && e.message ? e.message : e); REC.calls.push(rec); throw e;
          }
        };
        cache.set(name, fn);
        return fn;
      }
    });
  }

  function wrapHost(host) {
    if (host === null || typeof host !== 'object') return host;
    var cache = new Map();
    return new Proxy(host, {
      get: function (target, prop, receiver) {
        if (prop === '__pivotWrapped') return true;
        var orig = Reflect.get(target, prop, receiver);
        if (typeof orig !== 'function') return orig;
        var name = String(prop);
        if (cache.has(name)) return cache.get(name);
        var fn = function () {
          var rec = { method: name, ok: true };
          try {
            var r = orig.apply(target, arguments);
            if (name === 'getTitle') { rec.result = brief(r); REC.titles.push(String(r)); }
            else rec.result = brief(r);
            if (name === 'onTitle') rec.registered = typeof arguments[0] === 'function';
            REC.hostCalls.push(rec);
            return r;
          } catch (e) {
            rec.ok = false; rec.error = String(e && e.message ? e.message : e);
            REC.hostCalls.push(rec); throw e;
          }
        };
        cache.set(name, fn);
        return fn;
      }
    });
  }

  try {
    var _dt, _host;
    Object.defineProperty(window, 'repoDatatable', {
      configurable: true,
      get: function () { REC.hookDt = _dt ? 'hooked' : 'empty'; return _dt; },
      set: function (v) { _dt = wrapDatatable(v); REC.hookDt = 'hooked'; }
    });
    Object.defineProperty(window, 'repoHost', {
      configurable: true,
      get: function () { REC.hookHost = _host ? 'hooked' : 'empty'; return _host; },
      set: function (v) { _host = wrapHost(v); REC.hookHost = 'hooked'; }
    });
    REC.hookState = 'armed';
  } catch (e) {
    REC.hookState = 'define-failed: ' + (e && e.message ? e.message : e);
  }
})();`
}

// ─────────────────────── 页面内采集（一次 evaluate 拿全） ───────────────────────

const COLLECT_SCRIPT = `(function () {
  var REC = window.__pivotAssert || null;
  var de = document.documentElement;
  var vw = window.innerWidth || de.clientWidth || 0;
  var vh = window.innerHeight || de.clientHeight || 0;

  function describe(el) {
    if (!el || !el.tagName) return '?';
    var s = el.tagName.toLowerCase();
    if (el.id) return s + '#' + el.id;
    var c = typeof el.className === 'string' ? el.className.trim() : '';
    if (c) s += '.' + c.split(/\\s+/).slice(0, 2).join('.');
    return s;
  }

  // 可见文本。innerText 最准（自动排除不可见元素，也不含 script/style 正文），
  // 但它取不到时**不能直接退回 textContent** —— textContent 会把 <script> 的代码正文
  // 一起算成「页面文字」，于是白屏页面只要脚本长一点就被判成「有内容」（实测踩到过，
  // 会让白屏检测彻底失效）。这里的兜底是「克隆一份、剔掉脚本样式后再取文本」。
  var bodyText = '';
  try {
    if (document.body) {
      var it = '';
      try { it = document.body.innerText || ''; } catch (e) { it = ''; }
      if (it.replace(/\\s+/g, '').length > 0) {
        bodyText = it;
      } else {
        var clone = document.body.cloneNode(true);
        var junk = clone.querySelectorAll('script,style,noscript,template');
        for (var q = junk.length - 1; q >= 0; q--) { junk[q].parentNode.removeChild(junk[q]); }
        bodyText = clone.textContent || '';
      }
    }
  } catch (e) { bodyText = ''; }
  bodyText = bodyText.replace(/\\s+/g, ' ').trim();

  // ① 整页横向溢出（最硬的判据：页面被撑出视口）
  var overflow = { horizontal: false, viewportWidth: vw, scrollWidth: de.scrollWidth || 0, offenders: [] };
  if ((de.scrollWidth || 0) > vw + 1) {
    overflow.horizontal = true;
    var all = document.body ? document.body.querySelectorAll('*') : [];
    for (var i = 0; i < all.length && overflow.offenders.length < 8; i++) {
      var el = all[i];
      var r = el.getBoundingClientRect();
      if (r.width < 1 || r.height < 1) continue;
      if (r.right <= vw + 1) continue;
      var cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) continue;
      overflow.offenders.push({
        el: describe(el),
        right: Math.round(r.right),
        width: Math.round(r.width),
        text: (el.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 60)
      });
    }
  }

  // ② 内容被裁掉（overflow:hidden 且没做省略号）——「长文本溢出」那一类
  var clipped = [];
  var cands = document.body ? document.body.querySelectorAll('*') : [];
  for (var j = 0; j < cands.length && clipped.length < 8; j++) {
    var e2 = cands[j];
    var cs2 = getComputedStyle(e2);
    if (cs2.overflowX !== 'hidden' && cs2.overflowX !== 'clip') continue;
    if (e2.clientWidth < 8) continue;
    if (e2.scrollWidth > e2.clientWidth + 8 && cs2.textOverflow !== 'ellipsis') {
      clipped.push({ el: describe(e2), scrollWidth: e2.scrollWidth, clientWidth: e2.clientWidth });
    }
  }

  // ③ canvas —— 图表「整行坍缩」就是高度塌成个位数
  var canvases = [];
  var cs3 = document.querySelectorAll('canvas');
  for (var k = 0; k < cs3.length; k++) {
    var c = cs3[k];
    var cr = c.getBoundingClientRect();
    canvases.push({
      el: describe(c),
      parent: c.parentElement ? describe(c.parentElement) : null,
      w: Math.round(cr.width),
      h: Math.round(cr.height)
    });
  }

  // ④ 可见元素数：判断「不是白板」
  var visible = 0;
  var vs = document.body ? document.body.querySelectorAll('*') : [];
  for (var m = 0; m < vs.length; m++) {
    var r3 = vs[m].getBoundingClientRect();
    if (r3.width > 0 && r3.height > 0) visible++;
  }

  var errMatch = bodyText.match(/加载失败|读取失败|渲染失败|出错了|Cannot read propert|is not a function|is not defined/);

  return {
    title: document.title || '',
    url: location.href,
    viewport: { width: vw, height: vh, scrollWidth: de.scrollWidth || 0, scrollHeight: de.scrollHeight || 0 },
    bodyTextLength: bodyText.length,
    bodyTextHead: bodyText.slice(0, 400),
    // 完整可见文本（截 20000 字防超大页面撑爆 CDP 响应）—— containsText/excludesText 用它判，
    // 只截 400 字的 head 会让「文本在第 500 字才出现」被误判成缺失
    fullText: bodyText.slice(0, 20000),
    visibleElements: visible,
    hasRepoDatatable: typeof window.repoDatatable !== 'undefined' && window.repoDatatable !== null,
    hasRepoHost: typeof window.repoHost !== 'undefined' && window.repoHost !== null,
    overflow: overflow,
    clipped: clipped,
    canvases: canvases,
    loading: /加载中|Loading\\.\\.\\./.test(bodyText),
    errorText: errMatch ? errMatch[0] : null,
    emptyState: /暂无数据|还没有记录|没有匹配|暂无内容|无数据|还没有任何|这一列还是空的/.test(bodyText),
    anomalies: (bodyText.match(/\\bNaN\\b|\\[object Object\\]|Invalid Date|undefined%|NaN%|\\bInfinity\\b/g) || []).slice(0, 6),
    rec: REC
  };
})()`

// ─────────────────────────── 等待渲染稳定 ───────────────────────────

async function waitStable(cdp, sessionId, quietMs, maxMs) {
  const t0 = Date.now()
  let last = null
  let lastChangeAt = Date.now()
  while (Date.now() - t0 < maxMs) {
    let sig
    try {
      const r = await cdp.send(
        'Runtime.evaluate',
        {
          expression:
            "(function(){var b=document.body;return b?((b.textContent||'').length+':'+document.querySelectorAll('*').length+':'+document.querySelectorAll('canvas').length):'0'})()",
          returnByValue: true,
        },
        sessionId,
        8000,
      )
      sig = String(r.result ? r.result.value : '')
    } catch {
      sig = 'eval-error'
    }
    if (sig !== last) {
      last = sig
      lastChangeAt = Date.now()
    } else if (Date.now() - lastChangeAt >= quietMs) {
      return { stable: true, waitedMs: Date.now() - t0 }
    }
    await sleep(120)
  }
  return { stable: false, waitedMs: Date.now() - t0 }
}

// ─────────────────────────── 单个视口跑一轮 ───────────────────────────

async function runViewport(cdp, ctx) {
  const { url, viewport, instrument, settleQuietMs, settleMaxMs, shotPath, blockExternal } = ctx
  const net = { requests: [], failed: [] }
  const consoleErrors = []
  const consoleWarnings = []
  const pageErrors = []
  const logErrors = []

  const created = await cdp.send('Target.createTarget', { url: 'about:blank' })
  const targetId = created.targetId
  const attached = await cdp.send('Target.attachToTarget', { targetId, flatten: true })
  const sid = attached.sessionId

  const offs = []
  const argText = (a) => (a && a.value !== undefined ? String(a.value) : a && a.description ? a.description : a ? a.type : '')

  offs.push(
    cdp.on('Runtime.consoleAPICalled', (p, s) => {
      if (s !== sid) return
      const text = (p.args || []).map(argText).join(' ')
      if (p.type === 'error') consoleErrors.push(text)
      else if (p.type === 'warning') consoleWarnings.push(text)
    }),
  )
  offs.push(
    cdp.on('Runtime.exceptionThrown', (p, s) => {
      if (s !== sid) return
      const d = p.exceptionDetails || {}
      pageErrors.push({
        text: d.text || '',
        detail: (d.exception && (d.exception.description || d.exception.value)) || '',
        line: d.lineNumber,
      })
    }),
  )
  offs.push(
    cdp.on('Log.entryAdded', (p, s) => {
      if (s !== sid) return
      const e = p.entry || {}
      if (e.level === 'error') logErrors.push({ source: e.source || '', text: e.text || '' })
    }),
  )
  offs.push(
    cdp.on('Network.requestWillBeSent', (p, s) => {
      if (s !== sid) return
      net.requests.push({ id: p.requestId, url: (p.request && p.request.url) || '', type: p.type || '' })
    }),
  )
  offs.push(
    cdp.on('Network.loadingFailed', (p, s) => {
      if (s !== sid) return
      const req = net.requests.find((r) => r.id === p.requestId)
      net.failed.push({ url: req ? req.url : '', type: (req && req.type) || p.type || '', error: p.errorText || '' })
    }),
  )

  let loaded = false
  let loadError = null
  // 等 load 事件要在导航之前订阅
  const loadWaiter = new Promise((resolve) => {
    const off = cdp.on('Page.loadEventFired', (_p, s) => {
      if (s !== sid) return
      off()
      resolve(true)
    })
    setTimeout(() => {
      off()
      resolve(false)
    }, NAV_TIMEOUT_MS)
  })

  try {
    await cdp.send('Page.enable', {}, sid)
    await cdp.send('Runtime.enable', {}, sid)
    await cdp.send('Network.enable', {}, sid)
    await cdp.send('Log.enable', {}, sid)
    // 外链不可达时必须主动阻断：否则解析阻塞的 <script src> 会把页面挂死（见 probeExternal 注释）
    if (blockExternal) {
      await cdp.send('Network.setBlockedURLs', { urls: ['http://*', 'https://*'] }, sid)
    }
    await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: instrument }, sid)
    await cdp.send(
      'Emulation.setDeviceMetricsOverride',
      {
        width: viewport.width,
        height: viewport.height,
        deviceScaleFactor: 1,
        mobile: !!viewport.mobile,
      },
      sid,
    )
    await cdp.send('Page.navigate', { url }, sid)
    loaded = await loadWaiter
    const settle = await waitStable(cdp, sid, settleQuietMs, settleMaxMs)

    const collected = await cdp.send(
      'Runtime.evaluate',
      { expression: COLLECT_SCRIPT, returnByValue: true, awaitPromise: false },
      sid,
      15000,
    )
    const data = collected.result ? collected.result.value : null

    let shotBytes = null
    if (shotPath) {
      try {
        const shot = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true }, sid, 20000)
        if (shot && shot.data) {
          fs.mkdirSync(path.dirname(shotPath), { recursive: true })
          fs.writeFileSync(shotPath, Buffer.from(shot.data, 'base64'))
          shotBytes = fs.statSync(shotPath).size
        }
      } catch (e) {
        loadError = loadError || `截图失败：${e.message}`
      }
    }

    return {
      viewport,
      loaded,
      loadError,
      settle,
      blockExternal: !!blockExternal,
      shotPath: shotBytes != null ? shotPath : null,
      shotBytes,
      data,
      consoleErrors,
      consoleWarnings,
      pageErrors,
      logErrors,
      net,
    }
  } finally {
    for (const off of offs) {
      try {
        off()
      } catch {
        /* ignore */
      }
    }
    try {
      await cdp.send('Target.closeTarget', { targetId }, undefined, 5000)
    } catch {
      /* 关页失败不影响结论 */
    }
  }
}

// ─────────────────────────── 断言判定 ───────────────────────────

/** 空白归一：页面文本与快照值两边都过一遍，避免「多个空格 / 换行」造成的假不匹配 */
function normText(s) {
  return String(s == null ? '' : s)
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * 这条值适不适合当探针 —— 判据是「模板会不会**原样**把它显示出来」。
 * 排除的三类都是**格式会被改写**的值，留作探针必然假红（实测教训见下）：
 *   - 日期 / 时间：多维表格的日期列几乎总被模板重排版式（`2026-08-12` 显示成 `08-12` / `8月12日`）；
 *   - 纯数字：金额/序号会被加上货币符号、千分位、单位（`96` → `¥96.00`）；
 *   - 短 id / 选项 id：select 字段存的是选项 id（`o0`），前端一定翻译成中文选项名再显示。
 */
function usableProbe(s) {
  if (s.length < 2) return false // 单字符值（"1"/"是"）在页面上到处都可能撞上，不算证据
  if (/^\d+$/.test(s)) return false
  if (/^\d{4}[-/.]\d{1,2}([-/.]\d{1,2})?([ T].*)?$/.test(s)) return false
  if (/^[a-z0-9_-]{1,3}$/i.test(s)) return false
  return true
}

/**
 * 从快照里挑「数据特征值」当探针 —— 判「数据有没有真的画上屏」的**模板无关**硬判据。
 *
 * 为什么不用「空态文案反判」：空态短语是**模板私有**的，且会出现在正常渲染的页面上
 * （实测 pivot-dashboard 看板视图每个空列都带「这一列还是空的」占位 ⇒ 数据好好的却命中空态短语，
 * 假红）。改用真实记录值去页面文本里找，就不依赖任何模板 class / 文案：数据画上屏了，
 * 就必然有值出现在可见文本里。
 *
 * 抽样方式是**逐字段轮转**，不是全局按长度排序 —— 全局排序会让「日期列」这种又长又格式化的
 * 字段独占全部名额（实测：14 个探针全是 `2026-08-*`，而页面一个都没原样显示 ⇒ 假红）。
 * 逐字段各取几个再轮转，才能覆盖到真正被显示的文本字段。
 */
function characteristicValues(snapshot, limit = 16, perCol = 4) {
  const recs = snapshot && Array.isArray(snapshot.records) ? snapshot.records : []
  const byCol = new Map()
  const seen = new Set()
  for (const r of recs) {
    const vals = r && r.values ? r.values : null
    if (!vals) continue
    for (const k of Object.keys(vals)) {
      const s = normText(vals[k])
      if (!usableProbe(s) || seen.has(s)) continue
      seen.add(s)
      if (!byCol.has(k)) byCol.set(k, [])
      byCol.get(k).push(s)
    }
  }
  // 每列按长度降序（越长越不容易偶然撞上），只留前 perCol 个；再各列轮转取满 limit
  const lanes = [...byCol.values()].map((arr) => arr.sort((a, b) => b.length - a.length).slice(0, perCol))
  const out = []
  for (let i = 0; out.length < limit; i++) {
    let progressed = false
    for (const lane of lanes) {
      if (lane[i] !== undefined) {
        out.push(lane[i])
        progressed = true
        if (out.length >= limit) break
      }
    }
    if (!progressed) break
  }
  return out
}

/** 期望值：优先用调用方显式给的，其次从快照自动推 */
function buildExpectations(snapshot, expectSpec) {
  const spec = expectSpec || {}
  const auto = {}
  if (snapshot && typeof snapshot === 'object') {
    if (Array.isArray(snapshot.records)) auto.records = snapshot.records.length
    if (Array.isArray(snapshot.fields)) auto.fieldCount = snapshot.fields.length
  }
  return {
    probeValues: Array.isArray(spec.probeValues) ? spec.probeValues : characteristicValues(snapshot),
    probeMinHits: spec.probeMinHits !== undefined ? spec.probeMinHits : 1,
    records: spec.records !== undefined ? spec.records : auto.records,
    fieldCount: spec.fieldCount !== undefined ? spec.fieldCount : auto.fieldCount,
    tolerateEmpty: !!spec.tolerateEmpty,
    requireEmpty: !!spec.requireEmpty,
    containsText: Array.isArray(spec.containsText) ? spec.containsText : [],
    excludesText: Array.isArray(spec.excludesText) ? spec.excludesText : [],
    minVisibleElements: spec.minVisibleElements !== undefined ? spec.minVisibleElements : 8,
    noHorizontalOverflow: spec.noHorizontalOverflow !== false,
    maxWriteCalls: spec.maxWriteCalls !== undefined ? spec.maxWriteCalls : null,
    requireSdkRead: spec.requireSdkRead !== false,
    allowNetworkFailures: !!spec.allowNetworkFailures,
  }
}

function judge(vp, expected, opts) {
  const out = []
  const add = (level, name, ok, detail) => out.push({ level, name, ok, detail: detail || '' })
  const tag = vp.viewport.name || `${vp.viewport.width}x${vp.viewport.height}`
  const d = vp.data || {}
  const rec = d.rec || {}
  const calls = Array.isArray(rec.calls) ? rec.calls : []
  const readCalls = calls.filter((c) => c.kind === 'read' && c.method === 'read')
  const writeCalls = calls.filter((c) => c.kind === 'write')

  // ① 页面本身
  // 「白屏」的判据只看**有没有可见元素**：内容多寡与页面类型强相关（空态页本来就没几个
  // 元素），拿元素数量当硬门槛会误杀正常页面。内容偏少降为提醒。
  const vis = d.visibleElements || 0
  const txtLen = d.bodyTextLength || 0
  add('error', 'page.loaded', vp.loaded, vp.loaded ? '' : '页面未在超时内 load 完成')
  add('error', 'page.not-blank', vis >= 1 || txtLen >= 10, `可见元素 ${vis} 个 / 文本 ${txtLen} 字 —— 页面疑似白屏`)
  add(
    'warn',
    'page.sparse',
    vis >= expected.minVisibleElements,
    vis >= expected.minVisibleElements ? '' : `可见元素仅 ${vis} 个（参考阈值 ${expected.minVisibleElements}）—— 确认是否渲染完整`,
  )

  // ② 数据桥（这是「本地绿线上炸」最关键的一条：没有 SDK 页面永远走兜底）
  add('error', 'sdk.injected', d.hasRepoDatatable === true, d.hasRepoDatatable ? '' : 'window.repoDatatable 不存在 —— 线上不会注入数据桥，页面读不到任何数据')
  if (expected.requireSdkRead) {
    add('error', 'sdk.read-called', readCalls.length > 0, readCalls.length > 0 ? `read() 调用 ${readCalls.length} 次` : '页面从未调用 repoDatatable.read()')
    const failedRead = readCalls.find((c) => c.ok === false)
    add(
      'error',
      'sdk.read-ok',
      failedRead === undefined,
      failedRead ? `read() 失败：${failedRead.error}` : readCalls.length ? '' : '无 read 调用可判（已由 sdk.read-called 报出）',
    )
    const jr = readCalls.find((c) => c.result && typeof c.result === 'object')
    if (jr && expected.records !== undefined && expected.records > 0) {
      add(
        'error',
        'sdk.read-has-records',
        (jr.result.records || 0) > 0,
        `read() 返回 ${jr.result.records || 0} 条记录（数据表里是 ${expected.records} 条）—— 0 条说明解析维度/表头对不上`,
      )
    }
  }

  // ③ 渲染态（不依赖模板 class，全按可见文本判）
  add('error', 'render.no-loading', d.loading !== true, d.loading ? '页面稳定后仍停在「加载中」—— 渲染卡住或异步链路断了' : '')
  add('error', 'render.no-error-text', d.errorText == null, d.errorText ? `页面出现失败文案：「${d.errorText}」` : '')

  // 「数据画上屏了没有」= 用真实记录值当探针去可见文本里找。比空态文案反判可靠得多，
  // 且不依赖模板（见 characteristicValues 的注释）。命中数**无论判不判 has-data 都算出来**
  // 放进事实行：坏数据演练时人要靠它确认「毒真的到页面上了」，看不到就不敢信。
  const hay = normText(typeof d.fullText === 'string' ? d.fullText : '')
  const probes = expected.probeValues
  const hits = probes.filter((v) => hay.includes(normText(v)))
  vp.probeHits = { hit: hits.length, total: probes.length }

  if (expected.requireEmpty) {
    add('error', 'render.expect-empty', d.emptyState === true, d.emptyState ? '' : '期望空态（坏数据演练）但页面没有给出空态提示')
  } else if (!expected.tolerateEmpty && expected.records !== undefined && expected.records > 0) {
    if (probes.length === 0) {
      // 快照里没有可用探针（记录为空 / 全是单字符值）⇒ 如实说明这条没判，不假装通过
      add('warn', 'render.has-data', true, '快照里没有可用作探针的数据值（记录为空或全是单字符值）——本条未判')
    } else if (hits.length >= expected.probeMinHits) {
      add('error', 'render.has-data', true, `页面文本命中 ${hits.length}/${probes.length} 个数据特征值`)
    } else if (d.emptyState === true) {
      add(
        'error',
        'render.has-data',
        false,
        `数据表有 ${expected.records} 条记录，页面却报空态、且一个数据值都没出现 —— 典型「字段名对不上 / 结构不匹配 / 数据没喂进去」`,
      )
    } else {
      // 页面没报空态，只是文本里找不到原始值 —— 可能只渲染了聚合图表（正常），也可能字段没对上（故障）。
      // 这一条机器分不清，故降为提醒并要求截图人眼确认，不当 ERROR 误杀。
      add(
        'warn',
        'render.values-not-found',
        false,
        `页面未见空态，但可见文本里找不到任何数据特征值（试过 ${probes.slice(0, 3).map((v) => `「${v}」`).join('、')}…）——` +
          `若页面只渲染聚合数字/图表属正常；若是明细视图，则是字段名或结构没对上。加 --shots 截图确认`,
      )
    }
  }

  // ④ 控制台与异常
  add('error', 'console.no-errors', vp.consoleErrors.length === 0, vp.consoleErrors.slice(0, 3).join(' | '))
  add('error', 'runtime.no-page-errors', vp.pageErrors.length === 0, vp.pageErrors.slice(0, 2).map((e) => e.text + ' ' + (e.detail || '')).join(' | '))

  // ⑤ 外链（只放行 ECharts CDN；动态注入的外链只有这里能抓到）
  const external = vp.net.requests.filter((r) => /^https?:\/\//i.test(r.url) && !/^https?:\/\/(127\.0\.0\.1|localhost)(:|\/)/i.test(r.url))
  const badExternal = external.filter((r) => !JS_WHITELIST.some((h) => r.url.includes(h)))
  add(
    'error',
    'network.whitelist',
    badExternal.length === 0,
    badExternal.length ? `白名单外请求：${[...new Set(badExternal.map((r) => r.url))].slice(0, 4).join(' , ')}` : '',
  )
  if (!expected.allowNetworkFailures) {
    const failedExternal = vp.net.failed.filter((f) => /^https?:\/\//i.test(f.url) && !/127\.0\.0\.1|localhost/i.test(f.url))
    add('warn', 'network.failed', failedExternal.length === 0, failedExternal.length ? `请求失败（离线或 CDN 不可达）：${[...new Set(failedExternal.map((f) => f.url))].slice(0, 3).join(' , ')}` : '')
  }

  // ⑥ 布局
  if (expected.noHorizontalOverflow) {
    const ov = d.overflow || {}
    add(
      'error',
      'layout.no-horizontal-overflow',
      ov.horizontal !== true,
      ov.horizontal ? `页面横向溢出：scrollWidth ${ov.scrollWidth} > 视口 ${ov.viewportWidth}；越界元素 ${(ov.offenders || []).map((o) => `${o.el}(right=${o.right})`).join(' , ')}` : '',
    )
  }
  const clipped = d.clipped || []
  add('warn', 'layout.clipped', clipped.length === 0, clipped.length ? `内容被裁且无省略号：${clipped.map((c) => `${c.el}(${c.scrollWidth}>${c.clientWidth})`).join(' , ')}` : '')

  // ⑦ 图表坍缩（「图表整行坍缩」是透视页最经典的事故）
  const canvases = d.canvases || []
  const collapsed = canvases.filter((c) => c.h > 0 && c.h < 40 && c.w > 100)
  const zero = canvases.filter((c) => c.w < 2 || c.h < 2)
  add(
    'error',
    'charts.no-collapse',
    collapsed.length === 0 && zero.length === 0,
    collapsed.length || zero.length
      ? `疑似坍缩的图表：${[...collapsed, ...zero].map((c) => `${c.el}(${c.w}x${c.h})`).join(' , ')}`
      : canvases.length
        ? `${canvases.length} 个 canvas 尺寸正常`
        : '页面无 canvas（未用图表库，跳过）',
  )

  // ⑦b 外链被阻断时，图表那一条**没有真正验证过** —— 如实标记，别让它伪装成通过
  if (vp.blockExternal) {
    add(
      'warn',
      'charts.not-verified',
      false,
      '外链资源不可达（无网 / 被阻断）⇒ 图表库没加载，本次未验证图表渲染；有网环境下会真加载并检查图表是否坍缩',
    )
  }

  // ⑧ 文本异常（字段名对不上最常见的表象就是 NaN / [object Object]）
  const anomalies = d.anomalies || []
  add('error', 'text.no-anomalies', anomalies.length === 0, anomalies.length ? `页面出现异常文本：${anomalies.join(' , ')}` : '')

  // ⑨ 写操作（渲染期不该写库）
  if (expected.maxWriteCalls !== null) {
    add(
      'error',
      'writes.threshold',
      writeCalls.length <= expected.maxWriteCalls,
      `渲染期写调用 ${writeCalls.length} 次（阈值 ${expected.maxWriteCalls}）：${[...new Set(writeCalls.map((c) => c.method))].join(' , ')}`,
    )
  }
  const unexpectedWrites = writeCalls.filter((c) => c.ok === false && !c.rejected)
  add('warn', 'writes.no-failures', unexpectedWrites.length === 0, unexpectedWrites.length ? `写调用报错：${unexpectedWrites.map((c) => `${c.method}(${c.error})`).join(' , ')}` : '')

  // ⑩ 显式期望（--expect / <meta name="pivot-assert">）
  const fullText = typeof d.fullText === 'string' ? d.fullText : ''
  for (const t of expected.containsText) {
    const hit = fullText.includes(t)
    add('error', `expect.contains:${t}`, hit, hit ? '' : `页面里没有出现期望文本「${t}」`)
  }
  for (const t of expected.excludesText) {
    const hit = fullText.includes(t)
    add('error', `expect.excludes:${t}`, !hit, hit ? `页面里不该出现「${t}」却出现了` : '')
  }

  return { tag, assertions: out }
}

// ─────────────────────────── 主入口 ───────────────────────────

/**
 * 真渲染 + 断言。
 *
 * @param {object} opts
 * @param {string}   opts.htmlPath      已注入桩的预览 HTML（preview_stub.js 的产物）
 * @param {Array}    [opts.viewports]   [{width,height,name,mobile}]
 * @param {object}   [opts.snapshot]    parseWorkbook() 的结果（用于自动推期望值）
 * @param {object}   [opts.expect]      显式期望（见 buildExpectations）
 * @param {boolean}  [opts.failWrites]  写方法一律 reject（验页面写失败不崩）
 * @param {string}   [opts.shotDir]     截图目录
 * @param {string}   [opts.chromePath]  显式指定 Chrome
 * @param {Function} [opts.onLog]       进度回调
 * @returns {Promise<object>} { ok, envUnavailable, viewports, assertions, errors, warns, summary }
 */
async function runAssertions(opts) {
  const {
    htmlPath,
    viewports = [DEFAULT_VIEWPORT],
    snapshot = null,
    expect: expectSpec = null,
    failWrites = false,
    shotDir = null,
    settleQuietMs = DEFAULT_SETTLE_QUIET_MS,
    settleMaxMs = DEFAULT_SETTLE_MAX_MS,
    chromePath = null,
    onLog = null,
  } = opts || {}

  const log = (m) => {
    if (onLog) onLog(m)
  }

  const chrome = findChrome(chromePath)
  if (chrome == null) {
    const forced = chromePath || process.env.PIVOT_CHROME || ''
    return {
      ok: false,
      envUnavailable: true,
      reason: 'NO_CHROME',
      message: forced
        ? `指定的浏览器不存在：${forced}（来自 ${chromePath ? 'chromePath 参数' : 'PIVOT_CHROME'}）—— 路径写错了，不是「本机没装 Chrome」。`
        : '本机找不到 Chrome / Chromium —— 断言需要真渲染引擎。' +
          '装了 Chrome 再跑，或用 PIVOT_CHROME=<可执行文件路径> 指定；' +
          '找不到时**不假装通过**（静态门禁仍然是有效的，但「能跑起来」这件事没有被验证）。',
      chrome: null,
      viewports: [],
      assertions: [],
      errors: [],
      warns: [],
      summary: { errorCount: 0, warnCount: 0, checkedViewports: 0, envUnavailable: true },
    }
  }

  const expected = buildExpectations(snapshot, expectSpec)
  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pivot-assert-'))
  let child = null
  let cdp = null
  let browserVersion = null
  let externalInfo = { reachable: null, detail: null, blocked: false }
  const results = []
  const globalErrors = []

  try {
    const portFile = path.join(userDataDir, 'DevToolsActivePort')
    const args = [
      '--headless=new',
      '--remote-debugging-port=0',
      `--user-data-dir=${userDataDir}`,
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-gpu',
      '--disable-dev-shm-usage',
      '--hide-scrollbars',
      '--force-device-scale-factor=1',
      '--remote-allow-origins=*',
      'about:blank',
    ]
    const stderrChunks = []
    child = spawn(chrome, args, { stdio: ['ignore', 'ignore', 'pipe'] })
    child.stderr.on('data', (b) => {
      if (stderrChunks.length < 40) stderrChunks.push(String(b))
    })
    child.on('error', (e) => globalErrors.push(`Chrome 启动失败：${e.message}`))

    // 等 DevToolsActivePort（第一行端口，第二行 ws 路径）
    let port = null
    let wsPath = '/devtools/browser'
    const bootDeadline = Date.now() + CHROME_BOOT_MS
    while (Date.now() < bootDeadline) {
      if (fs.existsSync(portFile)) {
        const lines = fs.readFileSync(portFile, 'utf8').split('\n').map((s) => s.trim())
        if (lines[0]) {
          port = lines[0]
          if (lines[1]) wsPath = lines[1]
          break
        }
      }
      await sleep(120)
    }
    if (port == null) {
      return {
        ok: false,
        envUnavailable: true,
        reason: 'CHROME_BOOT_FAILED',
        message: `Chrome 启动后未产出 DevToolsActivePort（${CHROME_BOOT_MS}ms）。stderr 尾部：${stderrChunks.join('').slice(-600)}`,
        chrome: { path: chrome, version: null },
        viewports: [],
        assertions: [],
        errors: globalErrors,
        warns: [],
        summary: { errorCount: globalErrors.length || 1, warnCount: 0, checkedViewports: 0, envUnavailable: true },
      }
    }

    cdp = new CDP(`ws://127.0.0.1:${port}${wsPath}`)
    await cdp.connect()
    let version = null
    try {
      const v = await cdp.send('Browser.getVersion')
      version = v.product || null
      browserVersion = version
    } catch {
      /* 版本拿不到不影响断言 */
    }

    log(`· 渲染引擎：${chrome}${version ? `（${version}）` : ''}`)

    // 页面怎么喂进去：data: URL（零网络、零端口；理由见 buildDataUrl 注释）
    const page = buildDataUrl(htmlPath)
    if (page.urlLength > DATA_URL_WARN_LENGTH) {
      log(
        `⚠️  预览 HTML 偏大（${Math.round(page.htmlBytes / 1024)}KB → data URL ${Math.round(page.urlLength / 1024)}KB），` +
          '若导航失败请先精简页面体积',
      )
    }

    // 外链可达性：不可达就**主动阻断**，否则解析阻塞的 <script src> 会把整页挂死
    const probe = await probeExternal(cdp, `https://${JS_WHITELIST[0]}/echarts.min.js`, 4000)
    const blockExternal = !probe.reachable
    externalInfo = { reachable: probe.reachable, detail: probe.detail, blocked: blockExternal }
    if (blockExternal) {
      log(`· 外链不可达（${probe.detail}）→ 本次阻断外部请求；「图表渲染」这一条不会被验证`)
    } else {
      log(`· 外链可达 → 正常加载图表库`)
    }

    const instrument = instrumentSource({ failWrites })

    for (const vp of viewports) {
      const name = vp.name || `${vp.width}x${vp.height}`
      log(`· 视口 ${name}（${vp.width}×${vp.height}）…`)
      let vpResult
      try {
        vpResult = await runViewport(cdp, {
          url: page.url,
          blockExternal,
          viewport: { ...vp, name },
          instrument,
          settleQuietMs,
          settleMaxMs,
          shotPath: shotDir ? path.join(shotDir, `${path.basename(htmlPath, '.html')}-${name}.png`) : null,
        })
      } catch (e) {
        results.push({
          tag: name,
          viewport: { ...vp, name },
          fatal: e.message,
          assertions: [{ level: 'error', name: 'viewport.run', ok: false, detail: `视口渲染失败：${e.message}` }],
        })
        continue
      }
      const judged = judge(vpResult, expected)
      results.push({
        tag: judged.tag,
        viewport: vpResult.viewport,
        loaded: vpResult.loaded,
        settle: vpResult.settle,
        shotPath: vpResult.shotPath,
        shotBytes: vpResult.shotBytes,
        facts: {
          title: vpResult.data ? vpResult.data.title : null,
          visibleElements: vpResult.data ? vpResult.data.visibleElements : null,
          bodyTextLength: vpResult.data ? vpResult.data.bodyTextLength : null,
          canvases: vpResult.data ? (vpResult.data.canvases || []).length : 0,
          sdkCalls: vpResult.data && vpResult.data.rec ? (vpResult.data.rec.calls || []).length : 0,
          writeCalls: vpResult.data && vpResult.data.rec ? (vpResult.data.rec.calls || []).filter((c) => c.kind === 'write').length : 0,
          probeHits: vpResult.probeHits || null,
          externalRequests: [...new Set(vpResult.net.requests.filter((r) => /^https?:\/\//i.test(r.url) && !/127\.0\.0\.1|localhost/i.test(r.url)).map((r) => r.url))],
        },
        assertions: judged.assertions,
      })
    }
  } finally {
    try {
      if (cdp) cdp.close()
    } catch {
      /* ignore */
    }
    try {
      if (child && child.pid) {
        child.kill('SIGTERM')
        await sleep(150)
        try {
          child.kill('SIGKILL')
        } catch {
          /* 已经退出 */
        }
      }
    } catch {
      /* ignore */
    }
    try {
      fs.rmSync(userDataDir, { recursive: true, force: true })
    } catch {
      /* 临时目录清理失败不影响结论 */
    }
  }

  // 汇总
  const flat = []
  for (const r of results) {
    for (const a of r.assertions) flat.push({ viewport: r.tag, ...a })
  }
  const errors = flat.filter((a) => !a.ok && a.level === 'error')
  const warns = flat.filter((a) => !a.ok && a.level === 'warn')
  const checked = results.filter((r) => !r.fatal).length

  return {
    ok: errors.length === 0 && checked > 0,
    envUnavailable: false,
    reason: null,
    message: null,
    chrome: { path: chrome, version: browserVersion },
    external: externalInfo,
    page: { htmlPath, urlLength: null },
    viewports: results,
    assertions: flat,
    errors,
    warns,
    summary: {
      errorCount: errors.length,
      warnCount: warns.length,
      checkedViewports: checked,
      totalViewports: viewports.length,
      counts: flat.length,
      envUnavailable: false,
    },
    expected,
  }
}

// ─────────────────────────── 人读报告 ───────────────────────────

function formatReport(result) {
  const L = []
  if (result.envUnavailable) {
    L.push(`⚠️  断言跳过（环境不具备）：${result.message}`)
    return L.join('\n')
  }
  const { summary } = result
  for (const vp of result.viewports) {
    const head = `视口 ${vp.tag}`
    if (vp.fatal) {
      L.push(`✘ ${head}：${vp.fatal}`)
      continue
    }
    const bad = vp.assertions.filter((a) => !a.ok)
    const facts = vp.facts || {}
    const probe = facts.probeHits && facts.probeHits.total ? ` / 数据特征值命中 ${facts.probeHits.hit}/${facts.probeHits.total}` : ''
    const factLine = `元素 ${facts.visibleElements} / 文本 ${facts.bodyTextLength} 字 / SDK 调用 ${facts.sdkCalls} 次（写 ${facts.writeCalls}） / canvas ${facts.canvases}${probe}${vp.shotPath ? ` / 截图 ${vp.shotPath}` : ''}`
    L.push(`${bad.filter((a) => a.level === 'error').length === 0 ? '✔' : '✘'} ${head}：${factLine}`)
    for (const a of bad) {
      L.push(`    ${a.level === 'error' ? '✘' : '⚠️ '} ${a.name}${a.detail ? ` — ${a.detail}` : ''}`)
    }
    if (vp.settle && vp.settle.stable === false) {
      L.push(`    ⚠️  渲染在 ${vp.settle.waitedMs}ms 内未稳定（可能在持续重绘，结论只能算近似）`)
    }
  }
  L.push('')
  if (result.ok) {
    L.push(`✔ 断言全过（${summary.checkedViewports}/${summary.totalViewports} 视口，${summary.counts} 项检查${summary.warnCount ? `，${summary.warnCount} 条提醒` : ''}）`)
  } else {
    L.push(`✘ 断言失败：${summary.errorCount} 项 ERROR${summary.warnCount ? ` / ${summary.warnCount} 条提醒` : ''}`)
  }
  return L.join('\n')
}

module.exports = { runAssertions, formatReport, findChrome, JS_WHITELIST }

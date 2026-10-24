'use strict';
/* ============================================================
   女娲 · 连接器 —— 总览页（index.html，不分空间）
   4 个 tab：全部 / 我的 / 官方 / 已连接。
   仅浏览 + 连接/断开 + 查看工具 + 调试；新增/编辑/启停用等维护全部走管理端。
   停用的空间连接器完全隐藏（后端 overview 只返回 enabled）。
   登录、状态、抽屉、详情、OAuth、调试跳转等共用逻辑在 common.js。
   ============================================================ */

state.allProviders = [];    // 总览全量（官方 enabled + 我有权限空间的自建 enabled）
state.connections = [];     // 当前用户全部连接（跨空间）

// 总览页标记：无 discover tab/指南面板，common.js 据此隐藏「使用指南」按钮与跳转
window.OVERVIEW_MODE = true;

/* ---------------- 进入主界面 / 刷新 ---------------- */

function enterApp() {
  $('#view-login').classList.add('hidden');
  $('#view-app').classList.remove('hidden');
  $('#user-badge').textContent = state.phone;
  // 总览页默认上下文空间 = 个人空间（官方连接归属、详情/调试兜底均指向它）
  state.spaceId = state.personalSpaceId;
  refreshAll().catch(e => toast(e.message, 'err'));
}

async function refreshAll() {
  await Promise.all([loadOverview(), loadConnections()]);
  renderTabs();
}

/** 写操作（连接/断开）后刷新；common.js 保存逻辑统一回调 */
async function refreshAfterWrite() {
  await refreshAll();
}

/* ---------------- 数据加载 ---------------- */

async function loadOverview() {
  const data = await api('/api/connector/providers/overview');
  state.allProviders = Array.isArray(data) ? data : [];
}

async function loadConnections() {
  const data = await api('/api/connector/connections');
  state.connections = Array.isArray(data) ? data : [];
}

/* ---------------- 分组 / 排序 ---------------- */

/** provider 归属：official=官方；personal=个人空间；other=其他空间 */
function spaceOf(p) {
  if (!p.spaceId) return 'official';
  if (Number(p.spaceId) === Number(state.personalSpaceId)) return 'personal';
  return 'other';
}

const SPACE_RANK = { personal: 0, other: 1, official: 2 };

/** tab → 对应搜索框 id（index.html：全部用 global-search，其余 tab 同名前缀） */
const SEARCH_ID = { all: 'global-search', mine: 'mine-search', official: 'official-search', connected: 'connected-search' };

function bySpaceRank(a, b) {
  return SPACE_RANK[spaceOf(a)] - SPACE_RANK[spaceOf(b)];
}

/** 来源空间名（官方 → 「官方」；空间 → 空间名） */
function spaceNameOf(p) {
  if (!p.spaceId) return '官方';
  const s = state.spaces.find(x => String(x.id) === String(p.spaceId));
  return s ? s.name : ('空间 ' + p.spaceId);
}

/** 连接归属空间：空间自建 → 自身空间；官方 → 个人空间 */
function sidOf(p) {
  return p.spaceId ? Number(p.spaceId) : state.personalSpaceId;
}

/** 搜索过滤：名称 / service / 分类 / 标签 */
function matches(p, kw) {
  if (!kw) return true;
  const k = kw.toLowerCase();
  const tags = Array.isArray(p.tags) ? p.tags.join(' ') : String(p.tags || '');
  return String(p.displayName || '').toLowerCase().includes(k)
    || String(p.service || '').toLowerCase().includes(k)
    || String(p.category || '').toLowerCase().includes(k)
    || tags.toLowerCase().includes(k);
}

/** 各 tab 的基础列表（未过滤） */
function tabBaseList(tab) {
  if (tab === 'all') {
    return [
      ...state.allProviders.filter(p => p.connected).sort(bySpaceRank),
      ...state.allProviders.filter(p => !p.connected && spaceOf(p) !== 'official').sort(bySpaceRank),
      ...state.allProviders.filter(p => !p.connected && spaceOf(p) === 'official'),
    ];
  }
  if (tab === 'mine') {
    return state.allProviders
      .filter(p => spaceOf(p) !== 'official')
      .sort((a, b) => (Number(b.connected) - Number(a.connected)) || bySpaceRank(a, b));
  }
  if (tab === 'official') {
    return state.allProviders
      .filter(p => spaceOf(p) === 'official')
      .sort((a, b) => Number(b.connected) - Number(a.connected));
  }
  // connected
  return state.allProviders.filter(p => p.connected).sort(bySpaceRank);
}

/* ---------------- 渲染 ---------------- */

function renderTabs() {
  const mineCount = state.allProviders.filter(p => spaceOf(p) !== 'official').length;
  const officialCount = state.allProviders.filter(p => spaceOf(p) === 'official').length;
  const connectedCount = state.allProviders.filter(p => p.connected).length;
  $('#tab-btn-mine').classList.toggle('hidden', mineCount === 0);
  $('#tab-btn-official').classList.toggle('hidden', officialCount === 0);
  $('#tab-btn-connected').classList.toggle('hidden', connectedCount === 0);
  // 当前 tab 变空被隐藏 → 回退「全部」
  const active = document.querySelector('.tab.active');
  if (active && active.classList.contains('hidden')) switchTab('all');
  ['all', 'mine', 'official', 'connected'].forEach(renderTab);
}

function renderTab(tab) {
  const kw = ($('#' + SEARCH_ID[tab])?.value || '').trim();
  const list = tabBaseList(tab).filter(p => matches(p, kw));
  const box = $('#' + tab + '-list');
  if (!box) return;
  if (!list.length) {
    box.innerHTML = `<div class="empty-note card" style="grid-column:1/-1">${
      kw ? '没有匹配的连接器' : tabEmptyHint(tab)}</div>`;
    return;
  }
  box.innerHTML = list.map(renderCard).join('');
}

function tabEmptyHint(tab) {
  if (tab === 'all') return '还没有可见连接器。连接器的维护（新增/编辑/启停用/导入）由管理端完成。';
  if (tab === 'mine') return '你还没有有权空间的自建连接器，也没有已连接的官方连接器。';
  if (tab === 'official') return '官方目录暂无可用连接器。';
  return '还没有已连接的连接器 —— 到「全部」为需要的连接器去连接 / 去授权。';
}

/* identityChips() 定义在 common.js —— 空间管理页（space.js）也要用同一份，免得两页规则漂开。 */

function renderCard(p) {
  const auth = p.authType || 'no_auth';
  const src = spaceOf(p);
  const srcLabel = spaceNameOf(p);
  const sid = sidOf(p);
  const connected = !!p.connected;
  const catChip = p.category ? `<span class="chip">${esc(p.category)}</span>` : '';
  const cntChip = p.actionCount != null
    ? `<span class="chip">${p.actionCount} 个工具</span>` : '';
  let actionBtn = '';
  if (connected) {
    actionBtn = `<button class="btn btn-danger btn-sm" data-disconnect-provider="${esc(p.service)}">断开</button>`;
  } else if (auth === 'oauth2' || auth === 'oauth2_device') {
    // 两种机制都走 data-oauth，由 common.js 的 startOAuth 按 authType 分流（扫码弹二维码，授权码开新标签页）
    actionBtn = `<button class="btn btn-coal btn-sm" data-oauth="${esc(p.service)}" data-spaceid="${sid}">${auth === 'oauth2_device' ? '扫码连接' : '去授权'}</button>`;
  } else if (['api_key', 'bearer', 'custom', 'no_auth'].includes(auth)) {
    // no_auth 也走这条入口：免鉴权不需要凭证，但同样需要一条连接行——
    // 否则它进不了用户连接表，启停 / 断开 / 排序都无从统一管理。
    const label = auth === 'no_auth' ? '连接' : '去连接';
    actionBtn = `<button class="btn btn-coal btn-sm" data-connect="${esc(p.service)}" data-spaceid="${sid}">${label}</button>`;
  }
  return `<div class="card">
    <div class="provider-card-body">
      <div class="provider-name">${esc(p.displayName || p.service)}</div>
      <div class="provider-service">${esc(p.service)}</div>
      <div class="provider-desc">${esc(p.description || '暂无介绍')}</div>
      <div class="provider-meta">
        ${connected ? '<span class="chip ok">已连接</span>' + identityChips(p.service) : ''}
        <span class="chip taupe">${esc(srcLabel)}</span>
        <span class="chip taupe">${esc(AUTH_LABEL[auth] || auth)}</span>
        ${catChip}
        ${cntChip}
      </div>
    </div>
    <div class="provider-foot">
      <span class="base-url">${esc(p.baseUrl || '')}</span>
      <span>
        ${actionBtn}
        <button class="btn btn-outline btn-sm" data-detail="${esc(p.service)}" data-spaceid="${sid}">查看工具</button>
      </span>
    </div>
  </div>`;
}

/* ---------------- 连接设置抽屉（直填类） ---------------- */

let connSpaceId = null;

function fillConnProviderSelect() {
  const sel = $('#conn-provider');
  const candidates = state.allProviders.filter(p =>
    ['api_key', 'bearer', 'custom', 'no_auth'].includes(p.authType || ''));
  sel.innerHTML = candidates.length
    ? candidates.map(p => `<option value="${esc(p.service)}">${esc(p.displayName || p.service)}（${esc(AUTH_LABEL[p.authType] || p.authType)}）</option>`).join('')
    : '<option value="">没有可连接的连接器</option>';
  renderConnFields();
}

function currentConnProvider() {
  const service = $('#conn-provider')?.value;
  return state.allProviders.find(p => p.service === service);
}

function renderConnFields() {
  const box = $('#conn-fields');
  const p = currentConnProvider();
  if (!p) { box.innerHTML = ''; return; }
  if (p.authType === 'no_auth') {
    // 免鉴权不收集凭证，建连接只是为了在用户连接表里留一行（启停 / 断开 / 排序都靠它）
    box.innerHTML = '<p class="editor-hint">该连接器免鉴权，无需填写凭证，直接点下方按钮建立连接即可。建立后可在「已连接」里统一启停或断开。</p>';
    return;
  }
  if (p.authType === 'api_key') {
    let keyName = 'apiKey';
    try {
      const cfg = typeof p.authConfig === 'string' ? JSON.parse(p.authConfig) : p.authConfig;
      if (cfg?.keyName) keyName = cfg.keyName;
    } catch { /* 回退默认 */ }
    box.innerHTML = `<label class="field">
      <span class="field-label">凭证字段 · ${esc(keyName)}</span>
      <input type="text" data-conn-field="${esc(keyName)}" placeholder="粘贴 API Key" required>
    </label>`;
  } else if (p.authType === 'bearer') {
    box.innerHTML = `<label class="field">
      <span class="field-label">凭证字段 · token</span>
      <input type="text" data-conn-field="token" placeholder="粘贴 Bearer Token" required>
    </label>`;
  } else {
    let cfg = null;
    try { cfg = typeof p.authConfig === 'string' ? JSON.parse(p.authConfig) : p.authConfig; } catch { /* 回退通用 JSON 输入 */ }
    if (Array.isArray(cfg?.fields) && cfg.fields.length) {
      box.innerHTML = cfg.fields.map(f => `<label class="field">
        <span class="field-label">凭证字段 · ${esc(f.label || f.name)}</span>
        <input type="${f.secret === false ? 'text' : 'password'}" data-conn-field="${esc(f.name)}" placeholder="${esc(f.placeholder || '')}" autocomplete="off" required>
      </label>`).join('');
    } else {
      box.innerHTML = `<label class="field">
        <span class="field-label">凭证字段（JSON 对象，如 {"appId":"...","appSecret":"..."}）</span>
        <textarea data-conn-json rows="4" spellcheck="false">{}</textarea>
      </label>`;
    }
  }
}

/** 打开连接设置：service 指定时预选该连接器；spaceId 为连接归属空间（官方→个人空间） */
function openConnDrawer(service, spaceId) {
  fillConnProviderSelect();
  if (service && [...$('#conn-provider').options].some(o => o.value === service)) {
    $('#conn-provider').value = service;
    renderConnFields();
  }
  connSpaceId = spaceId ?? (currentConnProvider() ? sidOf(currentConnProvider()) : state.personalSpaceId);
  $('#conn-drawer-mask').classList.remove('hidden');
  $('#conn-drawer').classList.remove('hidden');
  setTimeout(() => $('#conn-fields').querySelector('input, textarea')?.focus(), 60);
}

async function createConnection(e) {
  e.preventDefault();
  const p = currentConnProvider();
  if (!p) return toast('请先选择连接器', 'err');
  let fields = {};
  if (p.authType === 'custom') {
    const customInputs = $$('[data-conn-field]');
    if (customInputs.length) {
      customInputs.forEach(inp => { const v = inp.value.trim(); if (v) fields[inp.dataset.connField] = v; });
      if (!Object.keys(fields).length) return toast('请至少填写一个凭证字段', 'err');
    } else {
      try {
        fields = JSON.parse($('[data-conn-json]').value || '{}');
        if (typeof fields !== 'object' || Array.isArray(fields) || !Object.keys(fields).length) {
          return toast('custom 连接至少需要一个凭证字段', 'err');
        }
      } catch { return toast('凭证字段 JSON 解析失败', 'err'); }
    }
  } else {
    $$('[data-conn-field]').forEach(inp => { fields[inp.dataset.connField] = inp.value.trim(); });
  }
  try {
    await api('/api/connector/connections/api-key', {
      method: 'POST',
      body: {
        spaceId: connSpaceId ?? state.personalSpaceId,
        providerService: p.service,
        name: $('#conn-name').value.trim() || undefined,
        fields,
      },
    });
    toast('连接已建立（凭证已加密落库）', 'ok');
    closeConnDrawer();
    $('#conn-name').value = '';
    $$('[data-conn-field]').forEach(i => { i.value = ''; });
    const cj = $('[data-conn-json]'); if (cj) cj.value = '{}';
    await refreshAll();
  } catch (err) {
    toast('建连失败：' + err.message, 'err');
  }
}

/* ---------------- 事件绑定（总览页特有） ---------------- */

function bindSearch(tab) {
  const inp = $('#' + SEARCH_ID[tab]);
  if (!inp) return;
  let timer = null;
  inp.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(() => renderTab(tab), 250);
  });
}

function bindEvents() {
  $$('.tab').forEach(b => b.addEventListener('click', () => {
    switchTab(b.dataset.tab);
  }));

  ['all', 'mine', 'official', 'connected'].forEach(bindSearch);

  // 连接抽屉：切换连接器时重渲染凭证表单，并更新连接归属空间
  $('#conn-provider').addEventListener('change', () => {
    renderConnFields();
    connSpaceId = currentConnProvider() ? sidOf(currentConnProvider()) : state.personalSpaceId;
  });
  $('#conn-form').addEventListener('submit', createConnection);

  // 断开：断开该连接器的全部连接（确认时提示具体数量）
  document.addEventListener('click', async e => {
    const t = e.target.closest('button');
    if (!t || !t.dataset.disconnectProvider) return;
    const svc = t.dataset.disconnectProvider;
    const conns = state.connections.filter(c => c.providerService === svc);
    if (!conns.length) return toast('该连接器没有可断开的连接', 'err');
    // 断开是「按连接器全断」，而一个连接器可能同时挂着应用身份与用户身份两条连接。
    // 把身份标进确认文案，用户才知道自己按下去会一次删掉两种身份（含刚扫出来的那条）。
    const spaces = conns.map(c => {
      const s = state.spaces.find(x => String(x.id) === String(c.spaceId));
      const where = s ? s.name : (c.spaceId ? ('空间 ' + c.spaceId) : '官方');
      const identity = IDENTITY_LABEL[c.identity];
      return identity ? `${where}·${identity}` : where;
    });
    const msg = conns.length === 1
      ? `确定断开「${svc}」的 1 个连接（${spaces[0]}）？凭证将被删除。`
      : `确定断开「${svc}」的 ${conns.length} 个连接（${spaces.join('、')}）？凭证将被删除。`;
    if (!confirm(msg)) return;
    t.disabled = true;
    try {
      for (const c of conns) {
        await api('/api/connector/connections/' + c.id, { method: 'DELETE' });
      }
      toast(`已断开「${svc}」的 ${conns.length} 个连接`, 'ok');
      await refreshAll();
    } catch (err) {
      toast('断开失败：' + err.message, 'err');
    } finally {
      t.disabled = false;
    }
  });
}

/* ---------------- 启动 ---------------- */

bindCommonEvents();
bindEvents();
bootstrap();

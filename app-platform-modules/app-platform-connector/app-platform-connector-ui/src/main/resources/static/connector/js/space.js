'use strict';
/* ============================================================
   女娲 · 连接器 —— 空间管理页（space.html，第三个页面）
   按空间维护自建连接器：目录卡片 + 搜索 + 状态/连接筛选 +
   新增(s_前缀) / 编辑 / 启停用 / 导入 / 导出 / 删除 / 工具维护 / 建连。
   登录、抽屉、详情、OAuth、导入导出、保存路由等共用逻辑在 common.js
   （UI_MODE 自动判定为 'space'，创建自动补 s_ 前缀、维护走空间路由）。
   官方全局连接器与执行审计走管理端 admin.html，总览浏览走 index.html。
   ============================================================ */

state.catalog = [];   // 当前空间自建 provider（scope=space）

// 本页无 discover tab / 指南面板，抑制 common.js 的「使用指南」按钮与跳转
window.OVERVIEW_MODE = true;

/* ---------------- 进入主界面 / 刷新 ---------------- */

function enterApp() {
  $('#view-login').classList.add('hidden');
  $('#view-app').classList.remove('hidden');
  $('#user-badge').textContent = state.phone;
  renderSpaceSelect();
  refreshAll().catch(err => toast(err.message, 'err'));
}

async function refreshAll() {
  // 连接列表跟着目录一起取：卡片上的「已连接」后面要缀身份标注（应用身份 / 用户身份），
  // 而 providers 接口只回一个 connected 布尔，不告诉你连了几条、分别是什么身份。
  // 与总览页同源（同一个 /connections，不带 spaceId = 该用户全部连接），两页标注才一致。
  await Promise.all([loadCatalog(), loadConnections()]);
}

async function loadConnections() {
  const data = await api('/api/connector/connections');
  state.connections = Array.isArray(data) ? data : [];
}

/**
 * 写操作（创建/编辑/删除/导入/工具维护）后刷新；common.js 保存逻辑统一回调。
 * <p>连接也要一起重取：卡片上的「已连接 + 身份标注」是 connections 驱动的，只刷目录的话
 * 扫码/建连成功之后那颗 chip 不会出现，用户得手动刷新整页才看得到刚才那一步的结果。
 */
async function refreshAfterWrite() {
  await refreshAll();
}

/* ---------------- 空间选择 ---------------- */

function renderSpaceSelect() {
  const sel = $('#space-select');
  const spaces = state.spaces || [];
  // 重复名称统计，同名加 (#id) 后缀区分
  const nameCount = {};
  spaces.forEach(s => { const n = s.name || ''; nameCount[n] = (nameCount[n] || 0) + 1; });
  const MAX = 14; // 超长名截断阈值，完整名进 title（hover 可看全）
  const label = s => {
    const full = s.name || '';
    let name = full.length > MAX ? full.slice(0, MAX) + '…' : full;
    if (nameCount[full] > 1) name += ` (#${s.id})`;
    return { name, full };
  };
  // 个人空间置顶，团队空间按名称排序（中文按拼音）
  const byName = (a, b) => (a.name || '').localeCompare(b.name || '', 'zh-Hans-CN');
  const personal = spaces.filter(s => s.type === 'Personal').sort(byName);
  const team = spaces.filter(s => s.type !== 'Personal').sort(byName);
  const parts = ['<optgroup label="个人空间">'];
  personal.forEach(s => { const l = label(s); parts.push(`<option value="${s.id}" title="${esc(l.full)}">${esc(l.name)}</option>`); });
  parts.push('</optgroup>');
  if (team.length) {
    parts.push('<optgroup label="团队空间">');
    team.forEach(s => { const l = label(s); parts.push(`<option value="${s.id}" title="${esc(l.full)}">${esc(l.name)}</option>`); });
    parts.push('</optgroup>');
  }
  sel.innerHTML = parts.join('');
  sel.value = String(state.spaceId);
}

/* ---------------- 数据加载 ---------------- */

async function loadCatalog() {
  const kw = ($('#catalog-keyword')?.value || '').trim();
  const status = $('#catalog-status-filter')?.value || 'all';
  const connected = $('#catalog-conn-filter')?.value || 'all';
  const qs = new URLSearchParams({
    spaceId: String(state.spaceId),
    scope: 'space',
    status,
    connected,
    keyword: kw,
    pageNum: '1',
    pageSize: '500',
  });
  const data = await api('/api/connector/providers?' + qs.toString());
  state.catalog = (data && data.records) || [];
  // common.js 的 isOfficialProvider / startOAuth 依赖目录数据源识别官方与 byo 模式
  state.providers = state.catalog;
  renderCatalog();
}

/* ---------------- 渲染 ---------------- */

function renderCatalog() {
  const box = $('#catalog-list');
  if (!state.catalog.length) {
    box.innerHTML = `<div class="empty-note card" style="grid-column:1/-1">该空间还没有自建连接器 —— 点右上角「新增连接器」创建，或「导入」批量导入。</div>`;
    return;
  }
  box.innerHTML = state.catalog.map(renderCard).join('');
}

function renderCard(p) {
  const disabled = p.status === 'disabled';
  const cnt = p.actionCount != null ? `<span class="chip">${p.actionCount} 个工具</span>` : '';
  return `<div class="card${disabled ? ' provider-disabled' : ''}">
    <div class="provider-card-body">
      <div class="provider-name">${esc(p.displayName || p.service)}</div>
      <div class="provider-service">${esc(p.service)}</div>
      <div class="provider-desc">${esc(p.description || '暂无介绍')}</div>
      <div class="provider-meta">
        ${p.connected ? '<span class="chip ok">已连接</span>' + identityChips(p.service) : ''}
        <span class="chip${disabled ? ' err' : ' ok'}">${disabled ? '已停用' : '已启用'}</span>
        <span class="chip taupe">${esc(AUTH_LABEL[p.authType] || p.authType)}</span>
        ${p.category ? `<span class="chip">${esc(p.category)}</span>` : ''}
        ${cnt}
      </div>
    </div>
    <div class="provider-foot">
      <span class="base-url">${esc(p.baseUrl || '')}</span>
      <span>
        <button class="btn btn-outline btn-sm" data-space-view="${esc(p.service)}">查看工具</button>
        <button class="btn btn-outline btn-sm" data-edit-provider="${esc(p.service)}">编辑</button>
        <button class="btn btn-outline btn-sm" data-prov-toggle="${esc(p.service)}" data-target="${disabled ? 'enabled' : 'disabled'}">${disabled ? '启用' : '停用'}</button>
        <button class="btn btn-outline btn-sm" data-export="${esc(p.service)}">导出</button>
        <button class="btn btn-danger btn-sm" data-del-provider="${esc(p.service)}">删除</button>
      </span>
    </div>
  </div>`;
}

/* ---------------- 连接设置抽屉（详情抽屉「去连接」直填类） ---------------- */

let connSpaceId = null;

function fillConnProviderSelect() {
  const sel = $('#conn-provider');
  const candidates = state.catalog.filter(p =>
    ['api_key', 'bearer', 'custom', 'no_auth'].includes(p.authType || ''));
  sel.innerHTML = candidates.length
    ? candidates.map(p => `<option value="${esc(p.service)}">${esc(p.displayName || p.service)}（${esc(AUTH_LABEL[p.authType] || p.authType)}）</option>`).join('')
    : '<option value="">没有可连接的连接器</option>';
  renderConnFields();
}

function currentConnProvider() {
  const service = $('#conn-provider')?.value;
  return state.catalog.find(p => p.service === service);
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

/** 打开连接设置（详情抽屉「去连接」；spaceId 由 common.js 委托传入） */
function openConnDrawer(service, spaceId) {
  fillConnProviderSelect();
  if (service && [...$('#conn-provider').options].some(o => o.value === service)) {
    $('#conn-provider').value = service;
    renderConnFields();
  }
  connSpaceId = spaceId ?? state.spaceId;
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
        spaceId: connSpaceId ?? state.spaceId,
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
    await refreshAfterWrite();
  } catch (err) {
    toast('建连失败：' + err.message, 'err');
  }
}

/* ---------------- 事件绑定 ---------------- */

function bindEvents() {
  // 查看工具 → 详情抽屉（空间自建非官方 + 非只读 → 显示可编辑 manage-bar）
  document.addEventListener('click', e => {
    const t = e.target.closest('button[data-space-view]');
    if (!t) return;
    openDrawer(t.dataset.spaceView, { spaceId: state.spaceId });
  });

  // 启停用空间连接器
  document.addEventListener('click', async e => {
    const t = e.target.closest('button');
    if (!t || !t.dataset.provToggle) return;
    const svc = t.dataset.provToggle;
    const enabled = t.dataset.target === 'enabled';
    t.disabled = true;
    try {
      await api(`/api/connector/providers/${encodeURIComponent(svc)}/status?enabled=${enabled}`, { method: 'POST' });
      toast(enabled ? `已启用 ${svc}` : `已停用 ${svc}`, 'ok');
      await loadCatalog();
    } catch (err) {
      toast(err.message, 'err');
      t.disabled = false;
    }
  });

  // 筛选 / 搜索
  $('#catalog-status-filter').addEventListener('change', () => loadCatalog());
  $('#catalog-conn-filter').addEventListener('change', () => loadCatalog());
  let timer = null;
  $('#catalog-keyword').addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(() => loadCatalog(), 250);
  });

  // 连接抽屉
  $('#conn-provider').addEventListener('change', renderConnFields);
  $('#conn-form').addEventListener('submit', createConnection);
}

/* ---------------- 启动 ---------------- */

bindCommonEvents();
bindEvents();
bootstrap();

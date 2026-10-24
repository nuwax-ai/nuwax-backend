'use strict';
/* ============================================================
   女娲 · 连接器 —— 共用逻辑（空间端 index.html 与管理端 admin.html 共用）
   UI_MODE 按当前页面自动判定：
     - space  → 空间端（目录 / 我的连接 / 发现，空间自建维护）
     - admin  → 管理端（官方全局目录维护 + 执行审计）
   保存路由差异（用户侧 /api/connector/*  vs 管理侧 /api/system/connector/*）在此统一处理。
   ============================================================ */

const UI_MODE = location.pathname.endsWith('admin.html') ? 'admin' : 'space';

const $ = (sel, el = document) => el.querySelector(sel);
const $$ = (sel, el = document) => Array.from(el.querySelectorAll(sel));

const state = {
  token: localStorage.getItem('nx.connector.token') || null,
  phone: localStorage.getItem('nx.connector.phone') || '',
  spaces: [],
  spaceId: null,          // 当前上下文空间（总览页回退为个人空间；空间端为当前选中空间）
  personalSpaceId: null,  // 个人空间 id（总览页判定个人空间优先排序/官方连接归属）
  adminAll: [],           // 管理侧/官方目录 provider 全量（一次拉取，前端筛选/翻页）
  providerDetailCache: new Map(),
};

const AUTH_LABEL = {
  no_auth: '免鉴权', api_key: 'API Key', bearer: 'Bearer',
  oauth2: 'OAuth 2.0', oauth2_device: '扫码授权', custom: '自定义',
};

/**
 * 连接身份标注。连接行的 oauth_meta.identity 是建连时由服务端归一后写下的两个值之一；
 * 存量连接与非 OAuth 建连（api_key/静态令牌）没有这个键，读出来是 undefined —— 那种情况
 * 不显示身份标注，按「未知」处理，不要兜底成 user 猜给用户看。
 */
const IDENTITY_LABEL = { tenant: '应用身份', user: '用户身份' };

/**
 * 该连接器下已连接的身份标注（「应用身份」/「用户身份」）。
 * <p>同一个连接器会并存两条连接——扫码自注册产出应用身份，之后再次扫码做用户授权产出用户身份，
 * 而两条连接的 name 完全相同。只显示一个「已连接」，用户看到卡片与实际列表行数对不上，
 * 只会以为列表重复了。identity 读不到（存量连接）就返回空串，退回原来的单个「已连接」。
 * <p>放在 common.js 而不是某一页里：总览页（app.js）与空间管理页（space.js）都要用，
 * 各写一份必然会漂。用它的前提是那一页已经把连接列表读进 {@code state.connections}。
 */
function identityChips(service) {
  const ids = [...new Set((state.connections || [])
    .filter(c => c.providerService === service && c.identity)
    .map(c => c.identity))];
  return ids.map(id => ` <span class="chip ok">${esc(IDENTITY_LABEL[id] || id)}</span>`).join('');
}

const EXEC_LABEL = {
  DECLARATIVE: 'HTTP 接口', CUSTOM: '自定义',
  PLUGIN: '绑定插件', WORKFLOW: '绑定工作流',
};

/* ---------------- 基础工具 ---------------- */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

let toastTimer = null;
function toast(msg, kind = '') {
  const el = $('#toast');
  if (!el) return;
  el.textContent = msg;
  el.className = 'toast ' + kind;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add('hidden'), 3800);
}

async function api(path, { method = 'GET', body } = {}) {
  const headers = {};
  if (state.token) headers['Authorization'] = 'Bearer ' + state.token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let resp;
  try {
    resp = await fetch(path, {
      method, headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (e) {
    throw { code: 'network', message: '网络不可达：' + e.message };
  }
  const text = await resp.text();
  let json;
  try { json = JSON.parse(text); } catch {
    throw { code: String(resp.status), message: '响应不是 JSON（' + resp.status + '）：' + text.slice(0, 120) };
  }
  if (json.code !== '0000') {
    throw { code: json.code ?? json.displayCode ?? String(resp.status), message: json.message || '请求失败' };
  }
  return json.data;
}

function safeParse(s) { try { return JSON.parse(s); } catch { return null; } }

function fmtTime(v) {
  if (!v) return '—';
  const d = new Date(v);
  if (isNaN(d.getTime())) return '—';
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/* ---------------- 登录 / 启动 ---------------- */

async function doLogin(phone, password) {
  const data = await api('/api/user/passwordLogin', {
    method: 'POST', body: { phone, password },
  });
  const token = data?.token ?? data;
  if (!token || typeof token !== 'string') throw { code: 'login', message: '登录响应缺少 token' };
  state.token = token;
  state.phone = phone;
  localStorage.setItem('nx.connector.token', token);
  localStorage.setItem('nx.connector.phone', phone);
}

function logout() {
  state.token = null;
  localStorage.removeItem('nx.connector.token');
  localStorage.removeItem('nx.connector.phone');
  $('#view-app').classList.add('hidden');
  $('#view-login').classList.remove('hidden');
  $('#login-password').value = '';
}

async function loadSpaces() {
  const data = await api('/api/space/list');
  const arr = Array.isArray(data) ? data : (data?.records ?? data?.list ?? []);
  state.spaces = arr.map(it => ({
    id: it.id ?? it.spaceId,
    name: it.name ?? it.spaceName ?? ('空间 ' + (it.id ?? it.spaceId)),
    type: it.type ?? null,
    role: it.currentUserRole ?? null,
  })).filter(s => s.id != null);
  if (!state.spaces.length) throw { code: 'space', message: '当前账号没有可用空间' };
  // 总览页：上下文空间回退为个人空间（/api/space/list 个人空间排第一）
  state.spaceId = state.spaces[0].id;
  state.personalSpaceId = state.spaces.find(s => s.type === 'Personal')?.id ?? state.spaces[0].id;
}

function showLogin() {
  $('#view-app').classList.add('hidden');
  $('#view-login').classList.remove('hidden');
  if (state.phone) $('#login-phone').value = state.phone;
}

/* ---------------- 抽屉开关（元素可能不存在，容错） ---------------- */

function closeDrawer() {
  $('#drawer')?.classList.add('hidden');
  $('#drawer-mask')?.classList.add('hidden');
}
function closeConnDrawer() {
  $('#conn-drawer')?.classList.add('hidden');
  $('#conn-drawer-mask')?.classList.add('hidden');
  $$('[data-conn-field]').forEach(i => { i.value = ''; });
}
function closeProviderDrawer() {
  $('#provider-drawer')?.classList.add('hidden');
  $('#provider-drawer-mask')?.classList.add('hidden');
  providerEditMode = null;
  providerAdminMode = false;
  oauthModeCfg = null;
  providerScopeSeed = null;
  providerAuthConfig = null;
  const secret = $('#oa-client-secret');
  if (secret) secret.value = '';
}
function closeActionDrawer() {
  $('#action-drawer')?.classList.add('hidden');
  $('#action-drawer-mask')?.classList.add('hidden');
  actionDrawerService = null;
  actionEditId = null;
}
function closeImportDrawer() {
  $('#import-drawer')?.classList.add('hidden');
  $('#import-drawer-mask')?.classList.add('hidden');
}
function closeByoDrawer() {
  $('#byo-drawer')?.classList.add('hidden');
  $('#byo-drawer-mask')?.classList.add('hidden');
  byoService = null;
  const secret = $('#byo-client-secret');
  if (secret) secret.value = '';
}

/* ---------------- 详情抽屉 ---------------- */

/** 失效指定 service 的详情缓存（含管理侧带停用条目的变体） */
function invalidateProviderDetail(service) {
  state.providerDetailCache.delete(service);
  state.providerDetailCache.delete(service + '::admin');
}

async function providerDetail(service, opts = {}) {
  const key = service + (opts.includeDisabled ? '::admin' : '');
  if (state.providerDetailCache.has(key)) return state.providerDetailCache.get(key);
  const qs = new URLSearchParams({ spaceId: opts.spaceId ?? state.spaceId });
  if (opts.includeDisabled) qs.set('includeDisabled', 'true');
  const data = await api(`/api/connector/providers/${encodeURIComponent(service)}?${qs}`);
  state.providerDetailCache.set(key, data);
  return data;
}

/** 判断 service 是否官方目录（space_id=0）：优先管理缓存，回退目录/总览列表 */
function isOfficialProvider(service) {
  const list = state.adminAll.length ? state.adminAll
    : (state.providers && state.providers.length ? state.providers
      : (state.allProviders || []));
  const p = list.find(p => p.service === service);
  return !!p && !p.spaceId;
}

async function openDrawer(service, opts = {}) {
  const drawer = $('#drawer');
  const body = $('#drawer-body');
  $('#drawer-title').textContent = service;
  body.innerHTML = '<div class="empty-note">加载中…</div>';
  $('#drawer-mask').classList.remove('hidden');
  drawer.classList.remove('hidden');
  try {
    const spaceId = opts.spaceId ?? state.spaceId;
    const readonly = !!opts.readonly;
    const detail = await providerDetail(service, { includeDisabled: opts.admin, spaceId });
    const p = detail.provider || {};
    const actions = detail.actions || [];
    // 管理入口（admin=true）下官方目录也按可维护渲染；总览浏览模式（readonly=true）一律只读
    const official = !p.spaceId;
    const editable = opts.admin || (!official && !readonly);
    const ownership = official
      ? (opts.admin ? '官方目录 · 管理侧可编辑' : (readonly ? '官方目录 · 只读' : '官方目录 · 只读，可导出'))
      : (readonly ? '空间自建 · 只读' : '本空间自定义 · 可编辑/导出/删除');
    body.innerHTML = `
      <table class="kv-table">
        <tr><th>认证方式</th><td>${esc(AUTH_LABEL[p.authType] || p.authType)}</td></tr>
        <tr><th>Base URL</th><td>${esc(p.baseUrl || '—')}</td></tr>
        <tr><th>通用代理</th><td>${p.proxyEnabled ? '已开启' : '未开启'}</td></tr>
        <tr><th>归属</th><td>${ownership}</td></tr>
      </table>
      ${editable ? `<div class="manage-bar">
        <button class="btn btn-outline btn-sm" data-edit-provider="${esc(service)}">编辑连接器</button>
        ${!official ? `<button class="btn btn-danger btn-sm" data-del-provider="${esc(service)}">删除连接器</button>` : ''}
        <button class="btn btn-outline btn-sm" data-export="${esc(service)}">导出</button>
        <button class="btn btn-coal btn-sm" data-add-action="${esc(service)}">＋ 添加工具</button>
      </div>` : ''}
      <div style="margin:8px 0 10px" class="field-label">工具列表</div>
      ${actions.length ? actions.map(a => actionRow(service, a, editable, spaceId)).join('')
        : '<div class="empty-note">该连接器还没有工具</div>'}
      ${!opts.admin && (p.authType === 'oauth2' || p.authType === 'oauth2_device'
        ? `<button class="btn btn-coal" data-oauth="${esc(service)}" data-spaceid="${spaceId}" style="margin-top:6px">${p.authType === 'oauth2_device' ? '扫码连接' : '发起 OAuth 授权'}</button>`
        : ['api_key', 'bearer', 'custom', 'no_auth'].includes(p.authType)
          ? `<button class="btn btn-coal" data-connect="${esc(service)}" data-spaceid="${spaceId}" style="margin-top:6px">${p.authType === 'no_auth' ? '连接' : '去连接'}</button>` : '')}
    `;
  } catch (e) {
    body.innerHTML = `<div class="empty-note">${esc(e.message)}</div>`;
  }
}

function actionRow(service, a, managed, spaceId) {
  const disabled = a.status === 'disabled';
  return `<div class="action-row">
    <div class="action-row-head">
      <span class="action-name">${esc(a.name || a.actionKey)}</span>
      ${disabled ? '<span class="chip err">已停用</span>' : ''}
      <span class="action-key">${esc(a.actionKey)}</span>
    </div>
    <div class="action-desc">${esc(a.description || '')}</div>
    <div class="action-row-foot">
      ${disabled
        ? `<button class="btn btn-coal btn-sm" disabled>调试</button>`
        : `<a class="btn btn-coal btn-sm" data-try-link href="debug.html?service=${encodeURIComponent(service)}&actionKey=${encodeURIComponent(a.actionKey)}&spaceId=${encodeURIComponent(spaceId ?? state.spaceId)}" target="_blank" rel="noopener">调试</a>`}
      ${UI_MODE === 'space' && !window.OVERVIEW_MODE ? `<button class="btn btn-outline btn-sm" data-guide="${esc(service)}" data-action="${esc(a.actionKey)}">使用指南</button>` : ''}
      ${managed ? `<button class="btn btn-outline btn-sm" data-edit-action="${a.id}" data-svc="${esc(service)}">编辑</button>
      <button class="btn btn-outline btn-sm" data-toggle-act="${a.id}" data-svc="${esc(service)}"
        data-target="${disabled ? 'enabled' : 'disabled'}">${disabled ? '启用' : '停用'}</button>
      <button class="btn btn-danger btn-sm" data-del-action="${a.id}" data-svc="${esc(service)}">删除</button>` : ''}
      <span class="chip" style="margin-left:auto">${esc(EXEC_LABEL[a.execType] || a.execType || 'HTTP 接口')}</span>
    </div>
  </div>`;
}

/* ---------------- 认证编辑器（结构化表单） ---------------- */

// 授权权限清单 = provider.auth_config.scopes，「新用户注册时下发给他的那份」。
// 为什么要单独开一只框：OAuth 类型的表单读的是**配置行**（见 fillProviderForm 的 cfgPath），
// 而 auth_config 这个字段 save 时压根不提交（见 collectAuthConfig 的 early return），
// 于是「这个连接器授权时会要哪些权限」在界面上完全没有落点 —— 偏偏它才是决定成不成事的那份。
// 别和 #oa-scopes 混：那个写配置行，只有「该用户没有自己那行」时才会被解析到；
// 本框写的 auth_config.scopes 是每个新用户注册时被种进他自己那行的种子。两处都留，标签各写各的。
let providerScopeSeed = null;   // { scopes: string[], source: string } —— 由 fillProviderForm 填充
let providerAuthConfig = null;  // 编辑中 provider 的 auth_config 原文；保存时只动 scopes 一个键

/** 取出 auth_config 里的 scopes；字段缺失就返回空数组（框照常渲染，新增连接器时也要能填）。 */
function parseScopeSeed(raw) {
  const ac = typeof raw === 'string' ? safeParse(raw) : raw;
  const scopes = Array.isArray(ac?.scopes) ? ac.scopes.filter(s => typeof s === 'string' && s) : [];
  const src = ac?._scopesSource || {};
  return {
    scopes,
    source: [src.version && ('v' + src.version), src.syncedAt, src.url].filter(Boolean).join(' · '),
  };
}

/** 授权权限清单的只读提示（官方层维护分支用；那种情况下编辑走的不是本表单）。 */
function seedScopesHtml() {
  if (!providerScopeSeed || !providerScopeSeed.scopes.length) return '';
  const { scopes, source } = providerScopeSeed;
  return '<div class="oauth-callback-line">授权权限清单：<b>' + scopes.length + '</b> 项'
    + '（用户连接时按这份清单申请，另含平台恒定追加的 <code>offline_access</code>）'
    + (source ? '<br>来源 <code>' + esc(source) + '</code>' : '')
    + '</div>';
}

/** 授权权限清单编辑块。总是渲染 —— 它是这份清单在界面上唯一的落点。 */
function seedScopesFieldHtml() {
  const scopes = providerScopeSeed ? providerScopeSeed.scopes : [];
  const src = providerScopeSeed && providerScopeSeed.source;
  return `
    <div class="field">
      <span class="field-label">授权权限清单 scopes（空格或逗号分隔）</span>
      <textarea id="oa-seed-scopes" rows="3" spellcheck="false" placeholder="如 offline_access contact:user.base:readonly drive:drive:readonly">${esc(scopes.join(' '))}</textarea>
      <p class="editor-hint">当前 <b>${scopes.length}</b> 项。用户首次扫码自注册时，平台按这份清单向他自己的 App 申请权限（另恒定追加 <code>offline_access</code>）。
        <b>只对新用户生效</b>：已经注册过的用户走他自己那行配置，要改得单独补那行。留空则该连接器只申请 <code>offline_access</code>。${src ? '<br>来源 <code>' + esc(src) + '</code>' : ''}</p>
    </div>`;
}

/** 按认证方式渲染结构化认证编辑器 */
function renderAuthEditor(authType, cfg) {
  const box = $('#prov-auth-editor');
  cfg = cfg || {};
  if (authType === 'no_auth') {
    box.innerHTML = '<p class="editor-hint">免鉴权连接器不收集任何凭证，连接即可直接执行。</p>';
    return;
  }
  if (authType === 'bearer') {
    box.innerHTML = '<p class="editor-hint">建连时收集一个 Token，执行时自动注入 Authorization: Bearer &lt;token&gt;，无需额外配置。</p>';
    return;
  }
  if (authType === 'oauth2' || authType === 'oauth2_device') {
    // 两种机制共用这一套 App 配置表单（client_id/secret + 两个端点 + scopes 形状完全一致），
    // 差别只在 authUrl 的语义：授权码流程里它是「授权页」，设备码流程里它是「设备授权端点」。
    // 表单标签与占位符按类型分化，免得用户把设备端点填成授权页。
    const device = authType === 'oauth2_device';
    const mode = cfg?.appMode === 'byo' ? 'byo' : 'platform';
    const hasSecret = !!(cfg && cfg.hasClientSecret);
    box.dataset.hasSecret = hasSecret ? '1' : '';
    box.innerHTML = `
      <div class="field">
        <span class="field-label">OAuth App 模式（二选一）</span>
        <select id="prov-oauth-mode">
          <option value="platform"${mode !== 'byo' ? ' selected' : ''}>platform · 全局公共 App（配置一次，全员共用）</option>
          <option value="byo"${mode === 'byo' ? ' selected' : ''}>byo · 个人 App（每个用户连接时自填）</option>
        </select>
      </div>
      <div id="prov-oauth-shared"${mode === 'byo' ? ' class="hidden"' : ''}>
        <div class="row-2">
          <label class="field"><span class="field-label">Client ID</span>
            <input id="oa-client-id" type="text" value="${esc(cfg?.clientId || '')}" placeholder="${device ? '飞书开放平台的 App ID，如 cli_xxx' : '在 IdP 注册的 Client ID'}" autocomplete="off"></label>
          <label class="field"><span class="field-label">Client Secret</span>
            <input id="oa-client-secret" type="password" value="" placeholder="${hasSecret ? '已设置，留空保持不变' : '首次必填（加密落库）'}" autocomplete="new-password"></label>
        </div>
        <div class="row-2">
          <label class="field"><span class="field-label">${device ? '设备授权端点 authUrl' : '授权端点 authUrl'}</span>
            <input id="oa-authurl" type="text" value="${esc(cfg?.authUrl || '')}" placeholder="${device ? 'https://accounts.feishu.cn/oauth/v1/device_authorization' : 'https://idp.example.com/oauth/authorize'}"></label>
          <label class="field"><span class="field-label">令牌端点 tokenUrl</span>
            <input id="oa-tokenurl" type="text" value="${esc(cfg?.tokenUrl || '')}" placeholder="${device ? 'https://open.feishu.cn/open-apis/authen/v2/oauth/token' : 'https://idp.example.com/oauth/token'}"></label>
        </div>
        <label class="field"><span class="field-label">Scopes（共享 App 的权限清单，可选）</span>
          <input id="oa-scopes" type="text" value="${esc((cfg?.scopes || []).join(' '))}" placeholder="${device ? '如 offline_access contact:user.base:readonly' : '如 read:user repo'}"></label>
        <p class="editor-hint">这一格是「共享 App 配置行」的清单，只在<b>用户没有自己那行配置</b>时才会被解析到。
          用户扫码自注册后会落一行属于他自己的配置，走的是他自己那行；注册时下发给他的那份在下方<b>授权权限清单</b>。</p>
        ${device
          ? '<div class="oauth-callback-line">扫码建连<b>不需要</b>回调地址：用户在 App 里确认后，平台服务端自己轮询令牌端点取回凭证。scopes 里请保留 <code>offline_access</code>，否则拿不到 refresh_token，令牌过期后要重新扫码。</div>'
          : `<div class="oauth-callback-line">回调地址（请到 IdP 登记）：<code>${esc(location.origin + '/api/connector/oauth/callback')}</code></div>`}
        <p class="editor-hint">${device
          ? '用户点「扫码连接」后弹出二维码，用 App 扫码确认即建连，全程不跳转、不需要公网回调地址。该 App 配置为共享：平台级管理员维护后全员共用（自建连接器为空间共享）。client_secret 加密落库，界面与日志不回显明文。'
          : '该 App 配置为共享：平台级管理员维护后全员共用（自建连接器为空间共享）。client_secret 加密落库，界面与日志不回显明文。'}</p>
      </div>
      <div id="prov-oauth-byo-hint"${mode === 'byo' ? '' : ' class="hidden"'}>
        <p class="editor-hint">该连接器为 byo 模式：平台不维护公共 App。用户连接时在授权抽屉中自行填写自己的 App（Client ID / Secret / 授权端点 / 令牌端点），配置按用户隔离加密保存。</p>
      </div>
      ${seedScopesFieldHtml()}`;
    const onOauthMode = () => {
      const m = $('#prov-oauth-mode').value;
      $('#prov-oauth-shared').classList.toggle('hidden', m !== 'platform');
      $('#prov-oauth-byo-hint').classList.toggle('hidden', m !== 'byo');
    };
    $('#prov-oauth-mode').addEventListener('change', onOauthMode);
    onOauthMode();
    return;
  }
  if (authType === 'api_key') {
    box.innerHTML = `
      <div class="row-2">
        <label class="field"><span class="field-label">凭证字段名</span>
          <input id="ak-keyname" type="text" value="${esc(cfg.keyName || 'apiKey')}" placeholder="apiKey"></label>
        <label class="field"><span class="field-label">注入位置</span>
          <select id="ak-injectto">
            <option value="header"${cfg.injectTo !== 'query' ? ' selected' : ''}>请求头 header</option>
            <option value="query"${cfg.injectTo === 'query' ? ' selected' : ''}>查询参数 query</option>
          </select></label>
      </div>
      <div class="row-2">
        <label class="field" id="ak-headername-wrap"><span class="field-label">请求头名称</span>
          <input id="ak-headername" type="text" value="${esc(cfg.headerName || 'X-Api-Key')}" placeholder="X-Api-Key"></label>
        <label class="field"><span class="field-label">值前缀（可选）</span>
          <input id="ak-prefix" type="text" value="${esc(cfg.prefix || '')}" placeholder="如 Token（可留空）"></label>
      </div>
      <p class="editor-hint">注入 query 时，查询参数名即「凭证字段名」。</p>`;
    const toggleHeaderName = () => {
      $('#ak-headername-wrap').style.visibility =
        $('#ak-injectto').value === 'header' ? '' : 'hidden';
    };
    $('#ak-injectto').addEventListener('change', toggleHeaderName);
    toggleHeaderName();
    return;
  }
  // custom：凭证字段 + 注入规则行编辑器
  box.innerHTML = `
    <div class="kv-editor">
      <div class="kv-editor-head"><span>凭证字段（建连界面逐项收集）</span>
        <button type="button" class="btn btn-outline btn-sm" id="add-auth-field">＋ 添加</button></div>
      <div id="auth-fields" class="kv-rows"></div>
    </div>
    <div class="kv-editor">
      <div class="kv-editor-head"><span>注入规则（凭证字段 → 请求位置）</span>
        <button type="button" class="btn btn-outline btn-sm" id="add-auth-inject">＋ 添加</button></div>
      <div id="auth-injects" class="kv-rows"></div>
    </div>
    <p class="editor-hint">凭证加密落库；执行时按注入规则写入请求头 / 查询参数（格式 header:名称 或 query:名称）。</p>`;
  const fields = Array.isArray(cfg.fields) && cfg.fields.length
    ? cfg.fields : [{ name: 'apiKey', label: 'API Key' }];
  fields.forEach(f => addAuthFieldRow(f));
  const injects = Array.isArray(cfg.inject) && cfg.inject.length
    ? cfg.inject : [{ from: 'apiKey', to: 'header:X-Api-Key' }];
  injects.forEach(r => addAuthInjectRow(r));
  $('#add-auth-field').addEventListener('click', () => addAuthFieldRow({}));
  $('#add-auth-inject').addEventListener('click', () => addAuthInjectRow({}));
}

function addAuthFieldRow(f) {
  const row = document.createElement('div');
  row.className = 'kv-row kv-row-3';
  row.innerHTML = `
    <input type="text" data-r="name" value="${esc(f.name || '')}" placeholder="字段名 如 apiKey">
    <input type="text" data-r="label" value="${esc(f.label || '')}" placeholder="显示名 如 API Key">
    <label class="mini-check"><input type="checkbox" data-r="secret" ${f.secret === false ? '' : 'checked'}><span>密文框</span></label>
    <button type="button" class="row-del" data-del-row title="删除该行">×</button>`;
  $('#auth-fields').appendChild(row);
}

function addAuthInjectRow(rule) {
  const to = String(rule.to || '');
  const colon = to.indexOf(':');
  const target = colon > 0 ? to.slice(0, colon) : 'header';
  const name = colon > 0 ? to.slice(colon + 1) : '';
  const row = document.createElement('div');
  row.className = 'kv-row kv-row-inject';
  row.innerHTML = `
    <input type="text" data-r="from" value="${esc(rule.from || '')}" placeholder="凭证字段名">
    <span class="row-arrow">→</span>
    <select data-r="target">
      <option value="header"${target !== 'query' ? ' selected' : ''}>请求头</option>
      <option value="query"${target === 'query' ? ' selected' : ''}>查询参数</option>
    </select>
    <input type="text" data-r="name" value="${esc(name)}" placeholder="名称 如 X-Api-Key">
    <button type="button" class="row-del" data-del-row title="删除该行">×</button>`;
  $('#auth-injects').appendChild(row);
}

/** 收集结构化认证编辑器 → authConfig 对象；校验失败返回 null（已 toast） */
function collectAuthConfig() {
  const authType = $('#prov-authtype').value;
  // 两种 OAuth 机制的**凭证**（client_id/secret + 端点）都走 shared-config/byo-config 单独存，不进 auth_config；
  // 但 auth_config 里的 scopes 是注册下发布，界面上有框（#oa-seed-scopes），得单独回写。
  if (authType === 'oauth2' || authType === 'oauth2_device') return collectSeedScopes();
  if (authType === 'no_auth' || authType === 'bearer') return {};
  if (authType === 'api_key') {
    const injectTo = $('#ak-injectto').value;
    const keyName = $('#ak-keyname').value.trim() || 'apiKey';
    const cfg = { keyName, injectTo };
    if (injectTo === 'header') cfg.headerName = $('#ak-headername').value.trim() || keyName;
    const prefix = $('#ak-prefix').value.trim();
    if (prefix) cfg.prefix = prefix;
    return cfg;
  }
  const fields = $$('#auth-fields .kv-row').map(row => ({
    name: $('[data-r="name"]', row).value.trim(),
    label: $('[data-r="label"]', row).value.trim(),
    secret: $('[data-r="secret"]', row).checked,
  })).filter(f => f.name);
  if (!fields.length) { toast('自定义认证至少需要一个凭证字段', 'err'); return null; }
  const fieldNames = new Set(fields.map(f => f.name));
  const inject = [];
  for (const row of $$('#auth-injects .kv-row')) {
    const from = $('[data-r="from"]', row).value.trim();
    const name = $('[data-r="name"]', row).value.trim();
    if (!from && !name) continue;
    if (!from || !name) { toast('注入规则需同时填写凭证字段名与目标名称', 'err'); return null; }
    if (!fieldNames.has(from)) { toast(`注入规则引用了未定义的凭证字段：${from}`, 'err'); return null; }
    inject.push({ from, to: `${$('[data-r="target"]', row).value}:${name}` });
  }
  return { fields, inject };
}

/**
 * 把界面上的授权权限清单合并回 provider.auth_config（OAuth 类型专用的保存口）。
 * 返回 undefined = 本次不动 auth_config —— 字段没渲染（官方层维护分支）或内容一个字没改。
 * 后端对本字段是「传了就整串覆盖、不传就不更新」，所以少带一个键等于把它删掉：
 * appRegistration / tenantToken / scopeCatalog 这些必须原样带回，本函数只动 scopes 与 _scopesSource。
 */
function collectSeedScopes() {
  const el = $('#oa-seed-scopes');
  if (!el) return undefined;
  const scopes = el.value.split(/[,\s]+/).map(s => s.trim()).filter(Boolean);
  const before = providerScopeSeed ? providerScopeSeed.scopes : [];
  if (scopes.length === before.length && scopes.every((s, i) => s === before[i])) return undefined;
  const cfg = providerAuthConfig && typeof providerAuthConfig === 'object' ? { ...providerAuthConfig } : {};
  if (!scopes.length) {
    delete cfg.scopes;
    delete cfg._scopesSource;
    return cfg;
  }
  cfg.scopes = scopes;
  // 手工改过之后，原来那份「lark-cli v1.0.96 同步」的出处就不成立了，别留着骗人
  cfg._scopesSource = { note: '连接器编辑页手工填写', editedAt: fmtStamp(new Date()) };
  return cfg;
}

/** 「YYYY-MM-DD HH:mm:ss」，与脚本写入 _scopesSource.syncedAt 的格式一致。 */
function fmtStamp(d) {
  const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/**
 * 收集 OAuth App 编辑器（oauth2 与 oauth2_device 共用同一套字段）：
 * 返回 null=校验失败（已 toast）；{skip:true}=编辑态未改动跳过保存；
 * undefined=非 OAuth 类；否则为 shared-config 请求体。
 */
function collectOAuthAppConfig() {
  const authType = $('#prov-authtype').value;
  if (authType !== 'oauth2' && authType !== 'oauth2_device') return undefined;
  if (($('#prov-oauth-mode')?.value || 'platform') === 'byo') return undefined;
  const clientId = $('#oa-client-id').value.trim();
  const clientSecret = $('#oa-client-secret').value.trim();
  const authUrl = $('#oa-authurl').value.trim();
  const tokenUrl = $('#oa-tokenurl').value.trim();
  const scopes = $('#oa-scopes').value.split(/[,\s]+/).map(s => s.trim()).filter(Boolean);
  const hasSecret = $('#prov-auth-editor').dataset.hasSecret === '1';
  const creating = !providerEditMode;
  // 扫码连接器少填 offline_access 的后果比授权码更隐蔽：建连会成功、动作也能跑，
  // 但 7200 秒后令牌过期且无 refresh_token 可用，连接静默僵死，只能重新扫码。
  const authUrlLabel = authType === 'oauth2_device' ? '设备授权端点' : '授权端点';
  // 扫码（oauth2_device）与授权码（oauth2）的必填项不同，这里必须分开：
  //   - 授权码：没有 client_id 一个授权请求都发不出去，四项缺一不可；
  //   - 扫码：这类连接器就是「用户自注册」——用户在 App 内扫一次码，平台拿注册协议换回
  //     这个用户专属的 client_id/client_secret，所以建连接器时只需要两个端点。
  // 早先不分类型一律要求四项，用户手里没有 client_id 只能编一个占位值，占位值又让建连走了
  // 「平台自有 App」分支，飞书回一句与原因无关的「The specified app does not exist.」。
  const deviceFlow = authType === 'oauth2_device';
  const missingEndpoints = !authUrl || !tokenUrl;
  if (creating && deviceFlow && missingEndpoints) {
    toast(`创建扫码连接器需填写 ${authUrlLabel} / 令牌端点（Client ID 留空即用户扫码自注册）`, 'err');
    return null;
  }
  if (creating && !deviceFlow && (missingEndpoints || !clientId || !clientSecret)) {
    toast(`创建 OAuth 连接器需完整填写 Client ID / Client Secret / ${authUrlLabel} / 令牌端点`, 'err');
    return null;
  }
  if (!creating && !clientId && !clientSecret && !authUrl && !tokenUrl && !scopes.length) {
    return { skip: true };
  }
  if (missingEndpoints || (!deviceFlow && !clientId)) {
    toast(`App 配置需填写 ${deviceFlow ? '' : 'Client ID / '}${authUrlLabel} / 令牌端点`, 'err');
    return null;
  }
  // secret 只有当「这次确实在提交一个 clientId」时才是必填：扫码连接器允许两者都不填
  if (!hasSecret && !clientSecret && (!deviceFlow || clientId)) {
    toast('首次配置 OAuth App 必须填写 Client Secret', 'err');
    return null;
  }
  return { clientId, clientSecret: clientSecret || undefined, authUrl, tokenUrl, scopes };
}

/* ---------------- 新增 / 编辑连接器抽屉 ---------------- */

let providerEditMode = null;
let providerAdminMode = false;
let oauthModeCfg = null;

function openProviderDrawer(editService, opts = {}) {
  providerEditMode = editService || null;
  providerAdminMode = !!opts.admin;
  oauthModeCfg = null;
  providerScopeSeed = null;
  providerAuthConfig = null;
  $('#provider-form').reset();
  $('#prov-service').readOnly = !!editService;
  $('#provider-drawer-title').textContent = editService ? '编辑连接器' : '新增连接器';
  $('#provider-submit').textContent = editService ? '保存修改' : '创建连接器';
  const sel = $('#prov-authtype');
  sel.disabled = false;
  sel.innerHTML = `<option value="no_auth">免鉴权</option><option value="api_key">API Key</option>
    <option value="bearer">Bearer</option><option value="oauth2">OAuth 2.0</option>
    <option value="oauth2_device">扫码授权（设备码）</option><option value="custom">自定义</option>`;
  $('#prov-auth-editor').innerHTML = '';
  if (editService) {
    fillProviderForm(editService).catch(err => {
      toast('加载连接器失败：' + err.message, 'err');
      closeProviderDrawer();
    });
  } else {
    renderAuthEditor(sel.value, null);
    setTimeout(() => $('#prov-service').focus(), 60);
  }
  $('#provider-drawer-mask').classList.remove('hidden');
  $('#provider-drawer').classList.remove('hidden');
}

async function fillProviderForm(service) {
  const detail = await providerDetail(service);
  const p = detail.provider || {};
  $('#prov-service').value = p.service || service;
  $('#prov-display').value = p.displayName || '';
  $('#prov-desc').value = p.description || '';
  $('#prov-baseurl').value = p.baseUrl || '';
  $('#prov-category').value = p.category || '';
  $('#prov-tags').value = Array.isArray(p.tags) ? p.tags.join(', ') : '';
  const sel = $('#prov-authtype');
  if (p.authType === 'oauth2' || p.authType === 'oauth2_device') {
    const official = !p.spaceId;
    // OAuth 分支的两条路都不经 auth_config，但里面的 scopes 是「授权时到底要哪些权限」的源头
    // （注册时种进 byo 配置行），界面别处看不到 —— 先取出来，两条路都要用。
    providerAuthConfig = typeof p.authConfig === 'string' ? safeParse(p.authConfig) : (p.authConfig || null);
    providerScopeSeed = parseScopeSeed(p.authConfig);
    if (official && !providerAdminMode) {
      sel.innerHTML = `<option value="${esc(p.authType)}">${esc(AUTH_LABEL[p.authType] || p.authType)}（官方层维护）</option>`;
      sel.disabled = true;
      $('#prov-auth-editor').innerHTML =
        '<p class="editor-hint">OAuth 连接器的认证配置（授权端点 / scopes）由官方层维护，此处仅可修改名称、Base URL、分类等基础信息。</p>'
        + seedScopesHtml();
    } else {
      sel.value = p.authType;
      const cfgPath = official
        ? '/api/system/connector/oauth-config?service=' + encodeURIComponent(service)
        : '/api/connector/oauth/shared-config?service=' + encodeURIComponent(service);
      let cfg = null;
      try { cfg = await api(cfgPath); } catch { /* 读取失败兜底空表单，不阻塞元数据编辑 */ }
      oauthModeCfg = { ...(cfg || {}), appMode: p.oauthAppMode || 'platform' };
      renderAuthEditor(p.authType, oauthModeCfg);
    }
  } else {
    sel.value = p.authType || 'no_auth';
    const cfg = typeof p.authConfig === 'string' ? safeParse(p.authConfig) : p.authConfig;
    renderAuthEditor(sel.value, cfg);
  }
}

async function saveProvider(e) {
  e.preventDefault();
  const editing = providerEditMode;
  const service = $('#prov-service').value.trim();
  if (!editing && !/^[a-z][a-z0-9_]{2,31}$/.test(service)) {
    return toast('service 需小写字母开头，3-32 位小写字母/数字/下划线', 'err');
  }
  // 空间端新增：service 幂等加 s_ 前缀（空间私有标识）；编辑/管理端官方不加
  const spaceScoped = !editing && UI_MODE !== 'admin';
  const effectiveService = spaceScoped && !service.startsWith('s_') ? 's_' + service : service;
  const authConfig = collectAuthConfig();
  if (authConfig === null) return;
  const oauthApp = collectOAuthAppConfig();
  if (oauthApp === null) return;
  const tags = $('#prov-tags').value.split(/[,，]/).map(s => s.trim()).filter(Boolean);
  const body = {
    service: effectiveService,
    displayName: $('#prov-display').value.trim() || service,
    description: $('#prov-desc').value.trim() || undefined,
    authType: $('#prov-authtype').value,
    baseUrl: $('#prov-baseurl').value.trim(),
    category: $('#prov-category').value.trim() || undefined,
    tags: tags.length ? tags : undefined,
    authConfig,
    oauthAppMode: $('#prov-oauth-mode')?.value || undefined,
  };
  try {
    if (editing) {
      // 编辑路由：官方 → 管理侧 meta（保持 managed_by=system）；空间自建 → 用户侧
      if (isOfficialProvider(editing)) {
        await api('/api/system/connector/providers/' + encodeURIComponent(editing) + '/meta', { method: 'PUT', body });
      } else {
        await api('/api/connector/providers/' + encodeURIComponent(editing), { method: 'PUT', body });
      }
    } else if (UI_MODE === 'admin') {
      // 管理端：新增即官方全局（space_id=0、managed_by=system、全员可见）
      await api('/api/system/connector/providers', { method: 'POST', body });
    } else {
      body.spaceId = state.spaceId;
      await api('/api/connector/providers', { method: 'POST', body });
    }
    // OAuth 连接器：provider 落库后保存共享 App 配置（官方→平台公共 App；自建→空间共享）
    if (oauthApp && !oauthApp.skip) {
      try {
        const official = editing ? isOfficialProvider(editing) : UI_MODE === 'admin';
        const oauthPath = official
          ? '/api/system/connector/oauth-config'
          : '/api/connector/oauth/shared-config';
        await api(oauthPath, {
          method: 'POST',
          body: { service: editing || service, ...oauthApp },
        });
      } catch (appErr) {
        toast('连接器已保存，但 OAuth App 配置失败，请重新编辑补填：' + appErr.message, 'err');
      }
    }
    invalidateProviderDetail(editing || service);
    closeProviderDrawer();
    if (typeof refreshAfterWrite === 'function') await refreshAfterWrite();
    if (editing) {
      openDrawer(editing, { admin: isOfficialProvider(editing) });
      toast(`连接器 ${editing} 已更新`, 'ok');
    } else {
      toast(UI_MODE === 'admin'
        ? `官方全局连接器 ${service} 已创建（全员可见）`
        : `连接器 ${service} 已创建 —— 到「查看工具」里为它添加工具`, 'ok');
    }
  } catch (err) {
    toast((editing ? '保存失败：' : '创建失败：') + err.message, 'err');
  }
}

/* ---------------- 动作编辑抽屉 ---------------- */

let actionDrawerService = null;
let actionEditId = null;
let bindingItemsCache = [];

function openActionDrawer(service, action) {
  actionDrawerService = service;
  actionEditId = action?.id ?? null;
  $('#action-form').reset();
  $('#act-exec-type').value = 'DECLARATIVE';
  renderBindSpaceOptions();
  $('#act-bind-label').textContent = '绑定插件（仅已发布）';
  $('#act-bind-select').innerHTML = '<option value="">— 请选择 —</option>';
  $('#act-bind-desc').textContent = '';
  applyExecTypeUi('DECLARATIVE');
  ['#spec-query', '#spec-headers', '#spec-body', '#act-args']
    .forEach(sel => { $(sel).innerHTML = ''; });
  $('#action-drawer-title').textContent = action ? `编辑工具 · ${action.actionKey}` : '新增工具';
  $('#act-key').readOnly = !!action;
  if (action) fillActionForm(action);
  $('#action-drawer-mask').classList.remove('hidden');
  $('#action-drawer').classList.remove('hidden');
  setTimeout(() => (action ? $('#act-name') : $('#act-key')).focus(), 60);
}

function fillActionForm(a) {
  $('#act-key').value = a.actionKey || '';
  $('#act-name').value = a.name || '';
  $('#act-desc').value = a.description || '';
  $('#act-tags').value = Array.isArray(a.tags) ? a.tags.join(', ') : '';
  const spec = typeof a.httpSpec === 'string' ? safeParse(a.httpSpec) : (a.httpSpec || {});
  const execType = a.execType || (spec && spec.bindType ? spec.bindType : 'DECLARATIVE');
  $('#act-exec-type').value = execType;
  const isBind = execType === 'PLUGIN' || execType === 'WORKFLOW';
  if (isBind) $('#act-bind-label').textContent = execType === 'WORKFLOW' ? '绑定工作流（仅已发布）' : '绑定插件（仅已发布）';
  applyExecTypeUi(execType);
  // 入参：编辑回显用已保存的 inputArgs（loadBindable 只选中不触发 change，故不会覆盖回显/用户手改）
  const inputArgs = typeof a.inputArgs === 'string' ? safeParse(a.inputArgs) : (a.inputArgs || []);
  if (Array.isArray(inputArgs)) {
    inputArgs.forEach(arg => addArgNode($('#act-args'), arg));
  } else if (inputArgs && typeof inputArgs === 'object') {
    Object.entries(inputArgs).forEach(([k, v]) =>
      addArgNode($('#act-args'), { name: k, description: typeof v === 'string' ? v : JSON.stringify(v), dataType: 'String' }));
  }
  if (isBind) {
    const bindSpaceId = spec.spaceId ? Number(spec.spaceId) : null;
    renderBindSpaceOptions(bindSpaceId);
    loadBindable(execType === 'WORKFLOW' ? 'workflow' : 'plugin',
      { selectId: String(spec.bindId ?? spec.id ?? ''), spaceId: bindSpaceId });
  } else {
    $('#spec-method').value = (spec.method || 'GET').toUpperCase();
    $('#spec-path').value = spec.path || '';
    $('#spec-timeout').value = spec.timeoutMs > 0 ? spec.timeoutMs : '';
    $('#spec-extract').value = spec.response?.extract || '';
    $('#spec-bodyraw').value = spec.bodyRaw || '';
    Object.entries(spec.query || {}).forEach(([k, v]) => addMappingRow('#spec-query', k, v));
    Object.entries(spec.headers || {}).forEach(([k, v]) => addMappingRow('#spec-headers', k, v));
    if (Array.isArray(spec.bodyFields) && spec.bodyFields.length) {
      spec.bodyFields.forEach(f => addBodyFieldNode($('#spec-body'), f));
    } else if (spec.body && typeof spec.body === 'object') {
      Object.entries(spec.body).forEach(([k, v]) =>
        addBodyFieldNode($('#spec-body'), { name: k, type: 'string', mapping: String(v ?? '') }));
    }
  }
}

/* ---- 执行类型：HTTP / 绑定插件 / 绑定工作流 ---- */

function applyExecTypeUi(execType) {
  const isBind = execType === 'PLUGIN' || execType === 'WORKFLOW';
  const httpBox = $('#act-http-box');
  const bindBox = $('#act-bind-box');
  if (httpBox) httpBox.classList.toggle('hidden', isBind);
  if (bindBox) bindBox.classList.toggle('hidden', !isBind);
  $('#spec-path').required = !isBind;
}

async function loadBindable(bindType, opts = {}) {
  const sel = $('#act-bind-select');
  if (!sel) return;
  const spaceId = opts.spaceId ?? currentBindSpaceId();
  const qs = spaceId ? `&spaceId=${encodeURIComponent(spaceId)}` : '';
  try {
    bindingItemsCache = await api(`/api/connector/bindable?type=${bindType}${qs}`) || [];
  } catch (err) {
    toast('加载绑定列表失败：' + err.message, 'err');
    return;
  }
  sel.innerHTML = '<option value="">— 请选择 —</option>' + bindingItemsCache.map(it =>
    `<option value="${it.id}">${it.icon ? esc(it.icon) + ' ' : ''}${esc(it.name || ('#' + it.id))}</option>`).join('');
  if (opts.selectId) {
    const found = bindingItemsCache.find(it => String(it.id) === String(opts.selectId));
    if (found) sel.value = String(found.id);
  }
  updateBindDesc();
}

function currentBindItem() {
  const sel = $('#act-bind-select');
  const id = sel?.value;
  if (!id) return null;
  return bindingItemsCache.find(it => String(it.id) === String(id)) || null;
}

function updateBindDesc() {
  const it = currentBindItem();
  const el = $('#act-bind-desc');
  if (!el) return;
  if (!it) { el.textContent = ''; return; }
  const argNames = Array.isArray(it.inputArgs) ? it.inputArgs.map(x => x.name).filter(Boolean).join('、') : '';
  el.textContent = `${it.name || ''}${it.description ? ' — ' + it.description : ''}${argNames ? '　入参：' + argNames : ''}`;
}

/** 绑定列表生效空间：空间端固定当前空间；管理端取空间下拉选中值（未选时回退主空间） */
function currentBindSpaceId() {
  if (UI_MODE === 'space') return state.spaceId;
  const sel = $('#act-bind-space');
  if (sel && sel.value) return Number(sel.value);
  return state.spaces.length ? state.spaces[0].id : null;
}

/** 填充管理端「空间」筛选下拉（space 模式隐藏该控件，绑定列表固定当前空间） */
function renderBindSpaceOptions(selectId) {
  const field = $('#act-bind-space-field');
  const sel = $('#act-bind-space');
  if (!sel) return;
  if (UI_MODE === 'space') {
    if (field) field.classList.add('hidden');
    return;
  }
  if (field) field.classList.remove('hidden');
  sel.innerHTML = state.spaces.map(s =>
    `<option value="${s.id}">${esc(s.name)}</option>`).join('');
  if (selectId) {
    const found = state.spaces.find(s => String(s.id) === String(selectId));
    if (found) sel.value = String(found.id);
    else sel.selectedIndex = 0;
  }
}

function addMappingRow(sel, name, mapping) {
  const row = document.createElement('div');
  row.className = 'kv-row kv-row-map';
  row.innerHTML = `
    <input type="text" data-r="k" value="${esc(name ?? '')}" placeholder="参数名">
    <span class="row-arrow">←</span>
    <input type="text" data-r="v" value="${esc(mapping ?? '')}" placeholder="输入参数名 / opt:参数名 / literal:固定值">
    <button type="button" class="row-del" data-del-row title="删除该行">×</button>`;
  $(sel).appendChild(row);
}

/* ---- 输入参数 Arg 树 ---- */

const ARG_TYPES = ['String', 'Integer', 'Number', 'Boolean', 'Object',
  'Array_File', 'Array_String', 'Array_Integer', 'Array_Number', 'Array_Boolean', 'Array_Object'];
const ARG_CONTAINER_TYPES = ['Object', 'Array_Object'];

function addArgNode(container, arg) {
  arg = arg || {};
  const type = ARG_TYPES.includes(arg.dataType) ? arg.dataType : 'String';
  const el = document.createElement('div');
  el.className = 'bf-node af-node';
  el.innerHTML = `
    <div class="kv-row bf-row af-row">
      <input type="text" data-r="name" value="${esc(arg.name ?? '')}" placeholder="参数名">
      <input type="text" data-r="desc" value="${esc(arg.description ?? '')}" placeholder="参数说明（供 Agent 理解）">
      <select data-r="type">${ARG_TYPES.map(t =>
        `<option value="${t}"${t === type ? ' selected' : ''}>${t}</option>`).join('')}</select>
      <label class="af-req"><input type="checkbox" data-r="req"${arg.require ? ' checked' : ''}>必填</label>
      <button type="button" class="row-del" data-del-row title="删除该参数（含下级）">×</button>
    </div>
    <div class="bf-children kv-rows af-children"></div>
    <button type="button" class="btn btn-outline btn-sm bf-add-child af-add-child">＋ 添加下级参数</button>`;
  container.appendChild(el);
  setArgNodeType(el, type, arg);
  $('[data-r="type"]', el).addEventListener('change', e => setArgNodeType(el, e.target.value, null));
  return el;
}

function setArgNodeType(el, type, initData) {
  const childrenBox = $('.af-children', el);
  const addChildBtn = $('.af-add-child', el);
  childrenBox.innerHTML = '';
  const isContainer = ARG_CONTAINER_TYPES.includes(type);
  addChildBtn.classList.toggle('hidden', !isContainer);
  if (!isContainer) return;
  if (type === 'Array_Object') {
    const band = document.createElement('div');
    band.className = 'bf-item-band';
    band.textContent = '元素字段结构（下级参数描述数组的每个元素）';
    childrenBox.appendChild(band);
  }
  (initData?.subArgs || []).forEach(s => addArgNode(childrenBox, s));
}

function collectArgNodes(container) {
  const out = [];
  for (const el of Array.from(container.children)) {
    if (!el.classList.contains('af-node')) continue;
    const node = collectOneArgNode(el);
    if (node === null) return null;
    out.push(node);
  }
  return out;
}

function collectOneArgNode(el) {
  const name = $('[data-r="name"]', el).value.trim();
  if (!name) {
    toast('输入参数名不能为空', 'err');
    return null;
  }
  const node = { name };
  const desc = $('[data-r="desc"]', el).value.trim();
  if (desc) node.description = desc;
  node.dataType = $('[data-r="type"]', el).value;
  if ($('[data-r="req"]', el).checked) node.require = true;
  if (ARG_CONTAINER_TYPES.includes(node.dataType)) {
    const subs = [];
    for (const child of Array.from($('.af-children', el).children)) {
      if (!child.classList.contains('af-node')) continue;
      const s = collectOneArgNode(child);
      if (s === null) return null;
      subs.push(s);
    }
    if (subs.length) node.subArgs = subs;
  }
  return node;
}

function collectRows(sel) {
  const obj = {};
  $$(sel + ' .kv-row').forEach(row => {
    const k = $('[data-r="k"]', row).value.trim();
    const v = $('[data-r="v"]', row).value.trim();
    if (k) obj[k] = v;
  });
  return obj;
}

/* ---- Body 字段树 ---- */

const BODY_TYPES = ['string', 'number', 'boolean', 'object', 'array'];

function addBodyFieldNode(container, node, isItem = false) {
  node = node || {};
  const type = BODY_TYPES.includes(node.type) ? node.type : 'string';
  const el = document.createElement('div');
  el.className = 'bf-node' + (isItem ? ' bf-item' : '');
  el.innerHTML = `
    <div class="kv-row bf-row">
      ${isItem
        ? '<span class="bf-item-tag">元素</span>'
        : `<input type="text" data-r="name" value="${esc(node.name ?? '')}" placeholder="字段名">`}
      <select data-r="type">${BODY_TYPES.map(t =>
        `<option value="${t}"${t === type ? ' selected' : ''}>${t}</option>`).join('')}</select>
      <span class="row-arrow bf-arrow">←</span>
      <input type="text" data-r="mapping" value="${esc(node.mapping ?? '')}" placeholder="输入参数名 / opt:参数名 / literal:固定值">
      ${isItem ? '' : '<button type="button" class="row-del" data-del-row title="删除该字段（含下级）">×</button>'}
    </div>
    <div class="bf-children kv-rows"></div>
    <button type="button" class="btn btn-outline btn-sm bf-add-child">＋ 添加子字段</button>`;
  container.appendChild(el);
  setBodyNodeType(el, type, isItem, node);
  return el;
}

function setBodyNodeType(el, type, isItem, initData) {
  const row = $('.bf-row', el);
  const mapping = $('[data-r="mapping"]', row);
  const arrow = $('.bf-arrow', row);
  const childrenBox = $('.bf-children', el);
  const addChildBtn = $('.bf-add-child', el);
  childrenBox.innerHTML = '';
  const hideMapping = type === 'object' || isItem;
  mapping.classList.toggle('hidden', hideMapping);
  arrow.classList.toggle('hidden', hideMapping);
  addChildBtn.classList.toggle('hidden', type !== 'object');
  if (type === 'array') {
    mapping.placeholder = '值为整个数组的输入参数名（可加 opt: 前缀）';
    const band = document.createElement('div');
    band.className = 'bf-item-band';
    band.textContent = '元素结构（对应输入参数 Arg · 仅声明，不逐项取值）';
    childrenBox.appendChild(band);
    addBodyFieldNode(childrenBox, initData?.item || { type: 'string' }, true);
  } else if (type === 'object') {
    (initData?.children || []).forEach(c => addBodyFieldNode(childrenBox, c));
  } else {
    mapping.placeholder = '输入参数名 / opt:参数名 / literal:固定值';
  }
}

function collectBodyFields(container) {
  const out = [];
  for (const el of Array.from(container.children)) {
    if (!el.classList.contains('bf-node')) continue;
    const node = collectOneBodyNode(el);
    if (node === null) return null;
    out.push(node);
  }
  return out;
}

function collectOneBodyNode(el) {
  const isItem = el.classList.contains('bf-item');
  const type = $('[data-r="type"]', el).value;
  const nameEl = $('[data-r="name"]', el);
  const name = nameEl ? nameEl.value.trim() : '';
  if (!isItem && !name) {
    toast('Body 字段名不能为空', 'err');
    return null;
  }
  const mapping = $('[data-r="mapping"]', el).value.trim();
  if (type === 'object') {
    const node = isItem ? { type } : { name, type };
    const children = [];
    for (const child of Array.from($('.bf-children', el).children)) {
      if (!child.classList.contains('bf-node')) continue;
      const c = collectOneBodyNode(child);
      if (c === null) return null;
      children.push(c);
    }
    if (children.length) node.children = children;
    return node;
  }
  if (type === 'array') {
    if (!mapping) {
      toast(`array 字段「${name}」需填写提供整个数组的输入参数名`, 'err');
      return null;
    }
    const itemEl = Array.from($('.bf-children', el).children)
      .find(c => c.classList.contains('bf-node'));
    const item = itemEl ? collectOneBodyNode(itemEl) : null;
    if (item === null) return null;
    const node = isItem ? { type, mapping } : { name, type, mapping };
    if (item) node.item = item;
    return node;
  }
  if (isItem) return { type };
  if (!mapping) {
    toast(`Body 字段「${name}」需填写映射（输入参数名 / opt: / literal:）`, 'err');
    return null;
  }
  return { name, type, mapping };
}

async function saveAction(e) {
  e.preventDefault();
  const service = actionDrawerService;
  if (!service) return;
  const actionKey = $('#act-key').value.trim();
  if (!/^[a-zA-Z][a-zA-Z0-9_]{0,127}$/.test(actionKey)) {
    return toast('actionKey 需字母开头，仅含字母/数字/下划线', 'err');
  }
  const argNodes = collectArgNodes($('#act-args'));
  if (argNodes === null) return;
  const tags = $('#act-tags').value.split(/[,，]/).map(s => s.trim()).filter(Boolean);
  const execType = $('#act-exec-type').value;
  let body;
  if (execType === 'PLUGIN' || execType === 'WORKFLOW') {
    const it = currentBindItem();
    if (!it) return toast('请选择要绑定的' + (execType === 'WORKFLOW' ? '工作流' : '插件'), 'err');
    body = {
      actionKey,
      name: $('#act-name').value.trim() || actionKey,
      description: $('#act-desc').value.trim() || undefined,
      tags: tags.length ? tags : undefined,
      inputArgs: argNodes.length ? argNodes : undefined,
      execType,
      execRef: String(it.id),
      httpSpec: { bindType: execType, bindId: String(it.id), name: it.name, icon: it.icon, description: it.description, spaceId: currentBindSpaceId() ?? undefined },
    };
  } else {
    const path = $('#spec-path').value.trim();
    if (!path) return toast('请填写请求路径', 'err');
    const spec = { method: $('#spec-method').value, path };
    const timeout = Number($('#spec-timeout').value);
    if (timeout > 0) spec.timeoutMs = timeout;
    const extract = $('#spec-extract').value.trim();
    if (extract) spec.response = { extract };
    const bodyRaw = $('#spec-bodyraw').value.trim();
    if (bodyRaw) {
      spec.bodyRaw = bodyRaw;
    } else {
      const bodyFields = collectBodyFields($('#spec-body'));
      if (bodyFields === null) return;
      if (bodyFields.length) spec.bodyFields = bodyFields;
    }
    const queryMap = collectRows('#spec-query');
    if (Object.keys(queryMap).length) spec.query = queryMap;
    const headerMap = collectRows('#spec-headers');
    if (Object.keys(headerMap).length) spec.headers = headerMap;
    body = {
      actionKey,
      name: $('#act-name').value.trim() || actionKey,
      description: $('#act-desc').value.trim() || undefined,
      tags: tags.length ? tags : undefined,
      inputArgs: argNodes.length ? argNodes : undefined,
      execType: 'DECLARATIVE',
      httpSpec: spec,
    };
  }
  const official = isOfficialProvider(service);
  try {
    if (actionEditId) {
      if (official) {
        const detail = state.providerDetailCache.get(service);
        const action = detail?.actions?.find(a => String(a.id) === String(actionEditId));
        const key = action?.actionKey || actionKey;
        await api(`/api/system/connector/providers/${encodeURIComponent(service)}/actions/${encodeURIComponent(key)}`,
          { method: 'PUT', body });
      } else {
        await api('/api/connector/actions/' + actionEditId, { method: 'PUT', body });
      }
      toast(`工具 ${actionKey} 已更新`, 'ok');
    } else {
      const apiPath = official
        ? `/api/system/connector/providers/${encodeURIComponent(service)}/actions`
        : `/api/connector/providers/${encodeURIComponent(service)}/actions`;
      await api(apiPath, { method: 'POST', body });
      toast(`工具 ${actionKey} 已创建`, 'ok');
    }
    invalidateProviderDetail(service);
    closeActionDrawer();
    if (typeof refreshAfterWrite === 'function') await refreshAfterWrite();
    openDrawer(service, { admin: official });
  } catch (err) {
    toast('保存工具失败：' + err.message, 'err');
  }
}

/* ---------------- 删除 / 导出 / 导入 ---------------- */

async function deleteProviderConfirmed(service) {
  if (!confirm(`删除连接器 ${service}？其全部工具将一并删除。若仍有用户连接，删除会被拒绝（需先断开）。`)) return;
  try {
    await api('/api/connector/providers/' + encodeURIComponent(service), { method: 'DELETE' });
    invalidateProviderDetail(service);
    closeDrawer();
    if (typeof refreshAfterWrite === 'function') await refreshAfterWrite();
    toast(`连接器 ${service} 已删除`, 'ok');
  } catch (err) {
    toast('删除失败：' + err.message, 'err');
  }
}

async function deleteActionConfirmed(id, service) {
  if (!confirm('删除该工具？')) return;
  try {
    const official = isOfficialProvider(service);
    const path = official
      ? '/api/system/connector/actions/' + encodeURIComponent(id)
      : '/api/connector/actions/' + encodeURIComponent(id);
    await api(path, { method: 'DELETE' });
    invalidateProviderDetail(service);
    if (typeof refreshAfterWrite === 'function') await refreshAfterWrite();
    openDrawer(service, { admin: official });
    toast('工具已删除', 'ok');
  } catch (err) {
    toast('删除失败：' + err.message, 'err');
  }
}

/** 下载 JSON 导出包（创建 a 标签触发下载） */
function downloadBundle(bundle, name) {
  const blob = new Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 500);
}

async function exportProvider(service) {
  try {
    const bundle = UI_MODE === 'admin'
      ? await api('/api/system/connector/providers/export', { method: 'POST', body: { services: [service] } })
      : await api(`/api/connector/export?service=${encodeURIComponent(service)}&spaceId=${state.spaceId}`,
        { method: 'POST' });
    downloadBundle(bundle, `${service}.connector.json`);
    toast(`已导出 ${service}（仅元数据，不含凭证）`, 'ok');
  } catch (err) {
    toast('导出失败：' + err.message, 'err');
  }
}

let lastImportId = null;

function openImportDrawer() {
  $('#import-json').value = '';
  $('#import-file').value = '';
  $('#import-report').innerHTML = '';
  $('#btn-import-apply').disabled = true;
  lastImportId = null;
  $('#import-drawer-mask').classList.remove('hidden');
  $('#import-drawer').classList.remove('hidden');
}

async function importPreview() {
  const text = $('#import-json').value.trim();
  if (!text) return toast('请粘贴导入包 JSON 或选择文件', 'err');
  let bundle;
  try { bundle = JSON.parse(text); } catch { return toast('导入包不是合法 JSON', 'err'); }
  const btn = $('#btn-import-preview');
  btn.disabled = true;
  try {
    // 管理端 → 官方目录导入（不带 spaceId）；空间端 → 导入当前空间
    const report = UI_MODE === 'admin'
      ? await api('/api/connector/import', { method: 'POST', body: bundle })
      : await api('/api/connector/import/space?spaceId=' + state.spaceId, { method: 'POST', body: bundle });
    lastImportId = report.importId;
    renderImportReport(report);
    const hasChanges = (report.addCount || 0) + (report.updateCount || 0) > 0;
    $('#btn-import-apply').disabled = !hasChanges;
    if (!hasChanges) toast('预览完成：没有需要落库的变更');
  } catch (err) {
    $('#import-report').innerHTML = `<div class="empty-note">${esc(err.message)}</div>`;
    $('#btn-import-apply').disabled = true;
    lastImportId = null;
  } finally {
    btn.disabled = false;
  }
}

function renderImportReport(report) {
  const chipCls = { add: 'ok', update: 'taupe', unchanged: '', skip_protected: 'warn' };
  const opLabel = { add: '新增', update: '更新', unchanged: '不变', skip_protected: '跳过' };
  $('#import-report').innerHTML = `
    <div class="import-counts">
      <span class="chip ok">新增 ${report.addCount || 0}</span>
      <span class="chip taupe">更新 ${report.updateCount || 0}</span>
      <span class="chip">不变 ${report.unchangedCount || 0}</span>
      <span class="chip warn">跳过 ${report.skipProtectedCount || 0}</span>
    </div>
    <table class="kv-table import-items">
      <tr><th>类型</th><th>对象</th><th>操作</th></tr>
      ${(report.items || []).map(it => {
        const op = String(it.op || '').toLowerCase();
        return `<tr>
          <td>${it.type === 'provider' ? '连接器' : '工具'}</td>
          <td>${esc(it.service)}${it.actionKey ? ' / ' + esc(it.actionKey) : ''}
            ${it.reason ? `<div class="import-reason">${esc(it.reason)}</div>` : ''}</td>
          <td><span class="chip ${chipCls[op] ?? ''}">${opLabel[op] || esc(it.op)}</span></td>
        </tr>`;
      }).join('')}
    </table>`;
}

async function importApply() {
  if (!lastImportId) return;
  const btn = $('#btn-import-apply');
  btn.disabled = true;
  try {
    const report = UI_MODE === 'admin'
      ? await api('/api/connector/import/apply', { method: 'POST', body: { importId: lastImportId } })
      : await api('/api/connector/import/space/apply?spaceId=' + state.spaceId, { method: 'POST', body: { importId: lastImportId } });
    toast(`导入完成：新增 ${report.addCount || 0} · 更新 ${report.updateCount || 0} · 跳过 ${report.skipProtectedCount || 0}`, 'ok');
    closeImportDrawer();
    if (typeof refreshAfterWrite === 'function') await refreshAfterWrite();
  } catch (err) {
    toast('导入失败：' + err.message, 'err');
    btn.disabled = false;
  }
}

/* ---------------- OAuth ---------------- */

/** 按 service 在当前页已加载的 provider 列表里找那条记录（各页挂的 state 键不同）。 */
function findProvider(service) {
  // index=adminAll/allProviders，space=catalog/providers，debug=providers；逐个回退，找不到返回 undefined
  return state.adminAll?.find(p => p.service === service)
    || state.providers?.find(p => p.service === service)
    || state.allProviders?.find(p => p.service === service)
    || state.catalog?.find(p => p.service === service);
}

async function startOAuth(service, spaceId) {
  const sid = spaceId ?? state.spaceId;
  const p = findProvider(service);
  // 扫码（oauth2_device）排在最前，且<b>不</b>按 byo/platform 预弹抽屉：这类连接器一律由服务端定调——
  // 名下没有可用的 client_id 就走「扫码现注册」，有就走设备授权。两种形态读的是同一份配置行，
  // 前端根本看不到，猜不出该不该先收 App；猜错的代价是用户被堵在抽屉前，永远走不到注册二维码。
  // 真缺配置时服务端会以 oauth_config_missing 拒绝，下面的 catch 再补开抽屉也不迟。
  if (p?.authType === 'oauth2_device') return startDeviceOAuth(service, sid, p);
  // 授权码 + byo：App 完全由用户提供，没配过就发不出任何授权请求，先弹抽屉把 App 收上来
  if (p?.oauthAppMode === 'byo') return openByoDrawer(service, sid, p);
  await startAuthorizeCode(service, sid, p);
}

/**
 * 授权码流程：换授权链接并开新标签页。
 * <p>与 {@link startDeviceOAuth} 平级，保存 BYO 配置之后也要按类型分派到这里，所以单独抽出来。
 */
async function startAuthorizeCode(service, sid, p) {
  try {
    const data = await api(`/api/connector/oauth/authorize?service=${encodeURIComponent(service)}&spaceId=${sid}`);
    if (!data?.authorizeUrl) throw { message: '未返回授权 URL' };
    toast('已打开授权页；授权完成后回到本页刷新连接列表', 'ok');
    window.open(data.authorizeUrl, '_blank');
  } catch (e) {
    // 兜底：provider 记录没找到（列表还没加载完、或从别的入口进来）时会落在这里，而 byo 连接器
    // 此时必然报「App 未配置」。这种情况该补的是配置抽屉，不是一句让人无从下手的报错。
    if (e.code === 'oauth_config_missing' && p?.oauthAppMode === 'byo') {
      return openByoDrawer(service, sid, p);
    }
    toast('发起授权失败：' + e.message, 'err');
  }
}

/* ---------------- 扫码建连（oauth2_device / 设备码流程） ----------------
   会话是异步的：发起 → 出码 → 每 interval 秒轮询一次，直到终态或窗口过期。
   前端不持有授权 URL（服务端只给 qrUrl），所以这里唯一能做的就是把二维码摆出来、
   把状态与倒计时讲清楚，剩下的交给服务端轮询。 */

let qrTimer = null;          // 下一次轮询的 setTimeout
let qrCountdownTimer = null; // 倒计时 setInterval
let qrState = null;          // 当前会话的 state；置 null 即表示会话已废
let qrService = null;
let qrSpaceId = null;
let qrDeadline = 0;          // 授权窗口截止时间戳（ms）
let qrInterval = 5;          // 当前轮询间隔（秒），服务端可随 slow_down 抬高
let qrMode = null;           // 'register' | 'authorize'，由服务端在 begin 响应里给出

function deviceAuthorizePath(service, spaceId) {
  const q = new URLSearchParams({ service });
  if (spaceId != null) q.set('spaceId', spaceId);
  return '/api/connector/oauth/device/authorize?' + q.toString();
}

async function startDeviceOAuth(service, spaceId, provider) {
  qrService = service;
  qrSpaceId = spaceId;
  try {
    const data = await api(deviceAuthorizePath(service, spaceId));
    if (!data?.state) throw { message: '未返回 state' };
    openQrDrawer(data, service);
  } catch (e) {
    // 服务端说「配置不够」时：byo 的连接器补开抽屉（两个端点填上就能继续），
    // 平台连接器则把服务端那句话原样带出来——它已经点名了缺哪个端点，比笼统劝一句有用。
    if (e.code === 'oauth_config_missing' && provider?.oauthAppMode === 'byo') {
      return openByoDrawer(service, spaceId, provider);
    }
    toast('发起扫码失败：' + e.message, 'err');
  }
}

function openQrDrawer(data, service) {
  qrState = data.state;
  qrMode = data.mode || 'authorize';
  qrInterval = data.interval || 5;
  qrDeadline = Date.now() + (data.expiresIn || 600) * 1000;
  // 两种形态的用户动作不一样：一个是「同意把权限授给平台」，一个是「在飞书里创建一个
  // 归自己所有的应用」。都说成「确认授权」，用户根本不知道自己刚同意了什么。
  // 判据一律用服务端的 mode，不用 provider 配置——同一个连接器会先后经历两种模式。
  const registering = qrMode === 'register';
  $('#qr-drawer-title').textContent = (registering ? '扫码注册应用 · ' : '扫码连接 · ') + service;
  $('#qr-usercode').textContent = data.userCode || '—';
  setQrStatus('', registering
    ? '第 1 步 · 请用飞书扫描下方二维码，在飞书内确认创建应用；平台会把你专属的 App 凭证按用户维度保存'
    : '请用 App 扫描下方二维码，在手机上确认授权');
  const img = $('#qr-img');
  // 同一个 state 下重新加载图片时 URL 完全相同，浏览器可能直接用缓存；
  // 服务端已发 no-store，这里再挂个时间戳把缓存彻底绕开。
  img.src = data.qrUrl + '&_=' + Date.now();
  img.classList.remove('hidden');
  const btn = $('#qr-refresh');
  btn.disabled = false;
  btn.textContent = '重新获取二维码';
  // 每一轮都先收起「继续授权」：它是注册成功后的专属下一步，重取二维码时要退回初始形态，
  // 否则上一轮的按钮会挂在新一轮的会话上。
  // 用 `?.` 而不是断言：这个抽屉由多个页面共用，漏了按钮的页面（如 admin.html 根本没有抽屉）
  // 以前会在这里抛 TypeError，而抛出位置在显示抽屉之前 —— 症状是「点了扫码连接什么都不发生」，
  // 从现象完全看不出是少了个按钮。
  $('#qr-next')?.classList.add('hidden');
  $('#qr-drawer-mask').classList.remove('hidden');
  $('#qr-drawer').classList.remove('hidden');
  startQrCountdown();
  // 首次也等一个 interval 再问：用户不可能在这一瞬间就扫完，立刻轮询只会白拿一次 pending
  scheduleQrPoll(qrInterval * 1000);
}

function setQrStatus(color, text) {
  const el = $('#qr-status');
  el.style.color = color;
  el.textContent = text;
}

function scheduleQrPoll(delayMs) {
  clearTimeout(qrTimer);
  qrTimer = setTimeout(pollDevice, delayMs);
}

async function pollDevice() {
  if (!qrState) return;
  // 窗口已过就别再打服务端了：state 行同样已过期，请求只会换来一个 expired
  if (Date.now() > qrDeadline) {
    return finishQr('#dc2626', '二维码已过期，请点下方按钮重新获取', { hideQr: true, allowRetry: true });
  }
  let view;
  try {
    view = await api('/api/connector/oauth/device/poll', {
      method: 'POST',
      body: { state: qrState, interval: qrInterval },
    });
  } catch (e) {
    // 网络抖动或服务端瞬时超时不该终结会话：授权窗口还剩很久，按原间隔继续试。
    // 服务端那边也照此处理（传输错误当 pending），两侧语义一致。
    setQrStatus('#b45309', '轮询暂时失败，正在重试…（' + e.message + '）');
    return scheduleQrPoll(qrInterval * 1000);
  }
  if (!view) return finishQr('#dc2626', '轮询无响应，请重新获取二维码', { hideQr: true, allowRetry: true });
  if (view.status === 'pending') {
    qrInterval = view.interval || qrInterval;
    return scheduleQrPoll(qrInterval * 1000);
  }
  if (view.status === 'authorized') {
    // 优先用服务端那句话：自注册与授权的终态文案不同（前者是「应用注册完成」），
    // 在这里重新拼一句会把服务端刚区分开的两件事又说回同一句。
    const done = view.message
      || ('已连接' + (view.displayName ? '：' + view.displayName : ''));
    // 注册成功 ≠ 接入完成。这一步只产出 App 身份（client_id/client_secret 已落配置行），既不建连接、
    // 也没有令牌；要拿用户令牌必须再授权一次。而「要不要继续」是用户的决定，所以只把下一步显式
    // 摆出来，不自动重新发起。
    // 这里必须按 qrMode 判断而不是 provider 配置：同一连接器第二次扫码时配置没变、分支已变。
    if (qrMode === 'register') {
      finishQr('#16a34a', done, { hideQr: true, allowRetry: true, nextLabel: '继续授权（以你的身份）' });
    } else {
      finishQr('#16a34a', done, { hideQr: true, allowRetry: true });
    }
    toast(done, 'ok');
    // 让列表上的「已连接」chip 立刻出现，不必用户手动刷新
    if (typeof refreshAfterWrite === 'function') refreshAfterWrite().catch(() => { });
    return;
  }
  const reason = view.message || ({
    denied: '你已拒绝本次授权',
    expired: '二维码已过期，请重新获取',
    already_completed: '本次扫码已在其他页面完成建连',
    failed: '授权失败，请重新获取二维码',
  })[view.status] || '授权未完成';
  finishQr('#dc2626', reason, { hideQr: true, allowRetry: true });
}

/**
 * 结束会话并定格界面。
 * <p>opts.hideQr：把二维码收起来，免得用户对着一个已失效的码再扫一次。
 * opts.allowRetry：保留「重新获取」按钮可点（终态都能重试，只有请求在飞时才禁用）。
 * opts.nextLabel：传入则露出「继续授权」按钮并以此文案命名。用于自注册成功后的下一步——
 * 会话已经结束（state 已消费、定时器已停），但流程还没走完，需要一个显式入口接着走。
 */
function finishQr(color, text, opts = {}) {
  stopQrTimers();
  qrState = null;
  setQrStatus(color, text);
  $('#qr-countdown').textContent = '';
  if (opts.hideQr) $('#qr-img').classList.add('hidden');
  const btn = $('#qr-refresh');
  btn.disabled = !opts.allowRetry;
  if (opts.allowRetry) btn.textContent = '重新获取二维码';
  // 按钮可能不存在（页面没放），所以整段容错：没有这个按钮时「继续授权」这一步就退化掉，
  // 会话本身照常收尾 —— 比在这里抛异常强，抛了会把后面的 toast 与列表刷新一起吞掉。
  const next = $('#qr-next');
  if (next) {
    if (opts.nextLabel) {
      next.textContent = opts.nextLabel;
      next.classList.remove('hidden');
    } else {
      next.classList.add('hidden');
    }
  }
}

function startQrCountdown() {
  clearInterval(qrCountdownTimer);
  const el = $('#qr-countdown');
  const tick = () => {
    const left = Math.max(0, Math.round((qrDeadline - Date.now()) / 1000));
    el.textContent = left > 0 ? `· 剩余 ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}` : '· 已超时';
    if (left <= 0) { clearInterval(qrCountdownTimer); qrCountdownTimer = null; }
  };
  tick();
  qrCountdownTimer = setInterval(tick, 1000);
}

function stopQrTimers() {
  clearTimeout(qrTimer); qrTimer = null;
  clearInterval(qrCountdownTimer); qrCountdownTimer = null;
}

function closeQrDrawer() {
  // 关抽屉必须把两个定时器一起清掉：轮询定时器会在抽屉关掉后继续打服务端直到窗口过期，
  // 倒计时定时器则会一直改一个看不见的节点。qrState 置 null 是第二道闸，
  // 万一有回调已在飞行中，回来后也会因为 qrState 为空而直接返回。
  stopQrTimers();
  qrState = null;
  $('#qr-drawer-mask')?.classList.add('hidden');
  $('#qr-drawer')?.classList.add('hidden');
}

async function refreshQr() {
  if (!qrService) return;
  const btn = $('#qr-refresh');
  btn.disabled = true;
  btn.textContent = '获取中…';
  stopQrTimers();
  qrState = null;
  try {
    const data = await api(deviceAuthorizePath(qrService, qrSpaceId));
    if (!data?.state) throw { message: '未返回 state' };
    openQrDrawer(data, qrService);
  } catch (e) {
    btn.disabled = false;
    btn.textContent = '重新获取二维码';
    setQrStatus('#dc2626', '重新获取失败：' + e.message);
  }
}

/* ---------------- BYO 个人 App 抽屉 ---------------- */

let byoService = null;
let byoSpaceId = null;
let byoProvider = null;   // 这条 byo 连接器的 provider 记录；保存后按它的 authType 决定接着走哪条流程

function openByoDrawer(service, spaceId, provider) {
  byoService = service;
  byoSpaceId = spaceId ?? state.spaceId;
  byoProvider = provider || findProvider(service) || null;
  // 扫码类（oauth2_device）与授权码类的后继动作不同，抽屉里的话得跟着不同：一个说「接着弹二维码」，
  // 一个说「接着开授权页」。说不清楚的话，用户会以为保存完就结束了。
  const device = byoProvider?.authType === 'oauth2_device';
  $('#byo-form').reset();
  $('#byo-client-secret').value = '';
  $('#byo-callback-url').textContent = location.origin + '/api/connector/oauth/callback';
  api('/api/connector/oauth/byo-config?service=' + encodeURIComponent(service))
    .then(cfg => {
      if (!cfg) return;
      $('#byo-client-id').value = cfg.clientId || '';
      $('#byo-authurl').value = cfg.authUrl || '';
      $('#byo-tokenurl').value = cfg.tokenUrl || '';
      $('#byo-scopes').value = (cfg.scopes || []).join(' ');
    })
    .catch(() => { /* 未配置过：留空待填 */ });
  $('#byo-drawer-title').textContent = '个人 App · ' + service;
  // 两句提示用 `?.`：HTML 与 JS 是分开缓存的两个资源，浏览器可能拿着旧 HTML 配新 JS，
  // 那时这两个节点还不存在——为此抛 TypeError 会坏成「点了连接什么都不发生」。
  const hint = $('#byo-drawer-hint');
  if (hint) {
    hint.textContent = '该连接器为 byo 模式：平台不提供公共 App，请填写你自己的 OAuth 应用凭据，'
      + '仅你本人可见（client_secret 加密落库，界面与日志不回显明文）。'
      + (device
        ? '扫码类只需填两个端点；Client ID / Client Secret 留空即可——扫码时平台会用这个用户自己的身份'
          + '注册一个 App，凭据按用户维度保存，保存后会继续弹出二维码。'
        : '');
  }
  const foot = $('#byo-drawer-foot');
  if (foot) {
    foot.textContent = device
      ? '保存后将立即弹出二维码扫码授权；下次连接自动复用你的配置。'
      : '保存后将立即发起 OAuth 授权；下次连接自动复用你的配置。';
  }
  const submit = $('#byo-submit');
  if (submit) {
    submit.textContent = device ? '保存我的 App 并扫码授权' : '保存我的 App 并授权';
  }
  $('#byo-drawer-mask').classList.remove('hidden');
  $('#byo-drawer').classList.remove('hidden');
  setTimeout(() => $('#byo-client-id').focus(), 60);
}

async function saveByoConfig(e) {
  e.preventDefault();
  const service = byoService;
  if (!service) return;
  const clientId = $('#byo-client-id').value.trim();
  const clientSecret = $('#byo-client-secret').value.trim();
  const authUrl = $('#byo-authurl').value.trim();
  const tokenUrl = $('#byo-tokenurl').value.trim();
  // 扫码类允许只填两个端点：这类连接器的 client_id/client_secret 是用户扫码时现注册出来的
  // （平台调注册端点换取，按用户维度存进认证记录），事前无人可填。授权码类没 client_id 则发不出请求。
  const device = byoProvider?.authType === 'oauth2_device';
  if (!authUrl || !tokenUrl || (!device && (!clientId || !clientSecret))) {
    return toast(device
      ? '请填写设备授权端点与令牌端点（Client ID / Client Secret 可留空，扫码时由平台自注册）'
      : '请完整填写 Client ID / Client Secret / 授权端点 / 令牌端点', 'err');
  }
  const scopes = $('#byo-scopes').value.split(/[,\s]+/).map(s => s.trim()).filter(Boolean);
  try {
    await api('/api/connector/oauth/byo-config', {
      method: 'POST',
      body: { service, clientId, clientSecret, authUrl, tokenUrl, scopes: scopes.length ? scopes : undefined },
    });
  } catch (err) {
    // 保存失败就留在抽屉里：填的四个字段还在，用户改一下就能重试，退回页面等于让他重填一遍
    return toast('保存 BYO 配置失败：' + err.message, 'err');
  }
  closeByoDrawer();
  const sid = byoSpaceId ?? state.spaceId;
  // 配置已落库，接下来按类型分派：扫码类继续出二维码（/oauth/authorize 对它是直接拒绝的），
  // 授权码类才去要授权链接。失败要说清「配置已经保存了」，免得用户以为白填了又重填一次。
  if (byoProvider?.authType === 'oauth2_device') {
    return startDeviceOAuth(service, sid, byoProvider);
  }
  try {
    const data = await api(`/api/connector/oauth/authorize?service=${encodeURIComponent(service)}&spaceId=${sid}`);
    if (!data?.authorizeUrl) throw { message: '未返回授权 URL' };
    toast('已保存你的 App 配置并打开授权页', 'ok');
    window.open(data.authorizeUrl, '_blank');
  } catch (err) {
    toast('App 配置已保存，但发起授权失败：' + err.message, 'err');
  }
}

/* ---------------- Tab / 启动（两页共用，enterApp 由各页实现） ---------------- */

function switchTab(name) {
  $$('.tab').forEach(b => b.classList.toggle('active', b.dataset.tab === name));
  $$('.tab-panel').forEach(p => p.classList.toggle('hidden', p.id !== 'tab-' + name));
}

async function bootstrap() {
  if (!state.token) return showLogin();
  try {
    await loadSpaces();
    enterApp();
  } catch (e) {
    if (e.code === '4010' || e.code === '4011') return showLogin();
    showLogin();
    toast('会话校验失败：' + e.message, 'err');
  }
}

/* ---------------- 通用事件绑定（两页共用） ----------------
   页面特有的：目录/连接/发现 → app.js；官方表格/审计 → admin.js。
   元素可能不存在（如管理端无 conn/byo 抽屉），一律容错。 */

function bindCommonEvents() {
  $('#login-form').addEventListener('submit', async e => {
    e.preventDefault();
    const btn = e.target.querySelector('button');
    btn.disabled = true;
    try {
      await doLogin($('#login-phone').value.trim(), $('#login-password').value);
      await loadSpaces();
      enterApp();
    } catch (err) {
      toast('登录失败：' + err.message, 'err');
    } finally {
      btn.disabled = false;
    }
  });
  $('#btn-logout')?.addEventListener('click', logout);
  $('#space-select')?.addEventListener('change', async e => {
    state.spaceId = Number(e.target.value);
    await refreshAll().catch(err => toast(err.message, 'err'));
  });

  // 新增连接器抽屉（空间端新建=空间自建；管理端新建=官方全局，路由在 saveProvider 内）
  $('#btn-new-provider')?.addEventListener('click', () => openProviderDrawer());
  $('#provider-drawer-close')?.addEventListener('click', closeProviderDrawer);
  $('#provider-drawer-mask')?.addEventListener('click', closeProviderDrawer);
  $('#provider-form')?.addEventListener('submit', saveProvider);
  $('#prov-authtype')?.addEventListener('change', () =>
    renderAuthEditor($('#prov-authtype').value, null));
  // 空间端新增：service 失焦自动补 s_ 前缀（幂等，仅对合法输入补，防 123abc→s_123abc 绕过校验），编辑/管理端官方不处理
  $('#prov-service')?.addEventListener('blur', () => {
    if (UI_MODE !== 'space' || providerEditMode) return;
    const inp = $('#prov-service');
    const v = inp.value.trim();
    if (!v || !/^[a-z][a-z0-9_]{2,31}$/.test(v)) return;
    if (!v.startsWith('s_')) inp.value = 's_' + v;
  });

  // 动作编辑抽屉
  $('#action-drawer-close')?.addEventListener('click', closeActionDrawer);
  $('#action-drawer-mask')?.addEventListener('click', closeActionDrawer);
  $('#action-form')?.addEventListener('submit', saveAction);
  $('#add-query')?.addEventListener('click', () => addMappingRow('#spec-query'));
  $('#add-header')?.addEventListener('click', () => addMappingRow('#spec-headers'));
  $('#add-body')?.addEventListener('click', () => addBodyFieldNode($('#spec-body'), null));
  $('#add-arg')?.addEventListener('click', () => addArgNode($('#act-args'), null));

  // 执行类型切换（HTTP 接口 / 绑定插件 / 绑定工作流）
  $('#act-exec-type')?.addEventListener('change', e => {
    const type = e.target.value;
    applyExecTypeUi(type);
    if (type === 'PLUGIN' || type === 'WORKFLOW') {
      $('#act-bind-label').textContent = type === 'WORKFLOW' ? '绑定工作流（仅已发布）' : '绑定插件（仅已发布）';
      $('#act-bind-select').innerHTML = '<option value="">— 请选择 —</option>';
      $('#act-bind-desc').textContent = '';
      loadBindable(type === 'WORKFLOW' ? 'workflow' : 'plugin');
    }
  });
  // 绑定项切换：自动带入该插件/工作流入参作为工具入参（可再编辑）
  $('#act-bind-select')?.addEventListener('change', () => {
    const it = currentBindItem();
    updateBindDesc();
    const argsBox = $('#act-args');
    argsBox.innerHTML = '';
    if (it && Array.isArray(it.inputArgs)) {
      it.inputArgs.forEach(a => addArgNode(argsBox, a));
    }
  });
  $('#act-bind-refresh')?.addEventListener('click', () => {
    loadBindable($('#act-exec-type').value === 'WORKFLOW' ? 'workflow' : 'plugin');
  });
  // 空间筛选（管理端）：切换后按该空间重载已发布插件/工作流，并清空已带入入参
  $('#act-bind-space')?.addEventListener('change', () => {
    $('#act-bind-select').innerHTML = '<option value="">— 请选择 —</option>';
    $('#act-bind-desc').textContent = '';
    $('#act-args').innerHTML = '';
    loadBindable($('#act-exec-type').value === 'WORKFLOW' ? 'workflow' : 'plugin');
  });

  // 导入抽屉
  $('#btn-import')?.addEventListener('click', openImportDrawer);
  $('#import-drawer-close')?.addEventListener('click', closeImportDrawer);
  $('#import-drawer-mask')?.addEventListener('click', closeImportDrawer);
  $('#btn-import-preview')?.addEventListener('click', importPreview);
  $('#btn-import-apply')?.addEventListener('click', importApply);
  $('#import-file')?.addEventListener('change', e => {
    const file = e.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => { $('#import-json').value = String(reader.result || ''); };
    reader.readAsText(file);
  });

  // Body 字段树：类型切换即重建节点形态（子树重置，名称/映射保留）
  document.addEventListener('change', e => {
    if (!(e.target instanceof Element) || !e.target.matches('.bf-node [data-r="type"]')) return;
    const nodeEl = e.target.closest('.bf-node');
    if (nodeEl) setBodyNodeType(nodeEl, e.target.value, nodeEl.classList.contains('bf-item'), null);
  });

  // 通用抽屉开关 + Escape
  $('#drawer-close')?.addEventListener('click', closeDrawer);
  $('#drawer-mask')?.addEventListener('click', closeDrawer);
  $('#conn-drawer-close')?.addEventListener('click', closeConnDrawer);
  $('#conn-drawer-mask')?.addEventListener('click', closeConnDrawer);
  $('#byo-drawer-close')?.addEventListener('click', closeByoDrawer);
  $('#byo-drawer-mask')?.addEventListener('click', closeByoDrawer);
  $('#byo-form')?.addEventListener('submit', saveByoConfig);
  // 扫码抽屉：三处入口都要关（关闭按钮 / 遮罩 / Escape），否则定时器会留在后台跑
  $('#qr-drawer-close')?.addEventListener('click', closeQrDrawer);
  $('#qr-drawer-mask')?.addEventListener('click', closeQrDrawer);
  $('#qr-refresh')?.addEventListener('click', refreshQr);
  // 「继续授权」复用重取二维码那条路径：服务端此刻已能解析出 client_id，同一次请求自然转成
  // 设备授权（mode=authorize）。前端不需要、也不该自己区分这两种发起。
  $('#qr-next')?.addEventListener('click', refreshQr);
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
      closeDrawer(); closeConnDrawer(); closeProviderDrawer();
      closeActionDrawer(); closeImportDrawer(); closeByoDrawer(); closeQrDrawer();
    }
  });

  // 全局委托（两页通用；data-guide 仅空间端，管理端动作行不渲染该按钮）
  document.addEventListener('click', async e => {
    // 调试已是原生 <a target="_blank">：点击仅顺手关掉抽屉，不拦截，导航照常由浏览器完成
    const tryLink = e.target.closest('a[data-try-link]');
    if (tryLink) closeDrawer();
    const t = e.target.closest('button');
    if (!t) return;
    if (t.hasAttribute('data-del-row')) {
      const bodyNode = t.closest('.bf-node');
      (bodyNode || t.closest('.kv-row'))?.remove();
      return;
    }
    if (t.classList.contains('af-add-child')) {
      addArgNode($('.af-children', t.closest('.af-node')), null);
      return;
    }
    if (t.classList.contains('bf-add-child')) {
      addBodyFieldNode($('.bf-children', t.closest('.bf-node')), null);
      return;
    }
    if (t.hasAttribute('data-load-more')) {
      t.disabled = true;
      t.textContent = '加载中…';
      try { await loadMoreCatalog(); } catch (err) { toast(err.message, 'err'); }
      return;
    }
    if (t.dataset.detail) return openDrawer(t.dataset.detail, { spaceId: t.dataset.spaceId || undefined, readonly: true });
    if (t.dataset.oauth) return startOAuth(t.dataset.oauth, t.dataset.spaceId || undefined);
    if (t.dataset.connect) {
      closeDrawer();
      openConnDrawer(t.dataset.connect, t.dataset.spaceId || undefined);
      return;
    }
    if (t.dataset.editProvider) {
      closeDrawer();
      return openProviderDrawer(t.dataset.editProvider, { admin: isOfficialProvider(t.dataset.editProvider) });
    }
    if (t.dataset.delProvider) return deleteProviderConfirmed(t.dataset.delProvider);
    if (t.dataset.export) return exportProvider(t.dataset.export);
    if (t.dataset.addAction) return openActionDrawer(t.dataset.addAction, null);
    if (t.dataset.editAction) {
      const svc = t.dataset.svc;
      // 管理端详情按 includeDisabled 变体缓存（service::admin），回退取之，保证编辑抽屉能打开
      const detail = state.providerDetailCache.get(svc) || state.providerDetailCache.get(svc + '::admin');
      const action = detail?.actions?.find(a => String(a.id) === String(t.dataset.editAction));
      if (action) openActionDrawer(svc, action);
      return;
    }
    if (t.dataset.delAction) return deleteActionConfirmed(t.dataset.delAction, t.dataset.svc);
    if (t.dataset.toggleAct) {
      const id = t.dataset.toggleAct, svc = t.dataset.svc, enabled = t.dataset.target === 'enabled';
      const official = isOfficialProvider(svc);
      try {
        if (official) {
          await api(`/api/system/connector/actions/${encodeURIComponent(id)}/status?enabled=${enabled}`, { method: 'PUT' });
        } else {
          await api(`/api/connector/actions/${encodeURIComponent(id)}/status?enabled=${enabled}`, { method: 'POST' });
        }
        invalidateProviderDetail(svc);
        openDrawer(svc, { admin: official });
        toast(enabled ? '已启用该工具' : '已停用该工具', 'ok');
      } catch (err) { toast(err.message, 'err'); }
      return;
    }
    if (UI_MODE === 'space' && !window.OVERVIEW_MODE && t.hasAttribute('data-guide') && t.dataset.guide !== undefined && t.dataset.guide !== null) {
      switchTab('discover');
      return showGuide(t.dataset.guide, t.dataset.action);
    }
    if (t.dataset.try) {
      closeDrawer();
      window.open('debug.html?service=' + encodeURIComponent(t.dataset.try)
        + '&actionKey=' + encodeURIComponent(t.dataset.action || '')
        + '&spaceId=' + (t.dataset.spaceId || state.spaceId), '_blank');
      return;
    }
  });
}

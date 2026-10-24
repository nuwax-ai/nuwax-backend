'use strict';
/* ============================================================
   女娲 · 连接器执行调试（独立页）
   同源调用 /api/connector/* 与 /api/user/*；凭证明文不出连接器模块边界。
   通过 URL ?service=&actionKey=&spaceId= 携带上下文，供动作行「调试」跳转。
   ============================================================ */

const $ = (sel, el = document) => el.querySelector(sel);
const $$ = (sel, el = document) => Array.from(el.querySelectorAll(sel));

const state = {
  token: localStorage.getItem('nx.connector.token') || null,
  phone: localStorage.getItem('nx.connector.phone') || '',
  spaces: [],
  spaceId: null,
  providers: [],          // 当前空间 provider 全量列表
  connections: [],        // 当前用户连接
};

const providerDetailCache = new Map();

/* ---------------- 基础工具 ---------------- */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

let toastTimer = null;
function toast(msg, kind = '') {
  const el = $('#toast');
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
  })).filter(s => s.id != null);
  if (!state.spaces.length) throw { code: 'space', message: '当前账号没有可用空间' };
  state.spaceId = state.spaces[0].id;
}

function showLogin() {
  $('#view-app').classList.add('hidden');
  $('#view-login').classList.remove('hidden');
  if (state.phone) $('#login-phone').value = state.phone;
}

async function bootstrap() {
  if (!state.token) return showLogin();
  try {
    await loadSpaces();
    applyUrlSpace();
    enterApp();
  } catch (e) {
    if (e.code === '4010' || e.code === '4011') return showLogin();
    showLogin();
    toast('会话校验失败：' + e.message, 'err');
  }
}

/* ---------------- 调试 ---------------- */

async function loadConnections() {
  const data = await api('/api/connector/connections?spaceId=' + state.spaceId);
  state.connections = Array.isArray(data) ? data : [];
}

async function providerDetail(service) {
  if (providerDetailCache.has(service)) return providerDetailCache.get(service);
  const qs = new URLSearchParams({ spaceId: state.spaceId });
  const data = await api(`/api/connector/providers/${encodeURIComponent(service)}?${qs}`);
  providerDetailCache.set(service, data);
  return data;
}

/** 全量拉取当前空间连接器（pageSize=2000，端点无上限），回显 preselectService。 */
async function loadAllProviders(preselectService, preselectAction) {
  const qs = new URLSearchParams({ spaceId: state.spaceId, pageNum: 1, pageSize: 2000 });
  const data = await api('/api/connector/providers?' + qs);
  state.providers = data?.records ?? (Array.isArray(data) ? data : []);
  const sel = $('#dbg-provider');
  sel.innerHTML = state.providers.length
    ? state.providers.map(p => `<option value="${esc(p.service)}">${esc(p.displayName || p.service)}</option>`).join('')
    : '<option value="">目录为空</option>';
  if (preselectService && state.providers.some(p => p.service === preselectService)) {
    sel.value = preselectService;
  }
  if (sel.value) await loadActions(sel.value, preselectAction);
}

async function loadActions(service, preselectAction) {
  const actionSel = $('#dbg-action');
  actionSel.innerHTML = '<option>加载中…</option>';
  try {
    const detail = await providerDetail(service);
    const actions = detail.actions || [];
    actionSel.innerHTML = actions.length
      ? actions.map(a => `<option value="${esc(a.actionKey)}" data-args='${esc(JSON.stringify(a.inputArgs || {}))}'>${esc(a.name || a.actionKey)}（${esc(a.actionKey)}）</option>`).join('')
      : '<option value="">该连接器没有动作</option>';
    if (preselectAction && actions.some(a => a.actionKey === preselectAction)) {
      actionSel.value = preselectAction;
    }
    fillConnectionSelect(service);
    prefillArgs();
  } catch (e) {
    actionSel.innerHTML = `<option value="">${esc(e.message)}</option>`;
  }
}

function fillConnectionSelect(service) {
  const sel = $('#dbg-connection');
  const mine = state.connections.filter(c => c.providerService === service);
  const provider = state.providers.find(p => p.service === service);
  const options = ['<option value="">自动（第一个 active 连接）</option>'];
  if (provider?.authType === 'no_auth') options.push('<option value="__none__">不使用连接（免鉴权）</option>');
  mine.forEach(c => options.push(`<option value="${c.id}">#${c.id} ${esc(c.name || c.providerService)}</option>`));
  sel.innerHTML = options.join('');
}

function prefillArgs() {
  const opt = $('#dbg-action').selectedOptions[0];
  const ta = $('#dbg-args');
  if (!opt || (ta.value.trim() !== '' && ta.value.trim() !== '{}')) return;
  try {
    const inputArgs = JSON.parse(opt.dataset.args || '{}');
    // 类型化 Arg 数组 → 取各参数名；旧扁平 map → 取键
    const keys = Array.isArray(inputArgs)
      ? inputArgs.map(a => a && a.name).filter(Boolean)
      : Object.keys(inputArgs);
    ta.value = keys.length
      ? JSON.stringify(Object.fromEntries(keys.map(k => [k, ''])), null, 2)
      : '{}';
  } catch { ta.value = '{}'; }
}

async function runExecute(e) {
  e.preventDefault();
  const service = $('#dbg-provider').value;
  const actionKey = $('#dbg-action').value;
  if (!service || !actionKey) return toast('请选择连接器与动作', 'err');
  let args = {};
  try {
    args = JSON.parse($('#dbg-args').value || '{}');
  } catch { return toast('输入参数不是合法 JSON', 'err'); }
  const connVal = $('#dbg-connection').value;
  const body = { providerService: service, actionKey, args };
  if (connVal && connVal !== '__none__') body.connectionId = Number(connVal);

  const statusChip = $('#dbg-status');
  const box = $('#dbg-result');
  statusChip.className = 'chip';
  statusChip.textContent = '执行中';
  box.textContent = '正在执行…';
  try {
    const data = await api('/api/connector/runtime/execute', { method: 'POST', body });
    renderResult(data);
  } catch (err) {
    statusChip.className = 'chip err';
    statusChip.textContent = err.code;
    box.textContent = err.message;
  }
}

function renderResult(r) {
  const chip = $('#dbg-status');
  if (r.success) {
    chip.className = 'chip ok';
    chip.textContent = 'SUCCESS';
  } else if (r.errorCode === 'ssrf_blocked') {
    chip.className = 'chip warn';
    chip.textContent = 'POLICY · ' + r.errorCode;
  } else {
    chip.className = 'chip err';
    chip.textContent = r.errorCode || 'FAILED';
  }
  $('#dbg-result').textContent = JSON.stringify(r, null, 2);
}

/** URL 参数：spaceId 命中空间列表则选中（否则保持第一个空间）。 */
function applyUrlSpace() {
  const urlSpaceId = new URLSearchParams(location.search).get('spaceId');
  if (urlSpaceId && state.spaces.some(s => String(s.id) === urlSpaceId)) {
    state.spaceId = Number(urlSpaceId);
  }
}

function enterApp() {
  $('#view-login').classList.add('hidden');
  $('#view-app').classList.remove('hidden');
  $('#user-badge').textContent = state.phone;
  const sel = $('#dbg-space');
  sel.innerHTML = state.spaces.map(s =>
    `<option value="${s.id}">${esc(s.name)}</option>`).join('');
  sel.value = String(state.spaceId);

  const params = new URLSearchParams(location.search);
  const service = params.get('service') || '';
  const actionKey = params.get('actionKey') || '';
  Promise.resolve()
    .then(() => loadConnections())
    .then(() => loadAllProviders(service, actionKey))
    .catch(e => toast(e.message, 'err'));
}

/* ---------------- 事件绑定 ---------------- */

$('#btn-logout').addEventListener('click', logout);

$('#login-form').addEventListener('submit', async e => {
  e.preventDefault();
  const btn = e.target.querySelector('button[type=submit]');
  btn.disabled = true;
  try {
    await doLogin($('#login-phone').value.trim(), $('#login-password').value);
    await loadSpaces();
    applyUrlSpace();
    enterApp();
  } catch (err) {
    toast('登录失败：' + err.message, 'err');
  } finally {
    btn.disabled = false;
  }
});

$('#dbg-space').addEventListener('change', async e => {
  state.spaceId = Number(e.target.value);
  providerDetailCache.clear();
  try {
    await loadConnections();
    await loadAllProviders('', '');
  } catch (err) {
    toast(err.message, 'err');
  }
});

$('#dbg-provider').addEventListener('change', e => {
  if (e.target.value) loadActions(e.target.value);
});

$('#dbg-action').addEventListener('change', prefillArgs);

$('#dbg-form').addEventListener('submit', runExecute);

bootstrap();

'use strict';
/* ============================================================
   女娲 · 连接器 —— 官方管理端（admin.html）
   官方全局目录维护（新增 / 编辑 / 启停用 / 动作维护 / 导入官方包）+ 执行审计。
   登录、状态、抽屉、表单编辑器、保存路由等共用逻辑在 common.js。
   ============================================================ */

const ADMIN_PAGE_SIZE = 20;
const RUNS_PAGE_SIZE = 20;

state.adminAll = [];          // 官方全量 provider（一次拉取，前端筛选/翻页/排序）
state.adminPage = 1;
state.adminTotal = 0;
state.adminKeyword = '';
state.adminStatus = '';       // 状态筛选：''=全部 / enabled / disabled
state.adminAuthType = '';     // 认证方式筛选：''=全部 / no_auth / api_key / bearer / oauth2 / oauth2_device / custom
state.selected = new Set();   // 勾选的 service（批量导出）
state.sortDirty = false;      // 排序有未保存变更
state.runs = [];
state.runsPage = 1;
state.runsTotal = 0;

/** 写操作（保存/删除/导入）后刷新；common.js 保存逻辑统一回调 */
async function refreshAfterWrite() {
  await loadAdmin();
}

function enterApp() {
  $('#view-login').classList.add('hidden');
  $('#view-app').classList.remove('hidden');
  $('#user-badge').textContent = state.phone;
  switchTab('official');
  Promise.all([loadAdmin(), loadRuns()]).catch(e => toast(e.message, 'err'));
}

/* ---------------- 官方目录 ---------------- */

/** 官方全量：走管理侧官方全量端点（含停用，按 sort_order，附工具数） */
async function loadAdmin() {
  const data = await api('/api/system/connector/providers');
  state.adminAll = Array.isArray(data) ? data : [];
  state.adminPage = 1;
  renderAdmin();
}

/** 任一筛选激活时禁用排序（视图顺序 ≠ 全量顺序，避免歧义） */
function adminFilterActive() {
  return !!(state.adminKeyword || state.adminStatus || state.adminAuthType);
}

function adminFiltered() {
  // 管理端只看官方全局（space_id=0/null）；空间自建归空间端维护
  let list = state.adminAll.filter(p => !p.spaceId);
  if (state.adminStatus) list = list.filter(p => p.status === state.adminStatus);
  if (state.adminAuthType) list = list.filter(p => p.authType === state.adminAuthType);
  if (state.adminKeyword) {
    const kw = state.adminKeyword.toLowerCase();
    list = list.filter(p =>
      String(p.displayName || '').toLowerCase().includes(kw)
      || String(p.service || '').toLowerCase().includes(kw));
  }
  return list;
}

function renderAdmin() {
  const tbody = $('#admin-table tbody');
  const list = adminFiltered();
  const filterActive = adminFilterActive();
  state.adminTotal = list.length;
  const pages = Math.max(1, Math.ceil(list.length / ADMIN_PAGE_SIZE));
  if (state.adminPage > pages) state.adminPage = pages;
  const start = (state.adminPage - 1) * ADMIN_PAGE_SIZE;
  const rows = list.slice(start, start + ADMIN_PAGE_SIZE);
  tbody.innerHTML = rows.length
    ? rows.map(p => adminRow(p, filterActive)).join('')
    : '<tr><td colspan="9" class="admin-empty">官方目录为空 —— 点「新增官方连接器」创建，或「导入官方包」批量接入</td></tr>';
  const checkAll = $('#admin-check-all');
  if (checkAll) checkAll.checked = list.length > 0 && list.every(p => state.selected.has(p.service));
  const saveBtn = $('#btn-save-order');
  if (saveBtn) {
    saveBtn.classList.toggle('is-dirty', state.sortDirty);
    saveBtn.disabled = filterActive;
    saveBtn.title = filterActive
      ? '筛选激活时排序禁用，请先清除筛选'
      : (state.sortDirty ? '排序有未保存变更，点击提交' : '');
  }
  renderAdminPager(pages);
}

function adminRow(p, filterActive) {
  const checked = state.selected.has(p.service) ? ' checked' : '';
  const sortOps = filterActive
    ? '<span class="admin-muted">筛选时禁用</span>'
    : `<button class="btn btn-outline btn-xs" data-sort-top="${esc(p.service)}" title="置顶">⇈</button>
       <button class="btn btn-outline btn-xs" data-sort-up="${esc(p.service)}" title="上移">↑</button>
       <button class="btn btn-outline btn-xs" data-sort-down="${esc(p.service)}" title="下移">↓</button>
       <button class="btn btn-outline btn-xs" data-sort-bottom="${esc(p.service)}" title="置底">⇊</button>`;
  return `<tr data-svc="${esc(p.service)}" draggable="${!filterActive}">
    <td><input type="checkbox" class="admin-row-check" data-check="${esc(p.service)}"${checked}></td>
    <td><div class="admin-name">${esc(p.displayName || p.service)}</div>
      ${p.category ? `<div class="admin-sub">${esc(p.category)}</div>` : ''}</td>
    <td><span class="admin-mono">${esc(p.service)}</span></td>
    <td>${esc(AUTH_LABEL[p.authType] || p.authType || '—')}</td>
    <td>${p.actionCount ?? '—'}</td>
    <td><span class="chip ${p.status === 'enabled' ? 'ok' : ''}">${p.status === 'enabled' ? '启用' : '停用'}</span></td>
    <td class="admin-time">${fmtTime(p.modified)}</td>
    <td class="admin-sort-ops" title="${filterActive ? '筛选激活时排序禁用' : '拖拽整行或点按钮排序'}">${sortOps}</td>
    <td class="admin-ops">
      <button class="btn btn-outline btn-sm" data-admin-view="${esc(p.service)}">查看</button>
      <button class="btn btn-outline btn-sm" data-admin-edit="${esc(p.service)}">编辑</button>
      <button class="btn btn-outline btn-sm" data-admin-export="${esc(p.service)}">导出</button>
      <button class="btn btn-outline btn-sm" data-admin-toggle="${esc(p.service)}"
        data-target="${p.status === 'enabled' ? 'disabled' : 'enabled'}">${p.status === 'enabled' ? '停用' : '启用'}</button>
    </td>
  </tr>`;
}

function renderAdminPager(pages) {
  $('#admin-pager').innerHTML = `
    <button class="btn btn-outline btn-sm" data-admin-prev ${state.adminPage <= 1 ? 'disabled' : ''}>上一页</button>
    <span class="admin-pageinfo">第 ${state.adminPage} / ${pages} 页 · 共 ${state.adminTotal} 个</span>
    <button class="btn btn-outline btn-sm" data-admin-next ${state.adminPage >= pages ? 'disabled' : ''}>下一页</button>`;
}

/* ---------------- 排序管理（按钮 + 拖拽，跨页用按钮） ---------------- */

/** 在官方全量数组中移动某项：mode = up / down / top / bottom */
function moveItemInAll(svc, mode) {
  const list = state.adminAll;
  const i = list.findIndex(p => p.service === svc);
  if (i < 0) return;
  let target = null;
  if (mode === 'top') target = i > 0 ? 0 : null;
  else if (mode === 'bottom') target = i < list.length - 1 ? list.length - 1 : null;
  else if (mode === 'up') target = i > 0 ? i - 1 : null;
  else if (mode === 'down') target = i < list.length - 1 ? i + 1 : null;
  if (target == null) return;
  const [item] = list.splice(i, 1);
  list.splice(target, 0, item);
  state.sortDirty = true;
  renderAdmin();
}

/** 拖拽落下：把 moveSvc 插到 targetSvc 前/后（before 由鼠标在目标行上下半区决定） */
function dropItemAt(moveSvc, targetSvc, before) {
  const list = state.adminAll;
  const from = list.findIndex(p => p.service === moveSvc);
  if (from < 0 || moveSvc === targetSvc) return;
  const [item] = list.splice(from, 1);
  const to = list.findIndex(p => p.service === targetSvc);
  if (to < 0) {
    list.splice(0, 0, item);
  } else {
    list.splice(before ? to : to + 1, 0, item);
  }
  state.sortDirty = true;
  renderAdmin();
}

function bindAdminDrag() {
  const tbody = $('#admin-table tbody');
  tbody.addEventListener('dragstart', e => {
    const tr = e.target.closest('tr[data-svc]');
    if (!tr || adminFilterActive()) { e.preventDefault(); return; }
    state.dragService = tr.dataset.svc;
    tr.classList.add('dragging');
    e.dataTransfer.effectAllowed = 'move';
  });
  tbody.addEventListener('dragover', e => {
    e.preventDefault();
    const tr = e.target.closest('tr[data-svc]');
    if (!tr || !state.dragService || tr.dataset.svc === state.dragService) return;
    tr.classList.add('drag-over');
  });
  tbody.addEventListener('dragleave', e => {
    const tr = e.target.closest('tr[data-svc]');
    if (tr) tr.classList.remove('drag-over');
  });
  tbody.addEventListener('drop', e => {
    e.preventDefault();
    const tr = e.target.closest('tr[data-svc]');
    if (!tr || !state.dragService) return;
    const before = e.clientY < tr.getBoundingClientRect().top + tr.offsetHeight / 2;
    dropItemAt(state.dragService, tr.dataset.svc, before);
  });
  tbody.addEventListener('dragend', () => {
    state.dragService = null;
    $$('#admin-table tbody tr').forEach(r => r.classList.remove('dragging', 'drag-over'));
  });
}

/* ---------------- 批量导出 ---------------- */

async function exportSelected() {
  if (!state.selected.size) return toast('请先勾选要导出的行', 'err');
  await exportServices(Array.from(state.selected));
}

async function exportAll() {
  await exportServices(null); // 空 = 全部官方
}

async function exportServices(services) {
  try {
    const bundle = await api('/api/system/connector/providers/export', {
      method: 'POST',
      body: services ? { services } : undefined,
    });
    downloadBundle(bundle, services
      ? `connector-export-${services.length}.json`
      : 'connector-export-all.json');
    toast(services
      ? `已导出所选 ${services.length} 个连接器（仅元数据，不含凭证）`
      : '已导出全部官方连接器（仅元数据，不含凭证）', 'ok');
  } catch (err) {
    toast('导出失败：' + err.message, 'err');
  }
}

async function saveOrder() {
  if (adminFilterActive()) return toast('请先清除筛选再保存排序', 'err');
  if (!state.sortDirty) return toast('排序没有变更', 'info');
  try {
    await api('/api/system/connector/providers/order', {
      method: 'PUT',
      body: { services: state.adminAll.map(p => p.service) },
    });
    state.sortDirty = false;
    renderAdmin();
    toast('排序已保存，全局目录生效', 'ok');
  } catch (err) {
    toast('保存排序失败：' + err.message, 'err');
  }
}

/* ---------------- 执行审计 ---------------- */

async function loadRuns(page) {
  const p = page || 1;
  const qs = new URLSearchParams({ pageNum: p, pageSize: RUNS_PAGE_SIZE });
  const data = await api('/api/system/connector/runs?' + qs);
  state.runs = data?.records ?? [];
  state.runsTotal = data?.total ?? state.runs.length;
  state.runsPage = p;
  renderRuns();
}

function renderRuns() {
  const tbody = $('#runs-table tbody');
  $('#runs-summary').textContent = state.runsTotal ? `共 ${state.runsTotal} 条执行记录` : '';
  const statusCls = { success: 'ok', failed: 'err', policy_denied: 'warn' };
  tbody.innerHTML = state.runs.length
    ? state.runs.map(r => `<tr>
        <td class="admin-mono">${r.id}</td>
        <td class="admin-mono">${r.connectionId ?? '—'}</td>
        <td class="admin-mono">${r.actionId ?? '—'}</td>
        <td class="admin-mono">${r.agentId ?? '—'}</td>
        <td><span class="chip ${statusCls[r.status] || ''}">${esc(r.status || '—')}</span></td>
        <td class="admin-mono">${esc(r.errorCode || '—')}</td>
        <td>${r.durationMs != null ? r.durationMs + ' ms' : '—'}</td>
        <td class="admin-time">${fmtTime(r.created)}</td>
      </tr>`).join('')
    : '<tr><td colspan="8" class="admin-empty">暂无执行记录</td></tr>';
  const pages = Math.max(1, Math.ceil(state.runsTotal / RUNS_PAGE_SIZE));
  $('#runs-pager').innerHTML = `
    <button class="btn btn-outline btn-sm" data-runs-prev ${state.runsPage <= 1 ? 'disabled' : ''}>上一页</button>
    <span class="admin-pageinfo">第 ${state.runsPage} / ${pages} 页</span>
    <button class="btn btn-outline btn-sm" data-runs-next ${state.runsPage >= pages ? 'disabled' : ''}>下一页</button>`;
}

/* ---------------- 事件绑定（管理端特有） ---------------- */

function bindEvents() {
  $$('.tab').forEach(b => b.addEventListener('click', () => switchTab(b.dataset.tab)));

  let adminSearchTimer = null;
  $('#admin-keyword').addEventListener('input', e => {
    clearTimeout(adminSearchTimer);
    adminSearchTimer = setTimeout(() => {
      state.adminKeyword = e.target.value.trim();
      state.adminPage = 1;
      renderAdmin();
    }, 250);
  });

  $('#admin-status-filter').addEventListener('change', e => {
    state.adminStatus = e.target.value;
    state.adminPage = 1;
    renderAdmin();
  });
  $('#admin-auth-filter').addEventListener('change', e => {
    state.adminAuthType = e.target.value;
    state.adminPage = 1;
    renderAdmin();
  });

  $('#btn-save-order').addEventListener('click', saveOrder);
  $('#btn-export-selected').addEventListener('click', exportSelected);
  $('#btn-export-all').addEventListener('click', exportAll);
  bindAdminDrag();

  // checkbox 多选（表头全选 + 行勾选）
  document.addEventListener('change', e => {
    if (e.target.id === 'admin-check-all') {
      const on = e.target.checked;
      adminFiltered().forEach(p => on ? state.selected.add(p.service) : state.selected.delete(p.service));
      renderAdmin();
      return;
    }
    if (e.target.classList && e.target.classList.contains('admin-row-check')) {
      const svc = e.target.dataset.check;
      if (e.target.checked) state.selected.add(svc); else state.selected.delete(svc);
      const list = adminFiltered();
      const checkAll = $('#admin-check-all');
      if (checkAll) checkAll.checked = list.length > 0 && list.every(p => state.selected.has(p.service));
      return;
    }
  });

  $('#btn-refresh-runs').addEventListener('click', () =>
    loadRuns(state.runsPage).catch(err => toast(err.message, 'err')));

  document.addEventListener('click', async e => {
    const t = e.target.closest('button');
    if (!t) return;
    // 官方目录：翻页 / 排序按钮 / 行操作 / 启停用
    if (t.hasAttribute('data-admin-prev')) {
      if (state.adminPage > 1) { state.adminPage--; renderAdmin(); }
      return;
    }
    if (t.hasAttribute('data-admin-next')) {
      if (state.adminPage < Math.max(1, Math.ceil(state.adminTotal / ADMIN_PAGE_SIZE))) {
        state.adminPage++; renderAdmin();
      }
      return;
    }
    if (t.hasAttribute('data-sort-top')) return moveItemInAll(t.dataset.sortTop, 'top');
    if (t.hasAttribute('data-sort-up')) return moveItemInAll(t.dataset.sortUp, 'up');
    if (t.hasAttribute('data-sort-down')) return moveItemInAll(t.dataset.sortDown, 'down');
    if (t.hasAttribute('data-sort-bottom')) return moveItemInAll(t.dataset.sortBottom, 'bottom');
    if (t.dataset.adminView) return openDrawer(t.dataset.adminView, { admin: true });
    if (t.dataset.adminEdit) { closeDrawer(); return openProviderDrawer(t.dataset.adminEdit, { admin: true }); }
    if (t.dataset.adminExport) return exportProvider(t.dataset.adminExport);
    if (t.dataset.adminToggle) {
      const svc = t.dataset.adminToggle;
      const enabled = t.dataset.target === 'enabled';
      if (!enabled && !confirm(`停用官方连接器 ${svc}？停用后将从官方目录移除，全空间不可见。`)) return;
      try {
        await api(`/api/system/connector/providers/${encodeURIComponent(svc)}?enabled=${enabled}`, { method: 'PUT' });
        toast(enabled ? `官方连接器 ${svc} 已启用` : `官方连接器 ${svc} 已停用（从官方目录移除）`, 'ok');
        await loadAdmin();
      } catch (err) {
        toast('操作失败：' + err.message, 'err');
      }
      return;
    }
    // 审计分页
    if (t.hasAttribute('data-runs-prev')) {
      if (state.runsPage > 1) loadRuns(state.runsPage - 1).catch(err => toast(err.message, 'err'));
      return;
    }
    if (t.hasAttribute('data-runs-next')) {
      if (state.runsPage < Math.max(1, Math.ceil(state.runsTotal / RUNS_PAGE_SIZE))) {
        loadRuns(state.runsPage + 1).catch(err => toast(err.message, 'err'));
      }
    }
  });
}

/* ---------------- 启动 ---------------- */

bindCommonEvents();
bindEvents();
bootstrap();

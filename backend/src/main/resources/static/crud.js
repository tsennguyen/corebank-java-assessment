/**
 * Core Banking System — data management view (CRUD for customers, accounts, transactions,
 * recurring transactions and customer types). Reuses apiFetch/esc/fmtMoney/showBanner from app.js.
 * Every call prints the real request and response in #dataResult so reviewers can see the API.
 */
const crudState = { customerPage: 0, customerTotalPages: 1, log: [] };

function val(id) {
  const el = document.getElementById(id);
  return el ? el.value.trim() : '';
}

function setVal(id, value) {
  const el = document.getElementById(id);
  if (el) el.value = value === null || value === undefined ? '' : value;
}

function compact(obj) {
  const out = {};
  Object.keys(obj).forEach((k) => {
    if (obj[k] !== '' && obj[k] !== null && obj[k] !== undefined) out[k] = obj[k];
  });
  return out;
}

function smallBtn(label, handler, kind = 'btn-outline') {
  return `<button class="${kind}" style="padding:0.25rem 0.5rem;font-size:0.76rem;" onclick="${handler}">${label}</button>`;
}

function statusBadge(status) {
  const ok = ['ACTIVE', 'COMPLETED', 'RESOLVED'];
  const err = ['CLOSED', 'CANCELLED', 'FAILED'];
  const cls = ok.includes(status) ? 'badge-ok' : err.includes(status) ? 'badge-err' : 'badge-warn';
  return `<span class="badge ${cls}">${esc(status)}</span>`;
}

async function callApi(method, url, body, extraHeaders = {}) {
  if (!accessToken) {
    showBanner('Vui lòng đăng nhập ADMIN trước.', true);
    return { ok: false, status: 0, body: null };
  }
  const headers = Object.assign({}, extraHeaders);
  const options = { method, headers };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await apiFetch(url, options);
  } catch (err) {
    showBanner(`Lỗi kết nối: ${err.message}`, true);
    return { ok: false, status: 0, body: null };
  }
  const text = res.status === 204 ? '' : await res.text();
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch (e) {
    parsed = text;
  }
  const out = document.getElementById('dataResult');
  if (out) {
    // Newest call first; keep a short history so a create/update stays visible after the list reloads.
    const reqLine = `${method} ${url}${body !== undefined ? '\n' + JSON.stringify(body, null, 2) : ''}`;
    const resText = parsed === null ? '(no body)' : JSON.stringify(parsed, null, 2);
    const time = new Date().toLocaleTimeString('vi-VN');
    crudState.log.unshift(
      `[${time}] ${reqLine}\n→ HTTP ${res.status}\n${resText.length > 2500 ? resText.slice(0, 2500) + '\n…' : resText}`
    );
    crudState.log = crudState.log.slice(0, 6);
    out.style.display = 'block';
    out.textContent = crudState.log.join('\n\n────────────────────────────────────────\n\n');
  }
  if (!res.ok) showBanner(`${method} ${url} thất bại (${formatProblemDetail(res.status, parsed)})`, true);
  return { ok: res.ok, status: res.status, body: parsed };
}

/* ------------------------------------------------------------------ Customers */
async function loadCustomers(page = crudState.customerPage) {
  const params = new URLSearchParams({ page: String(page), size: '8', sort: 'id,desc' });
  if (val('custSearchName')) params.set('name', val('custSearchName'));
  if (val('custSearchLocation')) params.set('location', val('custSearchLocation'));
  if (val('custSearchType')) params.set('customerTypeCode', val('custSearchType'));
  const { ok, body } = await callApi('GET', `/api/v1/customers?${params}`);
  const tbody = document.getElementById('customerTableBody');
  if (!ok || !tbody) return;
  crudState.customerPage = body.page;
  crudState.customerTotalPages = Math.max(body.totalPages, 1);
  document.getElementById('customerPageInfo').textContent =
    `Trang ${body.page + 1}/${crudState.customerTotalPages} · ${body.totalElements} khách hàng`;
  const rows = body.content || [];
  tbody.innerHTML = rows.length
    ? rows
        .map(
          (c) => `<tr>
        <td>#${esc(c.id)}</td>
        <td><code>${esc(c.customerCode)}</code></td>
        <td>${esc(c.fullName)}</td>
        <td><code>${esc(c.nationalId)}</code></td>
        <td>${esc(c.location)}</td>
        <td>${esc(c.customerTypeCode || '—')}</td>
        <td>v${esc(c.version)}</td>
        <td class="row" style="gap:0.3rem;">
          ${smallBtn('Sửa', `editCustomer(${Number(c.id)})`)}
          ${smallBtn('Xem TK', `showCustomerAccounts(${Number(c.id)})`)}
          ${smallBtn('Xoá', `deleteCustomer(${Number(c.id)})`)}
        </td>
      </tr>`
        )
        .join('')
    : '<tr><td colspan="8" style="color: var(--muted);">Không có khách hàng khớp bộ lọc.</td></tr>';
}

function customerPage(delta) {
  const next = crudState.customerPage + delta;
  if (next < 0 || next >= crudState.customerTotalPages) return;
  loadCustomers(next);
}

function resetCustomerForm() {
  ['custId', 'custVersion', 'custFullName', 'custNationalId', 'custEmail', 'custPhone', 'custAddress', 'custDob'].forEach((id) =>
    setVal(id, '')
  );
  setVal('custLocation', 'Hà Nội');
  setVal('custType', 'INDIVIDUAL');
  document.getElementById('custNationalId').disabled = false;
  document.getElementById('customerFormTitle').textContent = 'Tạo khách hàng mới';
}

async function editCustomer(id) {
  const { ok, body } = await callApi('GET', `/api/v1/customers/${id}`);
  if (!ok) return;
  setVal('custId', body.id);
  setVal('custVersion', body.version);
  setVal('custFullName', body.fullName);
  setVal('custNationalId', body.nationalId);
  setVal('custEmail', body.email);
  setVal('custPhone', body.phone);
  setVal('custAddress', body.address);
  setVal('custLocation', body.location);
  setVal('custDob', body.dateOfBirth);
  setVal('custType', body.customerTypeCode || 'INDIVIDUAL');
  document.getElementById('custNationalId').disabled = true;
  document.getElementById('customerFormTitle').textContent = `Sửa khách hàng #${body.id} (version ${body.version})`;
}

async function saveCustomer() {
  const id = val('custId');
  const common = {
    fullName: val('custFullName'),
    email: val('custEmail'),
    phone: val('custPhone'),
    address: val('custAddress'),
    location: val('custLocation'),
    dateOfBirth: val('custDob'),
    customerTypeCode: val('custType'),
  };
  const result = id
    ? await callApi('PUT', `/api/v1/customers/${id}`, compact(Object.assign(common, { version: Number(val('custVersion')) })))
    : await callApi('POST', '/api/v1/customers', compact(Object.assign(common, { nationalId: val('custNationalId') })));
  if (!result.ok) return;
  showBanner(id ? `Đã cập nhật khách hàng #${id} (version mới ${result.body.version}).` : `Đã tạo khách hàng #${result.body.id} (${result.body.customerCode}).`);
  if (!id) setVal('accCustomerId', result.body.id);
  resetCustomerForm();
  await loadCustomers(0);
}

async function deleteCustomer(id) {
  if (!confirm(`Xoá mềm khách hàng #${id}? Bản ghi vẫn giữ trong DB để kiểm toán.`)) return;
  const { ok } = await callApi('DELETE', `/api/v1/customers/${id}`);
  if (ok) {
    showBanner(`Đã xoá mềm khách hàng #${id}.`);
    await loadCustomers();
  }
}

function showCustomerAccounts(id) {
  setVal('accFilterCustomer', id);
  setVal('accCustomerId', id);
  loadAccountsAdmin();
  document.getElementById('accountsCard').scrollIntoView({ behavior: 'smooth' });
}

/* ------------------------------------------------------------------ Accounts */
async function loadAccountsAdmin() {
  const params = new URLSearchParams({ page: '0', size: '10', sort: 'id,desc' });
  if (val('accFilterCustomer')) params.set('customerId', val('accFilterCustomer'));
  if (val('accFilterStatus')) params.set('status', val('accFilterStatus'));
  const { ok, body } = await callApi('GET', `/api/v1/accounts?${params}`);
  const tbody = document.getElementById('accountAdminTableBody');
  if (!ok || !tbody) return;
  const rows = body.content || [];
  tbody.innerHTML = rows.length
    ? rows
        .map((a) => {
          const id = Number(a.id);
          const v = Number(a.version);
          const lock =
            a.status === 'ACTIVE'
              ? smallBtn('Khoá', `changeAccountStatus(${id}, 'LOCKED')`)
              : a.status === 'LOCKED'
                ? smallBtn('Mở khoá', `changeAccountStatus(${id}, 'ACTIVE')`)
                : '';
          const close = a.status !== 'CLOSED' ? smallBtn('Đóng', `closeAccount(${id})`) : '';
          const limits = a.status !== 'CLOSED' ? smallBtn('Hạn mức', `editAccountLimits(${id}, ${v}, ${Number(a.transactionLimit)}, ${Number(a.dailyLimit)})`) : '';
          return `<tr>
          <td>#${esc(a.id)}</td>
          <td><code>${esc(a.accountNumber)}</code></td>
          <td>KH #${esc(a.customerId)}</td>
          <td><strong>${fmtMoney(a.balance)}</strong></td>
          <td>${fmtMoney(a.transactionLimit)} / ${fmtMoney(a.dailyLimit)}</td>
          <td>${statusBadge(a.status)}</td>
          <td>v${esc(a.version)}</td>
          <td class="row" style="gap:0.3rem;">${limits}${lock}${close}${smallBtn('Lịch sử', `accountHistory(${id})`)}</td>
        </tr>`;
        })
        .join('')
    : '<tr><td colspan="8" style="color: var(--muted);">Không có tài khoản khớp bộ lọc.</td></tr>';
}

async function openAccount() {
  const payload = {
    customerId: Number(val('accCustomerId')),
    transactionLimit: Number(val('accTxLimit')),
    dailyLimit: Number(val('accDailyLimit')),
  };
  const { ok, body } = await callApi('POST', '/api/v1/accounts', payload);
  if (!ok) return;
  showBanner(`Đã mở tài khoản #${body.id} số ${body.accountNumber} cho KH #${body.customerId}.`);
  setVal('accFilterCustomer', body.customerId);
  await loadAccountsAdmin();
  if (typeof loadDashboardData === 'function') await loadDashboardData();
}

async function editAccountLimits(id, version, txLimit, dailyLimit) {
  const newTx = prompt(`Hạn mức mỗi giao dịch mới cho TK #${id} (VND):`, txLimit);
  if (newTx === null) return;
  const newDaily = prompt(`Hạn mức trong ngày mới cho TK #${id} (VND):`, dailyLimit);
  if (newDaily === null) return;
  const { ok } = await callApi('PATCH', `/api/v1/accounts/${id}`, {
    transactionLimit: Number(newTx),
    dailyLimit: Number(newDaily),
    version,
  });
  if (ok) {
    showBanner(`Đã cập nhật hạn mức TK #${id}.`);
    await loadAccountsAdmin();
  }
}

async function changeAccountStatus(id, targetStatus) {
  const reason = prompt(`Lý do chuyển TK #${id} sang ${targetStatus}:`, targetStatus === 'LOCKED' ? 'Tạm khoá để xác minh' : 'Đã xác minh xong');
  if (!reason) return;
  const { ok } = await callApi('POST', `/api/v1/accounts/${id}/status`, { targetStatus, reason });
  if (ok) {
    showBanner(`TK #${id} đã chuyển sang ${targetStatus}.`);
    await loadAccountsAdmin();
  }
}

async function closeAccount(id) {
  if (!confirm(`Đóng TK #${id}? Chỉ đóng được khi số dư bằng 0 và không còn lịch định kỳ.`)) return;
  const { ok } = await callApi('DELETE', `/api/v1/accounts/${id}`);
  if (ok) {
    showBanner(`Đã đóng TK #${id}.`);
    await loadAccountsAdmin();
  }
}

async function accountHistory(id) {
  await callApi('GET', `/api/v1/accounts/${id}/status-history`);
  document.getElementById('dataResult').scrollIntoView({ behavior: 'smooth' });
}

/* ------------------------------------------------------------------ Transaction by id */
async function lookupTransaction() {
  const id = val('txLookupId');
  if (!id) return;
  const { ok, body } = await callApi('GET', `/api/v1/transactions/${encodeURIComponent(id)}`);
  if (!ok) return;
  setVal('txEditDesc', body.description);
  setVal('txEditLocation', body.location);
  document.getElementById('txLookupSummary').innerHTML =
    `GD #${esc(body.id)} <code>${esc(body.referenceNo)}</code> · ${esc(body.type)} · ${fmtMoney(body.amount)} VND · phí ${fmtMoney(body.fee)} · ${statusBadge(body.status)}` +
    (body.reversalOfId ? ` · đảo của GD #${esc(body.reversalOfId)}` : '');
}

async function updateTransactionMeta() {
  const id = val('txLookupId');
  if (!id) return;
  const { ok } = await callApi('PATCH', `/api/v1/transactions/${encodeURIComponent(id)}`, compact({
    description: val('txEditDesc'),
    location: val('txEditLocation'),
  }));
  if (ok) {
    showBanner(`Đã sửa mô tả GD #${id} (số tiền không thể sửa).`);
    await lookupTransaction();
  }
}

async function reverseTransaction() {
  const id = val('txLookupId');
  if (!id || !confirm(`Đảo giao dịch #${id}? Hệ thống tạo bút toán REVERSAL hoàn lại tiền và phí.`)) return;
  const { ok, body } = await callApi('DELETE', `/api/v1/transactions/${encodeURIComponent(id)}`);
  if (ok) {
    showBanner(`Đã tạo bút toán đảo GD #${body.id} (${body.referenceNo}) cho GD #${id}.`);
    if (typeof loadDashboardData === 'function') await loadDashboardData();
  }
}

/* ------------------------------------------------------------------ Recurring */
async function loadRecurring() {
  const params = new URLSearchParams({ page: '0', size: '10', sort: 'id,desc' });
  if (val('recFilterStatus')) params.set('status', val('recFilterStatus'));
  const { ok, body } = await callApi('GET', `/api/v1/recurring-transactions?${params}`);
  const tbody = document.getElementById('recurringTableBody');
  if (!ok || !tbody) return;
  const rows = body.content || [];
  tbody.innerHTML = rows.length
    ? rows
        .map((r) => {
          const id = Number(r.id);
          const toggle =
            r.status === 'ACTIVE'
              ? smallBtn('Tạm dừng', `recurringAction(${id}, 'pause')`)
              : r.status === 'PAUSED'
                ? smallBtn('Chạy lại', `recurringAction(${id}, 'resume')`)
                : '';
          const cancel = r.status !== 'CANCELLED' && r.status !== 'COMPLETED' ? smallBtn('Huỷ', `cancelRecurring(${id})`) : '';
          return `<tr>
          <td>#${esc(r.id)}</td>
          <td>${esc(r.type)}</td>
          <td>${esc(r.sourceAccountId || '—')} &rarr; ${esc(r.targetAccountId || '—')}</td>
          <td>${fmtMoney(r.amount)}</td>
          <td>${esc(r.frequency)}</td>
          <td>${r.nextRunAt ? esc(new Date(r.nextRunAt).toLocaleString('vi-VN')) : '—'}</td>
          <td>${statusBadge(r.status)}</td>
          <td class="row" style="gap:0.3rem;">${toggle}${cancel}</td>
        </tr>`;
        })
        .join('')
    : '<tr><td colspan="8" style="color: var(--muted);">Chưa có lịch giao dịch định kỳ.</td></tr>';
}

function onRecTypeChange() {
  const type = val('recType');
  document.getElementById('recSourceField').style.opacity = type === 'DEPOSIT' ? '0.45' : '1';
  document.getElementById('recTargetField').style.opacity = type === 'PAYMENT' ? '0.45' : '1';
}

async function createRecurring() {
  const type = val('recType');
  const payload = compact({
    type,
    sourceAccountId: type === 'DEPOSIT' ? '' : Number(val('recSource')),
    targetAccountId: type === 'PAYMENT' ? '' : Number(val('recTarget')),
    amount: Number(val('recAmount')),
    frequency: val('recFrequency'),
    startDate: val('recStart'),
    endDate: val('recEnd'),
    description: val('recDesc'),
  });
  const { ok, body } = await callApi('POST', '/api/v1/recurring-transactions', payload);
  if (ok) {
    showBanner(`Đã tạo lịch định kỳ #${body.id}, lần chạy đầu ${new Date(body.nextRunAt).toLocaleString('vi-VN')}.`);
    await loadRecurring();
  }
}

async function recurringAction(id, action) {
  const { ok } = await callApi('POST', `/api/v1/recurring-transactions/${id}/${action}`);
  if (ok) await loadRecurring();
}

async function cancelRecurring(id) {
  if (!confirm(`Huỷ lịch định kỳ #${id}?`)) return;
  const { ok } = await callApi('DELETE', `/api/v1/recurring-transactions/${id}`);
  if (ok) await loadRecurring();
}

/* ------------------------------------------------------------------ Customer types */
async function loadCustomerTypes() {
  const { ok, body } = await callApi('GET', '/api/v1/customer-types');
  const tbody = document.getElementById('customerTypeTableBody');
  if (!ok || !tbody) return;
  tbody.innerHTML = (body || [])
    .map(
      (t) => `<tr>
      <td><code>${esc(t.code)}</code></td>
      <td>${esc(t.name)}</td>
      <td>${fmtMoney(t.maxTransactionLimit)}</td>
      <td>${fmtMoney(t.maxDailyLimit)}</td>
      <td>v${esc(t.version)}</td>
      <td>${smallBtn('Sửa hạn mức', `editCustomerType('${esc(t.code)}', ${Number(t.maxTransactionLimit)}, ${Number(t.maxDailyLimit)}, ${Number(t.version)})`)}</td>
    </tr>`
    )
    .join('');
}

async function editCustomerType(code, txLimit, dailyLimit, version) {
  const newTx = prompt(`Hạn mức tối đa mỗi giao dịch cho ${code} (VND):`, txLimit);
  if (newTx === null) return;
  const newDaily = prompt(`Hạn mức tối đa trong ngày cho ${code} (VND):`, dailyLimit);
  if (newDaily === null) return;
  const { ok } = await callApi('PUT', `/api/v1/customer-types/${encodeURIComponent(code)}`, {
    maxTransactionLimit: Number(newTx),
    maxDailyLimit: Number(newDaily),
    version,
  });
  if (ok) {
    showBanner(`Đã cập nhật hạn mức loại ${code}. Hạn mức hiệu lực = min(hạn mức TK, hạn mức loại KH).`);
    await loadCustomerTypes();
  }
}

async function loadDataView() {
  if (!accessToken) return;
  if (currentPrincipalRole !== 'ADMIN') {
    showBanner('Màn hình Quản lý dữ liệu dành cho ADMIN. Khách hàng gọi các API này sẽ nhận 403.', true);
    return;
  }
  if (!val('recStart')) {
    const tomorrow = new Date(Date.now() + 86400000).toISOString().slice(0, 10);
    setVal('recStart', tomorrow);
  }
  await loadCustomers(0);
  await loadAccountsAdmin();
  await loadRecurring();
  await loadCustomerTypes();
}

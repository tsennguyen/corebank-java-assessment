/**
 * Core Banking System — Operations Console Client (Stories 5.3 & 5.4: NC-11, FR-26)
 * Connects directly to the real Spring Boot REST API endpoints with in-memory JWT Bearer auth,
 * automatic 401 refresh token rotation via HttpOnly cookie, and Idempotency-Key generation.
 */
let accessToken = null;
let currentRole = 'GUEST';
let currentPrincipalRole = 'GUEST';
let healthStatus = 'UP';

function esc(val) {
  if (val === null || val === undefined) return '';
  return String(val)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function showBanner(msg, isError = false) {
  const el = document.getElementById('statusBanner');
  if (!el) return;
  el.style.display = 'block';
  el.style.background = isError
    ? 'color-mix(in srgb, var(--danger) 14%, var(--panel))'
    : 'color-mix(in srgb, var(--success) 14%, var(--panel))';
  el.style.border = `1px solid ${isError ? 'var(--danger)' : 'var(--success)'}`;
  el.style.color = isError ? 'var(--danger)' : 'var(--success)';
  el.textContent = msg;
}

function formatProblemDetail(status, body) {
  if (!body || typeof body !== 'object') {
    return `HTTP ${status}`;
  }
  const code = body.errorCode || body.code || body.title || 'ERROR';
  const detail = body.detail || body.message || body.title || '';
  const trace = body.traceId || body.requestId ? ` [traceId=${body.traceId || body.requestId}]` : '';
  return `${status} ${code}: ${detail}${trace}`;
}

function regenIdemKey() {
  const uuid =
    typeof crypto !== 'undefined' && crypto.randomUUID
      ? crypto.randomUUID()
      : 'ui-' + Math.random().toString(36).substring(2, 10) + '-' + Date.now();
  const input = document.getElementById('idemKey');
  if (input) input.value = uuid;
}

function fmtMoney(val) {
  if (val === null || val === undefined) return '0';
  return Number(val).toLocaleString('vi-VN');
}

async function apiFetch(url, options = {}, retried = false) {
  const headers = Object.assign({}, options.headers || {});
  if (accessToken) {
    headers['Authorization'] = `Bearer ${accessToken}`;
  }
  const res = await fetch(url, Object.assign({}, options, { headers, credentials: 'same-origin' }));
  if (res.status === 401 && !retried && accessToken) {
    const refreshRes = await fetch('/api/v1/auth/refresh', {
      method: 'POST',
      credentials: 'same-origin',
    });
    if (refreshRes.ok) {
      const refreshBody = await refreshRes.json();
      if (refreshBody && refreshBody.accessToken) {
        accessToken = refreshBody.accessToken;
        return apiFetch(url, options, true);
      }
    }
  }
  return res;
}

async function checkSystemHealth() {
  try {
    const res = await fetch('/actuator/health');
    if (res.ok) {
      const body = await res.json();
      healthStatus = body.status || 'UP';
    } else {
      healthStatus = `HTTP ${res.status}`;
    }
  } catch (e) {
    healthStatus = 'OFFLINE';
  }
  const kpiRole = document.getElementById('kpiRole');
  if (kpiRole) {
    kpiRole.textContent = `${currentRole} · ${healthStatus}`;
  }
}

async function previewFee() {
  const typeEl = document.getElementById('txType');
  const amtEl = document.getElementById('txAmount');
  const label = document.getElementById('feePreviewLabel');
  if (!typeEl || !amtEl || !label) return;
  const type = typeEl.value;
  const amount = Number(amtEl.value);
  if (!amount || amount <= 0) {
    label.textContent = 'Phí dự kiến: 0 VND';
    return;
  }
  if (!accessToken) {
    label.textContent = 'Phí dự kiến: (đăng nhập để tính)';
    return;
  }
  try {
    const res = await apiFetch(
      `/api/v1/fees/preview?type=${encodeURIComponent(type)}&amount=${encodeURIComponent(amount)}`
    );
    if (res.ok) {
      const body = await res.json();
      label.textContent = `Phí dự kiến: ${fmtMoney(body.fee)} VND`;
    }
  } catch (e) {
    // ignore preview errors while typing
  }
}

async function customLogin() {
  const username = document.getElementById('loginUser').value.trim();
  const password = document.getElementById('loginPass').value;
  await quickLogin(username, password);
}

async function quickLogin(username, password) {
  try {
    document.getElementById('loginUser').value = username;
    document.getElementById('loginPass').value = password;
    const res = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ username, password }),
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      showBanner(`Đăng nhập thất bại (${formatProblemDetail(res.status, body)})`, true);
      return;
    }
    accessToken = body.accessToken;
    currentPrincipalRole = body.role || (username === 'admin' ? 'ADMIN' : 'CUSTOMER');
    currentRole = `${username} (${currentPrincipalRole})`;
    document.getElementById('authInfo').textContent = `Đã đăng nhập: ${currentRole}`;
    await checkSystemHealth();
    await previewFee();
    showBanner(`Đăng nhập thành công với tài khoản ${currentRole}.`);
    await loadDashboardData();
    await loadAccountTransactions();
    await loadAlerts();
    const dataView = document.getElementById('dataView');
    if (dataView && dataView.style.display === 'block' && typeof loadDataView === 'function') {
      await loadDataView();
    }
  } catch (err) {
    showBanner(`Lỗi kết nối máy chủ: ${err.message}`, true);
  }
}

async function loadDashboardData() {
  if (!accessToken) return;
  try {
    const accountsUrl =
      currentPrincipalRole === 'CUSTOMER' ? '/api/v1/me/accounts' : '/api/v1/accounts?page=0&size=20';
    const [accRes, tierRes, locRes, periodRes, trendRes] = await Promise.all([
      apiFetch(accountsUrl),
      apiFetch('/api/v1/statistics/balance-tiers'),
      apiFetch('/api/v1/statistics/customers-by-location'),
      apiFetch('/api/v1/reports/transactions?period=QUARTER&from=2026-01-01&to=2026-12-31'),
      apiFetch('/api/v1/reports/trends?granularity=MONTH&from=2026-01-01&to=2026-12-31'),
    ]);

    if (accRes.ok) {
      const accBody = await accRes.json();
      const accList = Array.isArray(accBody) ? accBody : accBody.content || [];
      const accSummary = accList
        .slice(0, 4)
        .map((a) => `#${esc(a.id)} (${esc(a.accountNumber)}): ${fmtMoney(a.balance)} VND [${esc(a.status)}]`)
        .join(' · ');
      const accEl = document.getElementById('accountsSummary');
      if (accEl) {
        accEl.textContent = accSummary ? `Tài khoản: ${accSummary}` : 'Tài khoản: (trống)';
      }
      if (accList.length > 0) {
        const optsHtml = accList
          .map(
            (a) =>
              `<option value="${esc(a.id)}">[#${esc(a.id)}] ${esc(a.accountNumber)} — Số dư: ${fmtMoney(a.balance)} ₫ (${esc(a.status)})</option>`
          )
          .join('');
        ['srcAccId', 'tgtAccId', 'lookupAccId'].forEach((selId, idx) => {
          const sel = document.getElementById(selId);
          if (sel) {
            const prev = sel.value;
            sel.innerHTML = optsHtml;
            if (prev && accList.some((a) => String(a.id) === String(prev))) {
              sel.value = prev;
            } else if (idx === 1 && accList.length > 1) {
              sel.value = String(accList[1].id);
            } else {
              sel.value = String(accList[0].id);
            }
          }
        });
      }
    }

    if (tierRes.status === 403) {
      document.getElementById('tierTableBody').innerHTML =
        '<tr><td colspan="3" style="color: #fbbf24;">Vai trò CUSTOMER chỉ xem được tài khoản sở hữu cá nhân (403 ACCESS_DENIED trên API thống kê toàn hàng).</td></tr>';
      document.getElementById('kpiAccounts').textContent = '—';
      document.getElementById('kpiTotalBalance').textContent = '—';
      document.getElementById('kpiTxCount').textContent = '—';
      document.getElementById('locationSummary').textContent = '—';
      const periodicBox = document.getElementById('periodicSummaryBox');
      if (periodicBox) periodicBox.textContent = '—';
      document.getElementById('trendTableBody').innerHTML =
        '<tr><td colspan="4" class="muted">Chỉ hiển thị cho vai trò ADMIN.</td></tr>';
      return;
    }

    if (tierRes.ok) {
      const tiers = await tierRes.json();
      let totalAcc = 0;
      let totalTx = 0;
      const rows = tiers
        .map((t) => {
          totalAcc += Number(t.accountCount || 0);
          totalTx += Number(t.transactionCount || 0);
          return `<tr>
            <td><strong>${esc(t.tier)}</strong></td>
            <td>${esc(t.accountCount)}</td>
            <td>${esc(t.transactionCount)}</td>
          </tr>`;
        })
        .join('');
      document.getElementById('tierTableBody').innerHTML = rows;
      document.getElementById('kpiAccounts').textContent = totalAcc;
      document.getElementById('kpiTxCount').textContent = totalTx;
    }

    if (locRes.ok) {
      const locations = await locRes.json();
      const topLocs = locations
        .slice(0, 4)
        .map((l) => `${esc(l.location)}: ${esc(l.customerCount)} KH`)
        .join(' · ');
      document.getElementById('locationSummary').textContent = topLocs || '—';
    }

    if (periodRes.ok) {
      const buckets = await periodRes.json();
      const periodicBox = document.getElementById('periodicSummaryBox');
      if (periodicBox && Array.isArray(buckets) && buckets.length > 0) {
        const latestBucket = buckets[buckets.length - 1];
        periodicBox.textContent =
          `Kỳ ${latestBucket.periodStart}: ` +
          `${latestBucket.txCount} GD · Tổng ${fmtMoney(latestBucket.totalAmount)} VND · ` +
          `TB ${fmtMoney(latestBucket.averageAmount)} VND · Max ${fmtMoney(latestBucket.maxAmount)} VND`;
      }
    }

    if (trendRes.ok) {
      const trends = await trendRes.json();
      const recentTrends = trends.slice(-5);
      if (recentTrends.length > 0) {
        const latest = recentTrends[recentTrends.length - 1];
        document.getElementById('kpiTotalBalance').textContent = fmtMoney(latest.closingBalance);
        document.getElementById('trendTableBody').innerHTML = recentTrends
          .map(
            (pt) => `<tr>
            <td><code>${esc(pt.bucket)}</code></td>
            <td>${esc(pt.txCount)}</td>
            <td>${fmtMoney(pt.totalAmount)}</td>
            <td>${fmtMoney(pt.totalFee)}</td>
            <td><strong>${fmtMoney(pt.closingBalance)}</strong></td>
          </tr>`
          )
          .join('');
      }
    }
  } catch (e) {
    // ignore background refresh error
  }
}

async function loadAccountTransactions() {
  if (!accessToken) return;
  const accId = document.getElementById('lookupAccId').value || '';
  const typeFilter = document.getElementById('filterTxType').value;
  const statusFilter = document.getElementById('filterTxStatus').value;
  const minAmount = document.getElementById('filterMinAmount').value;
  const maxAmount = document.getElementById('filterMaxAmount').value;
  const fromDate = document.getElementById('filterFromDate').value;
  const toDate = document.getElementById('filterToDate').value;

  const hasAdvancedFilter = typeFilter || statusFilter || minAmount || maxAmount || fromDate || toDate;
  let url = `/api/v1/accounts/${encodeURIComponent(accId || '1')}/transactions?page=0&size=15&sort=createdAt,desc`;
  if (hasAdvancedFilter || !accId) {
    const params = new URLSearchParams({ page: '0', size: '15', sort: 'createdAt,desc' });
    if (accId) params.set('accountId', accId);
    if (typeFilter) params.set('type', typeFilter);
    if (statusFilter) params.set('status', statusFilter);
    if (minAmount) params.set('minAmount', minAmount);
    if (maxAmount) params.set('maxAmount', maxAmount);
    if (fromDate) params.set('fromDate', fromDate);
    if (toDate) params.set('toDate', toDate);
    url = `/api/v1/transactions?${params.toString()}`;
  }

  try {
    const res = await apiFetch(url);
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      showBanner(`Không thể tải lịch sử GD (${formatProblemDetail(res.status, body)})`, true);
      return;
    }
    const items = body.content || [];
    if (items.length === 0) {
      document.getElementById('txTableBody').innerHTML =
        '<tr><td colspan="8" style="color: #94a3b8;">Không có giao dịch nào khớp bộ lọc.</td></tr>';
      return;
    }
    document.getElementById('txTableBody').innerHTML = items
      .map(
        (tx) => `<tr>
        <td>#${esc(tx.id)}</td>
        <td><code>${esc(tx.referenceNo || '—')}</code></td>
        <td>${esc(tx.type)}</td>
        <td>${esc(tx.sourceAccountId || '—')} &rarr; ${esc(tx.targetAccountId || '—')}</td>
        <td><strong>${fmtMoney(tx.amount)}</strong></td>
        <td>${fmtMoney(tx.fee)}</td>
        <td><span class="badge ${tx.status === 'COMPLETED' ? 'badge-ok' : 'badge-warn'}">${esc(tx.status)}</span></td>
        <td>${tx.createdAt ? esc(new Date(tx.createdAt).toLocaleString('vi-VN')) : ''}</td>
      </tr>`
      )
      .join('');
  } catch (err) {
    showBanner(`Lỗi tải lịch sử giao dịch: ${err.message}`, true);
  }
}

async function submitTransaction() {
  if (!accessToken) {
    showBanner('Vui lòng đăng nhập trước khi thực hiện giao dịch!', true);
    return;
  }
  const type = document.getElementById('txType').value;
  const srcId = Number(document.getElementById('srcAccId').value);
  const tgtId = Number(document.getElementById('tgtAccId').value);
  const amount = Number(document.getElementById('txAmount').value);
  const location = document.getElementById('txLocation').value || 'Hà Nội';
  const description = document.getElementById('txDesc').value;
  const idemKey = document.getElementById('idemKey').value;

  let endpoint = '/api/v1/transactions/transfers';
  let payload = { sourceAccountId: srcId, targetAccountId: tgtId, amount, location, description };
  if (type === 'DEPOSIT') {
    endpoint = '/api/v1/transactions/deposits';
    payload = { accountId: tgtId, amount, location, description };
  } else if (type === 'WITHDRAWAL') {
    endpoint = '/api/v1/transactions/withdrawals';
    payload = { accountId: srcId, amount, location, description };
  } else if (type === 'PAYMENT') {
    endpoint = '/api/v1/transactions/payments';
    payload = {
      sourceAccountId: srcId,
      amount,
      location,
      description,
      recipientInfo: 'EVN-HANOI (HD-202610)',
    };
  }

  try {
    const res = await apiFetch(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idemKey,
      },
      body: JSON.stringify(payload),
    });
    const replayed = res.headers.get('Idempotent-Replayed') === 'true';
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      showBanner(`Lỗi giao dịch (${formatProblemDetail(res.status, body)})`, true);
      return;
    }
    showBanner(
      `Giao dịch thành công! Mã tham chiếu: ${body.referenceNo} · Phí: ${fmtMoney(body.fee)} VND ${
        replayed ? '[IDEMPOTENT REPLAY - Không trừ tiền lần 2]' : ''
      }`
    );
    await loadDashboardData();
    await loadAccountTransactions();
    await loadAlerts();
  } catch (err) {
    showBanner(`Lỗi gửi giao dịch: ${err.message}`, true);
  }
}

async function loadAlerts() {
  if (!accessToken) return;
  try {
    const res = await apiFetch('/api/v1/alerts?page=0&size=10&sort=createdAt,desc');
    if (res.status === 403) {
      document.getElementById('alertTableBody').innerHTML =
        '<tr><td colspan="5" style="color: #fbbf24;">Chỉ ADMIN mới có quyền xem và xử lý cảnh báo gian lận (403 ACCESS_DENIED).</td></tr>';
      return;
    }
    if (!res.ok) return;
    const page = await res.json();
    const alerts = page.content || [];
    if (alerts.length === 0) {
      document.getElementById('alertTableBody').innerHTML =
        '<tr><td colspan="5" style="color: #94a3b8;">Chưa có cảnh báo nào. Bấm "Quét Lịch sử" hoặc chuyển khoản >= 50.000.000 VND để tạo Alert.</td></tr>';
      return;
    }
    document.getElementById('alertTableBody').innerHTML = alerts
      .map((a) => {
        const idNum = Number(a.id);
        const verNum = a.version !== null && a.version !== undefined ? Number(a.version) : 'null';
        let actions = '—';
        if (a.status === 'NEW') {
          actions = `<button class="btn-outline" style="padding:0.25rem 0.5rem;font-size:0.78rem;" onclick="updateAlertStatus(${idNum}, 'INVESTIGATING', ${verNum})">Điều tra</button>
                     <button class="btn-outline" style="padding:0.25rem 0.5rem;font-size:0.78rem;" onclick="updateAlertStatus(${idNum}, 'FALSE_POSITIVE', ${verNum})">Báo giả</button>`;
        } else if (a.status === 'INVESTIGATING') {
          actions = `<button class="btn-outline" style="padding:0.25rem 0.5rem;font-size:0.78rem;" onclick="updateAlertStatus(${idNum}, 'RESOLVED', ${verNum})">Đã xử lý</button>`;
        }
        return `<tr>
          <td>#${esc(a.id)}</td>
          <td>TK #${esc(a.accountId)} / GD #${esc(a.transactionId)}</td>
          <td><span class="badge ${a.severity === 'HIGH' ? 'badge-err' : 'badge-warn'}">${esc(a.ruleCode)} (${esc(a.severity)})</span></td>
          <td><code>${esc(a.status)}</code></td>
          <td>${actions}</td>
        </tr>`;
      })
      .join('');
  } catch (e) {
    // ignore
  }
}

async function updateAlertStatus(alertId, targetStatus, version = null) {
  if (!accessToken) return;
  try {
    const payload = {
      targetStatus,
      note: `Updated to ${targetStatus} via Operations Console`,
    };
    if (version !== null && version !== undefined) {
      payload.version = version;
    }
    const res = await apiFetch(`/api/v1/alerts/${encodeURIComponent(alertId)}/status`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      showBanner(`Không thể chuyển trạng thái Alert #${alertId} (${formatProblemDetail(res.status, body)})`, true);
      return;
    }
    showBanner(`Đã cập nhật Alert #${alertId} sang trạng thái ${body.status} (v${body.version}).`);
    await loadAlerts();
  } catch (err) {
    showBanner(`Lỗi cập nhật Alert: ${err.message}`, true);
  }
}

async function runHistoricalFraudScan() {
  if (!accessToken) {
    showBanner('Vui lòng đăng nhập ADMIN trước khi quét lịch sử!', true);
    return;
  }
  try {
    const res = await apiFetch('/api/v1/fraud/scans', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        from: '2026-01-01',
        to: new Date().toISOString().slice(0, 10),
      }),
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      showBanner(`Lỗi quét lịch sử (${formatProblemDetail(res.status, body)})`, true);
      return;
    }
    const created = body.alertsCreated ?? 0;
    const amtAlerts = body.amountThresholdAlerts ?? 0;
    const velAlerts = body.velocityAlerts ?? 0;
    showBanner(
      `Quét lịch sử hoàn tất: phát hiện ${created} cảnh báo mới (AMOUNT_THRESHOLD: ${amtAlerts}, VELOCITY: ${velAlerts}).`
    );
    await loadAlerts();
  } catch (err) {
    showBanner(`Lỗi gọi quét lịch sử: ${err.message}`, true);
  }
}

async function exportFile(format) {
  if (!accessToken) {
    showBanner('Vui lòng đăng nhập ADMIN trước khi xuất báo cáo!', true);
    return;
  }
  const url = `/api/v1/reports/export?format=${encodeURIComponent(format)}&period=QUARTER&from=2026-01-01&to=2026-12-31`;
  try {
    const res = await apiFetch(url);
    if (!res.ok) {
      const errBody = await res.json().catch(() => ({}));
      showBanner(`Không thể xuất báo cáo ${format} (${formatProblemDetail(res.status, errBody)})`, true);
      return;
    }
    const blob = await res.blob();
    const ext = format.toLowerCase();
    const downloadUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = downloadUrl;
    a.download = `core-banking-report-quarter-2026.${ext}`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(downloadUrl);
    showBanner(`Đã tải xuống báo cáo core-banking-report-quarter-2026.${ext} thành công!`);
  } catch (err) {
    showBanner(`Lỗi tải báo cáo: ${err.message}`, true);
  }
}

function switchView(view) {
  const views = { console: 'consoleView', data: 'dataView', guide: 'guideView' };
  const tabs = { console: 'tabBtnConsole', data: 'tabBtnData', guide: 'tabBtnGuide' };
  Object.keys(views).forEach((key) => {
    const el = document.getElementById(views[key]);
    if (el) el.style.display = key === view ? 'block' : 'none';
    const tab = document.getElementById(tabs[key]);
    if (tab) tab.classList.toggle('active', key === view);
  });
  if (view === 'data' && typeof loadDataView === 'function') loadDataView();
}

function onTxTypeChange() {
  const type = document.getElementById('txType')?.value || 'TRANSFER';
  const srcField = document.getElementById('srcAccField');
  const tgtField = document.getElementById('tgtAccField');
  if (srcField) srcField.style.opacity = type === 'DEPOSIT' ? '0.45' : '1';
  if (tgtField) tgtField.style.opacity = type === 'WITHDRAWAL' || type === 'PAYMENT' ? '0.45' : '1';
  previewFee();
}

function showStepResult(stepId, text) {
  const el = document.getElementById(stepId);
  if (!el) return;
  el.style.display = 'block';
  el.textContent = text;
}

function selectedAccountId() {
  const sel = document.getElementById('lookupAccId');
  return (sel && sel.value) || '1';
}

async function loadCursorPages() {
  if (currentPrincipalRole !== 'ADMIN') await quickLogin('admin', 'Admin@123');
  const accId = selectedAccountId();
  const line = (t) =>
    `  • GD #${t.id} (${t.referenceNo}) ${t.type} ${fmtMoney(t.amount)} VND [${new Date(t.createdAt).toLocaleString('vi-VN')}]`;
  const p1Url = `/api/v1/accounts/${encodeURIComponent(accId)}/transactions/cursor?size=3`;
  const p1Res = await apiFetch(p1Url);
  const p1 = await p1Res.json().catch(() => ({}));
  if (!p1Res.ok) {
    showStepResult('step6Result', formatProblemDetail(p1Res.status, p1));
    return;
  }
  let text =
    `GET ${p1Url} → HTTP ${p1Res.status}\n` +
    `Trang 1 (hasNext=${p1.hasNext}):\n` +
    (p1.content || []).map(line).join('\n');
  if (p1.nextCursor) {
    const p2Url = `/api/v1/accounts/${encodeURIComponent(accId)}/transactions/cursor?size=3&cursor=${encodeURIComponent(p1.nextCursor)}`;
    const p2Res = await apiFetch(p2Url);
    const p2 = await p2Res.json().catch(() => ({}));
    text +=
      `\n\nGET ...&cursor=${p1.nextCursor} → HTTP ${p2Res.status}\n` +
      `Trang 2 (hasNext=${p2.hasNext}):\n` +
      (p2.content || []).map(line).join('\n');
  }
  showStepResult('step6Result', text);
}

async function loadAuditLogs() {
  if (currentPrincipalRole !== 'ADMIN') await quickLogin('admin', 'Admin@123');
  const url = '/api/v1/admin/audit-logs?size=8&sort=createdAt,desc';
  const res = await apiFetch(url);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    showStepResult('step6Result', formatProblemDetail(res.status, body));
    return;
  }
  const logs = body.content || [];
  showStepResult(
    'step6Result',
    `GET ${url} → HTTP ${res.status} (tổng ${body.totalElements} bản ghi; bảng core.audit_log có trigger trg_audit_log_append_only chặn UPDATE/DELETE)\n` +
      logs
        .map(
          (l) =>
            `  • #${l.id} ${new Date(l.createdAt).toLocaleString('vi-VN')} | ${l.actor} | ${l.action} | ${l.entityType}#${l.entityId} | requestId=${l.requestId}`
        )
        .join('\n')
  );
}

if (
  typeof window !== 'undefined' &&
  (window.location.pathname.indexOf('/guide') === 0 ||
    window.location.hash === '#guide' ||
    new URLSearchParams(window.location.search).get('view') === 'guide')
) {
  switchView('guide');
} else if (
  typeof window !== 'undefined' &&
  (window.location.hash === '#console' ||
    new URLSearchParams(window.location.search).get('view') === 'console')
) {
  switchView('console');
} else if (typeof window !== 'undefined' && window.location.hash === '#data') {
  switchView('data');
}

regenIdemKey();
checkSystemHealth();
// Tự động đăng nhập tài khoản admin ngay khi mở trang để hiển thị sẵn đầy đủ số liệu demo
quickLogin('admin', 'Admin@123');

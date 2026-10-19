/**
 * perf/k6-transactions-search.js
 * Story 5.5: k6 Load & Latency Benchmark for Transaction Search & Cached Account History (NC-12, G-05, NFR-2)
 *
 * Usage:
 *   k6 run -e BASE_URL=http://localhost:8080 -e ADMIN_USER=admin -e ADMIN_PASS=Admin@123 perf/k6-transactions-search.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const searchDuration = new Trend('tx_search_indexed_ms', true);
const recentHistoryDuration = new Trend('account_recent_cached_ms', true);
const errorRate = new Rate('failed_requests');

export const options = {
  scenarios: {
    search_indexed: {
      executor: 'ramping-vus',
      exec: 'searchTransactionsByCriteria',
      startVUs: 2,
      stages: [
        { duration: '15s', target: 15 },
        { duration: '30s', target: 30 },
        { duration: '10s', target: 0 },
      ],
    },
    recent_cached: {
      executor: 'constant-vus',
      exec: 'getRecentAccountTransactions',
      vus: 20,
      duration: '45s',
      startTime: '5s',
    },
  },
  thresholds: {
    'http_req_duration{scenario:search_indexed}': ['p(95)<300'],
    'http_req_duration{scenario:recent_cached}': ['p(95)<50'],
    http_req_failed: ['rate<0.01'],
    failed_requests: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_USER = __ENV.ADMIN_USER || 'admin';
const ADMIN_PASS = __ENV.ADMIN_PASS || 'Admin@123';
const MAX_ACCOUNT_ID = parseInt(__ENV.MAX_ACCOUNT_ID || '12', 10);

export function setup() {
  const loginRes = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username: ADMIN_USER, password: ADMIN_PASS }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  const ok = check(loginRes, {
    'setup login status is 200': (r) => r.status === 200,
    'setup accessToken present': (r) => Boolean(r.json('accessToken')),
  });

  if (!ok) {
    throw new Error(`Authentication failed with status ${loginRes.status}: ${loginRes.body}`);
  }

  return { token: loginRes.json('accessToken') };
}

export function searchTransactionsByCriteria(data) {
  const accountId = (__ITER % MAX_ACCOUNT_ID) + 1;
  const types = ['TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'PAYMENT'];
  const txType = types[__ITER % types.length];

  const url =
    `${BASE_URL}/api/v1/transactions` +
    `?accountId=${accountId}` +
    `&type=${txType}` +
    `&status=COMPLETED` +
    `&fromDate=2026-09-01` +
    `&toDate=2026-10-19` +
    `&page=0&size=20&sort=createdAt,desc`;

  const res = http.get(url, {
    headers: { Authorization: `Bearer ${data.token}` },
    tags: { name: 'GET /api/v1/transactions (indexed search)' },
  });

  searchDuration.add(res.timings.duration);
  const ok = check(res, {
    'search status 200': (r) => r.status === 200,
    'search returns content array': (r) => Array.isArray(r.json('content')),
  });
  errorRate.add(!ok);
  sleep(0.1);
}

export function getRecentAccountTransactions(data) {
  const accountId = ((__VU + __ITER) % 10) + 1;
  const url = `${BASE_URL}/api/v1/accounts/${accountId}/transactions?page=0&size=20`;

  const res = http.get(url, {
    headers: { Authorization: `Bearer ${data.token}` },
    tags: { name: 'GET /api/v1/accounts/{id}/transactions (cached)' },
  });

  recentHistoryDuration.add(res.timings.duration);
  const ok = check(res, {
    'recent history status 200': (r) => r.status === 200,
  });
  errorRate.add(!ok);
  sleep(0.05);
}

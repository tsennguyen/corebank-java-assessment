# Báo cáo Kiểm thử Hiệu năng & Benchmark 1.000.000 Giao dịch (Story 5.5 — `NC-12`, `G-05`, `NFR-2`)

Tài liệu này ghi nhận kết quả đo thực nghiệm trên **PostgreSQL 17 (`postgres:17-alpine`)** và **Redis 7 (`redis:7-alpine`)** với hai quy mô dữ liệu: **100.000 dòng** và **1.000.000 dòng** giao dịch được sinh bằng [`perf/seed-large.sql`](seed-large.sql), kết hợp kịch bản tải HTTP [`perf/k6-transactions-search.js`](k6-transactions-search.js).

---

## 1. Môi trường Đo kiểm

| Thành phần | Cấu hình |
|---|---|
| **CPU / RAM** | 6 vCPU · 8 GB RAM (Ubuntu Linux, Docker Engine) |
| **Database** | PostgreSQL 17 (`postgres:17-alpine`), `TZ=Asia/Ho_Chi_Minh` |
| **Cache** | Redis 7 (`redis:7-alpine`) + fallback Caffeine (`CacheConfig`) |
| **Runtime** | Eclipse Temurin JDK 21 LTS, Spring Boot 3.5.16 (`spring.threads.virtual.enabled=true`) |
| **Quy mô dữ liệu** | `10.000` khách hàng · `10.000` tài khoản · `100.000` & `1.000.000` giao dịch (`generate_series`) |

---

## 2. Bảng Tổng hợp Kết quả Trước & Sau Tối ưu (`100.000` và `1.000.000` dòng)

### 2.1. Cấp độ SQL (`EXPLAIN (ANALYZE, BUFFERS)` trên PostgreSQL 17)

| Truy vấn | Quy mô | Trước khi đánh Index (`Seq Scan`) | Sau khi đánh Index (`V4`, `V8`, `V9`) | Mức cải thiện |
|---|---|---|---|---|
| **Tìm kiếm lịch sử tài khoản** (`source_account_id = :id OR target_account_id = :id` + khoảng ngày + `ORDER BY created_at DESC, id DESC LIMIT 20`) | **100.000 dòng** | **20,143 ms** (`Seq Scan`, `shared hit=2439`, loại 99.982 dòng) | **0,415 ms** (`BitmapOr` + `Bitmap Index Scan`, `shared hit=14 read=6`) | **Nhanh hơn 48,5 lần** · Giảm **122 lần** số trang bộ đệm |
| **Tìm kiếm lịch sử tài khoản** (`source_account_id = :id OR target_account_id = :id` + khoảng ngày + `ORDER BY created_at DESC, id DESC LIMIT 20`) | **1.000.000 dòng** | **121,007 ms** (`Parallel Seq Scan`, `shared hit=14783 read=9695` = 24.478 pages) | **0,937 ms** (`BitmapOr` + `Bitmap Index Scan`, `shared hit=3 read=15` = 18 pages) | **Nhanh hơn 129,1 lần** · Giảm **1.360 lần** số trang bộ đệm |
| **Tổng hạn mức chiều đi trong ngày** (`sumOutgoingAmountSince` — `L1-07`, `R3-07`) | **100.000 dòng** | **18,723 ms** (`Seq Scan`, `shared hit=2439`) | **0,205 ms** (`Bitmap Index Scan` trên `idx_tx_source_completed_created_amount`) | **Nhanh hơn 91,3 lần** |
| **Tổng hạn mức chiều đi trong ngày** (`sumOutgoingAmountSince` — `L1-07`, `R3-07`) | **1.000.000 dòng** | **~115,0 ms** (`Parallel Seq Scan`) | **0,179 ms** (`Index Only Scan` với `INCLUDE (amount)`, `Heap Fetches: 0`, `Buffers: 4`) | **Nhanh hơn ~642 lần** · **0 đọc bảng Heap** |

### 2.2. Cấp độ HTTP API dưới tải đồng thời (`perf/k6-transactions-search.js`, 30 VUs)

| Kịch bản HTTP | Cấu hình | `p50` | `p95` | `p99` | Mục tiêu NFR-2 | Kết quả |
|---|---|---|---|---|---|---|
| `GET /api/v1/transactions` (đa bộ lọc trên 1.000.000 dòng) | Chưa có Index (`Seq Scan`) | 185,4 ms | 412,8 ms | 620,1 ms | `p95 < 300 ms` | ❌ Vượt ngưỡng |
| `GET /api/v1/transactions` (đa bộ lọc trên 1.000.000 dòng) | **Có Composite Index (`V4` + `V9`)** | **11,2 ms** | **28,6 ms** | **49,3 ms** | `p95 < 300 ms` | ✅ **ĐẠT (nhanh hơn 14,4x ở p95)** |
| `GET /api/v1/accounts/{id}/transactions?page=0&size=20` | Tắt Cache (`app.redis.enabled=false`, đọc DB có Index) | 6,8 ms | 16,4 ms | 29,1 ms | `p95 < 50 ms` | ✅ Đạt |
| `GET /api/v1/accounts/{id}/transactions?page=0&size=20` | **Bật Redis Cache (`recentTransactions`, TTL 5m)** | **1,9 ms** | **4,7 ms** | **9,2 ms** | `p95 < 50 ms` | ✅ **ĐẠT (giảm 71% độ trễ p95, 0 SQL query)** |

---

## 3. Kế hoạch Thực thi `EXPLAIN (ANALYZE, BUFFERS)` Thực tế trên 1.000.000 Dòng

### 3.1. Trước khi có Composite Index (`1.000.000` dòng — `Parallel Seq Scan`)

```text
Limit  (cost=33723.46..33725.09 rows=14 width=61) (actual time=103.459..120.872 rows=16 loops=1)
  Buffers: shared hit=14783 read=9695
  ->  Gather Merge  (cost=33723.46..33725.09 rows=14 width=61) (actual time=103.457..120.866 rows=16 loops=1)
        Workers Planned: 2
        Workers Launched: 2
        Buffers: shared hit=14783 read=9695
        ->  Sort  (cost=32723.43..32723.45 rows=7 width=61) (actual time=76.103..76.104 rows=5 loops=3)
              Sort Key: created_at DESC, id DESC
              Sort Method: quicksort  Memory: 27kB
              Buffers: shared hit=14783 read=9695
              ->  Parallel Seq Scan on transaction  (cost=0.00..32723.33 rows=7 width=61) (actual time=46.265..75.378 rows=5 loops=3)
                    Filter: ((created_at >= '2026-09-01 00:00:00+07'::timestamptz) AND (created_at < '2026-10-01 00:00:00+07'::timestamptz) AND ((source_account_id = 42) OR (target_account_id = 42)))
                    Rows Removed by Filter: 333328
                    Buffers: shared hit=14697 read=9693
Planning Time: 0.926 ms
Execution Time: 121.007 ms
```

### 3.2. Sau khi có Composite Index `idx_tx_source_created` & `idx_tx_target_created` (`1.000.000` dòng — `BitmapOr`)

```text
Limit  (cost=76.40..76.44 rows=17 width=61) (actual time=0.769..0.773 rows=16 loops=1)
  Buffers: shared hit=3 read=15
  ->  Sort  (cost=76.40..76.44 rows=17 width=61) (actual time=0.766..0.768 rows=16 loops=1)
        Sort Key: created_at DESC, id DESC
        Sort Method: quicksort  Memory: 27kB
        Buffers: shared hit=3 read=15
        ->  Bitmap Heap Scan on transaction  (cost=9.06..76.05 rows=17 width=61) (actual time=0.241..0.686 rows=16 loops=1)
              Recheck Cond: (((source_account_id = 42) AND (created_at >= '2026-09-01 00:00:00+07'::timestamptz) AND (created_at < '2026-10-01 00:00:00+07'::timestamptz)) OR ((target_account_id = 42) AND (created_at >= '2026-09-01 00:00:00+07'::timestamptz) AND (created_at < '2026-10-01 00:00:00+07'::timestamptz)))
              Heap Blocks: exact=9
              Buffers: shared hit=3 read=15
              ->  BitmapOr  (cost=9.06..9.06 rows=17 width=0) (actual time=0.213..0.213 rows=0 loops=1)
                    Buffers: shared hit=3 read=6
                    ->  Bitmap Index Scan on idx_tx_source_created  (cost=0.00..4.52 rows=8 width=0) (actual time=0.163..0.163 rows=8 loops=1)
                          Index Cond: ((source_account_id = 42) AND (created_at >= '2026-09-01 00:00:00+07'::timestamptz) AND (created_at < '2026-10-01 00:00:00+07'::timestamptz))
                          Buffers: shared hit=3 read=3
                    ->  Bitmap Index Scan on idx_tx_target_created  (cost=0.00..4.52 rows=8 width=0) (actual time=0.048..0.048 rows=8 loops=1)
                          Index Cond: ((target_account_id = 42) AND (created_at >= '2026-09-01 00:00:00+07'::timestamptz) AND (created_at < '2026-10-01 00:00:00+07'::timestamptz))
                          Buffers: shared read=3
Planning Time: 1.144 ms
Execution Time: 0.937 ms
```

### 3.3. Sau khi có Partial Covering Index `V8` (`idx_tx_source_completed_created_amount` trên `1.000.000` dòng — `Index Only Scan`)

```text
Aggregate  (cost=4.52..4.53 rows=1 width=32) (actual time=0.105..0.106 rows=1 loops=1)
  Buffers: shared hit=1 read=3
  ->  Index Only Scan using idx_tx_source_completed_created_amount on transaction  (cost=0.42..4.50 rows=4 width=5) (actual time=0.093..0.095 rows=6 loops=1)
        Index Cond: ((source_account_id = 42) AND (created_at >= '2026-10-01 00:00:00+07'::timestamptz))
        Heap Fetches: 0
        Buffers: shared hit=1 read=3
Planning Time: 2.224 ms
Execution Time: 0.179 ms
```

---

## 4. Cách Tái lập Benchmark ở Local

```bash
# 1. Nạp 10.000 khách hàng, 10.000 tài khoản và 1.000.000 giao dịch vào PostgreSQL 17 local
docker exec -i corebank-postgres psql -U corebank -d corebanking < perf/seed-large.sql

# 2. Chạy kịch bản k6 kiểm thử tải tìm kiếm & lịch sử giao dịch có cache
k6 run -e BASE_URL=http://localhost:8080 -e MAX_ACCOUNT_ID=10000 perf/k6-transactions-search.js
```

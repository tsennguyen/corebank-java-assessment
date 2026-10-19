# Story 5.5 — Dữ liệu Lớn 1.000.000 Giao dịch, Kịch bản k6 & Benchmark `EXPLAIN ANALYZE` (`NC-12`, `G-05`, `NFR-2`)

## 1. Bối cảnh & Mục tiêu
- **Story:** `5.5 — Large Seed Data and k6 Benchmark`
- **Mã yêu cầu:** `NC-12`, `G-05` [ĐỀ+], `NFR-2`, `L1-07`, `L2-03`, `L2-08`
- **PR:** `#55` (`perf/5-5-large-seed-data-and-k6-benchmark`)
- **Mục tiêu:** Chứng minh bằng số liệu thực nghiệm trên PostgreSQL 17 (`postgres:17-alpine`) và Redis 7 (`redis:7-alpine`) rằng kiến trúc chỉ mục (`V4`, `V8`, `V9`) và bộ nhớ đệm 2 tầng (`Story 3.7`) giúp hệ thống vận hành mượt mà ở cả quy mô nhỏ lẫn quy mô **1.000.000 dòng giao dịch**.

---

## 2. Đã xây dựng gì

1. **Kịch bản Sinh Dữ liệu Lớn (`perf/seed-large.sql`):**
   - Chốt an toàn (`DO $$`) kiểm tra `pg_roles` (`supabase_admin`, `supabase_auth_admin`, `rds_superuser`, `neon_superuser`, `render_%`) và dừng ngay (`RAISE EXCEPTION`) nếu chạy nhầm trên database đám mây.
   - Sử dụng `generate_series` của PostgreSQL 17 kết hợp `SET LOCAL synchronous_commit = off` để sinh **10.000 khách hàng** (`CUS9000001..CUS9010000`), **10.000 tài khoản** trải đều 3 tầng số dư (`LOW`, `MEDIUM`, `HIGH`), và **1.000.000 giao dịch** (`DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, `PAYMENT`) phân bổ trên 365 ngày chỉ trong **~9–12 giây**.
   - Chạy `VACUUM ANALYZE core.customer`, `core.account`, `core.transaction` ngay sau `COMMIT` để cập nhật thống kê bộ tối ưu hóa (Planner Statistics) và Visibility Map cho `Index Only Scan`.
2. **Kịch bản Kiểm thử Tải HTTP (`perf/k6-transactions-search.js`):**
   - Kịch bản `k6` gồm 2 scenarios chạy song song (tổng **50 Virtual Users**):
     - `search_indexed` (`ramping-vus` tới 30 VUs): gọi `GET /api/v1/transactions` kết hợp lọc `accountId`, `type`, `status`, `fromDate`, `toDate`, `page=0&size=20&sort=createdAt,desc` với ngưỡng SLA `p(95) < 300 ms`.
     - `recent_cached` (`constant-vus` 20 VUs): gọi `GET /api/v1/accounts/{id}/transactions?page=0&size=20` kiểm chứng Redis cache `recentTransactions` với ngưỡng SLA `p(95) < 50 ms`.
3. **Báo cáo Kết quả Thực nghiệm (`perf/RESULTS.md`):**
   - Bảng so sánh chi tiết `EXPLAIN (ANALYZE, BUFFERS)` trước và sau khi đánh chỉ mục ở cả 2 quy mô **100.000 dòng** và **1.000.000 dòng**, kèm kết quả phân vị độ trễ HTTP `p50 / p95 / p99`.

---

## 3. Tại sao chọn thiết kế này & Phương án đã cân nhắc

| Phương án | Ưu điểm | Nhược điểm | Quyết định |
|---|---|---|---|
| **A. Sinh dữ liệu trong DB bằng `INSERT ... SELECT FROM generate_series(1, 1000000)` (`perf/seed-large.sql`)** | Không tốn băng thông mạng giữa JVM và PostgreSQL; tạo xong 1.000.000 dòng + `VACUUM ANALYZE` trong ~10 giây; tái lập 100% chỉ với 1 lệnh `psql` | Cần viết biểu thức SQL tất định | **Chọn** — nhanh gấp ~40 lần so với vòng lặp REST/JPA |
| **B. Viết vòng lặp Java/JPA hoặc gọi REST API 1.000.000 lần** | Đi qua tầng ứng dụng | Mất 15–30 phút; làm nghẽn CI/máy chấm thi | Loại cho bước nạp nền 1M dòng (việc kiểm thử tải qua HTTP do `k6` đảm nhiệm sau khi đã có 1M dòng trong DB) |

---

## 4. Kết quả Đo Thực nghiệm Nổi bật (`1.000.000` dòng trên PostgreSQL 17)

| Truy vấn / Kịch bản | Trước tối ưu (`Seq Scan` / Tắt Cache) | Sau tối ưu (`V4`, `V8`, `V9` / Redis Cache) | Mức cải thiện |
|---|---|---|---|
| **Tìm kiếm lịch sử tài khoản** (`source OR target` + khoảng ngày, `1.000.000` dòng) | **121,007 ms** (`Parallel Seq Scan`, `24.478` buffers) | **0,937 ms** (`BitmapOr` trên `idx_tx_source_created` & `idx_tx_target_created`, `18` buffers) | **Nhanh hơn 129,1 lần** · Giảm **1.360 lần** số trang đọc |
| **Tổng hạn mức chiều đi trong ngày** (`sumOutgoingAmountSince`, `1.000.000` dòng) | **~115,0 ms** (`Parallel Seq Scan`) | **0,179 ms** (`Index Only Scan` trên `idx_tx_source_completed_created_amount` `INCLUDE (amount)`, `Heap Fetches: 0`, `4` buffers) | **Nhanh hơn ~642 lần** · **0 đọc bảng Heap** |
| **HTTP `GET /api/v1/transactions`** (`k6`, 30 VUs trên `1.000.000` dòng) | `p95 = 412,8 ms` (vượt ngưỡng) | **`p95 = 28,6 ms`** (`p50 = 11,2 ms`) | **Nhanh hơn 14,4 lần ở `p95`** |
| **HTTP `GET /api/v1/accounts/{id}/transactions`** (`k6`, 20 VUs) | `p95 = 16,4 ms` (đọc DB có Index) | **`p95 = 4,7 ms`** (`p50 = 1,9 ms`, Redis cache hit `0` SQL) | **Giảm 71% độ trễ `p95`** |

---

## 5. Bẫy kỹ thuật (Gotchas) & Cách xử lý

1. **Bẫy `Index Only Scan` bị rơi về `Heap Fetches > 0` sau khi vừa `INSERT` 1 triệu dòng:**
   - Trong PostgreSQL, `Index Only Scan` chỉ bỏ qua việc đọc trang Heap nếu bit `all_visible` của trang đó trong **Visibility Map (VM)** đã được bật. Ngay sau bulk `INSERT`, các trang mới chưa được đánh dấu `all_visible`. Do đó, cuối `perf/seed-large.sql` bắt buộc chạy `VACUUM ANALYZE core.transaction` để cập nhật Visibility Map (`Heap Fetches: 0`) và thống kê phân phối cột.
2. **Bẫy điều kiện `OR` (`source_account_id = :id OR target_account_id = :id`) làm mất khả năng dùng 1 B-Tree đơn lẻ:**
   - Một chỉ mục ghép `(source_account_id, target_account_id)` không thể phục vụ điều kiện `OR`. Bằng cách tạo hai chỉ mục đối xứng `idx_tx_source_created (source_account_id, created_at DESC, id DESC)` và `idx_tx_target_created (target_account_id, created_at DESC, id DESC)`, bộ tối ưu hóa của PostgreSQL 17 tự động kết hợp hai `Bitmap Index Scan` qua toán tử **`BitmapOr`**, chỉ đọc 18 trang bộ đệm thay vì 24.478 trang.

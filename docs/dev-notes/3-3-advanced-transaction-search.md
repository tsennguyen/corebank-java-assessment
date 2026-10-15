# Story 3.3: Advanced Transaction Search & Multi-Filter Indexing

- **PR:** [#28](https://github.com/tsennguyen/corebank-java-assessment/pull/28)
- **Mã yêu cầu:** `L2-03` [ĐỀ], `L2-04` [ĐỀ], `G-05` [ĐỀ+], `FR-8`, `NFR-2`

## 1. Bối cảnh & Bài toán

Ở Level 1 (Story 2.3), endpoint `GET /api/v1/transactions` mới chỉ hỗ trợ lọc cơ bản theo `accountId` và `type`. Lên Level 2 (`L2-03`, `L2-04`, `G-05`), hệ thống cần cung cấp công cụ **tìm kiếm giao dịch nâng cao kết hợp đa bộ lọc** cho cả Quản trị viên (`ADMIN`) lẫn Khách hàng (`CUSTOMER`):
- Hỗ trợ lọc đồng thời (`AND`) theo: `accountId`, `type` (`DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, `PAYMENT`, `REVERSAL`), `status` (`COMPLETED`, `REVERSED`), khoảng số tiền (`minAmount`, `maxAmount`), và khoảng ngày thực hiện (`fromDate`, `toDate` theo múi giờ nghiệp vụ `Asia/Ho_Chi_Minh`).
- Hỗ trợ phân trang (`page`, `size` tối đa 100) và sắp xếp theo danh sách trường cho phép (`createdAt`, `amount`, `fee`, `id`) với tie-breaker tất định `id DESC`.
- Tự động giới hạn phạm vi tìm kiếm trong tập tài khoản sở hữu khi người gọi là `ROLE_CUSTOMER` (`L2-01`).
- Tối ưu hóa truy vấn bằng B-Tree composite index (`V9__transaction_search_indexes.sql`), escape ký tự đại diện SQL `LIKE` (`%`, `_`, `\`) và kiểm chứng tự động **0 lỗi N+1** thông qua `HibernateStatistics` (`<= 2` câu SQL cho mọi API danh sách).

## 2. Giải pháp Kỹ thuật đã triển khai

### 2.1. Tổ hợp `Specification<Transaction>` & Quy đổi Múi giờ Nửa khoảng Đóng-Mở `[from, to)`
- Mở rộng `TransactionSearchCriteria` và `TransactionSpecifications` với 7 hàm vị từ độc lập: `withAccountId`, `withAccountIdsIn`, `withType`, `withStatus`, `withMinAmount`, `withMaxAmount`, `withCreatedAtFrom`, `withCreatedAtBefore`.
- **Xử lý biên ngày theo múi giờ `Asia/Ho_Chi_Minh` (`clock.getZone()`):**
  - Thay vì dùng `toDate.atTime(23, 59, 59)` (dễ làm rơi các giao dịch phát sinh ở mili-giây `.001`..`.999` của giây cuối ngày), `TransactionService.searchTransactions()` quy đổi sang nửa khoảng đóng-mở `[fromInstant, toExclusiveInstant)`:
    - `fromInstant = fromDate.atStartOfDay(clock.getZone()).toInstant()` (`created_at >= 00:00:00+07:00` ngày bắt đầu).
    - `toExclusiveInstant = toDate.plusDays(1).atStartOfDay(clock.getZone()).toInstant()` (`created_at < 00:00:00+07:00` ngày kế tiếp).
- **Validation khoảng lọc (`validateSearchCriteria`):** Chặn sớm `minAmount < 0`, `maxAmount < 0`, `minAmount > maxAmount`, hoặc `fromDate > toDate` bằng `400 VALIDATION_FAILED` chuẩn RFC 9457 `ProblemDetail` trước khi chạm DB.

### 2.2. Sắp xếp Tất định (Deterministic Sorting) & Whitelist Trường Sắp xếp (`L2-04`)
- Mở rộng `ALLOWED_SORT_FIELDS = Set.of("id", "createdAt", "amount", "fee")` trong `TransactionService`.
- Khi nhiều giao dịch có cùng mức phí (`fee = 0.00` hoặc `2000.00`) hoặc cùng số tiền (`amount`), `resolvePageable()` luôn tự động nối thêm `Sort.Order.desc("id")` làm khóa phụ (secondary tie-breaker), đảm bảo thứ tự giữa các trang hoàn toàn tất định, không bao giờ bị lặp hoặc sót dòng khi chuyển trang.

### 2.3. Escape Wildcard `%`, `_`, `\` trong `CustomerSpecifications` & Chống N+1 (`HibernateStatistics`)
- Trong `CustomerSpecifications.withName()` và `withLocation()`, bổ sung `escapeLikePattern(raw)` thoát các ký tự `\`, `%`, `_` cùng tham số escape character `'\\'` trong `cb.like(..., '\\')`, ngăn chặn người dùng truyền `name=%` hoặc `location=_` để lấy toàn bộ bảng.
- Bổ sung kiểm chứng tự động bằng `HibernateStatistics` (`sessionFactory.getStatistics().getPrepareStatementCount() <= 2`) trong `TransactionSearchIntegrationTest` cho cả 3 API danh sách (`GET /api/v1/transactions`, `GET /api/v1/customers`, `GET /api/v1/accounts`).

### 2.4. Thiết kế Index (`V9__transaction_search_indexes.sql`) & Kết quả `EXPLAIN (ANALYZE, BUFFERS)`
- Bổ sung 2 composite index trong `V9__transaction_search_indexes.sql`:
  - `idx_tx_status_created ON core.transaction (status, created_at DESC)`
  - `idx_tx_amount_created ON core.transaction (amount DESC, created_at DESC)`
- Kết hợp với các index đã có từ `V4` (`idx_tx_source_created`, `idx_tx_target_created`, `idx_tx_type_created`), kết quả `EXPLAIN (ANALYZE, BUFFERS)` thực tế trên PostgreSQL 17:
  1. **Truy vấn lọc theo tài khoản (`source OR target`) + khoảng ngày (`fromDate`..`toDate`):**
     ```sql
     EXPLAIN (ANALYZE, BUFFERS)
     SELECT id, reference_no, type, status, amount, fee, created_at
     FROM core.transaction
     WHERE (source_account_id = 1 OR target_account_id = 1)
       AND created_at >= '2026-09-01T00:00:00+07:00'
       AND created_at <  '2026-10-01T00:00:00+07:00'
     ORDER BY created_at DESC, id DESC LIMIT 10;
     ```
     - **Kế hoạch thực thi thực tế trên PostgreSQL 17:**
       - Trên tập dữ liệu nhỏ / khoảng ngày hẹp: `Limit (cost=8.18..8.22 rows=2 width=366) (actual time=0.142..0.143 rows=4 loops=1)` -> `Incremental Sort (Sort Key: created_at DESC, id DESC; Presorted Key: created_at)` -> `Index Scan using idx_tx_created on transaction (actual time=0.027..0.031 rows=4 loops=1)` · `Buffers: shared hit=5` · `Execution Time: 0.195 ms`.
       - Trên tập dữ liệu lớn khi độ chọn lọc theo tài khoản cao hơn khoảng ngày: PostgreSQL chuyển sang `BitmapOr` kết hợp 2 nhánh `Bitmap Index Scan on idx_tx_source_created` và `Bitmap Index Scan on idx_tx_target_created`.
  2. **Truy vấn lọc theo trạng thái (`status`) + sắp xếp `created_at DESC`:**
     - **Kế hoạch thực thi:** `Index Scan using idx_tx_status_created on transaction (cost=0.14..8.16 rows=1 width=180) (actual time=0.015..0.018 rows=1 loops=1)` · `Buffers: shared hit=2` · `Execution Time: 0.032 ms`.
  3. **Số lượng câu lệnh SQL (kiểm chứng tự động qua `HibernateStatistics`):**
     - `GET /api/v1/transactions` (Admin): **$\le 2$ câu SQL** (`1 SELECT` trang dữ liệu + `1 SELECT count(*)` khi tổng số dòng vượt kích thước trang).
     - `GET /api/v1/customers` & `GET /api/v1/accounts`: **$\le 2$ câu SQL** (cam kết 0 lỗi N+1).

## 3. Các phương án đã cân nhắc & Đánh đổi

| Phương án | Ưu điểm | Nhược điểm / Lý do chọn hoặc loại |
|---|---|---|
| **`BitmapOr` trên 2 composite index `(source_account_id, created_at DESC)` và `(target_account_id, created_at DESC)`** *(Đã chọn cho `GET /api/v1/transactions`)* | Tương thích trực tiếp với `Specification<Transaction>` động khi ghép cùng 6 bộ lọc khác; cả 2 nhánh `OR` đều đẩy được điều kiện `created_at` xuống `Bitmap Index Scan`. | Phải thực hiện bước `BitmapOr` + `top-N heapsort` trên tập kết quả trung gian của tài khoản đó (sẽ được tối ưu thêm bằng Keyset Pagination ở Story 6.1 cho lịch sử sâu). |
| **Quy đổi `[fromDate, toDate]` thành `[fromDate 00:00, toDate+1 00:00)` theo `Asia/Ho_Chi_Minh`** *(Đã chọn)* | Bao trọn 100% các giao dịch có mili-giây/micro-giây cuối ngày và giữ nguyên phép so sánh trên cột `created_at` nên sử dụng được B-Tree index (không dùng hàm `DATE(created_at AT TIME ZONE ...)` ở vế trái làm mất khả năng dùng index). | Cần quy đổi `LocalDate` sang `Instant` ở tầng `TransactionService` thông qua `clock.getZone()`. |

## 4. Kiểm thử & Xác minh

- **Unit Tests (`TransactionServiceTest`, `CustomerSpecificationsTest`):** Kiểm tra kết hợp 7 bộ lọc, sắp xếp theo `fee`, chặn khoảng lọc ngược (`minAmount > maxAmount`, `fromDate > toDate`), và escape ký tự `%`, `_`, `\`.
- **Web Slice Tests (`TransactionControllerTest`):** Kiểm tra binding toàn bộ query params, định dạng ISO date lỗi (`400 VALIDATION_FAILED`), và chặn `403 ACCESS_DENIED` khi `CUSTOMER` lọc theo `accountId` của người khác.
- **Integration Tests (`TransactionSearchIntegrationTest`, `FlywayMigrationIT`):** Chạy trên PostgreSQL 17 Testcontainers kiểm chứng biên thời gian mili-giây giờ VN (`23:59:59.500+07:00`), cô lập dữ liệu giữa `ADMIN` và `CUSTOMER`, assert `HibernateStatistics <= 2` câu SQL, và assert `EXPLAIN (ANALYZE, BUFFERS)` dùng index.

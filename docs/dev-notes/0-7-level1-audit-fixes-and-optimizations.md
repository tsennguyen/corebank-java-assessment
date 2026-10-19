# Story 0.7: Level 1 Concurrency, Idempotency & SQL Statistics Hardening

- **PR:** [#26](https://github.com/tsennguyen/corebank-java-assessment/pull/26)
- **Mã yêu cầu:** `L1-04` [ĐỀ], `L1-05` [ĐỀ], `L1-06` [ĐỀ], `L1-07` [ĐỀ], `L1-08` [ĐỀ], `L1-09` [ĐỀ], `NC-01`, `NC-02`, `NC-08`, `FR-5..FR-12`, `NFR-1`, `NFR-2`

## 1. Tóm tắt

Story `0-7` rà soát và gia cố toàn diện các trường biên (edge cases) của toàn bộ hệ thống Core Banking Level 1 sau khi tích hợp lớp bảo mật JWT (`Story 3.1`), tập trung vào 5 trụ cột:
1. An toàn đồng thời khi đảo giao dịch (`TransactionRepository.findByIdForUpdate`) và bảo toàn `409 LOCK_TIMEOUT` kết hợp khóa tư vấn 64-bit `pg_advisory_xact_lock(hashtext(:scope), hashtext(:lockKey))` trong `IdempotencyService`.
2. Bảo vệ toàn diện các trường bất biến trên API `PATCH /api/v1/transactions/{id}` (kể cả trường tùy ý qua `@JsonAnySetter`) với mã lỗi `400 IMMUTABLE_FIELD` chuẩn RFC 9457.
3. Chuẩn hóa phân loại địa lý (`COALESCE(NULLIF(BTRIM(location), ''), 'UNKNOWN')`), loại trừ giao dịch đã đảo (`status = 'COMPLETED' AND type <> 'REVERSAL'`) khỏi báo cáo thống kê SQL và đồng bộ `saveAndFlush(original)` trước khi truy vấn `JdbcClient`.
4. Bổ sung Partial Covering Index `idx_tx_source_completed_created_amount` và Functional Partial Index `idx_customer_location` trong migration `V8__optimize_daily_limit_index_and_comments.sql`.
5. Khắc phục cấu hình Docker multi-stage build, `application-prod.yml` tương thích với cả PostgreSQL 17 trực tiếp trên VPS lẫn Cloud Connection Pooler (Supavisor/PgBouncer) và chuẩn hóa thứ tự thời gian trong `R__demo_seed.sql`.

## 2. Đã làm gì

### 2.1. Quản lý Giao dịch & Chống trùng lặp (`IdempotencyService`, `TransactionService`, `UpdateTransactionRequest`)
- **`IdempotencyRepository` & `IdempotencyService` (`execute`):**
  - Nâng cấp khóa tư vấn giao dịch lên dạng 2 tham số 64-bit `pg_advisory_xact_lock(hashtext(:scope), hashtext(:lockKey))` để tách biệt không gian hash của `scope` và `lockKey`, triệt tiêu rủi ro va chạm Birthday Paradox của hàm `hashtext` 32-bit đơn lẻ.
  - Bắt riêng `PessimisticLockingFailureException | QueryTimeoutException` để ném thẳng ra `GlobalExceptionHandler` trả về `409 LOCK_TIMEOUT`, không bao giờ nuốt lỗi khóa rồi chạy tiếp vào logic giao dịch.
  - Kiểm tra `record.isExpired(clock.instant())` ngay khi đọc bản ghi cũ để xóa bản ghi quá hạn 24h và cho phép thực thi mới mà không phải đợi cron job 03:00 sáng.
- **`TransactionService.reverseTransaction` & `updateTransactionMetadata`:**
  - Áp dụng khóa bi quan `transactionRepository.findByIdForUpdate(id)` (`SELECT ... FOR UPDATE`) trên bản ghi giao dịch gốc trước khi kiểm tra trạng thái, giúp 2 luồng đảo đồng thời tuần tự hóa sạch sẽ và luồng thứ hai nhận đúng `409 ALREADY_REVERSED`.
  - Gọi `transactionRepository.saveAndFlush(original)` khi chuyển trạng thái giao dịch gốc sang `REVERSED` để đẩy câu lệnh `UPDATE` xuống PostgreSQL ngay lập tức trước mọi truy vấn `JdbcClient`.
- **`UpdateTransactionRequest`:** Bổ sung `@JsonAnySetter` (`captureUnknownField`) và `@Size(max = 100)` / `@Size(max = 500)` để từ chối mọi trường không thuộc `description`/`location` với mã lỗi `400 IMMUTABLE_FIELD`.

### 2.2. Chống Write Skew `Customer`–`Account`, Thống kê SQL & Migration Index (`V8`)
- **Chống Write Skew khi xóa mềm khách hàng / mở tài khoản:** Cả `CustomerService.deleteCustomer(id)` và `AccountService.createAccount(request)` đều lấy khóa bi quan `customerRepository.findByIdAndDeletedAtIsNullForUpdate(id)` trên dòng `core.customer` đầu tiên, tuân thủ thứ tự khóa một chiều `Customer -> Account`.
- **`StatisticsRepository`:**
  - `findBalanceTierStatistics`: lọc `WHERE status = 'COMPLETED' AND type <> 'REVERSAL'` trong CTE `tx_per_account`.
  - `findLocationStatistics`: gom nhóm theo `COALESCE(NULLIF(BTRIM(c.location), ''), 'UNKNOWN')`.
- **`V8__optimize_daily_limit_index_and_comments.sql`:**
  - Tạo Partial Covering Index `idx_tx_source_completed_created_amount ON core.transaction (source_account_id, created_at DESC) INCLUDE (amount) WHERE status = 'COMPLETED' AND type IN ('WITHDRAWAL', 'TRANSFER', 'PAYMENT')`.
  - Tạo Functional Partial Index `idx_customer_location ON core.customer ((COALESCE(NULLIF(TRIM(location), ''), 'UNKNOWN'))) WHERE deleted_at IS NULL`.

### 2.3. Kết quả Đo kiểm `EXPLAIN (ANALYZE, BUFFERS)` trên PostgreSQL 17
- Truy vấn tổng hạn mức rút/chuyển trong ngày (`sumOutgoingAmountSince`):
  ```sql
  EXPLAIN (ANALYZE, BUFFERS)
  SELECT COALESCE(SUM(amount), 0)
  FROM core.transaction
  WHERE source_account_id = 1
    AND status = 'COMPLETED'
    AND type IN ('WITHDRAWAL', 'TRANSFER', 'PAYMENT')
    AND created_at >= CURRENT_DATE;
  ```
  - **Kế hoạch thực thi:** `Index Only Scan using idx_tx_source_completed_created_amount on transaction (cost=0.14..4.16 rows=1 width=32) (actual time=0.019..0.020 rows=1 loops=1)`
  - **Heap Fetches:** `0` · **Buffers:** `shared hit=2` · **Execution Time:** `0.038 ms` (1 câu SQL duy nhất).

## 3. Vì sao làm vậy & Phương án đã cân nhắc

| Quyết định kỹ thuật | Phương án chọn | Phương án đã loại & Lý do |
|---|---|---|
| **Tuần tự hóa Đảo giao dịch (`DELETE /transactions/{id}`)** | Khóa bi quan `findByIdForUpdate(id)` trên dòng `core.transaction` trước khi khóa `Account(minId) -> Account(maxId)` | **Chỉ dựa vào `UNIQUE(reversal_of_id)` khi `INSERT`:** Gây lỗi `DataIntegrityViolationException` ở cuối transaction và đánh dấu transaction PostgreSQL là `aborted` (`25P02`) thay vì trả lỗi nghiệp vụ `409 ALREADY_REVERSED` ngay từ đầu. |
| **Chặn sửa trường tài chính trong `PATCH /transactions/{id}`** | Đón các trường tài chính phổ biến + `@JsonAnySetter` trong `UpdateTransactionRequest` và trả `400 IMMUTABLE_FIELD` tường minh | **Chỉ kiểm tra 5 trường cố định:** Bỏ lọt các trường như `createdAt`, `referenceNo`, `reversalOfId` nếu client cố tình gửi kèm. |
| **Đồng bộ JPA & `JdbcClient` trong `reverseTransaction`** | Gọi `transactionRepository.saveAndFlush(original)` | **Chỉ gọi `save(original)`:** Hibernate hoãn câu lệnh `UPDATE core.transaction SET status = 'REVERSED'` đến cuối transaction; truy vấn SQL thuần qua `JdbcClient` không kích hoạt auto-flush của JPA. |

## 4. Bẫy cần nhớ

1. **PostgreSQL Transaction Abortion (`25P02`):** Không bao giờ dùng `try / catch (DataIntegrityViolationException)` bên trong một `@Transactional` rồi tiếp tục chạy câu lệnh SQL khác trên cùng connection đó.
2. **Sự lệch pha giữa JPA Persistence Context và `JdbcClient`:** Khi kết hợp Spring Data JPA (ghi) và `JdbcClient` (đọc thống kê) trong cùng một giao dịch, bắt buộc dùng `saveAndFlush()` để đẩy thay đổi từ L1 Cache của Hibernate xuống PostgreSQL buffer.
3. **Thứ tự khóa DAG toàn hệ thống:** Luồng quản lý khách hàng/tài khoản khóa `Customer -> Account`; luồng giao dịch/đảo giao dịch khóa `Transaction -> Account(minId) -> Account(maxId)` — đồ thị một chiều không bao giờ tạo chu trình deadlock.

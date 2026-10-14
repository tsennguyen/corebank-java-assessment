# Story 0.7: Level 1 Concurrency, Idempotency & SQL Statistics Hardening

- **PR:** [#26](https://github.com/tsennguyen/corebank-java-assessment/pull/26)
- **Mã yêu cầu:** `L1-04` [ĐỀ], `L1-05` [ĐỀ], `L1-06` [ĐỀ], `L1-07` [ĐỀ], `L1-08` [ĐỀ], `L1-09` [ĐỀ], `NC-01`, `NC-02`, `NC-08`, `FR-5..FR-12`, `NFR-1`, `NFR-2`

## 1. Tóm tắt

Story `0-7` rà soát và gia cố toàn diện các trường biên (edge cases) của toàn bộ hệ thống Core Banking Level 1 sau khi tích hợp lớp bảo mật JWT (`Story 3.1`), tập trung vào 5 trụ cột:
1. An toàn đồng thời và chống lỗi `25P02 (current transaction is aborted)` trong `IdempotencyService` và `TransactionService.reverseTransaction()`.
2. Bảo vệ các trường bất biến tài chính (`amount`, `fee`, `type`, `accountId`) trên API `PUT /api/v1/transactions/{id}` với mã lỗi `400 VALIDATION_ERROR` chuẩn RFC 9457.
3. Chuẩn hóa phân loại địa lý (`NULLIF(TRIM(location), '') -> 'UNKNOWN'`), loại trừ giao dịch đã đảo (`status = 'COMPLETED'`) khỏi báo cáo thống kê SQL và đồng bộ JPA flush trước khi truy vấn `JdbcClient`.
4. Bổ sung covering index `idx_txn_daily_limit` và functional partial index `idx_customer_location` trong migration `V8__optimize_daily_limit_index_and_comments.sql`.
5. Khắc phục cấu hình Docker multi-stage build, `application-prod.yml` tương thích Supabase Supavisor và thứ tự thời gian trong `R__demo_seed.sql`.

## 2. Đã làm gì

### 2.1. Quản lý Giao dịch & Chống trùng lặp (`IdempotencyService`, `TransactionService`, `UpdateTransactionRequest`)
- **`IdempotencyService` (`execute`):** Thay thế mô hình `INSERT ... ON CONFLICT DO NOTHING` (hoặc bắt `DataIntegrityViolationException` trong cùng transaction gây lỗi PostgreSQL `25P02`) bằng cơ chế khóa tư vấn giao dịch `pg_advisory_xact_lock(hashtext(scope || ':' || key))` kết hợp kiểm tra `findByIdempotencyKeyAndScope`. Mọi câu lệnh SQL trong cùng `@Transactional` luôn sạch trạng thái.
- **`TransactionService.reverseTransaction`:**
  - Áp dụng `idempotencyRepository.acquireAdvisoryLock("REV", String.valueOf(id))` ngay đầu giao dịch để tuần tự hóa 2 yêu cầu đảo cùng một giao dịch gốc trước khi kiểm tra `existsByReversedTransactionId(id)`.
  - Gọi `transactionRepository.saveAndFlush(original)` khi chuyển trạng thái giao dịch gốc sang `REVERSED` để đảm bảo các truy vấn `JdbcClient` chạy ngay lập tức nhìn thấy trạng thái mới trong DB.
- **`UpdateTransactionRequest`:** Đón nhận thêm các trường tùy chọn `amount`, `fee`, `type`, `accountId`, `counterpartyAccountId`, `status` trong JSON payload và chủ động từ chối bằng `BusinessRuleException(ErrorCode.VALIDATION_ERROR)` (`HTTP 400`) nếu client cố tình gửi bất kỳ trường tài chính bất biến nào khác `null`.

### 2.2. Thống kê SQL & Migration Index (`StatisticsRepository`, `V8__optimize_daily_limit_index_and_comments.sql`)
- **`StatisticsRepository.getLocationStatistics`:** Chuẩn hóa biểu thức nhóm địa lý bằng `COALESCE(NULLIF(TRIM(c.location), ''), 'UNKNOWN')` để gộp mọi chuỗi rỗng hoặc chỉ chứa khoảng trắng vào nhóm `UNKNOWN`, đồng thời chỉ cộng tổng số dư của các tài khoản `a.status = 'ACTIVE'`.
- **`V8__optimize_daily_limit_index_and_comments.sql`:**
  - Tạo covering composite index `idx_txn_daily_limit ON core.transaction (account_id, created_at, type) INCLUDE (amount) WHERE status = 'COMPLETED'` phục vụ truy vấn kiểm tra hạn mức rút/chuyển tiền trong ngày bằng **Index Only Scan**.
  - Tạo functional partial index `idx_customer_location ON core.customer ((COALESCE(NULLIF(TRIM(location), ''), 'UNKNOWN'))) WHERE deleted_at IS NULL` khớp chính xác với biểu thức `GROUP BY` của báo cáo địa lý.
  - Bổ sung đầy đủ `COMMENT ON TABLE / COLUMN` tiếng Anh cho bảng `core.idempotency_key`.

### 2.3. Chuẩn hóa lỗi RFC 9457 & Hạ tầng Triển khai (`GlobalExceptionHandler`, `Dockerfile`, `application-prod.yml`)
- **`GlobalExceptionHandler`:** Mở rộng `HttpMessageNotReadableException` để bóc tách `InvalidFormatException` trên kiểu `enum` (liệt kê rõ danh sách giá trị hợp lệ của enum trong thông điệp lỗi) và chuẩn hóa thông điệp khi JSON body bị rỗng hoặc sai cú pháp.
- **`application-prod.yml`:** Loại bỏ `options: "-c lock_timeout=3000"` khỏi `data-source-properties` để tương thích với bộ điều phối kết nối Supabase Supavisor / PgBouncer ở chế độ transaction pooling (vốn từ chối startup parameter `options`), giữ `connection-init-sql: SET lock_timeout = '3s'`.

## 3. Vì sao làm vậy & Phương án đã cân nhắc

| Quyết định kỹ thuật | Phương án chọn | Phương án đã loại & Lý do |
|---|---|---|
| **Tuần tự hóa Idempotency Key & Reversal** | Dùng `pg_advisory_xact_lock(hashtext(...))` trước khi đọc/ghi trong cùng transaction | **Bắt `DataIntegrityViolationException` khi trùng khóa UNIQUE:** Trong PostgreSQL, mọi lỗi constraint violation đều đánh dấu transaction hiện hành là `aborted` (`25P02`), khiến Hibernate không thể thực thi tiếp bất kỳ câu lệnh nào nếu không mở `REQUIRES_NEW` tốn thêm kết nối HikariCP. |
| **Chặn sửa trường tài chính trong `PUT /transactions/{id}`** | Đón trường bất biến trong `UpdateTransactionRequest` và trả `400 VALIDATION_ERROR` tường minh | **Bật `fail-on-unknown-properties: true` toàn cục:** Vi phạm nguyên tắc Robustness Principle (Postel's Law) cho toàn bộ các endpoint khác và không phân biệt được lỗi gửi nhầm trường tài chính bất biến với trường thừa vô hại. |
| **Đồng bộ JPA & `JdbcClient` trong `reverseTransaction`** | Gọi `transactionRepository.saveAndFlush(original)` | **Chỉ gọi `save(original)`:** Hibernate hoãn câu lệnh `UPDATE core.transaction SET status = 'REVERSED'` đến cuối transaction hoặc trước truy vấn JPQL cùng bảng; truy vấn SQL thuần qua `JdbcClient` không kích hoạt auto-flush của JPA. |

## 4. Bẫy cần nhớ

1. **PostgreSQL Transaction Abortion (`25P02`):** Không bao giờ dùng `try / catch (DataIntegrityViolationException)` bên trong một `@Transactional` rồi tiếp tục chạy câu lệnh SQL khác trên cùng connection đó.
2. **Sự lệch pha giữa JPA Persistence Context và `JdbcClient`:** Khi kết hợp Spring Data JPA (ghi) và `JdbcClient` / `JdbcTemplate` (đọc thống kê) trong cùng một luồng kiểm thử hoặc giao dịch, bắt buộc dùng `saveAndFlush()` để đẩy thay đổi từ bộ nhớ L1 Cache của Hibernate xuống PostgreSQL buffer.
3. **Functional Index trong PostgreSQL:** Biểu thức trong `CREATE INDEX` phải khớp từng ký tự và kiểu dữ liệu với biểu thức trong `SELECT ... GROUP BY` thì bộ tối ưu hóa truy vấn (Query Planner) mới sử dụng được index.

## 5. Cách chạy & Kiểm thử

```bash
# Chạy toàn bộ unit tests + Spotless + Checkstyle + JaCoCo
./mvnw clean verify

# Chạy các bài kiểm thử tích hợp Testcontainers liên quan đến Story 0-7
./mvnw verify -Dit.test=Level1CoreBankingE2EIT,TransactionReversalIT,StatisticsIntegrationTest,FlywayMigrationIT
```

# Dev Note — Story 4.3: Phát hiện giao dịch bất thường thời gian thực trên Virtual Threads (`L3-03`, `L3-04`, `L3-10`)

## 1. Bối cảnh và yêu cầu
Trong hệ thống Core Banking, mỗi giao dịch chiều đi (`WITHDRAWAL`, `TRANSFER`, `PAYMENT`) cần được đánh giá tự động để phát hiện sớm hành vi gian lận và ghi nhận cảnh báo vào bảng `core.alert` (`L3-03`, `L3-04`, `FR-22`) theo 2 quy tắc cốt lõi:
1. **Vượt ngưỡng giá trị (`AMOUNT_THRESHOLD`):** Số tiền giao dịch $\ge$ `app.fraud.amount-threshold` (mặc định `50.000.000` VND) → sinh cảnh báo mức `HIGH`, trạng thái `NEW`.
2. **Tần suất giao dịch quá nhanh liên tiếp (`VELOCITY`):** Cùng một tài khoản nguồn thực hiện quá `app.fraud.velocity.max-count` (mặc định `5`) giao dịch chiều đi trong cửa sổ trượt `app.fraud.velocity.window-seconds` (mặc định `60` giây) → giao dịch thứ 6 sinh cảnh báo mức `MEDIUM`, trạng thái `NEW`.

Yêu cầu phi chức năng bắt buộc (`L3-10`, `NFR-4`, `ADR-012`, `ADR-015`):
- Không được làm chậm thời gian phản hồi của giao dịch tiền tệ và không giữ khóa `FOR UPDATE` lâu hơn.
- Nếu giao dịch tiền tệ bị rollback → tuyệt đối không đánh giá và không sinh `Alert`.
- Nếu một quy tắc cảnh báo ném lỗi hoặc Redis gặp sự cố → giao dịch tiền tệ vẫn trả về `201 Created`, lỗi được ghi log, đếm metric `fraud.evaluation.errors` và các quy tắc còn lại vẫn tiếp tục chạy.

## 2. Thiết kế và quyết định kỹ thuật

### 2.1. Tách rời đường đi của tiền bằng `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` Virtual Threads (`FraudEventListener`, `AsyncConfig`)
- Khi `TransactionService` hoàn tất ghi nhận dòng tiền, nó phát sự kiện miền `TransactionCompletedEvent`.
- `FraudEventListener.onTransactionCompleted(TransactionCompletedEvent)` được gắn đồng thời:
  - `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`: Chỉ kích hoạt **sau khi** transaction chuyển tiền đã commit thành công xuống PostgreSQL. Nếu giao dịch bị rollback (do thiếu số dư, vượt hạn mức, hoặc lỗi hệ thống), sự kiện bị hủy bỏ hoàn toàn.
  - `@Async`: Chạy trên `SimpleAsyncTaskExecutor` bật `setVirtualThreads(true)` (`vt-async-*`) trong `AsyncConfig`. Luồng HTTP xử lý giao dịch trả ngay `201 Created` cho client mà không phải chờ bộ quy tắc gian lận hay thao tác I/O Redis/DB của bảng `alert`.

### 2.2. Biến thể Chain of Responsibility không dừng sớm (`AnomalyRule` & `AnomalyRuleChain`)
- Mỗi quy tắc triển khai interface `AnomalyRule` (`AlertRuleCode ruleCode()`, `Optional<AnomalyFinding> evaluate(TransactionSnapshot)`):
  - `AmountThresholdRule` (`@Order(1)`): Kiểm tra `snapshot.amount().compareTo(amountThreshold) >= 0` → trả về `AnomalyFinding(AMOUNT_THRESHOLD, HIGH, reason)`.
  - `VelocityRule` (`@Order(2)`): Gọi `velocityWindow.recordAndCount(accountId, transactionId) > maxCount` → trả về `AnomalyFinding(VELOCITY, MEDIUM, reason)`.
- `AnomalyRuleChain` duyệt qua **toàn bộ** `List<AnomalyRule>` mà không dừng sớm (non-short-circuiting): một giao dịch 60 triệu đồng đồng thời là giao dịch thứ 6 trong 60 giây sẽ tạo đủ **2 bản ghi `Alert`** (`AMOUNT_THRESHOLD` và `VELOCITY`).
- Mỗi rule được bọc trong khối `try-catch` độc lập: nếu một rule ném ngoại lệ, `AnomalyRuleChain` ghi log lỗi, tăng bộ đếm Micrometer `fraud.evaluation.errors{rule=...}` và tiếp tục chạy rule kế tiếp.

### 2.3. Cửa sổ trượt vận tốc 2 tầng: Redis Sorted Set (`ZSET`) + Fallback `ArrayDeque` với `ReentrantLock` (`SlidingWindowVelocityTracker`)
- Khi `app.redis.enabled=true`, `SlidingWindowVelocityTracker` sử dụng cấu trúc **Redis Sorted Set (`ZSET`)** tại khóa `vel:{accountId}` với `score = epochMillis` và `member = transactionId`:
  1. `ZREMRANGEBYSCORE vel:{accountId} -inf (nowMs - windowMs - 1)`: Loại bỏ mọi giao dịch đã rơi ra ngoài cửa sổ trượt 60 giây.
  2. `ZADD vel:{accountId} nowMs {transactionId}`: Ghi nhận giao dịch hiện tại (tự động idempotent nếu trùng `transactionId`).
  3. `ZCARD vel:{accountId}`: Đếm chính xác số giao dịch trong cửa sổ trượt $[now - 60\text{s}, now]$.
  4. `EXPIRE vel:{accountId} 60`: Tự động dọn bộ nhớ Redis khi tài khoản không còn giao dịch mới.
- Khi `app.redis.enabled=false` hoặc khi Redis ném `RuntimeException`, hệ thống tự động fallback sang bộ nhớ cục bộ dùng `ConcurrentHashMap<Long, AccountSlidingWindow>`, bên trong mỗi tài khoản là một `ArrayDeque<VelocityEntry>` được bảo vệ bởi `ReentrantLock` (tuân thủ `performance.md` mục 14: **không dùng `synchronized`** để không ghim carrier thread của Java 21 Virtual Threads).
- Toàn bộ việc lấy thời gian hiện tại đều đi qua bean `Clock`, cho phép unit test dịch chuyển thời gian giả lập chính xác tới từng giây.

### 2.4. Ghi nhận `Alert` Idempotent với `ON CONFLICT DO NOTHING` (`AlertService`, `V13__alert.sql`)
- Bảng `core.alert` (`V13__alert.sql`) định nghĩa ràng buộc `CONSTRAINT uk_alert_tx_rule UNIQUE (transaction_id, rule_code)`.
- `AlertService.raise(...)` chạy trong `@Transactional(propagation = Propagation.REQUIRES_NEW)` và thực thi câu lệnh SQL nguyên tử qua `JdbcClient`:

```sql
INSERT INTO core.alert (
    transaction_id, account_id, rule_code, severity, reason, status, created_at, updated_at, version
)
SELECT t.id, a.id, :ruleCode, :severity, :reason, 'NEW', :now, :now, 0
FROM core.transaction t
JOIN core.account a ON a.id = :accountId
WHERE t.id = :transactionId
ON CONFLICT (transaction_id, rule_code) DO NOTHING
```

- Khi `inserted == 1` (cảnh báo mới thực sự được thêm), hệ thống tăng bộ đếm Micrometer `banking.alerts.raised{rule=...}`. Nếu sự kiện bị phát lại hoặc Admin chạy API quét lại lịch sử (Story 4.4) trên cùng khoảng thời gian, `ON CONFLICT (transaction_id, rule_code) DO NOTHING` đảm bảo không bao giờ sinh cảnh báo trùng và không ném `DataIntegrityViolationException`.

## 3. Hiệu năng truy vấn và kiểm chứng chỉ mục (`performance.md`)

### 3.1. Số lượng câu lệnh SQL
| Luồng nghiệp vụ | Số câu SQL trên luồng HTTP giao dịch | Số câu SQL trên luồng `@Async` Virtual Thread |
|---|---|---|
| Giao dịch thường (không vi phạm rule) | **0 câu SQL thêm** (0ms overhead cho giao dịch tiền) | **0 câu SQL** (chỉ kiểm tra ngưỡng trong RAM + cửa sổ trượt) |
| Giao dịch vi phạm 1 rule (`AMOUNT_THRESHOLD` hoặc `VELOCITY`) | **0 câu SQL thêm** trên luồng HTTP | **1 câu `INSERT ... ON CONFLICT DO NOTHING`** qua `JdbcClient` |
| Giao dịch vi phạm cả 2 rule | **0 câu SQL thêm** trên luồng HTTP | **2 câu `INSERT ... ON CONFLICT DO NOTHING`** qua `JdbcClient` |

### 3.2. Kế hoạch thực thi (`EXPLAIN (ANALYZE, BUFFERS)`)
Migration `V13__alert.sql` tạo sẵn 4 composite B-tree indexes tuân thủ quy tắc "filter columns first, sort columns (`created_at DESC, id DESC`) second" (`idx_alert_status_created`, `idx_alert_account_created`, `idx_alert_rule_created`, `idx_alert_created`). Kiểm chứng trong `AnomalyDetectionIntegrationTest`:

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, transaction_id, account_id, rule_code, severity, status, created_at
FROM core.alert
WHERE status = 'NEW'
ORDER BY created_at DESC, id DESC
LIMIT 20;
-- Limit  (actual time=0.018..0.020 rows=1 loops=1)
--   -> Index Scan using idx_alert_status_created on alert  (actual time=0.016..0.018 rows=1 loops=1)
--        Index Cond: ((status)::text = 'NEW'::text)
```

## 4. Các phương án đã cân nhắc (Alternatives Considered)

1. **Chạy kiểm tra bất thường đồng bộ ngay trong `TransactionService` trước khi commit:**
   - *Bác bỏ:* Làm tăng thời gian giữ khóa `FOR UPDATE` trên bảng `core.account` (gây nghẽn các luồng chuyển tiền song song) và vi phạm nguyên tắc cô lập lỗi: nếu Redis hoặc bảng `alert` gặp sự cố tạm thời, giao dịch chuyển tiền hợp lệ của khách hàng sẽ bị rollback.
2. **Dùng bộ đếm cửa sổ cố định (`INCR` + `EXPIRE`) thay vì Redis `ZSET` / `ArrayDeque`:**
   - *Bác bỏ:* Cửa sổ cố định bị lỗi bùng nổ ở ranh giới phút (boundary burst: 5 giao dịch ở giây 59 + 5 giao dịch ở giây 61 = 10 giao dịch trong 2 giây nhưng không bị phát hiện). Cửa sổ trượt bằng `ZSET` (`score = epochMs`) và `ArrayDeque` đảm bảo đếm chính xác trong mọi khoảng trượt 60 giây bất kỳ.
3. **Dùng `jpaRepository.save(alert)` và bắt `DataIntegrityViolationException` khi trùng `(transaction_id, rule_code)`:**
   - *Bác bỏ:* Khi Hibernate gặp lỗi `UniqueConstraint`, toàn bộ `Session` và transaction hiện tại bị đánh dấu `rollback-only`, gây lỗi `UnexpectedRollbackException`. Dùng `INSERT ... ON CONFLICT (transaction_id, rule_code) DO NOTHING` qua `JdbcClient` xử lý chống trùng êm ái ngay tại tầng PostgreSQL.

## 5. Bẫy kỹ thuật và bài học (Gotchas)

- **Bẫy `@TransactionalEventListener(AFTER_COMMIT)` chạy trên luồng `@Async` không còn transaction:** Vì luồng Virtual Thread mới không kế thừa transaction đã đóng của luồng HTTP, `AlertService.raise(...)` được đánh dấu `@Transactional(propagation = Propagation.REQUIRES_NEW)` để mở một transaction ngắn độc lập khi ghi vào `core.alert`.
- **Bẫy dọn dẹp dữ liệu trong Integration Test khi luồng `@Async` đang chạy nền:** Trong các bài test tích hợp chạy song song hoặc xóa dữ liệu ở `@AfterEach`, một luồng `@Async` từ test trước có thể vừa kịp ghi `Alert` ngay khi `@AfterEach` đang xóa `core.transaction`. Việc sử dụng `INSERT INTO core.alert ... SELECT t.id, a.id ... FROM core.transaction t JOIN core.account a ...` kết hợp `ON DELETE CASCADE` trên khóa ngoại giúp triệt tiêu hoàn toàn nguy cơ lỗi khóa ngoại khi dọn dẹp test.

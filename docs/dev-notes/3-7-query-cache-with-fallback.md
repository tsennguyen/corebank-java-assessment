# Story 3.7: Query Cache with Fallback (`Redis` + `Caffeine` + `AFTER_COMMIT` Eviction)

- **Mã yêu cầu:** `L2-08` [ĐỀ], `G-05` [ĐỀ+], `NFR-3`, `ADR-009`
- **Nhánh:** `feat/3-7-query-cache-with-fallback`

## 1. Bối cảnh & Bài toán

Theo yêu cầu `L2-08` (Level 2) và `G-05`, hệ thống cần tích hợp bộ nhớ đệm (Cache-Aside) cho danh sách tài khoản theo khách hàng (`accountsByCustomer` — phục vụ `GET /api/v1/me/accounts`) và lịch sử giao dịch gần nhất của tài khoản (`recentTransactions` — phục vụ trang đầu `GET /api/v1/accounts/{id}/transactions`). Trong hệ thống Core Banking, việc tích hợp cache đặt ra 3 bài toán kiến trúc khắt khe:

1. **Chống dữ liệu bẩn do Race Condition giữa Cache và Transaction CSDL:** Nếu xóa cache trước khi database transaction thực sự `COMMIT`, một luồng đọc đồng thời có thể thấy cache miss, đọc phải bản chụp cũ từ PostgreSQL (`READ COMMITTED`) và nạp lại số dư cũ vào cache. Ngược lại, nếu giao dịch bị rollback do lỗi nghiệp vụ (`INSUFFICIENT_BALANCE`, `LIMIT_EXCEEDED`), cache không được phép bị xóa oan hay ghi đè dữ liệu chưa commit.
2. **Chống điểm lỗi đơn (Single Point of Failure) khi Redis gặp sự cố:** Khi chạy trên môi trường không có Redis (`app.redis.enabled=false`) hoặc khi máy chủ Redis bị đứt kết nối/timeout lúc đang vận hành (`app.redis.enabled=true`), hệ thống không được phép trả lỗi `500` cho khách hàng mà phải tự động hạ cấp (Graceful Degradation) đọc thẳng từ PostgreSQL.
3. **Bảo vệ tuyệt đối đường đi của tiền:** Mọi luồng kiểm tra số dư, kiểm tra hạn mức và hạch toán (`TransactionService`, `LimitPolicy`) bắt buộc đọc trực tiếp từ PostgreSQL dưới khóa bi quan `SELECT ... FOR UPDATE`, tuyệt đối không bao giờ đọc số dư từ cache.

---

## 2. Quyết định Thiết kế & Giải pháp Kỹ thuật

### 2.1. Kiến trúc Cache Kép (`CacheConfig` & `CacheProperties`)
- **Chế độ Redis (`app.redis.enabled=true` — Docker Compose / Production):**
  - Khởi tạo `RedisCacheManager` với tiền tố khóa `corebank::`, tắt lưu giá trị `null`, cấu hình TTL riêng biệt:
    - `accountsByCustomer`: TTL **10 phút (`600s`)**, dùng `Jackson2JsonRedisSerializer<List<AccountResponse>>` định kiểu tường minh (`constructCollectionType(List.class, AccountResponse.class)`).
    - `recentTransactions`: TTL **5 phút (`300s`)**, dùng `Jackson2JsonRedisSerializer<PageResponse<TransactionResponse>>` định kiểu tường minh (`constructParametricType(PageResponse.class, TransactionResponse.class)`).
    - `customerTypes`: TTL **60 phút (`3600s`)**, chuẩn bị sẵn cho bảng phân hạng khách hàng ở Level 3.
  - **Vì sao dùng `Jackson2JsonRedisSerializer` định kiểu tường minh thay vì `DefaultTyping` đa hình?** Trong Java 21, `Stream.toList()` trả về `ImmutableCollections$ListN` và các DTO là `record` bất biến (`AccountResponse`, `TransactionResponse`, `PageResponse`). Định kiểu `JavaType` tường minh giúp JSON lưu trong Redis hoàn toàn thuần khiết (không chứa metadata `@class` nhạy cảm bảo mật) và giải mã trực tiếp qua canonical constructor của Java `record` mà không bao giờ vướng lỗi `InvalidDefinitionException`.
- **Chế độ Caffeine Fallback (`app.redis.enabled=false` — Local / Test):**
  - Tự động kích hoạt `CaffeineCacheManager` trong bộ nhớ (`maximumSize = 10_000`, `recordStats()`, TTL tương ứng `2m` / `1m` / `60m`) và tắt `management.health.redis.enabled` để `/actuator/health` luôn phản ánh đúng trạng thái hạ tầng đang bật.

### 2.2. Hạ cấp An toàn ở Runtime (`ResilientCacheErrorHandler`)
- Triển khai `CacheErrorHandler` tùy chỉnh: khi bất kỳ thao tác `GET`, `PUT`, `EVICT`, hoặc `CLEAR` nào ném `RuntimeException` (ví dụ `RedisConnectionFailureException` hoặc `QueryTimeoutException`), bộ xử lý ghi log `WARN` kèm tên cache + khóa và **nuốt ngoại lệ**.
- Nhờ đó, lỗi đọc Redis được coi như một lần *cache miss* thông thường, Spring tự động gọi xuống PostgreSQL và trả `200 OK` cho người dùng.

### 2.3. Xóa Cache Nguyên tử ở Pha `AFTER_COMMIT` (`CacheEvictionListener`)
- Thay vì gắn `@CacheEvict` trực tiếp trên các hàm `@Transactional` ghi tiền, `CacheEvictionListener` sử dụng `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`:
  - Khi nhận `TransactionCompletedEvent` (từ `deposit`, `withdraw`, `transfer`, `payment`, `reverseTransaction`, `executeScheduledTransaction`): xóa `recentTransactions` của cả `sourceAccountId` và `targetAccountId`, đồng thời tra `customerId` liên quan để xóa `accountsByCustomer` của cả người chuyển và người nhận.
  - Khi nhận `TransactionMetadataUpdatedEvent` (`PATCH /api/v1/transactions/{id}`): xóa `recentTransactions` của tài khoản nguồn và đích.
  - Khi nhận `AccountStatusChangedEvent` (mở tài khoản, khóa/mở khóa, đóng tài khoản) hoặc `AccountLimitsUpdatedEvent` (`PATCH /api/v1/accounts/{id}`): xóa ngay `accountsByCustomer` của khách hàng sở hữu tài khoản đó.
- Khi một giao dịch bị lỗi nghiệp vụ (ví dụ `422 INSUFFICIENT_BALANCE`) và rollback, pha `AFTER_COMMIT` không kích hoạt → dữ liệu cache hiện hữu được bảo toàn nguyên vẹn.

---

## 3. Bằng chứng Hiệu năng & Số câu SQL (`Rule 16 — performance.md`)

Kiểm chứng tự động bằng `HibernateStatistics` trên PostgreSQL 17 Testcontainers trong `CacheFallbackIT`:

| Kịch bản truy vấn | Lần gọi 1 (Cache Miss) | Lần gọi 2 (Cache Hit) | Sau khi Commit Giao dịch / Đổi trạng thái |
|---|---|---|---|
| `AccountService.getAccountsByCustomerId(customerId)` (`accountsByCustomer`) | **1 câu SQL** (`SELECT ... FROM core.account WHERE customer_id = ? ORDER BY id DESC`) | **0 câu SQL** (đọc 100% từ cache) | Xóa cache ở pha `AFTER_COMMIT` → lần đọc kế tiếp chạy **1 câu SQL** lấy số dư mới nhất |
| `TransactionService.getAccountTransactions(accountId, page=0, size=20)` (`recentTransactions`) | **$\le 2$ câu SQL** (`existsById` + `SELECT ... FROM core.transaction` qua composite index) | **0 câu SQL** (đọc 100% từ cache) | Xóa cache cả tài khoản nguồn & đích ở pha `AFTER_COMMIT` → lần đọc kế tiếp lấy danh sách giao dịch mới nhất |
| `TransactionService.getAccountTransactions(accountId, page=1, size=20)` (trang $\ge 1$ hoặc sort tùy biến) | Đi thẳng xuống PostgreSQL (**$\le 3$ câu SQL**), không làm bẩn cache trang đầu | Đi thẳng xuống PostgreSQL | — |

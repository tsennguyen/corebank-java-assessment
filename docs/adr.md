# Hồ sơ Quyết định Kiến trúc (Architecture Decision Records — ADR-001 → ADR-018)

Tài liệu này tổng hợp các quyết định thiết kế kiến trúc cốt lõi (Architecture Decision Records — ADR) được áp dụng xuyên suốt hệ thống **Core Banking System**.

---

## ADR-001: Kiến trúc Modular Monolith với Ranh giới Phụ thuộc Một chiều
- **Bối cảnh:** Hệ thống cần đảm bảo tính toàn vẹn giao dịch ACID tuyệt đối trên sổ cái tiền tệ nhưng vẫn sẵn sàng tách thành các dịch vụ độc lập (Microservices) khi mở rộng quy mô.
- **Quyết định:** Tổ chức mã nguồn theo kiến trúc **Modular Monolith** (`customer`, `account`, `transaction`, `auth`, `statistics`, `recurring`, `fraud`, `report`, `common`), mỗi module chia 4 tầng rõ rệt (`api -> application -> domain <- infrastructure`). Giao tiếp liên module khi phát sinh sự kiện phụ (như xóa cache hay đánh giá bất thường AML) thực hiện qua Spring Application Events (`@TransactionalEventListener(phase = AFTER_COMMIT)`).
- **Phương án đã loại:** Microservices ngay từ đầu (tạo độ trễ mạng, phức tạp hóa giao dịch phân tán 2PC/Saga không cần thiết cho phạm vi Core Ledger đơn cụm).

## ADR-002: Chống Deadlock bằng Khóa Bi quan Thứ tự ID Tăng dần & `lock_timeout = 3s`
- **Bối cảnh:** Khi hai giao dịch chuyển khoản chéo `A -> B` và `B -> A` diễn ra đồng thời, việc khóa tài khoản nguồn trước rồi mới khóa tài khoản đích sẽ dẫn đến Deadlock (Circular Wait).
- **Quyết định:** Luôn thu thập khóa bi quan `SELECT ... FOR UPDATE` (`LockModeType.PESSIMISTIC_WRITE`) theo thứ tự định danh tăng dần: khóa `Math.min(sourceId, targetId)` trước, `Math.max(sourceId, targetId)` sau; kết hợp `SET lock_timeout = '3s'` ở mức kết nối HikariCP và ánh xạ lỗi tranh chấp khóa thành HTTP `409 LOCK_TIMEOUT` (RFC 9457 `ProblemDetail`).
- **Hệ quả:** Triệt tiêu 100% khả năng xảy ra Deadlock giữa các luồng chuyển tiền và đảo giao dịch (đã kiểm chứng bằng `TransferConcurrencyIT` với 100 lệnh chuyển đồng thời trên 25 luồng worker và 10 luồng chuyển đối xứng).

## ADR-003: Biểu diễn Tiền tệ Chính xác Tuyệt đối với `NUMERIC(19, 2)` & `BigDecimal` (`HALF_UP`)
- **Bối cảnh:** Kiểu số thực dấu phẩy động (`float`, `double`) gây sai số làm tròn nhị phân không thể chấp nhận trong nghiệp vụ ngân hàng.
- **Quyết định:** Mọi cột tiền tệ trong PostgreSQL đều dùng kiểu `NUMERIC(19, 2)`; trong Java dùng `BigDecimal` với `RoundingMode.HALF_UP` (scale = 2) và so sánh giá trị bằng `compareTo()` thay vì `equals()`.

## ADR-004: Đảo Giao dịch Bút toán Đối ứng Bất biến (Compensating Double-Entry Reversal)
- **Bối cảnh:** Yêu cầu nghiệp vụ cho phép "hủy/xóa" giao dịch sai sót (`DELETE /api/v1/transactions/{id}`), nhưng chuẩn kiểm toán tài chính nghiêm cấm lệnh `DELETE` vật lý trên bảng giao dịch.
- **Quyết định:** Thực hiện đảo giao dịch bằng cách khóa bản ghi gốc (`FOR UPDATE`), chuyển trạng thái sang `REVERSED`, hoàn trả đúng số tiền và phí trên các tài khoản liên quan, đồng thời chèn một bản ghi mới có `type = REVERSAL` trỏ `reversal_of_id` (ràng buộc `UNIQUE`) về giao dịch gốc.

## ADR-005: Bảo vệ Chống Trừ tiền Hai lần bằng RDBMS `Idempotency-Key` & Khóa Tư vấn 64-bit
- **Bối cảnh:** Khi mạng chập chờn, ứng dụng khách hoặc cổng thanh toán sẽ gửi lặp lại request tạo giao dịch.
- **Quyết định:** Yêu cầu header `Idempotency-Key` trên toàn bộ các API ghi tiền tệ (`POST /deposits`, `/withdrawals`, `/transfers`, `/payments`). Sử dụng khóa tư vấn mức giao dịch 64-bit `pg_advisory_xact_lock(hashtext(:scope), hashtext(:lockKey))` cô lập theo người dùng (`scope = username`), đối chiếu mã băm `SHA-256` của payload chuẩn hóa: nếu trùng key + cùng payload thì phát lại phản hồi gốc kèm header `Idempotent-Replayed: true`; nếu khác payload trả `422 IDEMPOTENCY_KEY_REUSED`.

## ADR-006: Phân loại Múi giờ Nghiệp vụ Ngân hàng (`Asia/Ho_Chi_Minh`)
- **Bối cảnh:** Hạn mức giao dịch ngày (`dailyLimit`), lịch định kỳ (`RecurringTransaction`) và báo cáo tổng hợp theo tuần/quý/năm cần tính theo ngày làm việc tại Việt Nam, trong khi máy chủ và CSDL lưu chuẩn UTC.
- **Quyết định:** Lưu trữ toàn bộ mốc thời gian dạng `TIMESTAMPTZ` (UTC) trong PostgreSQL; quy đổi biên ngày/kỳ theo múi giờ `Asia/Ho_Chi_Minh` (`UTC+7`) khi tính hạn mức ngày và khi gom nhóm `date_trunc(:period, created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')`.

## ADR-007: Chuẩn hóa Phản hồi Lỗi RFC 9457 `ProblemDetail` & Che Dữ liệu Nhạy cảm (`MaskingUtils`)
- **Bối cảnh:** Các thông báo lỗi mặc định của framework dễ làm lộ cấu trúc nội bộ hoặc dữ liệu định danh cá nhân (PII) trong log.
- **Quyết định:** Thống nhất toàn bộ phản hồi lỗi qua `GlobalExceptionHandler` theo chuẩn **RFC 9457 (`application/problem+json`)** kèm trường mở rộng `code`, `requestId`, `timestamp`, `errors`. Mọi thông tin CCCD/CMND và số tài khoản khi ghi log đều đi qua `MaskingUtils`.

## ADR-008: Xác thực Stateless JWT (HS256) Kết hợp Xoay vòng Refresh Token Phát hiện Tái sử dụng
- **Bối cảnh:** Cần cơ chế xác thực vừa nhanh cho REST API (`ROLE_ADMIN`, `ROLE_CUSTOMER`), vừa có khả năng thu hồi phiên ngay lập tức khi phát hiện đánh cắp token.
- **Quyết định:** Access Token dùng JWT HS256 thời hạn ngắn (15 phút); Refresh Token là chuỗi ngẫu nhiên 256-bit lưu dưới dạng mã băm một chiều `SHA-256` (`rt:{sha256}`) theo họ `familyId`. Khi phát hiện một Refresh Token cũ đã xoay vòng bị gửi lại (**Token Reuse Detection**), hệ thống lập tức thu hồi toàn bộ `familyId` và trả `401 TOKEN_REUSED`. Kèm bộ giới hạn tốc độ đăng nhập (`LoginRateLimiter`) chặn brute-force trước bước kiểm tra BCrypt (`429 TOO_MANY_REQUESTS`).

## ADR-009: Kiến trúc Cache Hai Tầng Tự Phục hồi (`Redis` + `Caffeine` Fallback) & Xóa Cache `AFTER_COMMIT`
- **Bối cảnh:** Cần tăng tốc các truy vấn đọc tần suất cao (danh sách tài khoản theo khách hàng, trang đầu lịch sử giao dịch, cấu hình loại khách hàng) nhưng không được để sự cố sập Redis làm gián đoạn giao dịch ngân hàng.
- **Quyết định:** Thiết kế `ResilientCacheErrorHandler` bắt mọi lỗi kết nối Redis để tự động đọc trực tiếp từ PostgreSQL (hoặc dùng `CaffeineCacheManager` khi tắt Redis). Việc xóa cache (`CacheEvictionListener`) chỉ kích hoạt ở pha `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` để ngăn hiện tượng đọc dữ liệu bẩn (stale cache read) trước khi transaction DB commit.

## ADR-010: Tách biệt Mô hình Ghi (JPA) và Đọc Báo cáo/Thống kê (SQL Thuần qua `JdbcClient`)
- **Bối cảnh:** Dùng ORM (Hibernate) để tải hàng chục nghìn bản ghi lên bộ nhớ Java rồi gom nhóm gây tốn RAM và tạo lỗi $N+1$ queries.
- **Quyết định:** Sử dụng Spring Data JPA cho các luồng ghi cần kiểm tra bất biến miền (Domain Entity) và khóa bi quan; sử dụng `JdbcClient` với câu lệnh SQL tối ưu (CTE, `CASE WHEN`, `date_trunc`, `generate_series`, Window Functions) kết hợp Partial Covering Index (`V8`, `V9`) cho toàn bộ các API thống kê và báo cáo.

## ADR-011: Thực thi Giao dịch Định kỳ Đa Tiến trình với `FOR UPDATE SKIP LOCKED` & Cô lập `REQUIRES_NEW`
- **Bối cảnh:** Bộ lập lịch chạy định kỳ mỗi phút cần xử lý các lệnh `RecurringTransaction` đến hạn mà không bị trùng lặp khi chạy nhiều luồng/instance và không để 1 lệnh lỗi làm rollback cả lô.
- **Quyết định:** Truy vấn từng bản ghi đến hạn bằng `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1` trên partial index `idx_rec_due`, xử lý mỗi lệnh trong một giao dịch độc lập `@Transactional(propagation = Propagation.REQUIRES_NEW)` với mã tham chiếu tất định `REC-{id}-{yyyyMMdd}`. Sau 3 lần lỗi liên tiếp, lệnh tự động chuyển sang `PAUSED`.

## ADR-012: Phát hiện Bất thường Thời gian thực Bất đồng bộ (`@Async` + `AFTER_COMMIT`) & Quét Lịch sử bằng SQL Window Function
- **Bối cảnh:** Đánh giá quy tắc gian lận/bất thường (`AMOUNT_THRESHOLD >= 50M VND`, `VELOCITY > 5 GD / 60s`) không được làm tăng độ trễ hay làm rollback giao dịch chuyển tiền hợp lệ của khách hàng.
- **Quyết định:**
  - **Thời gian thực:** Phát sự kiện sau khi giao dịch commit (`AFTER_COMMIT`) sang luồng `@Async` chạy trên Java 21 Virtual Threads (`SlidingWindowVelocityTracker` dùng Redis ZSET hoặc `ArrayDeque` bảo vệ bởi `ReentrantLock`), ghi cảnh báo vào bảng `core.alert` với `INSERT ... ON CONFLICT (transaction_id, rule_code) DO NOTHING`.
  - **Quét lịch sử theo lô (`POST /api/v1/fraud/scans`):** Đẩy toàn bộ phép tính cửa sổ trượt xuống PostgreSQL bằng hàm cửa sổ `COUNT(*) OVER (PARTITION BY source_account_id ORDER BY created_at RANGE BETWEEN make_interval(secs => :windowSeconds) PRECEDING AND CURRENT ROW)`.

## ADR-013: Tính Chuỗi Số dư Cuối kỳ Toàn hệ thống bằng Hàm Cửa sổ Tích lũy Ngược (`Reverse Cumulative Sum`)
- **Bối cảnh:** Báo cáo xu hướng (`GET /api/v1/reports/trends`) cần tái dựng số dư cuối mỗi ngày/tuần/tháng trong quá khứ từ bảng giao dịch.
- **Quyết định:** Sử dụng `generate_series` tạo trục thời gian liên tục và lấy tổng số dư hiện tại trừ đi tổng biến động ròng (`system_balance_delta`) của các kỳ đứng sau thông qua hàm cửa sổ `SUM(bucket_delta) OVER (ORDER BY bucket ASC ROWS BETWEEN 1 FOLLOWING AND UNBOUNDED FOLLOWING)`.

## ADR-014: Vẽ Biểu đồ Headless (`JFreeChart`) & Xuất Báo cáo Luồng (`SXSSFWorkbook` / `OpenPDF`)
- **Bối cảnh:** Xuất báo cáo tài chính Excel (`.xlsx`) và PDF kèm 3 biểu đồ xu hướng trên khoảng dữ liệu lớn (lên tới 100.000 giao dịch) dễ gây tràn bộ nhớ Heap (`OutOfMemoryError`).
- **Quyết định:**
  - Sử dụng `ReportDataService` tải song song dữ liệu tổng hợp và chuỗi xu hướng trên Virtual Threads, vẽ 3 biểu đồ PNG `800x400` trong bộ nhớ bằng `JFreeChart` ở chế độ `-Djava.awt.headless=true`.
  - Áp dụng mẫu **Template Method (`ReportExporter<C>`)** kết hợp `StreamingResponseBody`: bản Excel dùng `SXSSFWorkbook(100)` (chỉ giữ 100 dòng trong RAM) đọc qua con trỏ JDBC (`fetchSize = 1000`) và gọi `workbook.dispose()` trong khối `finally`; bản PDF dùng `OpenPDF` nhúng font Unicode tiếng Việt.

## ADR-015: Java 21 Virtual Threads, Loại bỏ `synchronized` Chống Carrier Pinning & Giới hạn HikariCP Pool
- **Bối cảnh:** Khi bật Java 21 Virtual Threads (`spring.threads.virtual.enabled=true`), các khối `synchronized` bao quanh thao tác I/O sẽ ghim (pin) Virtual Thread vào OS carrier thread; đồng thời việc mở hàng trăm kết nối DB vật lý sẽ gây nghẽn PostgreSQL.
- **Quyết định:** Thay thế toàn bộ khóa nội bộ quanh I/O bằng `ReentrantLock` (`try/finally`), kiểm tra tự động bằng unit test quét mã nguồn đảm bảo 0 khối `synchronized` trong `src/main/java`, và giữ kích thước `HikariCP maximum-pool-size` nhỏ gọn (`10` ở `local`, `5` ở `prod`).

## ADR-016: Phân trang Con trỏ Keyset Pagination $O(\log N)$ cho Lịch sử Giao dịch (`CursorPageResponse`)
- **Bối cảnh:** Trên bảng giao dịch triệu dòng (`1.000.000+` bản ghi), phân trang `OFFSET/LIMIT` chậm dần tuyến tính $O(N)$ ở các trang sâu và bị trượt dòng khi có giao dịch mới chèn vào đầu bảng.
- **Quyết định:** Hỗ trợ song song tham số `?cursor=` (`GET /api/v1/accounts/{id}/transactions?cursor=...` và `/cursor`) mã hóa Base64URL cặp khóa thứ tự tuyệt đối `(createdAt, id)` kết hợp vị từ `(t.createdAt < :cursorCreatedAt) OR (t.createdAt = :cursorCreatedAt AND t.id < :cursorId)` trên chỉ mục `(created_at DESC, id DESC)` (`V14`) và kỹ thuật đọc `size + 1` dòng để xác định `hasNext` không cần `COUNT(*)`.

## ADR-017: Bảo vệ Hai Lớp cho Tiến trình Định kỳ Đa Instance (`ShedLock` + `FOR UPDATE SKIP LOCKED`)
- **Bối cảnh:** Khi triển khai nhiều replica Backend chạy song song, cần ngăn chặn các node cùng đánh thức bộ quét cron job một lúc gây lãng phí kết nối DB, đồng thời vẫn đảm bảo an toàn tuyệt đối nếu một node bị treo GC pause.
- **Quyết định:** Kết hợp `SchedulerLockService` (câu lệnh nguyên tử `INSERT INTO core.shedlock ... ON CONFLICT (name) DO UPDATE ... WHERE lock_until <= :now` với `lockAtMostFor = PT5M`, `lockAtLeastFor = PT10S`) ở tầng điều phối cron job và `SELECT ... FOR UPDATE SKIP LOCKED` ở tầng dòng dữ liệu.

## ADR-018: Nhật ký Kiểm toán Bất biến Cùng Giao dịch (`core.audit_log`) & Nguyên tử hóa Redis (`GETDEL` / Lua)
- **Bối cảnh:** Các thao tác nhạy cảm (đổi trạng thái tài khoản, đổi hạn mức, đảo giao dịch `REVERSAL`, xử lý cảnh báo gian lận) bắt buộc phải có dấu vết kiểm toán không thể chối bỏ và không bao giờ lệch trạng thái với dữ liệu nghiệp vụ.
- **Quyết định:**
  - Ghi bảng `core.audit_log` đồng bộ ngay trong cùng giao dịch nghiệp vụ (`@EventListener` + `Propagation.MANDATORY`), che dấu PII (`MaskingUtils`) trước khi lưu `before_json`/`after_json` (`JSONB`), và khóa cứng bảng bằng trigger PostgreSQL `trg_audit_log_append_only` (`BEFORE UPDATE OR DELETE`).
  - Nguyên tử hóa thao tác thu hồi Refresh Token trên Redis bằng `GETDEL` (`opsForValue().getAndDelete`) và bộ đếm đăng nhập sai bằng Lua script (`INCR` + `EXPIRE`), triệt tiêu hoàn toàn cửa sổ tranh chấp giữa hai lệnh mạng.


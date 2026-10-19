# Story 4.4 — Alert Management & Historical Fraud Scan API (`L3-03`, `L3-04`, `G-05`, `FR-22`, `FR-23`, `NFR-2`)

## 1. Bối cảnh & Yêu cầu
Sau khi Story 4.3 bổ sung cơ chế phát hiện bất thường theo thời gian thực (`AFTER_COMMIT` + `@Async` Virtual Threads), quản trị viên (`ADMIN`) cần:
1. **Tra cứu và lọc danh sách cảnh báo (`GET /api/v1/alerts`)** có phân trang theo `status` (`NEW`, `INVESTIGATING`, `RESOLVED`, `FALSE_POSITIVE`), `ruleCode` (`AMOUNT_THRESHOLD`, `VELOCITY`), `accountId`, và khoảng thời gian (`from`/`to` hoặc `fromDate`/`toDate`), kèm kiểm tra whitelist trường sắp xếp (`createdAt`, `severity`, `status`, `id`).
2. **Xem chi tiết một cảnh báo (`GET /api/v1/alerts/{id}`)** kèm `reason` giải thích lý do sinh cảnh báo.
3. **Chuyển trạng thái xử lý cảnh báo (`POST /api/v1/alerts/{id}/status`)** theo máy trạng thái hữu hạn (FSM) bắt buộc qua bước điều tra trước khi đóng (`NEW -> INVESTIGATING -> RESOLVED | FALSE_POSITIVE` hoặc `NEW -> FALSE_POSITIVE`) và ghi nhận `handledBy`, `resolutionNote`, `updatedAt`.
4. **Quét lịch sử giao dịch theo lô (`POST /api/v1/fraud/scans` và alias `POST /api/v1/alerts/scan`)** trong khoảng thời gian `[from, to]` bằng SQL Window Function để phát hiện các giao dịch lịch sử vi phạm quy tắc `AMOUNT_THRESHOLD` hoặc `VELOCITY` (ví dụ: dữ liệu nạp từ batch/seed hoặc các giao dịch phát sinh khi ngưỡng quy tắc được điều chỉnh), đảm bảo **idempotent** (`ON CONFLICT (transaction_id, rule_code) DO NOTHING`).

---

## 2. Quyết định Thiết kế

### 2.1. Máy trạng thái hữu hạn (Alert Status FSM)
Trạng thái của `Alert` (`AlertStatus`) tuân theo đồ thị chuyển trạng thái một chiều bắt buộc điều tra trước khi kết luận:
- `NEW` $\rightarrow$ `INVESTIGATING` hoặc `FALSE_POSITIVE` (không cho phép nhảy cóc trực tiếp `NEW -> RESOLVED` để đảm bảo quy trình kiểm soát nội bộ).
- `INVESTIGATING` $\rightarrow$ `RESOLVED` hoặc `FALSE_POSITIVE`.
- `RESOLVED` và `FALSE_POSITIVE` là **trạng thái kết thúc (terminal states)**: mọi yêu cầu chuyển tiếp từ 2 trạng thái này (hoặc chuyển sang chính trạng thái hiện tại) đều ném `InvalidStatusTransitionException` $\rightarrow$ HTTP `409 Conflict` (`code: INVALID_STATUS_TRANSITION`, RFC 9457 `ProblemDetail`).
- Khi cập nhật trạng thái, hệ thống ghi nhận `handledBy` (username từ JWT `Authentication`), `resolutionNote`, và `updatedAt = clock.instant()`.

### 2.2. Quét lịch sử trực tiếp trên PostgreSQL bằng Window Function (`RANGE BETWEEN ... PRECEDING`)
Thay vì kéo hàng chục nghìn bản ghi `core.transaction` về JVM heap để chạy vòng lặp kiểm tra từng giao dịch (dễ gây `OutOfMemoryError` và tốn hàng nghìn round-trip), `FraudScanRepository` đẩy toàn bộ xử lý xuống PostgreSQL 17 bằng `JdbcClient` với câu lệnh `INSERT INTO core.alert ... SELECT ... ON CONFLICT (transaction_id, rule_code) DO NOTHING`:
- **Quy tắc `AMOUNT_THRESHOLD`:** Quét các giao dịch `COMPLETED` thuộc nhóm outgoing (`WITHDRAWAL`, `TRANSFER`, `PAYMENT`) trong khoảng `[fromInstant, toInstant]` có `amount >= :threshold`.
- **Quy tắc `VELOCITY`:** Sử dụng PostgreSQL Window Function với khung trượt theo thời gian (`RANGE` frame với `make_interval`) kèm khoảng lùi biên `lookbackFromInstant = fromInstant - windowSeconds` để không bỏ sót các chuỗi giao dịch vắt qua ranh giới `fromInstant`:
  ```sql
  COUNT(*) OVER (
      PARTITION BY t.source_account_id
      ORDER BY t.created_at
      RANGE BETWEEN make_interval(secs => :windowSeconds) PRECEDING AND CURRENT ROW
  ) AS tx_count_in_window
  ```
- **Tính Idempotent tuyệt đối:** Cả quá trình phát hiện thời gian thực (Story 4.3) và quét lịch sử theo lô (Story 4.4) đều dựa trên ràng buộc duy nhất `uk_alert_tx_rule UNIQUE (transaction_id, rule_code)` kết hợp `ON CONFLICT (transaction_id, rule_code) DO NOTHING`. Số bản ghi thực sự chèn mới được trả về từ `.update()` trong `FraudScanResponse(alertsCreated, amountThresholdAlerts, velocityAlerts, scannedFrom, scannedTo)`.

### 2.3. Phân quyền RBAC chặt chẽ
- Toàn bộ endpoint `/api/v1/alerts/**` và `/api/v1/fraud/scans` được khóa chặt `@PreAuthorize("hasRole('ADMIN')")` — đảm bảo người dùng thông thường (`CUSTOMER`) hoặc truy cập ẩn danh không thể xem hoặc thay đổi cảnh báo gian lận.

---

## 3. Hiệu năng truy vấn và kiểm chứng chỉ mục (`performance.md`)

### 3.1. Số lượng câu lệnh SQL
| Endpoint / Nghiệp vụ | Số câu SQL thực thi | Ghi chú kỹ thuật |
|---|---|---|
| `GET /api/v1/alerts` (lọc đa tiêu chí + phân trang) | **$\le 2$ câu SQL** (`1 SELECT` dữ liệu + `1 SELECT COUNT(*)` khi cần) | Kiểm chứng tự động bằng `HibernateStatistics.getPrepareStatementCount() <= 2` trong `AlertManagementAndScanIT`, không bị N+1 (`Rule 16`). |
| `GET /api/v1/alerts/{id}` | **1 câu SQL** | `SELECT ... FROM core.alert WHERE id = ?` theo Primary Key. |
| `POST /api/v1/alerts/{id}/status` | **2 câu SQL** | `1 SELECT` theo PK + `1 UPDATE ... WHERE id = ? AND version = ?` (Optimistic Locking `@Version`). |
| `POST /api/v1/fraud/scans` | **2 câu SQL set-based** | `1 INSERT ... SELECT` cho `AMOUNT_THRESHOLD` + `1 WITH windowed_outgoing AS (...) INSERT ... SELECT` cho `VELOCITY` qua `JdbcClient` (0 vòng lặp N+1 trên JVM). |

### 3.2. Kế hoạch thực thi (`EXPLAIN (ANALYZE, BUFFERS)`)
1. **Truy vấn lọc và phân trang cảnh báo (`GET /api/v1/alerts?status=RESOLVED&sort=createdAt,desc`):**
```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, transaction_id, account_id, rule_code, severity, status
FROM core.alert
WHERE status = 'RESOLVED'
ORDER BY created_at DESC, id DESC
LIMIT 20;
-- Limit  (actual time=0.015..0.017 rows=1 loops=1)
--   Buffers: shared hit=2
--   -> Index Scan using idx_alert_status_created on alert  (actual time=0.014..0.015 rows=1 loops=1)
--        Index Cond: ((status)::text = 'RESOLVED'::text)
```
2. **Truy vấn quét lịch sử `VELOCITY` bằng SQL Window Function (`SCAN_VELOCITY_WINDOW_SQL`):**
```sql
EXPLAIN (ANALYZE, BUFFERS)
WITH windowed_outgoing AS (
    SELECT
        t.id AS transaction_id,
        t.source_account_id AS account_id,
        t.created_at,
        COUNT(*) OVER (
            PARTITION BY t.source_account_id
            ORDER BY t.created_at
            RANGE BETWEEN make_interval(secs => 60.0) PRECEDING AND CURRENT ROW
        ) AS tx_count_in_window
    FROM core.transaction t
    WHERE t.status = 'COMPLETED'
      AND t.type IN ('WITHDRAWAL', 'TRANSFER', 'PAYMENT')
      AND t.source_account_id IS NOT NULL
      AND t.created_at >= '2026-08-15T09:54:00Z'
      AND t.created_at <= '2026-08-15T10:30:00Z'
)
SELECT transaction_id, account_id, created_at, tx_count_in_window
FROM windowed_outgoing
WHERE created_at >= '2026-08-15T09:55:00Z' AND tx_count_in_window > 5;
-- Subquery Scan on windowed_outgoing  (actual time=0.042..0.051 rows=2 loops=1)
--   Filter: ((windowed_outgoing.created_at >= '2026-08-15 09:55:00+00'::timestamptz) AND (windowed_outgoing.tx_count_in_window > 5))
--   -> WindowAgg  (actual time=0.036..0.047 rows=8 loops=1)
--        -> Sort  (actual time=0.029..0.031 rows=8 loops=1)
--             Sort Key: t.source_account_id, t.created_at
--             -> Index Scan using idx_tx_status_created on transaction t  (actual time=0.014..0.021 rows=8 loops=1)
```

---

## 4. Các phương án đã cân nhắc (Alternatives Considered)
1. **Tải danh sách giao dịch lịch sử lên Java (`findAll`) rồi chạy lại `SlidingWindowVelocityTracker` trong bộ nhớ:**
   - *Bác bỏ:* Vi phạm `performance.md` mục 5 (mọi tổng hợp/phân tích trên tập dữ liệu lớn phải chạy dưới SQL) và làm hỏng trạng thái thời gian thực của `SlidingWindowVelocityTracker` (vốn dùng `clock.instant()` hiện tại chứ không phải `created_at` lịch sử). Dùng PostgreSQL Window Function `COUNT(*) OVER (... RANGE BETWEEN ... PRECEDING)` xử lý hoàn toàn tại tầng DB trong 1 câu SQL duy nhất.
2. **Dùng khung cửa sổ `ROWS BETWEEN 5 PRECEDING AND CURRENT ROW` thay vì `RANGE BETWEEN make_interval(...) PRECEDING`:**
   - *Bác bỏ:* `ROWS BETWEEN` chỉ đếm theo thứ tự dòng mà không xét khoảng cách thời gian thực, hoặc đòi hỏi phải tự tính hiệu `created_at - LAG(created_at, 5)`. Ngược lại, `RANGE BETWEEN make_interval(secs => :windowSeconds) PRECEDING AND CURRENT ROW` của PostgreSQL trực tiếp định nghĩa cửa sổ trượt theo giá trị cột `ORDER BY t.created_at` (`TIMESTAMPTZ`), trả về chính xác tổng số giao dịch trong $W$ giây liền trước.
3. **Cho phép chuyển trạng thái trực tiếp `NEW -> RESOLVED`:**
   - *Bác bỏ:* Theo đặc tả nghiệp vụ `FR-23` và `Story 4.4 AC 2`, mọi cảnh báo được xác nhận là sự cố thật (`RESOLVED`) bắt buộc phải trải qua bước `INVESTIGATING` để lưu vết người nhận xử lý; chỉ trường hợp báo động giả rõ ràng mới được đóng nhanh `NEW -> FALSE_POSITIVE`.

---

## 5. Bẫy kỹ thuật và bài học (Gotchas)
- **Bẫy mất chuỗi giao dịch vắt qua biên `fromInstant` khi quét lịch sử (`Boundary Burst`):** Nếu CTE `windowed_outgoing` chỉ lấy các giao dịch có `t.created_at >= :fromInstant`, một giao dịch diễn ra ở `fromInstant + 5s` là giao dịch thứ 6 (với 5 giao dịch trước đó nằm ở `fromInstant - 40s .. fromInstant - 5s`) sẽ chỉ đếm được `tx_count_in_window = 1` và bị bỏ sót. Giải pháp: CTE quét lùi về `lookbackFromInstant = fromInstant.minusSeconds(windowSeconds)` để nạp đủ ngữ cảnh cửa sổ trượt, sau đó tầng `SELECT` bên ngoài mới lọc `WHERE w.created_at >= :fromInstant AND w.tx_count_in_window > :maxCount`.
- **Bẫy độ phân giải microsecond của PostgreSQL `TIMESTAMPTZ`:** Kiểu `TIMESTAMPTZ` trong PostgreSQL có độ phân giải 1 microsecond ($10^{-6}\text{s}$), trong khi `java.time.Instant` có độ phân giải nanosecond ($10^{-9}\text{s}$). Khi quy đổi biên trên từ `LocalDate` hoặc `Instant`, cần cộng/trừ theo `ChronoUnit.MICROS` thay vì `plusNanos(1)` / `minusNanos(1)` để tránh trình điều khiển `pgjdbc` làm tròn sub-microsecond gây lệch biên.

---

## 6. Kiểm thử
- **Unit Test (`AlertStatusTest`):** Kiểm tra toàn bộ ma trận chuyển trạng thái $4 \times 4 = 16$ của `AlertStatus` (4 chiều hợp lệ, 12 chiều bất hợp lệ ném `InvalidStatusTransitionException`), kiểm tra khóa lạc quan `version` (`409 CONCURRENT_MODIFICATION`), và logic `AlertService`.
- **Web Slice Test (`AlertControllerTest`):** Kiểm tra phân quyền RBAC (`ADMIN` vs `CUSTOMER` `403 Forbidden`, `401 Unauthorized`), kiểm tra whitelist sort field (`400 INVALID_SORT_FIELD`), và định dạng RFC 9457 `ProblemDetail` cho `404 RESOURCE_NOT_FOUND` và `409 INVALID_STATUS_TRANSITION`.
- **Integration Test (`AlertManagementAndScanIT` trên PostgreSQL 17 Testcontainers):**
  - Quét lịch sử phát hiện cả giao dịch vượt ngưỡng `AMOUNT_THRESHOLD` và chuỗi giao dịch liên tiếp vi phạm `VELOCITY` (kể cả chuỗi giao dịch vắt qua ranh giới `fromInstant`).
  - Chạy lại cùng yêu cầu quét lần 2 trả về `alertsCreated = 0` (idempotent 100%).
  - Kiểm tra lọc phân trang `GET /api/v1/alerts` (`HibernateStatistics <= 2` câu SQL), kế hoạch `EXPLAIN` dùng `idx_alert_status_created` & `WindowAgg`, vòng đời chuyển trạng thái `NEW -> INVESTIGATING -> RESOLVED` lưu đúng `handledBy`/`resolutionNote`/`updatedAt`, và chặn `NEW -> RESOLVED` hoặc `RESOLVED -> NEW` với `409 INVALID_STATUS_TRANSITION`.

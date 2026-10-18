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
- **Tính Idempotent tuyệt đối:** Cả quá trình phát hiện thời gian thực (Story 4.3) và quét lịch sử theo lô (Story 4.4) đều dựa trên ràng buộc duy nhất `uq_alert_transaction_rule UNIQUE (transaction_id, rule_code)` kết hợp `ON CONFLICT (transaction_id, rule_code) DO NOTHING`. Số bản ghi thực sự chèn mới được trả về từ `.update()` trong `FraudScanResponse(alertsCreated, amountThresholdAlerts, velocityAlerts, scannedFrom, scannedTo)`.

### 2.3. Phân quyền RBAC chặt chẽ
- Toàn bộ endpoint `/api/v1/alerts/**` và `/api/v1/fraud/scans` được khóa chặt `@PreAuthorize("hasRole('ADMIN')")` — đảm bảo người dùng thông thường (`CUSTOMER`) hoặc truy cập ẩn danh không thể xem hoặc thay đổi cảnh báo gian lận.

---

## 3. Kiểm thử
- **Unit Test (`AlertStatusTest`):** Kiểm tra toàn bộ ma trận chuyển trạng thái $4 \times 4 = 16$ của `AlertStatus` (4 chiều hợp lệ, 12 chiều bất hợp lệ ném `InvalidStatusTransitionException`) và logic `AlertService`.
- **Web Slice Test (`AlertControllerTest`):** Kiểm tra phân quyền RBAC (`ADMIN` vs `CUSTOMER` `403 Forbidden`, `401 Unauthorized`), kiểm tra whitelist sort field (`400 INVALID_SORT_FIELD`), và định dạng RFC 9457 `ProblemDetail` cho `404 RESOURCE_NOT_FOUND` và `409 INVALID_STATUS_TRANSITION`.
- **Integration Test (`AlertManagementAndScanIT` trên PostgreSQL 17 Testcontainers):**
  - Quét lịch sử phát hiện cả giao dịch vượt ngưỡng `AMOUNT_THRESHOLD` và chuỗi giao dịch liên tiếp vi phạm `VELOCITY` (kể cả chuỗi giao dịch vắt qua ranh giới `fromInstant`).
  - Chạy lại cùng yêu cầu quét lần 2 trả về `alertsCreated = 0` (idempotent 100%).
  - Kiểm tra lọc phân trang `GET /api/v1/alerts`, vòng đời chuyển trạng thái `NEW -> INVESTIGATING -> RESOLVED` lưu đúng `handledBy`/`resolutionNote`/`updatedAt`, và chặn `NEW -> RESOLVED` hoặc `RESOLVED -> NEW` với `409 INVALID_STATUS_TRANSITION`.

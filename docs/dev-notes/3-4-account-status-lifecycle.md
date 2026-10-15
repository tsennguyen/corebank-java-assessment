# Story 3.4: Account Status Lifecycle & Immutable Audit History

- **PR:** [#29](https://github.com/tsennguyen/corebank-java-assessment/pull/29)
- **Mã yêu cầu:** `L2-05` [ĐỀ], `FR-3`, `FR-14`, `NFR-1`, `NFR-9`

## 1. Bối cảnh & Bài toán

Trong hệ thống Core Banking, tài khoản thanh toán không chỉ có hai trạng thái mở và đóng mà phải tuân thủ **vòng đời trạng thái chặt chẽ (`ACTIVE`, `LOCKED`, `CLOSED`)** phục vụ kiểm soát rủi ro và tuân thủ:
- Khi nghi ngờ giao dịch bất thường hoặc theo yêu cầu từ cơ quan chức năng / chủ tài khoản, Quản trị viên (`ADMIN`) cần **phong tỏa tạm thời (`LOCKED`)** tài khoản kèm lý do rõ ràng, và có thể **mở khóa (`ACTIVE`)** sau khi hoàn tất xác minh, hoặc **đóng vĩnh viễn (`CLOSED`)** khi số dư bằng `0.00`.
- Mọi thao tác làm biến động số dư (nạp tiền, rút tiền, chuyển khoản đi/đến, thanh toán hóa đơn, và **đảo chiều giao dịch — Reversal**) trên tài khoản đang `LOCKED` hoặc `CLOSED` đều phải bị chặn tuyệt đối với mã lỗi `422 ACCOUNT_NOT_ACTIVE`.
- Mọi lần chuyển trạng thái (kể cả lúc mở tài khoản `null -> ACTIVE`) phải được lưu vết bất biến trong bảng `core.account_status_history` và phát sự kiện miền `AccountStatusChangedEvent`.

## 2. Giải pháp Kỹ thuật đã triển khai

### 2.1. Máy trạng thái hữu hạn (Finite State Machine — FSM) trong `AccountStatus` & `Account`
- Định nghĩa quy tắc chuyển trạng thái ngay trong enum miền [`AccountStatus.canTransitionTo(AccountStatus target)`](../../backend/src/main/java/com/example/corebanking/account/domain/AccountStatus.java):
  - `ACTIVE → LOCKED` hoặc `ACTIVE → CLOSED`
  - `LOCKED → ACTIVE` hoặc `LOCKED → CLOSED`
  - `CLOSED` là **trạng thái kết thúc (terminal state)**: không cho phép bất kỳ chuyển đổi nào đi ra từ `CLOSED`.
  - Chặn tự chuyển trạng thái (`this == target`, ví dụ `LOCKED → LOCKED` hoặc `ACTIVE → ACTIVE`) để tránh sinh các bản ghi lịch sử giả tạo.
- Phương thức [`Account.changeStatus(AccountStatus targetStatus, Instant timestamp)`](../../backend/src/main/java/com/example/corebanking/account/domain/Account.java) kiểm tra:
  1. Nếu `!this.status.canTransitionTo(targetStatus)` → ném `BusinessRuleException(ErrorCode.INVALID_STATUS_TRANSITION)` (`409 Conflict`).
  2. Nếu `targetStatus == AccountStatus.CLOSED` và `balance > 0` → ném `BusinessRuleException(ErrorCode.ACCOUNT_HAS_BALANCE)` (`409 Conflict`). Khi hợp lệ, gán `closedAt = timestamp`.
- Đồng bộ `DELETE /api/v1/accounts/{id}` (`Account.close(Instant)`) ủy quyền trực tiếp vào `changeStatus(AccountStatus.CLOSED, timestamp)`, đảm bảo cả hai đường đóng tài khoản (`POST /status` và `DELETE /{id}`) đều áp dụng chung một bộ luật miền, ghi lịch sử và phát sự kiện.

### 2.2. Chặn tuyệt đối Giao dịch & Đảo chiều (`DELETE /api/v1/transactions/{id}`) khi `LOCKED`/`CLOSED`
- Trong [`LimitPolicy.validateLimits()`](../../backend/src/main/java/com/example/corebanking/transaction/domain/LimitPolicy.java), kiểm tra `!account.isActive()` được đặt ở đầu hàm trước khi kiểm tra hạn mức giao dịch/hạn mức ngày. Nhờ đó, nếu tài khoản đang `LOCKED` gửi lệnh rút tiền vượt hạn mức, hệ thống luôn trả đúng `422 ACCOUNT_NOT_ACTIVE` thay vì `422 LIMIT_EXCEEDED`.
- Trong [`Account.credit()`](../../backend/src/main/java/com/example/corebanking/account/domain/Account.java) và [`Account.debit()`](../../backend/src/main/java/com/example/corebanking/account/domain/Account.java), điều kiện `if (!isActive()) throw ACCOUNT_NOT_ACTIVE` bảo vệ mọi nhánh cập nhật số dư, bao gồm cả 4 nhánh của `TransactionService.reverseTransaction()` (`DEPOSIT`, `WITHDRAWAL`, `PAYMENT`, `TRANSFER`). Nếu bất kỳ tài khoản nguồn hoặc đích nào của giao dịch gốc đã chuyển sang `LOCKED` hoặc `CLOSED`, lệnh đảo chiều bị từ chối ngay với `422 ACCOUNT_NOT_ACTIVE` dưới khóa bi quan `SELECT ... FOR UPDATE`.

### 2.3. Bảng Lịch sử Kiểm toán `core.account_status_history` (`V10`) & Sự kiện `AccountStatusChangedEvent`
- Flyway migration [`V10__account_status_history.sql`](../../backend/src/main/resources/db/migration/V10__account_status_history.sql) tạo bảng `core.account_status_history` kèm:
  - Ràng buộc `chk_acc_status_hist_from` (`from_status IS NULL OR from_status IN ('ACTIVE', 'LOCKED', 'CLOSED')`) và `chk_acc_status_hist_to`.
  - Composite index `idx_acc_status_hist_account_changed ON core.account_status_history (account_id, changed_at DESC, id DESC)` phục vụ truy vấn lịch sử theo tài khoản không cần sort trên bộ nhớ.
  - Câu lệnh backfill idempotent tạo dòng lịch sử khởi tạo (`NULL -> status`, `reason = 'Account opened'`) cho mọi tài khoản đã tồn tại.
- Mỗi khi mở tài khoản (`createAccount`), đổi trạng thái (`changeStatus`), hoặc đóng tài khoản (`closeAccount`), [`AccountService`](../../backend/src/main/java/com/example/corebanking/account/application/AccountService.java) lưu `AccountStatusHistory` với người thực hiện lấy từ `SecurityContextHolder` và phát `AccountStatusChangedEvent`.
- Endpoint `GET /api/v1/accounts/{id}/status-history` được bảo vệ bằng `@PreAuthorize("hasRole('ADMIN') or @ownership.canAccessAccount(authentication, #id)")`, cho phép cả Admin và chính chủ tài khoản tra cứu lịch sử trạng thái nhưng chặn `403 ACCESS_DENIED` nếu khách hàng khác cố truy cập.

### 2.4. Số liệu `EXPLAIN (ANALYZE, BUFFERS)` & Số câu SQL (Rule 6 & Rule 16)
- **Số câu SQL trên mỗi lần gọi `GET /api/v1/accounts/{id}/status-history`:** Đúng **2 câu SQL** (`1 SELECT 1 FROM core.account WHERE id = ?` để kiểm tra tồn tại + `1 SELECT ... FROM core.account_status_history WHERE account_id = ? ORDER BY changed_at DESC, id DESC`), cam kết **0 N+1 query**.
- **Kế hoạch thực thi `EXPLAIN (ANALYZE, BUFFERS)` trên PostgreSQL 17:**
  ```sql
  EXPLAIN (ANALYZE, BUFFERS)
  SELECT id, account_id, from_status, to_status, reason, changed_by, changed_at
  FROM core.account_status_history
  WHERE account_id = 1
  ORDER BY changed_at DESC, id DESC;
  ```
  ```text
  Index Scan using idx_acc_status_hist_account_changed on account_status_history  (cost=0.14..8.16 rows=5 width=88) (actual time=0.014..0.016 rows=5 loops=1)
    Index Cond: (account_id = 1)
    Buffers: shared hit=2
  Planning Time: 0.068 ms
  Execution Time: 0.029 ms
  ```
  → PostgreSQL sử dụng trực tiếp **`Index Scan using idx_acc_status_hist_account_changed`** theo đúng thứ tự `(account_id, changed_at DESC, id DESC)`, không phát sinh bước `Sort` trên bộ nhớ.

## 3. Các phương án đã cân nhắc & Đánh đổi

| Phương án | Ưu điểm | Nhược điểm / Lý do chọn hoặc loại |
|---|---|---|
| **Tách bảng `core.account_status_history` riêng kèm dòng khởi tạo `NULL -> ACTIVE`** *(Đã chọn)* | Truy vết đầy đủ từ thời điểm mở tài khoản tới khi đóng; không làm phình bảng `core.account`; index `(account_id, changed_at DESC, id DESC)` tối ưu hóa truy vấn theo từng tài khoản. | Cần ghi thêm 1 dòng `INSERT` mỗi khi mở hoặc đổi trạng thái tài khoản (tần suất rất thấp so với giao dịch tiền tệ). |
| **Khóa bi quan `findByIdForUpdate` khi chuyển trạng thái** *(Đã chọn)* | Tuần tự hóa hoàn toàn giữa lệnh khóa/đóng tài khoản của Admin và các giao dịch nạp/rút/chuyển/đảo chiều đang chạy song song, không để lọt giao dịch vào tài khoản vừa bị khóa. | Giữ row lock trong vài mili-giây của transaction đổi trạng thái. |

## 4. Kiểm thử & Xác minh

- **Domain Unit Tests (`AccountTest`):** Kiểm tra toàn bộ ma trận chuyển trạng thái hợp lệ (`ACTIVE ⇄ LOCKED`, `ACTIVE/LOCKED → CLOSED`), từ chối tự chuyển (`ACTIVE → ACTIVE`, `LOCKED → LOCKED`) và mọi chuyển đổi từ `CLOSED` (`409 INVALID_STATUS_TRANSITION`), từ chối `credit`/`debit` khi `LOCKED` (`422 ACCOUNT_NOT_ACTIVE`).
- **Service & Web Slice Tests (`AccountServiceTest`, `AccountControllerTest`):** Kiểm tra lưu `AccountStatusHistory`, phát `AccountStatusChangedEvent`, validation `@NotBlank reason`, và phân quyền `@PreAuthorize` trên `/status-history`.
- **Integration Tests (`AccountStatusLifecycleIntegrationTest`, `FlywayMigrationIT`):** Kiểm thử toàn trình trên PostgreSQL 17 Testcontainers cho vòng đời `ACTIVE → LOCKED → ACTIVE → LOCKED → CLOSED`, chặn nạp/rút/chuyển/thanh toán/đảo chiều giao dịch khi `LOCKED`/`CLOSED`, và kiểm tra quyền sở hữu `CUSTOMER` vs `ADMIN`.

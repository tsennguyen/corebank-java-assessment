# Sơ đồ Thực thể Liên kết (Entity-Relationship Diagram — Schema `core`)

Tài liệu này mô tả chính xác cấu trúc 11 bảng trong schema `core` trên **PostgreSQL 17** được quản lý bởi **Flyway** (`V1__init_schema.sql` → `V14__scale_and_hardening.sql`), kèm danh mục toàn bộ **Chỉ mục (B-tree, Partial, Covering, Keyset Indexes)** và **Ràng buộc Toàn vẹn (`CHECK`, `UNIQUE`, `FOREIGN KEY`, `TRIGGER`)**.

---

## 1. Sơ đồ Thực thể Liên kết (`erDiagram`)

```mermaid
erDiagram
    CUSTOMER_TYPE ||--o{ CUSTOMER : "classifies (customer_type_id, customer_type_code)"
    CUSTOMER ||--o{ ACCOUNT : "owns (customer_id)"
    CUSTOMER ||--o| APP_USER : "links optional 1:1 (customer_id)"
    ACCOUNT ||--o{ ACCOUNT_STATUS_HISTORY : "audits (account_id)"
    ACCOUNT ||--o{ TRANSACTION : "debits (source_account_id)"
    ACCOUNT ||--o{ TRANSACTION : "credits (target_account_id)"
    TRANSACTION ||--o| TRANSACTION : "reverses (reversal_of_id)"
    RECURRING_TRANSACTION ||--o{ TRANSACTION : "triggers (recurring_id)"
    ACCOUNT ||--o{ RECURRING_TRANSACTION : "funds (source_account_id)"
    ACCOUNT ||--o{ RECURRING_TRANSACTION : "receives (target_account_id)"
    TRANSACTION ||--o{ ALERT : "raises (transaction_id)"
    ACCOUNT ||--o{ ALERT : "monitors (account_id)"

    CUSTOMER_TYPE {
        bigserial id PK "V12"
        varchar(32) code UK "INDIVIDUAL | BUSINESS"
        varchar(100) name
        numeric(19_2) max_transaction_limit "CHECK > 0"
        numeric(19_2) max_daily_limit "CHECK >= max_transaction_limit"
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }

    CUSTOMER {
        bigserial id PK "V2"
        varchar(32) customer_code UK "Sequence customer_code_seq"
        varchar(255) full_name
        varchar(32) national_id UK
        varchar(255) email
        varchar(32) phone
        varchar(255) address
        varchar(100) location
        date date_of_birth
        bigint customer_type_id FK "Default 1 (V12)"
        varchar(32) customer_type_code FK "Default INDIVIDUAL (V12)"
        timestamptz created_at
        timestamptz updated_at
        timestamptz deleted_at "Soft delete"
        bigint version
    }

    ACCOUNT {
        bigserial id PK "V3"
        varchar(32) account_number UK "12-digit Luhn (account_number_seq)"
        bigint customer_id FK
        varchar(3) currency "Default VND"
        numeric(19_2) balance "CHECK >= 0"
        numeric(19_2) transaction_limit "CHECK > 0"
        numeric(19_2) daily_limit "CHECK >= transaction_limit"
        date opened_date
        varchar(20) status "ACTIVE | LOCKED | CLOSED"
        timestamptz closed_at
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }

    ACCOUNT_STATUS_HISTORY {
        bigserial id PK "V10"
        bigint account_id FK
        varchar(20) from_status "NULL | ACTIVE | LOCKED | CLOSED"
        varchar(20) to_status "ACTIVE | LOCKED | CLOSED"
        varchar(500) reason
        varchar(100) changed_by
        timestamptz changed_at
    }

    TRANSACTION {
        bigserial id PK "V4"
        varchar(64) reference_no UK
        varchar(32) type "DEPOSIT | WITHDRAWAL | TRANSFER | PAYMENT | REVERSAL"
        bigint source_account_id FK
        bigint target_account_id FK
        numeric(19_2) amount "CHECK > 0"
        numeric(19_2) fee "CHECK >= 0"
        numeric(19_2) source_balance_after
        numeric(19_2) target_balance_after
        numeric(19_2) system_balance_delta
        varchar(255) location
        varchar(500) description
        varchar(32) status "COMPLETED | REVERSED"
        bigint reversal_of_id FK "UK"
        bigint recurring_id FK "V11"
        varchar(64) created_by
        timestamptz created_at
        timestamptz updated_at
    }

    IDEMPOTENCY_KEY {
        varchar(128) idem_key PK "Composite PK(idem_key, scope) (V6)"
        varchar(64) scope PK "Username or DEFAULT"
        varchar(64) request_hash "SHA-256 canonical payload"
        integer response_status
        text response_body
        timestamptz created_at
        timestamptz expires_at "24h TTL"
    }

    APP_USER {
        bigint id PK "GENERATED ALWAYS AS IDENTITY (V7)"
        varchar(100) username UK
        varchar(255) password_hash "BCrypt (strength 10)"
        varchar(20) role "ADMIN | CUSTOMER"
        bigint customer_id FK "UK (NULL for ADMIN, NOT NULL for CUSTOMER)"
        boolean enabled
        timestamptz last_login_at
        timestamptz created_at
        timestamptz updated_at
    }

    RECURRING_TRANSACTION {
        bigserial id PK "V11"
        varchar(20) type "DEPOSIT | PAYMENT | TRANSFER"
        bigint source_account_id FK
        bigint target_account_id FK
        numeric(19_2) amount "CHECK > 0"
        varchar(255) location
        varchar(500) description
        varchar(20) frequency "DAILY | WEEKLY | MONTHLY"
        date start_date
        date end_date
        timestamptz next_run_at "Partial index idx_rec_due"
        timestamptz last_run_at
        varchar(20) status "ACTIVE | PAUSED | CANCELLED | COMPLETED"
        integer failure_count "CHECK >= 0"
        varchar(500) last_error
        varchar(100) created_by
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }

    ALERT {
        bigint id PK "GENERATED ALWAYS AS IDENTITY (V13)"
        bigint transaction_id FK "UK(transaction_id, rule_code)"
        bigint account_id FK
        varchar(32) rule_code "AMOUNT_THRESHOLD | VELOCITY"
        varchar(16) severity "MEDIUM | HIGH"
        varchar(500) reason
        varchar(32) status "NEW | INVESTIGATING | RESOLVED | FALSE_POSITIVE"
        varchar(500) resolution_note
        varchar(64) handled_by
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }

    SHEDLOCK {
        varchar(64) name PK "V14 cluster scheduler lock"
        timestamptz lock_until "Lease expiration"
        timestamptz locked_at "Acquisition timestamp"
        varchar(255) locked_by "Instance identifier"
    }

    AUDIT_LOG {
        bigserial id PK "V14 append-only audit log"
        varchar(100) actor
        varchar(64) action "ACCOUNT_STATUS_CHANGED | ACCOUNT_LIMITS_UPDATED | TRANSACTION_REVERSED | ALERT_STATUS_CHANGED"
        varchar(64) entity_type "ACCOUNT | TRANSACTION | ALERT"
        varchar(64) entity_id
        text before_json "PII-masked snapshot"
        text after_json "PII-masked snapshot"
        varchar(64) request_id "X-Request-Id trace"
        varchar(64) ip
        timestamptz created_at
    }
```

---

## 2. Danh mục Chỉ mục Tối ưu hóa (Indexes — `V2` → `V14`)

| Bảng | Tên Chỉ mục | Loại Chỉ mục | Biểu thức Cột / Điều kiện `WHERE` | Migration | Mục đích Tối ưu Truy vấn |
|---|---|---|---|---|---|
| `core.customer` | `idx_customer_location` | Partial Expression B-tree | `(COALESCE(NULLIF(TRIM(location), ''), 'UNKNOWN')) WHERE deleted_at IS NULL` | `V2`, `V8` | Thống kê phân bố khách hàng đang hoạt động theo địa điểm (`L1-10`, `GET /api/v1/statistics/customers-by-location`) |
| `core.customer` | `idx_customer_deleted_at` | B-tree | `(deleted_at)` | `V2` | Lọc khách hàng chưa xóa mềm (`deleted_at IS NULL`) |
| `core.customer` | `idx_customer_type_id` | Partial B-tree | `(customer_type_id) WHERE deleted_at IS NULL` | `V12` | Tra cứu khách hàng theo phân loại (`INDIVIDUAL` / `BUSINESS`) |
| `core.customer` | `idx_customer_type_code` | Partial B-tree | `(customer_type_code) WHERE deleted_at IS NULL` | `V12` | Lọc/kiểm tra khách hàng đang dùng mã phân loại (`L3-05`) |
| `core.account` | `idx_account_customer_id` | B-tree | `(customer_id)` | `V3` | Liệt kê danh sách tài khoản theo khách hàng (`GET /api/v1/accounts`, `GET /api/v1/me/accounts`) |
| `core.account` | `idx_account_status` | B-tree | `(status)` | `V3` | Lọc tài khoản `ACTIVE` / `LOCKED` / `CLOSED` và thống kê bậc số dư (`L1-09`) |
| `core.transaction` | `idx_tx_source_created` | Composite B-tree | `(source_account_id, created_at DESC)` | `V4` | Lịch sử giao dịch chiều ghi nợ của tài khoản và quét gian lận Velocity (`L3-03`) |
| `core.transaction` | `idx_tx_target_created` | Composite B-tree | `(target_account_id, created_at DESC)` | `V4` | Lịch sử giao dịch chiều ghi có của tài khoản (`GET /api/v1/accounts/{id}/transactions`) |
| `core.transaction` | `idx_tx_created` | B-tree | `(created_at DESC)` | `V4` | Phân trang giao dịch toàn hệ thống theo thời gian mới nhất |
| `core.transaction` | `idx_tx_type_created` | Composite B-tree | `(type, created_at DESC)` | `V4` | Lọc giao dịch theo loại (`DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, `PAYMENT`, `REVERSAL`) |
| `core.transaction` | `idx_tx_source_completed_created_amount` | **Partial Covering Index** | `(source_account_id, created_at DESC) INCLUDE (amount) WHERE status = 'COMPLETED' AND type IN ('WITHDRAWAL', 'TRANSFER', 'PAYMENT')` | `V8` | **Index-Only Scan** tính tổng tiền rút/chuyển trong ngày (`sumOutgoingAmountSince`, giảm độ trễ trên 1M dòng từ `121,007 ms` xuống `0,937 ms`) |
| `core.transaction` | `idx_tx_status_created` | Composite B-tree | `(status, created_at DESC)` | `V9` | Tìm kiếm giao dịch nâng cao (`L2-04`) và stream báo cáo Excel/PDF theo kỳ (`L3-07`) |
| `core.transaction` | `idx_tx_amount_created` | Composite B-tree | `(amount DESC, created_at DESC)` | `V9` | Lọc giao dịch theo khoảng tiền (`minAmount`, `maxAmount`) và quét `AMOUNT_THRESHOLD` |
| `core.transaction` | `idx_tx_recurring_id` | Partial B-tree | `(recurring_id) WHERE recurring_id IS NOT NULL` | `V11` | Truy vết các giao dịch phát sinh từ lịch định kỳ |
| `core.transaction` | `idx_tx_src_keyset` / `idx_tx_tgt_keyset` / `idx_tx_created_id_keyset` | **Keyset Composite B-tree** | `(source_account_id, created_at DESC, id DESC)` / `(target_account_id, created_at DESC, id DESC)` / `(created_at DESC, id DESC)` | `V14` | Phân trang con trỏ Keyset Pagination $O(\log N)$ trên lịch sử giao dịch triệu dòng (`Story 6.1`) |
| `core.idempotency_key` | `idx_idempotency_expires_at` | B-tree | `(expires_at)` | `V6` | Dọn dẹp định kỳ các khóa `Idempotency-Key` hết hạn sau 24 giờ |
| `core.app_user` | `idx_app_user_customer_id` | Partial B-tree | `(customer_id) WHERE customer_id IS NOT NULL` | `V7` | Liên kết 1-1 tài khoản đăng nhập `CUSTOMER` với hồ sơ `core.customer` |
| `core.account_status_history` | `idx_acc_status_hist_account_changed` | Composite B-tree | `(account_id, changed_at DESC, id DESC)` | `V10` | Truy vấn lịch sử chuyển trạng thái của tài khoản (`L2-05`) |
| `core.recurring_transaction` | `idx_rec_due` | **Partial B-tree** | `(next_run_at ASC, id ASC) WHERE status = 'ACTIVE'` | `V11` | Quét các lệnh định kỳ đến hạn bằng `FOR UPDATE SKIP LOCKED` (`L2-07`) |
| `core.recurring_transaction` | `idx_rec_source_account` | Partial B-tree | `(source_account_id, status) WHERE source_account_id IS NOT NULL` | `V11` | Kiểm tra nhanh lệnh định kỳ đang hoạt động trước khi đóng tài khoản (`AccountClosureGuard`) |
| `core.recurring_transaction` | `idx_rec_target_account` | Partial B-tree | `(target_account_id, status) WHERE target_account_id IS NOT NULL` | `V11` | Kiểm tra lệnh định kỳ nhận tiền khi đóng tài khoản |
| `core.recurring_transaction` | `idx_rec_status_created` | Composite B-tree | `(status, created_at DESC, id DESC)` | `V11` | Phân trang danh sách lịch giao dịch định kỳ theo trạng thái |
| `core.alert` | `idx_alert_status_created` | Composite B-tree | `(status, created_at DESC, id DESC)` | `V13` | Lọc cảnh báo gian lận theo trạng thái xử lý (`NEW`, `INVESTIGATING`, ...) |
| `core.alert` | `idx_alert_account_created` | Composite B-tree | `(account_id, created_at DESC, id DESC)` | `V13` | Tra cứu cảnh báo theo tài khoản bị giám sát |
| `core.alert` | `idx_alert_rule_created` | Composite B-tree | `(rule_code, created_at DESC, id DESC)` | `V13` | Lọc cảnh báo theo quy tắc (`AMOUNT_THRESHOLD`, `VELOCITY`) |
| `core.alert` | `idx_alert_created` | Composite B-tree | `(created_at DESC, id DESC)` | `V13` | Phân trang danh sách cảnh báo toàn hệ thống |
| `core.audit_log` | `idx_audit_log_created` / `idx_audit_log_actor_created` / `idx_audit_log_action_created` | Composite B-tree | `(created_at DESC, id DESC)` / `(actor, created_at DESC, id DESC)` / `(action, created_at DESC, id DESC)` | `V14` | Tra cứu nhật ký kiểm toán bất biến theo thời gian, người thực hiện và hành động (`GET /api/v1/admin/audit-logs`, `Story 6.3`) |

*(Lưu ý: Migration `V5__drop_redundant_indexes.sql` đã chủ động xóa chỉ mục dư thừa `idx_account_number` vì cột `account_number` đã có ràng buộc `UNIQUE` tự tạo B-tree index).*

---

## 3. Danh mục Ràng buộc Toàn vẹn (`CHECK`, `UNIQUE`, `FOREIGN KEY`, `TRIGGER`)

| Bảng | Tên Ràng buộc | Loại | Định nghĩa Chi tiết | Ý nghĩa Nghiệp vụ |
|---|---|---|---|---|
| `core.customer` | `customer_customer_code_key` | `UNIQUE` | `UNIQUE (customer_code)` | Mã khách hàng (`CUS...`) duy nhất toàn hệ thống |
| `core.customer` | `customer_national_id_key` | `UNIQUE` | `UNIQUE (national_id)` | Số CCCD/CMND/ĐKKD không trùng lặp |
| `core.account` | `account_account_number_key` | `UNIQUE` | `UNIQUE (account_number)` | Số tài khoản 12 chữ số Luhn duy nhất |
| `core.account` | `fk_account_customer` | `FOREIGN KEY` | `customer_id REFERENCES core.customer(id)` | Mọi tài khoản phải thuộc về một khách hàng hợp lệ |
| `core.account` | `chk_account_balance` | `CHECK` | `balance >= 0` | Chặn tuyệt đối số dư âm ngay tại tầng CSDL |
| `core.account` | `chk_account_limits` | `CHECK` | `transaction_limit > 0 AND daily_limit >= transaction_limit` | Hạn mức ngày luôn lớn hơn hoặc bằng hạn mức từng lần |
| `core.transaction` | `transaction_reference_no_key` | `UNIQUE` | `UNIQUE (reference_no)` | Mã tham chiếu giao dịch duy nhất (chống trùng lệnh định kỳ `REC-{id}-{yyyyMMdd}`) |
| `core.transaction` | `transaction_reversal_of_id_key` | `UNIQUE` | `UNIQUE (reversal_of_id)` | Mỗi giao dịch gốc chỉ được đảo (`REVERSAL`) tối đa 1 lần duy nhất (`L1-08`) |
| `core.transaction` | `chk_tx_amount` | `CHECK` | `amount > 0` | Số tiền giao dịch luôn dương |
| `core.transaction` | `chk_tx_fee` | `CHECK` | `fee >= 0` | Phí giao dịch không âm |
| `core.idempotency_key` | `pk_idempotency_key` | `PRIMARY KEY` | `PRIMARY KEY (idem_key, scope)` | Cô lập khóa chống gửi trùng theo từng người dùng (`scope`) |
| `core.app_user` | `chk_app_user_role` | `CHECK` | `role IN ('ADMIN', 'CUSTOMER')` | Chỉ cho phép 2 vai trò hợp lệ trong hệ thống |
| `core.app_user` | `chk_app_user_customer_link` | `CHECK` | `(role = 'ADMIN' AND customer_id IS NULL) OR (role = 'CUSTOMER' AND customer_id IS NOT NULL)` | Ràng buộc chặt chẽ: `CUSTOMER` bắt buộc gắn với 1 hồ sơ khách hàng, `ADMIN` không gắn |
| `core.account_status_history` | `chk_acc_status_hist_from` / `to` | `CHECK` | `to_status IN ('ACTIVE', 'LOCKED', 'CLOSED')` | Đảm bảo nhật ký kiểm toán trạng thái tài khoản hợp lệ |
| `core.recurring_transaction` | `chk_rec_accounts_by_type` | `CHECK` | Kiểm tra cặp `(source_account_id, target_account_id)` theo từng `type` (`DEPOSIT`, `PAYMENT`, `TRANSFER`) và `source <> target` | Ngăn cấu hình sai tài khoản nguồn/đích của giao dịch định kỳ |
| `core.customer_type` | `customer_type_limits_check` | `CHECK` | `max_transaction_limit > 0 AND max_daily_limit >= max_transaction_limit` | Đảm bảo trần hạn mức phân loại khách hàng hợp lệ (`L3-06`) |
| `core.alert` | `uk_alert_tx_rule` | `UNIQUE` | `UNIQUE (transaction_id, rule_code)` | Chống sinh trùng cảnh báo khi quét lại lịch sử (`ON CONFLICT DO NOTHING`) |
| `core.alert` | `chk_alert_rule_code` / `status` | `CHECK` | `rule_code IN ('AMOUNT_THRESHOLD', 'VELOCITY')`, `status IN ('NEW', 'INVESTIGATING', 'RESOLVED', 'FALSE_POSITIVE')` | Chuẩn hóa vòng đời xử lý cảnh báo gian lận (`L3-04`) |
| `core.audit_log` | `trg_audit_log_append_only` | `TRIGGER` | `BEFORE UPDATE OR DELETE ON core.audit_log FOR EACH ROW EXECUTE FUNCTION core.prevent_audit_log_mutation()` | Đảm bảo bảng nhật ký kiểm toán là bất biến (`Append-Only` — chặn mọi thao tác sửa/xóa dòng kiểm toán, `Story 6.3`) |


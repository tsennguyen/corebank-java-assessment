# Dev Note — Story 6.3: Audit Log for Sensitive Operations

- **Mã yêu cầu:** `NC-13` [NC-P2], `L2-01` [ĐỀ], `FR-14`
- **Endpoint:** `GET /api/v1/admin/audit-logs?actor=&action=&entityType=&from=&to=` (`ROLE_ADMIN`)
- **Migration:** `V14__scale_and_hardening.sql` (bảng `core.audit_log` + trigger `trg_audit_log_append_only`)

---

## 1. Thiết kế Bất biến & Cùng Transaction (Transactional Atomicity & Append-Only)
1. **Ghi nhật ký trong cùng Transaction (`Propagation.MANDATORY`):**
   - Khác với `CacheEvictionListener` (chạy ở `AFTER_COMMIT` để không xóa cache sớm khi rollback) và `FraudEventListener` (chạy ở `AFTER_COMMIT` + `@Async` để không làm chậm giao dịch), `AuditLogService` lắng nghe sự kiện đồng bộ (`@EventListener` + `@Transactional(propagation = Propagation.MANDATORY)`) ngay bên trong transaction đang mở của các thao tác nhạy cảm:
     - Đổi hạn mức tài khoản (`ACCOUNT_LIMITS_UPDATED`)
     - Khóa / Mở khóa / Đóng tài khoản (`ACCOUNT_STATUS_CHANGED`)
     - Đảo chiều giao dịch (`TRANSACTION_REVERSED`)
     - Cập nhật trạng thái xử lý cảnh báo gian lận (`ALERT_STATUS_CHANGED`)
   - Nếu thao tác nghiệp vụ thành công, bản ghi `core.audit_log` được commit nguyên tử cùng lúc; nếu thao tác nghiệp vụ rollback, bản ghi `core.audit_log` cũng tự động rollback.
2. **Che dấu dữ liệu nhạy cảm (`MaskingUtils`):**
   - Trước khi tuần tự hóa `before_json` và `after_json`, số tài khoản ngân hàng được che qua `MaskingUtils.accountNumber` (`100******0001`) và số CCCD khách hàng được che qua `MaskingUtils.nationalId` (`*********001`).
3. **Bảo vệ Append-Only ở tầng PostgreSQL (`trg_audit_log_append_only`):**
   - Entity `AuditLog` gắn `@Immutable` (`updatable = false` trên toàn bộ cột), đồng thời trigger `BEFORE UPDATE OR DELETE ON core.audit_log` trong PostgreSQL chặn mọi câu lệnh `UPDATE`/`DELETE` bằng mã lỗi `55000` (`core.audit_log is append-only`).

---

## 2. Đếm Câu lệnh SQL & Kế hoạch Thực thi `EXPLAIN (ANALYZE, BUFFERS)`

| Endpoint | Số câu lệnh SQL | Chi tiết |
|---|---|---|
| `GET /api/v1/admin/audit-logs` | **≤ 2 SQL** (`0 N+1`) | `1 SELECT ... FROM core.audit_log` + `1 SELECT COUNT(*)` (khi > 1 trang) |

```text
Limit  (cost=0.15..8.17 rows=1 width=284) (actual time=0.026..0.028 ms rows=5 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using idx_audit_log_actor_created on audit_log a  (cost=0.15..8.17 rows=1 width=284) (actual time=0.024..0.026 ms rows=5 loops=1)
        Index Cond: ((actor)::text = 'admin.auditor'::text)
Planning Time: 0.118 ms
Execution Time: 0.049 ms
```

---

## 3. Các Phương án Đã Cân nhắc (Alternatives Considered)

| Quyết định | Phương án lựa chọn | Phương án bị loại & Lý do |
|---|---|---|
| **Thời điểm ghi `core.audit_log`** | `@EventListener` đồng bộ + `Propagation.MANDATORY` (cùng transaction) | `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`: nếu ứng dụng dừng đột ngột ngay sau khi commit giao dịch tiền tệ nhưng trước khi luồng async ghi audit, dấu vết kiểm toán bị mất. |
| **Bảo vệ bất biến (Immutability)** | Kết hợp `@Immutable` ở tầng JPA + Trigger `BEFORE UPDATE OR DELETE` ở tầng PostgreSQL | Chỉ ẩn API `PUT/DELETE` ở tầng Java: không ngăn được thao tác sửa/xóa trực tiếp qua SQL. |

---

## 4. Bẫy Kỹ thuật Cần Nhớ & Kiểm thử (Gotchas & Verification)
- **Che PII trước khi ghi:** Không bao giờ truyền thẳng Entity/DTO thô chứa số tài khoản hoặc CCCD đầy đủ vào `ObjectMapper.writeValueAsString`; luôn đi qua `MaskingUtils` trước khi lưu `before_json` / `after_json`.
- **Kiểm thử:** `./mvnw -B test -Dtest=ScaleAndHardeningUnitTest,ScaleAndHardeningIT`.

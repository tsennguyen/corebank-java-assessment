# Dev Note — Story 5.6: Production Release & Demo Readiness (`v1.0.0`)

- **Mã yêu cầu:** `E-01` [ĐỀ], `E-02` [ĐỀ], `E-03` [ĐỀ], `NC-10` [NC-P1], `NC-05` [NC-P0]
- **Thành phần chính:** `backend/src/main/resources/db/seed/R__demo_seed.sql`, `docker-compose.yml`, `render.yaml`

---

## 1. Bối cảnh & Mục tiêu (Context & Objectives)
Story 5.6 hoàn tất khâu chuẩn bị dữ liệu demo và phát hành chính thức phiên bản **`v1.0.0`** cho hệ thống **Core Banking System**:
1. **Dữ liệu Demo Cảnh báo Bất thường có sẵn (`R__demo_seed.sql`):**
   - Bổ sung giao dịch chuyển khoản chiều đi giá trị lớn `TXN-SEED-0017` (`75.000.000 VND >= 50.000.000 VND`) và chuỗi 6 giao dịch chuyển khoản liên tiếp trong 30 giây (`TXN-SEED-0018`..`TXN-SEED-0023`) giữa hai tài khoản doanh nghiệp `100800000109` và `100800000091`.
   - Khởi tạo sẵn 2 bản ghi `core.alert` (`AMOUNT_THRESHOLD` mức `HIGH` và `VELOCITY` mức `MEDIUM`) với `ON CONFLICT (transaction_id, rule_code) DO NOTHING` để ngay sau khi khởi động hoặc khôi phục dữ liệu hàng ngày lúc 03:30, `GET /api/v1/alerts` luôn có sẵn dữ liệu demo, đồng thời `POST /api/v1/fraud/scans` quét lịch sử vẫn hoạt động lũy đẳng (idempotent).
2. **Bảo toàn Bất biến Sổ cái (`Ledger Invariant`):**
   - Tổng số dư của 12 tài khoản mẫu (`1.401.171.000,00 VND`) khớp tuyệt đối (`0,00 VND` sai lệch) với tổng `system_balance_delta` của 23 giao dịch mẫu `TXN-SEED-0001`..`TXN-SEED-0023`, đồng thời giữ nguyên tập giao dịch của tài khoản cá nhân `cust.an` (`100800000018`) và `cust.binh` (`100800000026`).
3. **Kiểm toán Bảo mật & Ẩn danh (`E-02`):**
   - Toàn bộ mã nguồn, cấu hình, migration và tài liệu tuân thủ tuyệt đối quy tắc không chứa tên ngân hàng/tổ chức/cá nhân thật và không chứa file cấu hình tác nhân AI.

---

## 2. Kiểm chứng Bất biến Sổ cái & Kế hoạch Thực thi SQL

| Chỉ số kiểm chứng | Giá trị |
|---|---|
| Tổng số dư 12 tài khoản mẫu (`SUM(balance)`) | **`1.401.171.000,00 VND`** |
| Tổng biến thiên ròng 23 giao dịch (`DEPOSIT - WITHDRAWAL - PAYMENT - FEE`) | **`1.401.171.000,00 VND`** |
| Độ lệch sổ cái (`Ledger Drift`) | **`0,00 VND`** |

```sql
SELECT
  (SELECT SUM(balance) FROM core.account) AS total_account_balance,
  (SELECT SUM(CASE
      WHEN type = 'DEPOSIT' THEN amount - fee
      WHEN type IN ('WITHDRAWAL', 'PAYMENT') THEN -(amount + fee)
      WHEN type = 'TRANSFER' THEN -fee
      ELSE 0 END)
   FROM core.transaction WHERE status = 'COMPLETED') AS net_ledger_delta;
```

---

## 3. Các Phương án Đã Cân nhắc (Alternatives Considered)

| Quyết định | Phương án lựa chọn | Phương án bị loại & Lý do |
|---|---|---|
| **Tài khoản dùng để seed giao dịch bất thường (`TXN-SEED-0017..0023`)** | Dùng cặp tài khoản doanh nghiệp `100800000109` ⇄ `100800000091` (chuyển khoản nội bộ 0 phí) | Seed vào tài khoản cá nhân `100800000018` (`cust.an`): làm thay đổi số lượng giao dịch của `cust.an` và phá vỡ các bài kiểm thử tích hợp `OwnershipIntegrationTest` / `TransactionSearchIntegrationTest`. |
| **Cơ chế nạp dữ liệu demo (`R__demo_seed.sql`)** | Flyway Repeatable Migration (`ON CONFLICT DO NOTHING` + `setval` đồng bộ sequence) | Chạy script ngoài bằng tay: không tự động áp dụng khi container khởi động mới trên Docker Compose hoặc Render. |

---

## 4. Bẫy Kỹ thuật Cần Nhớ & Kiểm thử (Gotchas & Verification)
- **Đồng bộ PostgreSQL Sequence sau khi chèn ID/Reference tĩnh:** Cuối `R__demo_seed.sql` luôn gọi `setval('core.account_number_seq', ...)` để các tài khoản tạo mới qua API không bị đụng mã số tài khoản Luhn đã seed.
- **Kiểm thử:** `./mvnw -B verify -Pci` và `docker compose up --build -d`.

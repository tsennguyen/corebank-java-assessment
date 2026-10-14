# Dev Note 2.2: Account Management API

- **Tóm tắt:** Triển khai API quản lý tài khoản thanh toán: mở tài khoản số 12 chữ số sinh theo thuật toán Luhn, quản lý hạn mức giao dịch, phân loại nhóm số dư và đóng tài khoản an toàn. Mã yêu cầu: `L1-01`, `L1-02`, `L1-05`, `L2-04`. PR: [#9](https://github.com/tsennguyen/corebank-java-assessment/pull/9).
- **Đã làm gì:**
  - Viết Flyway migration `V3__account.sql` tạo bảng `core.account`, sequence `account_number_seq` và các ràng buộc `CHECK (balance >= 0)`, `CHECK (transaction_limit > 0 AND daily_limit >= transaction_limit)`.
  - Triển khai `AccountNumberGenerator` sinh số tài khoản 12 chữ số: tiền tố `100` + 8 chữ số sequence + 1 chữ số kiểm tra Luhn checksum.
  - Triển khai `BalanceTierPolicy` sử dụng cấu trúc dữ liệu `TreeMap.floorEntry` để phân loại nhóm số dư (`LOW`, `MEDIUM`, `HIGH`, `VIP`) với độ phức tạp $O(\log n)$.
  - Triển khai `AccountCustomerDeletionGuard` chặn xóa khách hàng khi vẫn còn tài khoản đang hoạt động.
  - Xây dựng `AccountController` và `AccountService`: mở tài khoản (201), xem chi tiết (200), tìm kiếm phân trang (200), điều chỉnh hạn mức (200), đóng tài khoản (204).
- **Vì sao làm vậy:** Số tài khoản theo chuẩn Luhn giúp phát hiện ngay sai sót khi người dùng nhập nhầm số tài khoản. Ràng buộc đóng tài khoản chỉ cho phép khi số dư bằng 0 bảo vệ tài sản của khách hàng.
- **Phương án khác đã cân nhắc:** Sinh số tài khoản ngẫu nhiên hoàn toàn (UUID); lý do không chọn: không thân thiện với người dùng và không tuân theo thông lệ ngân hàng.
- **Bẫy cần nhớ:**
  - Nếu tài khoản còn số dư (> 0), thao tác đóng tài khoản bị từ chối với HTTP 409 `ACCOUNT_HAS_BALANCE`.
  - Cập nhật hạn mức tài khoản `CLOSED` bị từ chối với HTTP 409 `INVALID_STATUS_TRANSITION` (thay vì lỗi 500).
  - Tra cứu số tài khoản dùng so khớp chính xác `EQUAL` thay vì `LIKE` để tận dụng B-tree UNIQUE index.
  - `AccountNumberGenerator` sử dụng sequence database nên đặt tại tầng hạ tầng `account.infrastructure`.
- **Cách chạy/test:**
  - `./mvnw test -Dtest=AccountControllerTest,AccountServiceTest,AccountTest,AccountNumberGeneratorTest,BalanceTierPolicyTest,AccountSpecificationsTest`

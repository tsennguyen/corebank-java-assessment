# Dev Note 2.1: Customer Management API

- **Tóm tắt:** Xây dựng trọn bộ API quản lý hồ sơ khách hàng (CRUD, tìm kiếm phân trang có whitelist, che mờ CCCD, khóa lạc quan và xóa mềm an toàn). Mã yêu cầu: `L1-01`, `L1-04`, `L2-04`. PR: [#8](https://github.com/tsennguyen/corebank-java-assessment/pull/8).
- **Đã làm gì:**
  - Viết Flyway migration `V2__customer.sql` tạo bảng `core.customer` và sequence `core.customer_code_seq`.
  - Triển khai `CustomerCodeGenerator` sinh mã định dạng `CUSxxxxxxx` tự động tăng.
  - Xây dựng domain entity `Customer` với cơ chế khóa lạc quan `@Version` và xóa mềm `deleted_at`.
  - Triển khai `MaskingUtils` tự động che số CCCD (chỉ hiển thị 3 số cuối, ví dụ `*********789`).
  - Xây dựng `CustomerController` và `CustomerService` hỗ trợ: tạo mới (201), xem chi tiết (200), tìm kiếm phân trang theo tên và địa điểm (200), cập nhật thông tin (200), xóa mềm (204).
  - Áp dụng whitelist sắp xếp và giới hạn kích thước phân trang (1 đến 100) để chống tấn công DoS và SQL Injection.
- **Vì sao làm vậy:** Quản lý thông tin khách hàng là nền tảng cốt lõi của ngân hàng. Sử dụng xóa mềm để bảo toàn tính toàn vẹn dữ liệu cho các báo cáo kiểm toán và giao dịch sau này.
- **Phương án khác đã cân nhắc:** Xóa cứng (hard-delete) bản ghi trong DB; lý do không chọn: vi phạm tính toàn vẹn lịch sử giao dịch và chuẩn kiểm toán tài chính.
- **Bẫy cần nhớ:**
  - CCCD gửi lên bắt buộc bắt đầu bằng `000` (giả lập), không chấp nhận CCCD người thật.
  - Cập nhật thông tin dùng `saveAndFlush` để ép Hibernate đẩy SQL UPDATE và tăng version trong DB ngay trước khi map sang DTO, tránh trả về version cũ cho client.
  - Tìm kiếm khách hàng theo tên hiện dùng `LIKE %...%`; đánh đổi là quét toàn bảng, hướng mở rộng cho dữ liệu lớn là tích hợp `pg_trgm` và GIN index.
- **Quyết định về CCCD:** `national_id` là `UNIQUE` trên toàn bảng, kể cả khách đã xóa mềm. Lý do: giữ vết định danh (KYC) không bị trùng lặp; một người quay lại thì khôi phục hồ sơ cũ thay vì tạo hồ sơ mới. Đánh đổi: không tạo được hồ sơ mới cùng CCCD sau khi xóa.
- **Cách chạy/test:**
  - `./mvnw test -Dtest=CustomerControllerTest,CustomerServiceTest,CustomerTest`

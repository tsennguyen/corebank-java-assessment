# Developer Note: Story 0.5 — API Correctness and Architecture Cleanup

## Tóm tắt
- **Story:** 0-5 API correctness and cleanup (Audit vòng 2).
- **Mã yêu cầu:** L1-04, L1-05, G-03, NC-08.
- **Mục tiêu:** Khắc phục triệt để các phát hiện audit vòng 2 về tính đúng đắn của khóa lạc quan (`@Version`), chuẩn hóa mã lỗi trạng thái tài khoản (409 thay vì 500), bảo mật PII trong log (che CCCD), dọn dẹp code chết và tối ưu phân trang/truy vấn.

## Đã làm gì
1. **Đồng bộ `@Version` ngay khi update (R1):**
   - Thay `repository.save(entity)` bằng `repository.saveAndFlush(entity)` trong cả `CustomerService.updateCustomer` và `AccountService.updateLimits`.
   - Viết bài test tích hợp `CustomerUpdateVersionIT` (chạy trên PostgreSQL qua Testcontainers) xác nhận version trả về tăng từ 0 lên 1 và cho phép gọi tiếp bản cập nhật kế tiếp với version 1.
2. **Chuẩn hóa mã lỗi vòng đời tài khoản (R2, R10):**
   - `Account.updateLimits` và `Customer.update` ném `BusinessRuleException(ErrorCode.INVALID_STATUS_TRANSITION)` thay vì `IllegalStateException`.
   - `GlobalExceptionHandler` ánh xạ thành HTTP 409 Conflict với ProblemDetail RFC 9457 rõ ràng, không gây hiểu nhầm hệ thống sập với 500.
3. **Che giấu PII trong Log (R3):**
   - Trong `CustomerService.createCustomer`, bọc số CCCD qua `MaskingUtils.nationalId(request.nationalId())` khi ghi log cảnh báo trùng CCCD.
4. **Dọn dẹp code chết & Tái cấu trúc hạ tầng (R4, R5, R6):**
   - Xóa bỏ `isValidLuhn` khỏi production code của `AccountNumberGenerator` (loại bỏ code chết theo G-03).
   - Di chuyển `AccountNumberGenerator` từ package `domain` sang `account.infrastructure` (đúng nguyên lý Clean Architecture vì phụ thuộc trực tiếp vào `JdbcTemplate` sequence DB).
   - Rút gọn `CustomerService` về 1 constructor duy nhất với `CustomerDeletionGuard` bắt buộc, loại bỏ `Optional`.
5. **Tìm kiếm chính xác số tài khoản & Sắp xếp phân trang tất định (R7, R8, R9):**
   - Sửa `AccountSpecifications.withAccountNumber` từ `LIKE %...%` sang `EQUAL` để tận dụng B-tree index UNIQUE của `account_number`.
   - Cấu hình `@PageableDefault` mặc định `id DESC` và bổ sung `resolvePageable` thêm tie-breaker `id DESC` nếu sort theo trường không duy nhất (`fullName`, `location`, `createdAt`).
   - Sửa mô tả Swagger ở `TransactionController` từ `FROZEN` thành `LOCKED` cho khớp enum `AccountStatus`.

## Vì sao làm vậy
- **Cơ chế flush trong JPA:** `save()` chỉ chuyển entity vào trạng thái dirty trong Persistence Context. SQL `UPDATE` và việc tăng version chỉ diễn ra lúc Hibernate flush (thường khi commit transaction). Nếu map DTO trước flush, DTO sẽ mang version cũ làm client bị 409 giả tạo. Gọi `saveAndFlush()` ép Hibernate thực thi câu UPDATE và làm tươi version ngay trước khi đóng gói DTO.
- **Phân trang tất định (Deterministic Pagination):** Trên cơ sở dữ liệu quan hệ, khi phân trang theo các trường có độ trùng lặp cao (`location`, `fullName`), nếu không có cột định danh duy nhất làm khóa phụ, các bản ghi có thể bị trượt qua trang khác hoặc hiển thị lặp lại.

## Phương án khác đã cân nhắc
- **Tìm kiếm số tài khoản bằng LIKE vs EQUAL:** Dùng LIKE linh hoạt nhưng quét toàn bảng (Full Table Scan) khi dữ liệu lớn. Số tài khoản ngân hàng là khóa định danh kinh doanh 12 chữ số cố định, người dùng hoặc hệ thống tra cứu theo số chính xác nên `cb.equal` là lựa chọn tối ưu về hiệu năng index.
- **Tìm kiếm tên khách hàng:** Giữ nguyên `LIKE %...%` cho tên khách hàng theo đúng yêu cầu đề bài; ghi nhận hướng nâng cấp sản phẩm thực tế là bổ sung extension `pg_trgm` và GIN index để tối ưu LIKE 2 đầu trên tập dữ liệu hàng triệu khách hàng.

## Bẫy cần nhớ
- Khi mock trong unit test Spring Data JPA: nếu code gọi `saveAndFlush()`, mockito stubbing phải khai báo `when(repo.saveAndFlush(...))` thay vì `when(repo.save(...))`.
- Thứ tự phân trang với Strict Stubbing của Mockito: `resolvePageable` can thiệp thêm sort `id DESC` nên đối tượng `Pageable` truyền vào repository sẽ có cả trường sort phụ.

## Cách chạy/test
```bash
# Chạy toàn bộ test suite (unit + integration)
./mvnw clean test

# Chạy riêng các test liên quan Story 0-5
./mvnw test -Dtest=CustomerUpdateVersionIT,CustomerServiceTest,AccountServiceTest,AccountSpecificationsTest,AccountNumberGeneratorTest,AccountTest,CustomerTest
```

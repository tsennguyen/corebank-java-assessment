# Dev Note 1.4: Problem Detail RFC 9457 Error Model

- **Tóm tắt:** Xây dựng mô hình xử lý lỗi tập trung theo chuẩn RFC 9457 (`application/problem+json`) với enum mã lỗi domain ổn định và gắn correlation request ID. Mã yêu cầu: `NC-08`. PR: [#6](https://github.com/tsennguyen/corebank-java-assessment/pull/6).
- **Đã làm gì:**
  - Định nghĩa enum `ErrorCode` ánh xạ HTTP Status, tiêu đề mặc định và URI loại lỗi (`urn:problem-type:...`).
  - Xây dựng cây ngoại lệ domain (`DomainException`, `BusinessRuleException`, `ResourceNotFoundException`, `DuplicateResourceException`).
  - Triển khai `GlobalExceptionHandler` kế thừa `ResponseEntityExceptionHandler` để bắt mọi ngoại lệ (validation, domain, concurrency, system) và trả về payload RFC 9457 `ProblemDetail`.
  - Bổ sung `RequestIdFilter` gắn `X-Request-Id` (UUID) vào MDC và response headers để phục vụ truy vết phân tán.
- **Vì sao làm vậy:** Chuẩn hóa giao tiếp lỗi giữa client và server theo chuẩn quốc tế, giúp phía giao diện dễ dàng bắt mã lỗi nghiệp vụ mà không làm rò rỉ stack trace nhạy cảm của hệ thống backend.
- **Phương án khác đã cân nhắc:** Trả về đối tượng JSON tự chế `{ "success": false, "message": "..." }`; lý do không chọn: không tuân thủ tiêu chuẩn IETF RFC 9457 và thiếu tính tương thích với hệ sinh thái API hiện đại.
- **Bẫy cần nhớ:** Mọi exception không bắt được (500) phải được che giấu thông tin nội bộ, chỉ hiển thị thông báo chung kèm requestId để liên hệ hỗ trợ.
- **Cách chạy/test:**
  - `./mvnw test -Dtest=GlobalExceptionHandlerTest,DomainExceptionTest,RequestIdFilterTest`

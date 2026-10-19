# Dev Note 1.5: OpenAPI Documentation

- **Tóm tắt:** Tích hợp `springdoc-openapi` 2.8.5 tự động sinh tài liệu API theo chuẩn OpenAPI 3 và cung cấp giao diện Swagger UI tương tác. Mã yêu cầu: `NC-09`. PR: [#7](https://github.com/tsennguyen/corebank-java-assessment/pull/7).
- **Đã làm gì:**
  - Tích hợp dependency `springdoc-openapi-starter-webmvc-ui:2.8.5`.
  - Cấu hình `OpenApiConfig` thiết lập thông tin liên hệ, giấy phép, bảo mật Bearer JWT và metadata hệ thống.
  - Phân nhóm Swagger tags (`Customer`, `Account`, `Transaction`, `Authentication`, `Report`, v.v.).
  - Viết integration test `OpenApiDocumentationIT` xác minh endpoint `/v3/api-docs` trả về tài liệu JSON hợp lệ và UI mở tại `/swagger-ui.html`.
- **Vì sao làm vậy:** Cung cấp tài liệu API sống động, luôn cập nhật theo code thật, hỗ trợ người chấm kiểm thử trực tiếp các endpoint trên trình duyệt mà không cần cài đặt thêm Postman.
- **Phương án khác đã cân nhắc:** Viết tài liệu thủ công bằng file markdown hoặc YAML; lý do không chọn: dễ bị lệch pha (drift) so với code thực tế khi tính năng phát triển nhanh.
- **Bẫy cần nhớ:** Tuân thủ quy tắc `no-real-names.md`: không để tên ngân hàng hay thông tin thật trong tiêu đề, mô tả hoặc dữ liệu mẫu của Swagger.
- **Cách chạy/test:**
  - Khởi động backend và mở trình duyệt: `http://localhost:8080/swagger-ui.html`
  - Chạy test: `./mvnw test -Dtest=OpenApiConfigTest,OpenApiDocumentationIT`

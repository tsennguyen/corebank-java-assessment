# Dev Note 1.2: Backend CI Pipeline

- **Tóm tắt:** Xây dựng quy trình tích hợp liên tục (CI) trên GitHub Actions kiểm tra toàn diện: biên dịch Java 21, kiểm tra style, chạy kiểm thử tự động với Testcontainers và xác minh ngưỡng JaCoCo. Mã yêu cầu: `NC-05`, `G-04`, `NC-04`. PR: [#3](https://github.com/tsennguyen/corebank-java-assessment/pull/3).
- **Đã làm gì:**
  - Tạo workflow `.github/workflows/backend-ci.yml` kích hoạt khi push hoặc mở PR vào nhánh `dev`, `main`.
  - Tích hợp runner `ubuntu-latest`, setup JDK 21 (Temurin), bật caching cho Maven dependencies.
  - Chạy `./mvnw clean verify -Pci` kích hoạt cổng JaCoCo gate 80% line coverage và chạy kiểm thử Testcontainers PostgreSQL 17 trên CI.
  - Cấu hình điều kiện graceful fallback `DisabledIfNoDockerCondition` cho các bài test integration để máy local không có Docker vẫn chạy được unit tests.
- **Vì sao làm vậy:** Đảm bảo mọi commit và PR đưa vào nhánh `dev` đều được kiểm chứng độc lập trên môi trường chuẩn, không phụ thuộc vào cấu hình máy cá nhân.
- **Phương án khác đã cân nhắc:** Dùng H2 in-memory database cho integration test; lý do không chọn: H2 không hỗ trợ đầy đủ các tính năng nâng cao của PostgreSQL (schema `core`, partial index, JSONB, locking dialect).
- **Bẫy cần nhớ:** Máy cá nhân không có Docker daemon sẽ bỏ qua integration test; chỉ khi lên GitHub Actions CI các test này mới được thực thi đầy đủ.
- **Cách chạy/test:**
  - Kiểm tra status check trên GitHub PR: `Backend CI (Java 21, Spotless, Checkstyle, Tests, JaCoCo)`.

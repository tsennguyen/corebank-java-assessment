# Dev Note 1.1: Project Skeleton & Quality Tooling

- **Tóm tắt:** Khởi tạo bộ khung dự án Java 21, Spring Boot 3.5.16 với các công cụ kiểm soát chất lượng code tự động (Spotless, Checkstyle, JaCoCo). Mã yêu cầu: `G-01`, `G-03`, `E-01`, `NC-05`. PR: [#1](https://github.com/tsennguyen/corebank-java-assessment/pull/1).
- **Đã làm gì:**
  - Thiết lập Maven Wrapper và cấu hình `pom.xml` với Spring Boot 3.5.16, Java 21 LTS.
  - Cấu hình Spotless plugin (`com.diffplug.spotless:spotless-maven-plugin:2.44.2`) theo chuẩn Google Java Format (AOSP).
  - Tích hợp Checkstyle (`checkstyle.xml`) chặn các lỗi cú pháp, quy ước đặt tên và kiểm soát package boundaries.
  - Cấu hình JaCoCo plugin đo lường độ phủ kiểm thử tự động với ngưỡng gate 80% line coverage.
  - Thiết lập cấu trúc thư mục modular monolith theo package-by-feature (`common`, `customer`, `account`, `transaction`, `auth`, `fraud`, `report`, `recurring`).
- **Vì sao làm vậy:** Thiết lập công cụ định dạng và phân tích tĩnh ngay từ commit đầu tiên giúp toàn bộ code trong dự án luôn nhất quán, loại bỏ tranh cãi về style code và đảm bảo chất lượng kỹ thuật cao nhất.
- **Phương án khác đã cân nhắc:** Dùng bộ format mặc định của IDE; lý do không chọn: không ràng buộc được trên CI và giữa các môi trường phát triển khác nhau.
- **Bẫy cần nhớ:** Chạy `./mvnw spotless:apply` trước khi commit để tránh fail build Checkstyle/Spotless trên CI.
- **Cách chạy/test:**
  - `./mvnw spotless:check checkstyle:check`
  - `./mvnw test`

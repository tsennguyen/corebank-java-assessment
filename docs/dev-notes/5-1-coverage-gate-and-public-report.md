# Story 5.1 — Cổng Chất lượng JaCoCo Coverage ≥ 80% & Xuất bản Báo cáo Công khai (GitHub Pages)

## 1. Tóm tắt
- **Story:** `5.1 — Coverage Gate & Public Report`
- **Mã yêu cầu:** `G-04` (Điểm cộng kiểm thử tự động & độ bao phủ), `NC-04` (Kiểm thử toàn diện với Testcontainers), `NFR-6`
- **Mục tiêu:** Đảm bảo cổng chất lượng kiểm thử tự động bắt buộc đạt tối thiểu **80% Line Coverage** trên mọi nhánh phát triển và xuất bản báo cáo JaCoCo HTML công khai lên **GitHub Pages** để hội đồng đánh giá trực tiếp.

## 2. Đã làm gì
- **`.github/workflows/coverage-pages.yml`**:
  - Thiết lập workflow tự động chạy trên nhánh `dev`, `main`, `prod` và cho phép kích hoạt thủ công (`workflow_dispatch`).
  - Thực thi `./mvnw -B verify -Pci` với Java 21 (Temurin) và PostgreSQL 17 Testcontainers, kích hoạt `jacoco:check` kiểm tra ngưỡng `LINE COVEREDRATIO >= 0.80` (đã loại trừ các lớp thuần cấu hình/DTO/main bootstrap).
  - Đóng gói thư mục `backend/target/site/jacoco` qua `actions/upload-pages-artifact@v3` và triển khai lên GitHub Pages qua `actions/deploy-pages@v4`.
- **`DeploymentConfigTest.java`** (`common/config`):
  - Bổ sung bài kiểm thử đơn vị xác minh cấu hình workflow `coverage-pages.yml` (quyền `pages: write`, `id-token: write`, đường dẫn cache Maven `backend/pom.xml`, lệnh `./mvnw -B verify -Pci` và đường dẫn xuất bản `backend/target/site/jacoco`).

## 3. Vì sao làm vậy
- **Minh bạch hóa chất lượng kiểm thử (`G-04`, `NC-04`):** Thay vì chỉ ghi con số phần trăm trong tài liệu, việc công bố trực tiếp báo cáo HTML của JaCoCo lên GitHub Pages giúp người chấm có thể mở xem chi tiết độ bao phủ của từng package (`account`, `transaction`, `fraud`, `recurring`, `report`, `auth`) và từng dòng lệnh nghiệp vụ.
- **Tránh "đánh bóng" chỉ số (`SM-C2`):** Cấu hình loại trừ các lớp `**/*Request.class`, `**/*Response.class`, `**/*Properties.class`, `**/*Config.class` trong `pom.xml` buộc chỉ số $\ge 80\%$ phải đạt được từ các bài kiểm thử hành vi thực sự trên Domain, Service, Repository và Controller.

## 4. Phương án khác đã cân nhắc
- **Dùng dịch vụ bên thứ ba (Codecov / Coveralls):** Cần cấp thêm token ngoài và phụ thuộc tài khoản bên thứ ba. Dùng trực tiếp **GitHub Pages (`actions/deploy-pages@v4`)** tích hợp sẵn trong GitHub Actions với OIDC (`id-token: write`), không cần quản lý secret ngoài.

## 5. Bẫy cần nhớ
- **Đường dẫn `cache-dependency-path` trong Monorepo/Subdirectory:** Vì `pom.xml` nằm ở `backend/pom.xml`, step `actions/setup-java@v4` khi bật `cache: 'maven'` bắt buộc phải khai báo `cache-dependency-path: 'backend/pom.xml'`, nếu không GitHub Actions sẽ báo lỗi không tìm thấy `pom.xml` ở thư mục gốc.

## 6. Cách chạy & kiểm thử
```bash
# Chạy kiểm tra JaCoCo gate (>= 80%) và sinh báo cáo HTML tại backend/target/site/jacoco/index.html
cd backend && ./mvnw -B verify -Pci

# Chạy unit test kiểm tra cấu hình CI/CD workflow
cd backend && ./mvnw -B -Dtest=DeploymentConfigTest test
```

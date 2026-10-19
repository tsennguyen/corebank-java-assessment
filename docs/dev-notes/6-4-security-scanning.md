# Story 6.4 — Quét Bảo mật Tự động với GitHub CodeQL (SAST), Dependency Review (SCA) & Dependabot

## 1. Tóm tắt
- **Story:** `6.4 — CodeQL, Dependabot & Dependency Scanning`
- **Mã yêu cầu:** `NC-13` (Mở rộng quy mô & gia cố hệ thống), `NC-05` (CI/CD Pipeline chuẩn doanh nghiệp)
- **Mục tiêu:** Tích hợp kiểm tra bảo mật tĩnh (**SAST — GitHub CodeQL**) và kiểm tra lỗ hổng thư viện phụ thuộc (**SCA — Dependency Review & Dependabot**) tự động trên mọi Pull Request và định kỳ hằng tuần.

## 2. Đã làm gì
- **`.github/workflows/security.yml`**:
  - **Job `codeql-sast`:** Khởi tạo bộ phân tích tĩnh `github/codeql-action/init@v3` cho ngôn ngữ `java-kotlin` (`build-mode: manual`), biên dịch mã nguồn backend (`./mvnw -B test-compile -DskipTests`) trên JDK 21 (Temurin) và thực thi `github/codeql-action/analyze@v3` để phát hiện sớm các mẫu lỗ hổng OWASP Top 10 (SQL Injection, Path Traversal, SSRF, Hardcoded Credentials...).
  - **Job `dependency-review`:** Chạy `actions/dependency-review-action@v4` trên mọi Pull Request vào `dev`, `main`, `prod` và chặn merge (`fail-on-severity: high`) nếu phát hiện thư viện mới được thêm vào có lỗ hổng CVE mức `High` hoặc `Critical`.
- **`.github/dependabot.yml`**:
  - Cấu hình quét và mở Pull Request cập nhật bản vá tự động hằng tuần (03:00 sáng Thứ Hai theo giờ `Asia/Ho_Chi_Minh`) cho cả hệ sinh thái `maven` (`/backend`) và `github-actions` (`/`), nhắm vào nhánh `dev` với nhãn `dependencies`.
- **`DeploymentConfigTest.java`** (`common/config`):
  - Kiểm thử tự động cấu hình `.github/workflows/security.yml` và `.github/dependabot.yml`.

## 3. Vì sao làm vậy
- **Defense-in-Depth trong SDLC (`NC-05`, `NC-13`):** Trong hệ thống tài chính ngân hàng, việc kiểm tra code thủ công không thể bao quát hết các lỗ hổng CVE phát sinh từ thư viện bên thứ ba (như Apache POI, OpenPDF, Spring Security, PostgreSQL JDBC Driver). Kết hợp CodeQL (quét mã nguồn) và Dependency Review + Dependabot (quét cây phụ thuộc) tạo thành hàng rào bảo mật tự động ngay từ bước mở PR.
- **Chế độ biên dịch tường minh (`build-mode: manual`):** Chỉ chạy `test-compile -DskipTests` giúp job CodeQL hoàn tất nhanh chóng (~1–2 phút) mà không phải chạy lại toàn bộ Testcontainers (vốn đã được thực thi bởi `ci-backend.yml`).

## 4. Phương án khác đã cân nhắc
- **OWASP Dependency-Check Maven Plugin chạy trong mỗi lần `./mvnw verify`:** Làm chậm mọi lần build local và CI vì phải tải cơ sở dữ liệu NVD CVE hàng trăm MB. Dùng **GitHub Dependency Review Action + Dependabot** tận dụng trực tiếp GitHub Advisory Database tốc độ cao mà không làm chậm vòng lặp build.

## 5. Bẫy cần nhớ
- **Quyền tối thiểu cho GITHUB_TOKEN (`permissions`):** Job CodeQL bắt buộc cần `security-events: write` để đẩy kết quả SARIF lên tab Security của GitHub, và `dependency-review` cần `pull-requests: read` cùng `contents: read`.

## 6. Cách chạy & kiểm thử
```bash
# Kiểm tra cấu hình workflow bảo mật và Dependabot
cd backend && ./mvnw -B -Dtest=DeploymentConfigTest test
```

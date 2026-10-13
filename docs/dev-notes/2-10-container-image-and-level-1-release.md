# Story 2.10: Đóng gói Container Image, Docker Compose 1 lệnh và Cấu hình CD/Staging (NC-06, NC-10)

- **Mã yêu cầu:** NC-06 [ĐỀ+], NC-10 [ĐỀ+], FR-24 [FR], NFR-10 [NFR], E-02
- **PR:** [#21](https://github.com/tsennguyen/corebank-java-assessment/pull/21)
- **Mục tiêu:** Đóng gói ứng dụng Core Banking hoàn chỉnh thành Docker image tối ưu (multi-stage, layered JAR, non-root user), cung cấp tổ hợp `docker-compose.yml` khởi chạy toàn bộ stack bằng 1 lệnh duy nhất, cấu hình IaC Blueprint (`render.yaml`) kèm CD workflow (`.github/workflows/cd.yml`) và phát hành cột mốc Level 1 (`v0.1.0`).

---

## 1. Bài toán & Yêu cầu kỹ thuật

Sau khi hoàn thiện toàn bộ 10 nhóm yêu cầu chức năng của **Level 1 (`L1-01`..`L1-10`)** và các năng lực chịu tải đồng thời (`NC-01`, `NC-02`, `NC-04`), hệ thống cần giải quyết bài toán đóng gói và triển khai (DevOps & Containerization):
1. **Đóng gói Container an toàn & nhẹ (`NC-06`, `NFR-10`):** Image runtime không được chứa bộ công cụ biên dịch (JDK/Maven), không chạy dưới quyền `root`, phải tận dụng cơ chế phân lớp (Layered JAR) của Spring Boot 3.5 để tối ưu hóa Docker build cache, và cài sẵn thư viện font hệ thống phục vụ việc xuất báo cáo PDF/Excel (JasperReports & Apache POI ở Level 3).
2. **Trải nghiệm 1 lệnh duy nhất cho người chấm (`FR-24`, `E-02`):** Người đánh giá chỉ cần chạy `docker compose up --build` tại thư mục gốc là có ngay PostgreSQL 17 + Backend Spring Boot đã chạy xong Flyway migrations (`V1`..`V6`) và nạp sẵn dữ liệu mẫu (`R__demo_seed.sql`).
3. **Sẵn sàng triển khai Cloud Staging & CD (`NC-10`, `NC-05`):** Cấu hình IaC `render.yaml` và `application-prod.yml` tối ưu cho môi trường đám mây giới hạn bộ nhớ (512MB RAM) kết hợp GitHub Actions kiểm thử tự động toàn bộ Docker Compose stack.

---

## 2. Quyết định Thiết kế & Kiến trúc

### 2.1. Multi-stage `backend/Dockerfile` với Spring Boot 3.5 Layered JAR
- **Stage 1 (`build` — `eclipse-temurin:21-jdk-jammy`):**
  - Tách bước copy `pom.xml` + `.mvn` và chạy `./mvnw dependency:go-offline -B` thành một layer riêng trước khi copy `src/`, giúp tái sử dụng Maven local cache khi chỉ sửa code Java.
  - Đóng gói fat JAR và dùng công cụ `jarmode=tools` mới của Spring Boot 3.5:
    ```bash
    java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted
    ```
- **Stage 2 (`runtime` — `eclipse-temurin:21-jre-jammy`):**
  - Chỉ dùng JRE 21 kết hợp cài đặt `fontconfig`, `fonts-dejavu-core` (ngăn lỗi `NullPointerException: Fontconfig head is null` khi xuất PDF/Excel trên môi trường headless Linux) và `curl` (cho Docker healthcheck).
  - Tạo nhóm và tài khoản hệ thống không đặc quyền `appuser:appgroup` (`UID/GID 10001`).
  - Sao chép lần lượt 4 layer từ ít biến động nhất đến hay biến động nhất: `dependencies/` → `spring-boot-loader/` → `snapshot-dependencies/` → `application/`.
  - Thiết lập `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"` và khởi chạy qua `org.springframework.boot.loader.launch.JarLauncher`.

### 2.2. Tổ hợp `docker-compose.yml` phối hợp qua `service_healthy`
- Dịch vụ `postgres` (`postgres:17-alpine`) cấu hình `healthcheck` bằng `pg_isready -U corebank -d corebank`.
- Dịch vụ `app` khai báo `depends_on: postgres: condition: service_healthy`, đảm bảo ứng dụng chỉ kết nối CSDL và chạy Flyway (`V1`..`V6` + `R__demo_seed.sql`) sau khi PostgreSQL đã sẵn sàng nhận kết nối.
- Container `app` cũng được gắn `healthcheck` gọi `curl -fsS http://localhost:8080/actuator/health` để hệ thống điều phối biết chính xác thời điểm ứng dụng sẵn sàng phục vụ lưu lượng.

### 2.3. Tối ưu `application-prod.yml`, `render.yaml` & `.github/workflows/cd.yml`
- **`application-prod.yml`:** Giới hạn `maximum-pool-size: ${HIKARI_MAX_POOL_SIZE:5}` (phù hợp hạn mức kết nối của Neon Serverless PostgreSQL và mức RAM 512MB trên Render), `connection-timeout: 5000`, và truyền `-c lock_timeout=3000` để bảo vệ luồng giao dịch.
- **`render.yaml`:** Định nghĩa IaC Blueprint cho Render (region `singapore`, branch `prod`, `rootDir: backend`, `healthCheckPath: /actuator/health`) với các biến nhạy cảm (`DATABASE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`) đặt `sync: false` để không bao giờ lưu thông tin mật trong Git.
- **`.github/workflows/cd.yml`:** Bổ sung job `container-smoke-test` tự động build `docker compose up -d --build` ngay trên CI runner, đợi container `healthy`, kiểm tra tiến trình chạy dưới quyền `appuser`, và xác nhận các endpoint `/actuator/health`, `/v3/api-docs`, `/swagger-ui/index.html`, `/api/v1/statistics/balance-tiers` trả kết quả đúng từ dữ liệu seed.

---

## 3. Các Phương án Đã Cân nhắc

| Phương án | Ưu điểm | Nhược điểm | Lý do chọn / loại |
|---|---|---|---|
| **1. Copy nguyên khối Fat JAR vào JRE image** | Dockerfile ngắn gọn | Mỗi lần sửa 1 dòng code phải tải lại toàn bộ layer ~70MB chứa thư viện Maven | **Loại** — Không tận dụng được Docker layer caching. |
| **2. Multi-stage + `jarmode=tools extract --layers --launcher` (Đã chọn)** | Tách bạch layer `dependencies/` và `application/`; layer code nghiệp vụ chỉ vài trăm KB | Cần thêm bước giải nén ở stage `build` | **Chọn** — Chuẩn khuyến nghị của Spring Boot 3.5, giảm mạnh thời gian build/push image và chống lỗi thiếu font/OOM. |
| **3. Dùng `djarmode=layertools`** | Cú pháp quen thuộc từ Spring Boot 2.3–3.2 | Đã bị đánh dấu deprecated trong Spring Boot 3.3+ | **Loại** — Dùng `jarmode=tools` hiện đại của Spring Boot 3.5. |

---

## 4. Bẫy Kỹ thuật & Kinh nghiệm Thực tế (Gotchas)

1. **Cú pháp `jarmode=tools` trong Spring Boot 3.5:**
   Khi truyền cả `--layers` và `--launcher`, công cụ `jarmode=tools` giải nén trực tiếp 4 thư mục lớp (`dependencies/`, `spring-boot-loader/`, `snapshot-dependencies/`, `application/`) vào thư mục đích `--destination extracted` (không tạo thêm thư mục con theo tên file JAR như khi không có `--layers`).
2. **Cấu hình bộ nhớ JVM trên Container 512MB RAM:**
   Nếu để mặc định (`G1GC` và không giới hạn `MaxRAMPercentage`), JVM kết hợp Metaspace, CodeCache và Direct Buffers rất dễ vượt ngưỡng 512MB của container dẫn tới việc Linux kernel `OOMKilled` (Exit 137). Việc kết hợp `-XX:MaxRAMPercentage=75.0`, `-XX:+UseSerialGC` và `-XX:+ExitOnOutOfMemoryError` giữ mức tiêu thụ bộ nhớ ổn định và minh bạch.
3. **Thiếu thư viện Font trên Base Image tối giản:**
   Các bản `jre-jammy` hoặc `alpine` không có sẵn font hệ thống; nếu không cài trước `fontconfig` và `fonts-dejavu-core` ngay từ nền tảng Docker, tính năng xuất sao kê PDF/Excel ở Epic 4 sẽ gặp lỗi runtime khi khởi tạo `AWT FontManager`.

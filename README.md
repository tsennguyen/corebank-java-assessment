# Core Banking System — Enterprise REST API Service

[![Backend CI](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/ci-backend.yml/badge.svg?branch=main)](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/ci-backend.yml)
[![Container Smoke Test](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/cd.yml/badge.svg)](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/cd.yml)
[![Security Scanning](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/security.yml/badge.svg)](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/security.yml)
![Java 21](https://img.shields.io/badge/Java-21_LTS_(Virtual_Threads)-orange)
![Spring Boot 3.5.16](https://img.shields.io/badge/Spring_Boot-3.5.16-brightgreen)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-blue)
![Coverage Gate](https://img.shields.io/badge/Coverage_gate-≥80%25_(CI)-green)

Hệ thống ngân hàng lõi (**Core Banking REST API**) xây dựng theo kiến trúc **Modular Monolith** trên nền tảng **Java 21 Virtual Threads**, **Spring Boot 3.5.16**, **PostgreSQL 17** và **Redis 7**. Hệ thống đảm bảo tính toàn vẹn tiền tệ tuyệt đối (`ACID`), chống Deadlock bằng khóa bi quan thứ tự ID tăng dần, chống trừ tiền hai lần bằng `Idempotency-Key`, phân quyền JWT + xoay vòng Refresh Token, tự động hóa giao dịch định kỳ, phát hiện giao dịch bất thường thời gian thực và kết xuất báo cáo tài chính kèm biểu đồ xu hướng.

## Bản demo trực tuyến
- Địa chỉ: https://root1.tailc1340f.ts.net:8443 (Swagger UI, API)
- Bản demo được bảo vệ bằng mã truy cập; mã và tài khoản thử nghiệm được gửi kèm email nộp bài.
- Dữ liệu là dữ liệu giả lập, tự khôi phục về trạng thái ban đầu lúc 03:30 (GMT+7) mỗi ngày.
- Kiểm tra trạng thái công khai: https://root1.tailc1340f.ts.net:8443/actuator/health

---

## 1. Tính năng cốt lõi (Core Capabilities)

### Level 1 — Sổ cái Ngân hàng Lõi & An toàn Đồng thời
- **Quản lý Khách hàng & Phân loại Hạn mức (`customer/`):** Hồ sơ định danh KYC, phân loại `INDIVIDUAL` / `BUSINESS` với trần hạn mức hiệu lực $\min(\text{accountLimit}, \text{customerTypeMaxLimit})$, khóa lạc quan `@Version`, xóa mềm (`deleted_at`) có kiểm tra tài khoản hoạt động, che CCCD trong log (`MaskingUtils`).
- **Quản lý Tài khoản & Vòng đời Trạng thái (`account/`):** Sinh số tài khoản 12 chữ số kiểm tra thuật toán Luhn, máy trạng thái hữu hạn `ACTIVE ⇄ LOCKED → CLOSED` kèm bảng lịch sử kiểm toán bất biến (`core.account_status_history`).
- **Xử lý Giao dịch & Chống Deadlock (`transaction/`):** Hỗ trợ `DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, `PAYMENT` và Đảo giao dịch bút toán đối ứng (`REVERSAL`); tính phí đa bậc $O(\log n)$ bằng `TreeMap.floorEntry`; khóa bi quan `SELECT ... FOR UPDATE` theo thứ tự ID tài khoản tăng dần kết hợp `lock_timeout = 3s` (đã kiểm chứng thực nghiệm bằng `TransferConcurrencyIT`: **100 lệnh chuyển khoản đồng thời trên pool 25 luồng worker giữa 10 tài khoản, cộng kịch bản 10 luồng chuyển đối xứng `A ↔ B`**, 0 Deadlock, bảo toàn 100% tổng tài sản + phí).
- **Bảo vệ Chống Trừ tiền Hai lần (`Idempotency-Key`):** Khóa tư vấn giao dịch 64-bit `pg_advisory_xact_lock(hashtext(:scope), hashtext(:lockKey))` cô lập theo người dùng, đối chiếu băm `SHA-256` canonical payload và phát lại phản hồi gốc kèm header `Idempotent-Replayed: true`.

### Level 2 — Bảo mật, Tìm kiếm Nâng cao, Giao dịch Định kỳ & Cache Hai tầng
- **Xác thực JWT & Phân quyền Chống IDOR (`auth/`):** OAuth2 Resource Server (JWT HS256), phân quyền `ROLE_ADMIN`, `ROLE_TELLER`, `ROLE_CUSTOMER`, kiểm tra quyền sở hữu tài nguyên $O(1)$ (`OwnershipGuard`), xoay vòng Refresh Token phát hiện tái sử dụng (`401 TOKEN_REUSED` thu hồi toàn bộ `familyId`) và giới hạn tốc độ đăng nhập (`429 TOO_MANY_REQUESTS`).
- **Tìm kiếm Đa tiêu chí & Thống kê SQL (`transaction/`, `statistics/`):** Lọc giao dịch theo khoảng ngày (`Asia/Ho_Chi_Minh`), khoảng tiền, loại, trạng thái trên Composite/Partial Covering Index; thống kê nhóm số dư (`LOW`, `MEDIUM`, `HIGH`) và phân bố khách hàng theo địa điểm bằng 1 câu truy vấn CTE.
- **Giao dịch Định kỳ Đa tiến trình (`recurring/`):** Lập lịch `DAILY`, `WEEKLY`, `MONTHLY` (kèm neo ngày cuối tháng chống trôi ngày 31), quét bằng `SELECT ... FOR UPDATE SKIP LOCKED` và cô lập lỗi từng lệnh bằng `@Transactional(propagation = Propagation.REQUIRES_NEW)`.
- **Cache Hai tầng Tự phục hồi (`common/cache`):** Kết hợp `RedisCacheManager` và fallback tự động về `Caffeine` / PostgreSQL qua `ResilientCacheErrorHandler`; xóa cache an toàn ở pha `AFTER_COMMIT`.

### Level 3 — Báo cáo Chu kỳ, Phát hiện Gian lận Thời gian thực, Xuất Excel & Observability
- **Báo cáo Giao dịch Theo Kỳ & Chuỗi Xu hướng (`report/`):** Tổng hợp theo `WEEK`, `QUARTER`, `YEAR` bằng `date_trunc` và tính chuỗi số dư cuối kỳ bằng `generate_series` + hàm cửa sổ tích lũy ngược (`SUM(...) OVER (ROWS BETWEEN 1 FOLLOWING AND UNBOUNDED FOLLOWING)`).
- **Phát hiện Giao dịch Bất thường & Quét Lịch sử (`fraud/`):** Đánh giá bất đồng bộ trên Java 21 Virtual Threads (`@Async` + `AFTER_COMMIT`) với quy tắc `AMOUNT_THRESHOLD` ($\ge 50\text{M VND}$) và `VELOCITY` ($> 5\text{ GD} / 60\text{s}$ qua Redis ZSET / `ArrayDeque` + `ReentrantLock`); hỗ trợ quét lịch sử bằng PostgreSQL Window Function `RANGE ... PRECEDING` và quản lý vòng đời cảnh báo (`NEW -> INVESTIGATING -> RESOLVED | FALSE_POSITIVE`).
- **Vẽ Biểu đồ Headless & Xuất Báo cáo Luồng (`report/export/`):** Kết xuất 3 biểu đồ PNG `800x400` bằng `JFreeChart` headless và stream trực tiếp file Excel `.xlsx` 3 sheets (`Tong hop`, `Chi tiet`, `Bieu do`) qua `SXSSFWorkbook(100)` + con trỏ JDBC (`fetchSize = 1000`).
- **Observability & Virtual Threads (`Actuator` / `Micrometer`):** Phơi bày an toàn `/actuator/health` (ẩn chi tiết hạ tầng với người lạ) và `/actuator/metrics` (`ADMIN`-only) với các bộ đo `banking.transactions`, `banking.alerts.raised`, `banking.recurring.executed`.

---

## 2. Công nghệ & Kiến trúc Modular Monolith

- **Ngôn ngữ & Nền tảng:** Java 21 LTS (Virtual Threads), Spring Boot 3.5.16 (Web, Data JPA, JDBC Client, Security, OAuth2 Resource Server, Validation, Cache, Actuator)
- **Cơ sở dữ liệu & Cache:** PostgreSQL 17, Flyway Migration (`V1`–`V13` + repeatable seed `R__demo_seed.sql`), Redis 7 + Caffeine
- **Báo cáo & Biểu đồ:** JFreeChart 1.5.5 (Headless PNG), Apache POI 5.5.1 (`SXSSFWorkbook` streaming)
- **Kiểm thử & Chất lượng:** JUnit 5, Mockito, Testcontainers (PostgreSQL 17 & Redis 7), JaCoCo (Coverage gate $\ge 80\%$), Spotless, Checkstyle, GitHub Actions (CI, CD Smoke Test, CodeQL SAST, Dependency Review)

```text
backend/src/main/java/com/example/corebanking/
├── auth/         # JWT HS256, Refresh Token Rotation, Rate Limiter, OwnershipGuard, SecurityConfig
├── customer/     # Hồ sơ KYC, phân loại INDIVIDUAL/BUSINESS, CustomerTypeLimitProvider
├── account/      # Tài khoản 12 số Luhn, hạn mức, FSM trạng thái & lịch sử kiểm toán
├── transaction/  # Nạp/rút/chuyển/thanh toán/đảo giao dịch, tính phí bậc thang, tìm kiếm đa tiêu chí
├── statistics/   # Thống kê bậc số dư (LOW/MEDIUM/HIGH) và phân bố khách hàng theo địa điểm
├── recurring/    # Thiết lập & bộ lập lịch giao dịch định kỳ (FOR UPDATE SKIP LOCKED)
├── fraud/        # Phát hiện bất thường thời gian thực (@Async Virtual Threads) & quét lịch sử Window Function
├── report/       # Báo cáo tuần/quý/năm, chuỗi xu hướng, JFreeChart PNG & xuất Excel streaming
└── common/       # ProblemDetail (RFC 9457), Idempotency-Key, Cache + Fallback, MaskingUtils, Config
```

---

## 3. Hướng dẫn Khởi chạy Nhanh (Quick Start)

### Cách 1: Khởi chạy trọn bộ bằng 1 lệnh với Docker Compose (Khuyến nghị)
```bash
# Khởi tạo PostgreSQL 17 + Redis 7 + Backend API + tự động chạy Flyway V1..V13 & nạp dữ liệu mẫu
docker compose up --build
```

### Cách 2: Chạy trực tiếp trên máy Local không cần Docker (PostgreSQL 17 cài sẵn)
Profile `local` kết nối mặc định tới database `core_banking` (user `postgres`, password `postgres`) và **tắt Redis mặc định** (`app.redis.enabled=false`, tự động dùng Caffeine cache và bộ đếm cửa sổ trượt trong bộ nhớ):
```bash
# 1. Tạo database local (nếu chưa có)
createdb -h localhost -p 5432 -U postgres core_banking

# 2. Khởi chạy ứng dụng với Maven Wrapper (tự động chạy Flyway V1..V13 và R__demo_seed.sql)
cd backend
SPRING_PROFILES_ACTIVE=local \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/core_banking \
SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD=postgres \
./mvnw spring-boot:run
```

### Cách 3: Gọi thử API mẫu (kèm xác thực JWT & `Idempotency-Key`)
```bash
# 1. Đăng nhập tài khoản Admin mặc định trên môi trường local để lấy JWT accessToken
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin@123"}' | jq -r '.accessToken')

# 2. Xem thống kê tài khoản & giao dịch theo nhóm số dư (LOW / MEDIUM / HIGH)
curl -s http://localhost:8080/api/v1/statistics/balance-tiers \
  -H "Authorization: Bearer $TOKEN" | jq

# 3. Nạp tiền vào tài khoản (kèm Idempotency-Key chống gửi trùng)
curl -s -X POST http://localhost:8080/api/v1/transactions/deposits \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-deposit-0001" \
  -d '{"accountId":1,"amount":1500000.00,"location":"Hà Nội","description":"Nạp tiền mặt tại quầy"}' | jq

# 4. Tải báo cáo Excel (.xlsx) gồm 3 sheets (Tong hop, Chi tiet, Bieu do PNG)
curl -s -L "http://localhost:8080/api/v1/reports/export?format=XLSX&period=QUARTER&from=2026-01-01&to=2026-12-31" \
  -H "Authorization: Bearer $TOKEN" -o core-banking-report.xlsx
```

- **Swagger UI:** `http://localhost:8080/swagger-ui/index.html`
- **Actuator Health:** `http://localhost:8080/actuator/health`

---

## 4. Tài liệu Kỹ thuật, Sơ đồ ERD & Quyết định Kiến trúc (ADR)
- **Sơ đồ Thực thể Liên kết (ERD — Flyway `V1`–`V13`):** [docs/erd.md](docs/erd.md)
- **Hồ sơ Quyết định Kiến trúc (`ADR-001` → `ADR-015`):** [docs/adr.md](docs/adr.md)
- **Ghi chú Phát triển Chi tiết theo từng Story/PR (`Developer Notes`):** [docs/dev-notes/README.md](docs/dev-notes/README.md)
- **Nhật ký Thay đổi Phiên bản (`Changelog`):** [CHANGELOG.md](CHANGELOG.md)

---

## 5. Hướng mở rộng (Future Enhancements & Scale Roadmap)
1. **Phân trang Con trỏ (`Keyset / Seek Pagination`):** Bổ sung truy vấn theo con trỏ `(created_at DESC, id DESC)` bên cạnh `OFFSET` cho bảng giao dịch quy mô hàng trăm triệu bản ghi để duy trì độ trễ $O(\log N)$ ổn định ở mọi trang sâu.
2. **Khóa Phân tán Mức Job (`ShedLock`) & Chuỗi Băm Kiểm toán (`Tamper-Evident Audit Log`):** Kết hợp `ShedLock` ở mức bộ lập lịch cùng `FOR UPDATE SKIP LOCKED` ở mức bản ghi; bổ sung bảng `core.audit_log` nối chuỗi băm `SHA-256` cho mọi thao tác quản trị nhạy cảm.
3. **Tối ưu Hóa Nguyên tử trên Redis & Chống Spoofing Proxy:** Chuyển thao tác đọc-xóa Refresh Token sang lệnh `GETDEL` / Lua script nguyên tử; gộp (`coalesce`) cảnh báo Velocity theo cửa sổ thời gian; giới hạn trích xuất `X-Forwarded-For` chỉ từ danh sách reverse proxy tin cậy.
4. **Lộ trình Nâng cấp Spring Boot 4 / Jakarta EE 11 & Kiến trúc Sự kiện:** Chuyển từ `@TransactionalEventListener` nội bộ sang mẫu **Transactional Outbox + Apache Kafka** khi tách module `fraud` và `report` thành các dịch vụ độc lập.

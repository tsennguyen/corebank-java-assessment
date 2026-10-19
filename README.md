# Core Banking System — Enterprise REST API Service

[![Backend CI](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/ci-backend.yml/badge.svg?branch=main)](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/ci-backend.yml)
[![Container Smoke Test](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/cd.yml/badge.svg)](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/cd.yml)
[![Security Scanning](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/security.yml/badge.svg)](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/security.yml)
![Java 21](https://img.shields.io/badge/Java-21_LTS_(Virtual_Threads)-orange)
![Spring Boot 3.5.16](https://img.shields.io/badge/Spring_Boot-3.5.16-brightgreen)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-blue)
![Coverage Gate](https://img.shields.io/badge/Coverage-92.73%25_Line_%7C_≥80%25_Gate-green)

Hệ thống ngân hàng lõi (**Core Banking REST API**) xây dựng theo kiến trúc **Modular Monolith** trên nền tảng **Java 21 Virtual Threads**, **Spring Boot 3.5.16**, **PostgreSQL 17** và **Redis 7**. Hệ thống đảm bảo tính toàn vẹn tiền tệ tuyệt đối (`ACID`), chống Deadlock bằng khóa bi quan thứ tự ID tăng dần, chống trừ tiền hai lần bằng `Idempotency-Key`, phân quyền JWT + xoay vòng Refresh Token, tự động hóa giao dịch định kỳ, phát hiện giao dịch bất thường thời gian thực và kết xuất báo cáo tài chính (`.xlsx` & `.pdf`) kèm biểu đồ xu hướng.

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
- **Xác thực JWT & Phân quyền Chống IDOR (`auth/`):** OAuth2 Resource Server (JWT HS256), phân quyền `ROLE_ADMIN` và `ROLE_CUSTOMER`, kiểm tra quyền sở hữu tài nguyên $O(1)$ (`OwnershipGuard`), xoay vòng Refresh Token phát hiện tái sử dụng (`401 TOKEN_REUSED` thu hồi toàn bộ `familyId`) và giới hạn tốc độ đăng nhập (`429 TOO_MANY_REQUESTS`).
- **Tìm kiếm Đa tiêu chí & Thống kê SQL (`transaction/`, `report/`):** Lọc giao dịch theo khoảng ngày (`Asia/Ho_Chi_Minh`), khoảng tiền, loại, trạng thái trên Composite/Partial Covering Index; thống kê nhóm số dư (`LOW`, `MEDIUM`, `HIGH`) và phân bố khách hàng theo địa điểm bằng 1 câu truy vấn CTE.
- **Giao dịch Định kỳ Đa tiến trình (`recurring/`):** Lập lịch `DAILY`, `WEEKLY`, `MONTHLY` (kèm neo ngày cuối tháng chống trôi ngày 31), quét bằng `SELECT ... FOR UPDATE SKIP LOCKED` và cô lập lỗi từng lệnh bằng `@Transactional(propagation = Propagation.REQUIRES_NEW)`.
- **Cache Hai tầng Tự phục hồi (`cache/`):** Kết hợp `RedisCacheManager` và fallback tự động về `Caffeine` / PostgreSQL qua `ResilientCacheErrorHandler`; xóa cache an toàn ở pha `AFTER_COMMIT`.

### Level 3 — Báo cáo Chu kỳ, Phát hiện Gian lận Thời gian thực, Xuất Excel/PDF & Observability
- **Báo cáo Giao dịch Theo Kỳ & Chuỗi Xu hướng (`report/`):** Tổng hợp theo `WEEK`, `QUARTER`, `YEAR` bằng `date_trunc` và tính chuỗi số dư cuối kỳ bằng `generate_series` + hàm cửa sổ tích lũy ngược (`SUM(...) OVER (ROWS BETWEEN 1 FOLLOWING AND UNBOUNDED FOLLOWING)`).
- **Phát hiện Giao dịch Bất thường & Quét Lịch sử (`fraud/`):** Đánh giá bất đồng bộ trên Java 21 Virtual Threads (`@Async` + `AFTER_COMMIT`) với quy tắc `AMOUNT_THRESHOLD` ($\ge 50\text{M VND}$) và `VELOCITY` ($> 5\text{ GD} / 60\text{s}$ qua Redis ZSET / `ArrayDeque` + `ReentrantLock`); hỗ trợ quét lịch sử bằng PostgreSQL Window Function `RANGE ... PRECEDING` và quản lý vòng đời cảnh báo (`NEW -> INVESTIGATING -> RESOLVED | FALSE_POSITIVE`).
- **Vẽ Biểu đồ Headless & Xuất Báo cáo Luồng Excel / PDF (`report/export/`):** Kết xuất 3 biểu đồ PNG `800x400` bằng `JFreeChart` headless; stream trực tiếp file Excel `.xlsx` 3 sheets (`Tong hop`, `Chi tiet`, `Bieu do`) qua `SXSSFWorkbook(100)` + con trỏ JDBC (`fetchSize = 1000`); và kết xuất báo cáo `.pdf` (`format=PDF`) bằng `OpenPDF 2.0.2` nhúng font Unicode tiếng Việt (`DejaVuSans.ttf` / `DejaVuSans-Bold.ttf`), 3 biểu đồ xu hướng PNG cùng bảng chi tiết giới hạn an toàn 1.000 dòng.
- **Observability & Virtual Threads (`Actuator` / `Micrometer`):** Phơi bày an toàn `/actuator/health` (ẩn chi tiết hạ tầng với người lạ) và `/actuator/metrics` (`ADMIN`-only) với các bộ đo `banking.transactions`, `banking.alerts.raised`, `banking.recurring.executed`.

---

## 2. Công nghệ & Kiến trúc Modular Monolith

- **Ngôn ngữ & Nền tảng:** Java 21 LTS (Virtual Threads), Spring Boot 3.5.16 (Web, Data JPA, JDBC Client, Security, OAuth2 Resource Server, Validation, Cache, Actuator)
- **Cơ sở dữ liệu & Cache:** PostgreSQL 17, Flyway Migration (`V1`–`V13` + repeatable seed `R__demo_seed.sql`), Redis 7 + Caffeine
- **Báo cáo & Biểu đồ:** JFreeChart 1.5.5 (Headless PNG), Apache POI 5.5.1 (`SXSSFWorkbook` streaming), OpenPDF 2.0.2 (`DejaVuSans` Unicode PDF)
- **Kiểm thử & Chất lượng:** **510 bài kiểm thử tự động** (JUnit 5, Mockito, Testcontainers PostgreSQL 17 & Redis 7), JaCoCo (**92.73% line coverage** / **74.20% branch coverage**, vượt cổng CI $\ge 80\%$), Spotless, Checkstyle, GitHub Actions (CI, CD Smoke Test, CodeQL SAST, Dependency Review)

```text
backend/src/main/java/com/example/corebanking/
├── auth/         # JWT HS256, Refresh Token Rotation, Rate Limiter, OwnershipGuard, SecurityConfig
├── customer/     # Hồ sơ KYC, phân loại INDIVIDUAL/BUSINESS, CustomerTypeLimitProvider
├── account/      # Tài khoản 12 số Luhn, hạn mức, FSM trạng thái & lịch sử kiểm toán
├── transaction/  # Nạp/rút/chuyển/thanh toán/đảo giao dịch, tính phí bậc thang, tìm kiếm đa tiêu chí
├── recurring/    # Thiết lập & bộ lập lịch giao dịch định kỳ (FOR UPDATE SKIP LOCKED)
├── fraud/        # Phát hiện bất thường thời gian thực (@Async Virtual Threads) & quét lịch sử Window Function
├── report/       # Thống kê, báo cáo tuần/quý/năm, chuỗi xu hướng, JFreeChart PNG & xuất Excel/PDF streaming
├── cache/        # Redis 7 + Caffeine fallback, ResilientCacheErrorHandler, AFTER_COMMIT cache eviction
└── common/       # ProblemDetail (RFC 9457), Idempotency-Key, MaskingUtils, Async/Time/OpenAPI Config
```

---

## 3. Hướng dẫn Khởi chạy Nhanh (Quick Start) & Tài khoản Mẫu

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

### Bảng Tài khoản Mẫu trên Môi trường Local (`AdminBootstrapRunner` & `R__demo_seed.sql`)

| Tên đăng nhập (`username`) | Mật khẩu mặc định (Local) | Vai trò (`role`) | Hồ sơ Khách hàng liên kết | Tài khoản Ngân hàng mẫu | Mục đích thử nghiệm |
|---|---|---|---|---|---|
| `admin` | `Admin@123` | `ROLE_ADMIN` | *(Không gắn `customer_id`)* | Toàn quyền truy cập hệ thống | Quản trị khách hàng, tài khoản, loại khách hàng, cảnh báo AML, xuất báo cáo `.xlsx`/`.pdf`, xem `/actuator/metrics` |
| `cust.an` | `Customer@123` | `ROLE_CUSTOMER` | `CUS8000001` (Nguyễn Văn An — `INDIVIDUAL`) | `100800000018` (`3.500.000 VND`) | Tự phục vụ (`/api/v1/me`), chuyển/rút tiền từ TK sở hữu, kiểm chứng chống IDOR (`403 ACCESS_DENIED` khi truy cập TK người khác) |
| `cust.binh` | `Customer@123` | `ROLE_CUSTOMER` | `CUS8000002` (Trần Thị Bình — `INDIVIDUAL`) | `100800000026` (`7.200.000 VND`) | Kiểm thử chuyển khoản chéo giữa `cust.an` ⇄ `cust.binh`, lập lịch định kỳ, tra cứu lịch sử giao dịch cá nhân |

*(Ngoài ra `R__demo_seed.sql` tạo sẵn 20 khách hàng cá nhân & doanh nghiệp `CUS8000001`..`CUS8000020`, 12 tài khoản thuộc cả 3 bậc số dư `LOW`/`MEDIUM`/`HIGH` và 16 giao dịch lịch sử trải đều 12 tháng).*

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

# 4a. Tải báo cáo Excel (.xlsx) gồm 3 sheets (Tong hop, Chi tiet, Bieu do PNG)
curl -s -L "http://localhost:8080/api/v1/reports/export?format=XLSX&period=QUARTER&from=2026-01-01&to=2026-12-31" \
  -H "Authorization: Bearer $TOKEN" -o core-banking-report.xlsx

# 4b. Tải báo cáo PDF (.pdf) nhúng font tiếng Việt DejaVuSans và 3 biểu đồ xu hướng PNG
curl -s -L "http://localhost:8080/api/v1/reports/export?format=PDF&period=QUARTER&from=2026-01-01&to=2026-12-31" \
  -H "Authorization: Bearer $TOKEN" -o core-banking-report.pdf
```

- **Swagger UI:** `http://localhost:8080/swagger-ui/index.html`
- **Actuator Health:** `http://localhost:8080/actuator/health`

---

## 4. Danh mục REST API Endpoints

| Nhóm Nghiệp vụ | Phương thức & Đường dẫn | Phân quyền | Mô tả Chức năng |
|---|---|---|---|
| **Auth & Self-Service** | `POST /api/v1/auth/login` | Public (Rate-limited) | Đăng nhập nhận cặp `accessToken` (JWT HS256) và `refreshToken` |
| | `POST /api/v1/auth/refresh` | Public | Xoay vòng Refresh Token một lần; phát hiện tái sử dụng (`401 TOKEN_REUSED`) |
| | `POST /api/v1/auth/logout` | Authenticated | Thu hồi Refresh Token hiện tại |
| | `GET /api/v1/me` | `CUSTOMER` / `ADMIN` | Xem hồ sơ định danh gắn với người dùng đang đăng nhập |
| | `GET /api/v1/me/accounts` | `CUSTOMER` / `ADMIN` | Liệt kê danh sách tài khoản thuộc sở hữu của người dùng đang đăng nhập |
| **Customers & Types** | `POST /api/v1/customers` | `ADMIN` | Tạo mới khách hàng (`INDIVIDUAL` / `BUSINESS`), sinh mã `CUS...` |
| | `GET /api/v1/customers/{id}` | `ADMIN` / Owner | Xem chi tiết khách hàng theo ID |
| | `GET /api/v1/customers` | `ADMIN` | Tìm kiếm & phân trang khách hàng (`keyword`, `location`) |
| | `PUT /api/v1/customers/{id}` | `ADMIN` | Cập nhật thông tin khách hàng (kiểm tra khóa lạc quan `version`) |
| | `DELETE /api/v1/customers/{id}` | `ADMIN` | Xóa mềm khách hàng (`deleted_at`), chặn nếu còn tài khoản `ACTIVE`/`LOCKED` |
| | `GET /api/v1/customer-types` | Authenticated | Danh sách phân loại khách hàng và trần hạn mức (`INDIVIDUAL`, `BUSINESS`) |
| | `GET /api/v1/customer-types/{code}` | Authenticated | Xem chi tiết cấu hình trần hạn mức theo mã phân loại |
| | `PUT /api/v1/customer-types/{code}` | `ADMIN` | Cập nhật trần hạn mức tối đa (`maxTransactionLimit`, `maxDailyLimit`) |
| **Accounts** | `POST /api/v1/accounts` | `ADMIN` | Mở tài khoản mới (sinh số tài khoản 12 chữ số kiểm tra Luhn) |
| | `GET /api/v1/accounts/{id}` | `ADMIN` / Owner | Xem chi tiết tài khoản & số dư hiện tại |
| | `GET /api/v1/accounts` | `ADMIN` | Tìm kiếm & lọc tài khoản (`customerId`, `status`, `tier`, `minBalance`, `maxBalance`) |
| | `PATCH /api/v1/accounts/{id}` | `ADMIN` | Cập nhật hạn mức giao dịch (`transactionLimit`, `dailyLimit`), chặn sửa trường bất biến |
| | `POST /api/v1/accounts/{id}/status` | `ADMIN` | Chuyển trạng thái `ACTIVE ⇄ LOCKED → CLOSED` và ghi nhật ký kiểm toán |
| | `GET /api/v1/accounts/{id}/status-history` | `ADMIN` / Owner | Xem lịch sử chuyển đổi trạng thái tài khoản (`core.account_status_history`) |
| | `DELETE /api/v1/accounts/{id}` | `ADMIN` | Đóng tài khoản (yêu cầu `balance = 0` và không còn lệnh định kỳ `ACTIVE`) |
| | `GET /api/v1/accounts/{id}/transactions` | `ADMIN` / Owner | Xem lịch sử giao dịch của một tài khoản (lọc đa tiêu chí) |
| **Transactions & Fees** | `GET /api/v1/fees/preview` | Authenticated | Xem trước phí giao dịch bậc thang (`type`, `amount`) |
| | `POST /api/v1/transactions/deposits` | `ADMIN` | Nạp tiền vào tài khoản (`Idempotency-Key` bắt buộc) |
| | `POST /api/v1/transactions/withdrawals` | `ADMIN` / Owner | Rút tiền từ tài khoản (`Idempotency-Key`, kiểm tra số dư + phí + hạn mức) |
| | `POST /api/v1/transactions/transfers` | `ADMIN` / Owner | Chuyển khoản nội bộ chống Deadlock (`Idempotency-Key`, khóa thứ tự ID tăng dần) |
| | `POST /api/v1/transactions/payments` | `ADMIN` / Owner | Thanh toán hóa đơn/dịch vụ (`Idempotency-Key`) |
| | `GET /api/v1/transactions/{id}` | `ADMIN` / Owner | Xem chi tiết giao dịch theo ID |
| | `PATCH /api/v1/transactions/{id}` | `ADMIN` | Cập nhật `description`/`location`, từ chối sửa `amount`/`type`/`account` (`400`) |
| | `DELETE /api/v1/transactions/{id}` | `ADMIN` | Đảo giao dịch bút toán đối ứng (`REVERSAL`), bảo toàn lịch sử kiểm toán |
| | `GET /api/v1/transactions` | `ADMIN` | Tìm kiếm giao dịch nâng cao (`accountId`, `type`, `status`, `minAmount`, `maxAmount`, `fromDate`, `toDate`, `keyword`) |
| **Recurring** | `POST /api/v1/recurring-transactions` | `ADMIN` / Owner | Thiết lập lịch giao dịch định kỳ (`DAILY`, `WEEKLY`, `MONTHLY`) |
| | `GET /api/v1/recurring-transactions/{id}` | `ADMIN` / Owner | Xem chi tiết lịch giao dịch định kỳ |
| | `GET /api/v1/recurring-transactions` | `ADMIN` / Owner | Danh sách lịch giao dịch định kỳ (tự động lọc theo tài khoản sở hữu với `CUSTOMER`) |
| | `POST /api/v1/recurring-transactions/{id}/pause` | `ADMIN` / Owner | Tạm dừng lịch định kỳ (`ACTIVE -> PAUSED`) |
| | `POST /api/v1/recurring-transactions/{id}/resume` | `ADMIN` / Owner | Kích hoạt lại lịch định kỳ (`PAUSED -> ACTIVE`, tính lại `nextRunAt` tương lai) |
| | `DELETE /api/v1/recurring-transactions/{id}` | `ADMIN` / Owner | Hủy vĩnh viễn lịch định kỳ (`-> CANCELLED`) |
| **Fraud & Alerts** | `GET /api/v1/alerts` | `ADMIN` | Danh sách cảnh báo bất thường (`status`, `ruleCode`, `severity`, `accountId`, `from`, `to`) |
| | `GET /api/v1/alerts/{id}` | `ADMIN` | Xem chi tiết cảnh báo bất thường |
| | `POST /api/v1/alerts/{id}/status` | `ADMIN` | Chuyển trạng thái xử lý cảnh báo (`NEW -> INVESTIGATING -> RESOLVED \| FALSE_POSITIVE`) |
| | `POST /api/v1/fraud/scans` (`/api/v1/alerts/scan`) | `ADMIN` | Quét lịch sử giao dịch theo lô bằng SQL Window Function (`AMOUNT_THRESHOLD` & `VELOCITY`) |
| **Statistics & Reports** | `GET /api/v1/statistics/balance-tiers` | `ADMIN` | Thống kê số lượng tài khoản, tổng/trung bình số dư và khối lượng GD theo bậc `LOW`/`MEDIUM`/`HIGH` |
| | `GET /api/v1/statistics/customers-by-location` | `ADMIN` | Thống kê phân bố khách hàng và tổng số dư theo địa điểm (`location`) |
| | `GET /api/v1/reports/transactions` | `ADMIN` | Báo cáo tổng hợp giao dịch theo chu kỳ `WEEK`, `QUARTER`, `YEAR` (`Asia/Ho_Chi_Minh`) |
| | `GET /api/v1/reports/trends` | `ADMIN` | Chuỗi thời gian xu hướng số lượng GD, khối lượng tiền và số dư cuối kỳ (`DAY`, `WEEK`, `MONTH`) |
| | `GET /api/v1/reports/export` | `ADMIN` | Xuất báo cáo tài chính trực tuyến định dạng Excel (`format=XLSX`) hoặc PDF (`format=PDF`) kèm 3 biểu đồ PNG |

---

## 5. Ma trận Truy vết Yêu cầu Đề bài & Kết quả Kiểm chứng

### 5.1. Yêu cầu Chung & Level 1 — Quản lý Cơ bản (10/10 Hoàn thành)

| Mã | Yêu cầu Đề bài | Giải pháp Kỹ thuật & Module Thực thi | Bài Kiểm thử Xác minh |
|---|---|---|---|
| `G-01`..`G-06` | RESTful API, PostgreSQL, Kiến trúc rõ ràng, Unit/IT Tests, Tối ưu DB, Hướng mở rộng | Modular Monolith 9 modules, Flyway `V1`–`V13`, RFC 9457 `ProblemDetail`, OpenAPI 3, 510 tests (92.73% line coverage) | Toàn bộ `*Test.java` & `*IT.java` |
| `L1-01` | Thêm mới khách hàng | `CustomerService.createCustomer`, sinh mã `CUS...` từ sequence, kiểm tra trùng CCCD (`409`) | `CustomerServiceTest`, `CustomerControllerIT` |
| `L1-02` | Sửa / xóa khách hàng | Khóa lạc quan `@Version`, xóa mềm `deleted_at`, chặn xóa khi còn tài khoản chưa đóng (`409 CUSTOMER_HAS_ACTIVE_ACCOUNTS`) | `CustomerServiceTest`, `CustomerControllerIT` |
| `L1-03` | Tìm kiếm khách hàng | Tìm theo ID, mã khách hàng, từ khóa tên/CCCD/email/SĐT và lọc theo `location` | `CustomerControllerIT` |
| `L1-04` | Tạo tài khoản ngân hàng | Sinh số tài khoản 12 chữ số kiểm tra thuật toán Luhn, kiểm tra `dailyLimit >= transactionLimit > 0` | `LuhnAccountNumberGeneratorTest`, `AccountControllerIT` |
| `L1-05` | Sửa / đóng tài khoản | `PATCH` hạn mức (chặn sửa số dư/chủ TK qua `@JsonAnySetter`), đóng TK khi `balance = 0` | `AccountServiceTest`, `AccountControllerIT` |
| `L1-06` | Tìm kiếm tài khoản | Lọc theo `customerId`, `status`, `tier` (`LOW`/`MEDIUM`/`HIGH`), khoảng số dư | `AccountControllerIT` |
| `L1-07` | Giao dịch Nạp / Rút / Chuyển / Thanh toán | Khóa bi quan `SELECT ... FOR UPDATE` theo thứ tự ID tăng dần, `lock_timeout = 3s`, tính phí bậc thang `TreeMap`, kiểm tra hạn mức lần & ngày | `TransactionServiceTest`, `DepositTransactionIT`, `WithdrawalTransferPaymentIT`, `TransferConcurrencyIT` |
| `L1-08` | Sửa thông tin / Hủy (Đảo) giao dịch | `PATCH` mô tả/địa điểm; `DELETE` tạo bút toán đảo đối ứng `REVERSAL` (`reversal_of_id UNIQUE`), hoàn tiền + phí nguyên tử | `TransactionPatchReversalIT` |
| `L1-09` | Thống kê tài khoản & giao dịch theo số dư (`LOW`, `MEDIUM`, `HIGH`) | Truy vấn CTE một lần (`StatisticsRepository.getBalanceTierStatistics`) phân nhóm `< 10M`, `10M–<100M`, `>= 100M VND` | `StatisticsControllerIT` |
| `L1-10` | Thống kê khách hàng theo địa điểm | Gom nhóm SQL trên partial expression index `idx_customer_location` | `StatisticsControllerIT` |

### 5.2. Level 2 — Bảo mật, Tìm kiếm Nâng cao, Định kỳ & Cache (8/8 Hoàn thành)

| Mã | Yêu cầu Đề bài | Giải pháp Kỹ thuật & Module Thực thi | Bài Kiểm thử Xác minh |
|---|---|---|---|
| `L2-01` | Đăng nhập & Xác thực JWT | `AuthController` (`/login`, `/refresh`, `/logout`), JWT HS256, xoay vòng Refresh Token phát hiện tái sử dụng (`401 TOKEN_REUSED`), Rate Limiter (`429`) | `JwtAuthAndIdempotencyIsolationIT`, `RefreshTokenAndRateLimitIT` |
| `L2-02` | Phân quyền `ADMIN` & `CUSTOMER` (Chống IDOR) | `@PreAuthorize` kết hợp `OwnershipGuard` kiểm tra quyền sở hữu tài khoản/giao dịch $O(1)$ (`403 ACCESS_DENIED`) | `CustomerAuthorizationIT` |
| `L2-03` | Tìm kiếm giao dịch theo khoảng thời gian | Quy đổi `fromDate`/`toDate` theo múi giờ `Asia/Ho_Chi_Minh` sang nửa khoảng mở `[fromInstant, toExclusiveInstant)` | `TransactionAdvancedSearchIT` |
| `L2-04` | Lọc giao dịch kết hợp nhiều tiêu chí | Kết hợp `accountId`, `type`, `status`, `minAmount`, `maxAmount`, `fromDate`, `toDate`, `keyword` trên index `V4`/`V9` | `TransactionAdvancedSearchIT` |
| `L2-05` | Quản lý vòng đời trạng thái tài khoản (`ACTIVE`, `LOCKED`, `CLOSED`) | FSM trong `Account.transitionStatus`, ghi bảng lịch sử bất biến `core.account_status_history` (`V10`) | `AccountStatusLifecycleIT` |
| `L2-06` | Thiết lập giao dịch định kỳ (`DAILY`, `WEEKLY`, `MONTHLY`) | `RecurringTransactionService`, giữ ngày neo `anchorDay` cuối tháng (31/01 → 28/02 → 31/03), chặn đóng TK khi còn lịch `ACTIVE` | `RecurringTransactionSetupIT` |
| `L2-07` | Tự động thực thi giao dịch định kỳ | `RecurringTransactionJob` + `RecurringExecutor` dùng `FOR UPDATE SKIP LOCKED` trên `idx_rec_due` và cô lập `REQUIRES_NEW` (tự động `PAUSED` sau 3 lần lỗi) | `RecurringTransactionSchedulerIT` |
| `L2-08` | Cache kết quả truy vấn thường xuyên | Cache 2 tầng (`Redis 7` + fallback `Caffeine`/DB qua `ResilientCacheErrorHandler`), xóa cache ở pha `AFTER_COMMIT` | `QueryCachingIT` |

### 5.3. Level 3 — Phân loại Khách hàng, Báo cáo, Gian lận, Xuất File & Observability (10/10 Hoàn thành)

| Mã | Yêu cầu Đề bài | Giải pháp Kỹ thuật & Module Thực thi | Bài Kiểm thử Xác minh |
|---|---|---|---|
| `L3-01` | Báo cáo giao dịch theo Tuần, Quý, Năm | `ReportRepository.getPeriodicTransactionReport` dùng `date_trunc('week'\|'quarter'\|'year', created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')` | `PeriodicTransactionReportIT` |
| `L3-02` | Chuỗi dữ liệu xu hướng số lượng GD & biến động số dư | `generate_series` kết hợp hàm cửa sổ tích lũy ngược (`Reverse Cumulative Sum`) tái dựng số dư cuối mỗi kỳ | `TrendReportAndChartIT` |
| `L3-03` | Phát hiện giao dịch bất thường (`AMOUNT_THRESHOLD` & `VELOCITY`) | Đánh giá thời gian thực `@Async` trên Virtual Threads (`AFTER_COMMIT`) + quét lịch sử bằng SQL Window Function `RANGE ... PRECEDING` | `RealTimeAnomalyDetectionIT`, `AlertManagementAndFraudScanIT` |
| `L3-04` | Lưu trữ & quản lý cảnh báo bất thường | Bảng `core.alert` (`V13`), API lọc và chuyển trạng thái `NEW -> INVESTIGATING -> RESOLVED \| FALSE_POSITIVE` | `AlertManagementAndFraudScanIT` |
| `L3-05` | Phân loại khách hàng (`INDIVIDUAL`, `BUSINESS`) | Bảng `core.customer_type` (`V12`), gán và nâng cấp phân loại khách hàng kèm kiểm tra khóa lạc quan | `CustomerClassificationAndLimitIT` |
| `L3-06` | Áp dụng trần hạn mức theo từng loại khách hàng | `CustomerTypeLimitProvider` kiểm tra hạn mức hiệu lực $\min(\text{accountLimit}, \text{customerTypeMaxLimit})$ cả khi mở/sửa TK và khi giao dịch | `CustomerClassificationAndLimitIT` |
| `L3-07` | Xuất báo cáo ra file Excel (`.xlsx`) hoặc PDF (`.pdf`) | `ExcelReportExporter` (`SXSSFWorkbook(100)` + JDBC cursor streaming) và `PdfReportExporter` (`OpenPDF 2.0.2` + font Unicode `DejaVuSans`) | `ExcelReportExportIT`, `PdfReportExportIT` |
| `L3-08` | Nhúng biểu đồ xu hướng vào file báo cáo xuất ra | `ChartRenderer` (`JFreeChart` headless) vẽ 3 biểu đồ PNG `800x400` nhúng trực tiếp vào sheet `Bieu do` (`.xlsx`) và trang báo cáo (`.pdf`) | `ExcelReportExportIT`, `PdfReportExportIT` |
| `L3-09` | Tích hợp Spring Boot Actuator & Micrometer Metrics | `/actuator/health` (`show-details: when_authorized`), `/actuator/metrics` (`ADMIN`-only), bộ đếm `banking.transactions`, `banking.alerts.raised`, `banking.recurring.executed` | `ActuatorMetricsAndVirtualThreadsIT` |
| `L3-10` | Đa luồng hiệu năng cao với Java 21 Virtual Threads | Bật `spring.threads.virtual.enabled=true`, thực thi `@Async` trên `SimpleAsyncTaskExecutor(virtualThreads=true)`, thay thế toàn bộ `synchronized` bằng `ReentrantLock` | `ActuatorMetricsAndVirtualThreadsIT`, `TransferConcurrencyIT` |

### 5.4. Tóm tắt Kết quả Đo Hiệu năng Thực nghiệm (PostgreSQL 17 & Concurrency)

| Hạng mục Kiểm chứng | Quy mô Dữ liệu / Tải | Trước Tối ưu | Sau Tối ưu (Partial Covering Index `V8`/`V9`) | Kết quả |
|---|---|---|---|---|
| **Truy vấn tổng hạn mức ngày (`sumOutgoingAmountSince`)** | **1.000.000 giao dịch** (`core.transaction`) | `121,007 ms` (`Seq Scan`) | **`0,937 ms`** (`Index-Only Scan` trên `idx_tx_source_completed_created_amount`, `Heap Fetches: 0`) | Tăng tốc **~129 lần** |
| **Chuyển khoản đồng thời đa tài khoản (`TransferConcurrencyIT`)** | **100 lệnh chuyển đồng thời** (25 worker threads, 10 tài khoản) + **10 luồng đối xứng `A ↔ B`** | Dễ xảy ra Deadlock nếu khóa theo thứ tự nguồn → đích | **0 Deadlock**, **0 Lost Update**, tổng số dư + phí bảo toàn chính xác tuyệt đối đến từng xu (`0.00 VND` sai lệch) | Đạt 100% tính đúng đắn ACID |
| **Xuất báo cáo Excel luồng (`SXSSFWorkbook(100)`)** | Stream qua `JdbcClient` (`fetchSize = 1000`) | `XSSFWorkbook` giữ toàn bộ DOM trong Heap | Giữ cố định **100 dòng** trong bộ nhớ RAM, tự động xóa file tạm `poifiles` trong `finally` | Chống `OutOfMemoryError` |

---

## 6. Tài liệu Kỹ thuật, Kiến trúc, Sơ đồ ERD & Quyết định Kiến trúc (ADR)
- **Kiến trúc Hệ thống & Luồng Giao dịch (`Modular Monolith`, `Virtual Threads`):** [docs/architecture.md](docs/architecture.md)
- **Sơ đồ Thực thể Liên kết & Danh mục Chỉ mục (ERD — Flyway `V1`–`V13`):** [docs/erd.md](docs/erd.md)
- **Hồ sơ Quyết định Kiến trúc (`ADR-001` → `ADR-015`):** [docs/adr.md](docs/adr.md)
- **Ghi chú Phát triển Chi tiết theo từng Story/PR (`Developer Notes`):** [docs/dev-notes/README.md](docs/dev-notes/README.md)
- **Nhật ký Thay đổi Phiên bản (`Changelog`):** [CHANGELOG.md](CHANGELOG.md)

---

## 7. Hướng mở rộng (Future Enhancements & Scale Roadmap)
1. **Phân trang Con trỏ (`Keyset / Seek Pagination`):** Bổ sung truy vấn theo con trỏ `(created_at DESC, id DESC)` bên cạnh `OFFSET` cho bảng giao dịch quy mô hàng trăm triệu bản ghi để duy trì độ trễ $O(\log N)$ ổn định ở mọi trang sâu.
2. **Khóa Phân tán Mức Job (`ShedLock`) & Chuỗi Băm Kiểm toán (`Tamper-Evident Audit Log`):** Kết hợp `ShedLock` ở mức bộ lập lịch cùng `FOR UPDATE SKIP LOCKED` ở mức bản ghi; bổ sung bảng `core.audit_log` nối chuỗi băm `SHA-256` cho mọi thao tác quản trị nhạy cảm.
3. **Tối ưu Hóa Nguyên tử trên Redis & Chống Spoofing Proxy:** Chuyển thao tác đọc-xóa Refresh Token sang lệnh `GETDEL` / Lua script nguyên tử; gộp (`coalesce`) cảnh báo Velocity theo cửa sổ thời gian; giới hạn trích xuất `X-Forwarded-For` chỉ từ danh sách reverse proxy tin cậy.
4. **Lộ trình Nâng cấp Spring Boot 4 / Jakarta EE 11 & Kiến trúc Sự kiện:** Chuyển từ `@TransactionalEventListener` nội bộ sang mẫu **Transactional Outbox + Apache Kafka** khi tách module `fraud` và `report` thành các dịch vụ độc lập.

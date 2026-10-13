# Core Banking System - REST API Service

[![Backend CI](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/ci-backend.yml/badge.svg?branch=dev)](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/ci-backend.yml)
[![Container Smoke Test](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/cd.yml/badge.svg)](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/cd.yml)
![Java 21](https://img.shields.io/badge/Java-21_LTS-orange)
![Spring Boot 3.5.16](https://img.shields.io/badge/Spring_Boot-3.5.16-brightgreen)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-blue)
![JaCoCo Coverage](https://img.shields.io/badge/Coverage-≥_80%25-green)

Hệ thống quản lý tài khoản ngân hàng và giao dịch tài chính (Core Banking REST API) xây dựng theo kiến trúc Modular Monolith chuẩn Enterprise. Dự án tập trung vào an toàn dữ liệu tài chính, xử lý giao dịch đồng thời với khóa bi quan (`SELECT ... FOR UPDATE`) theo thứ tự ID tăng dần chống Deadlock, bảo vệ chống trừ tiền 2 lần bằng `Idempotency-Key`, chuẩn hóa lỗi RFC 9457 `ProblemDetail`, che dữ liệu nhạy cảm (KYC/CCCD) và kiểm chứng tự động qua CI/CD.

## 1. Tính năng cốt lõi (Core Capabilities)
- **Quản lý Khách hàng (Customer Management):** Quản lý hồ sơ định danh KYC, khóa lạc quan `@Version`, hỗ trợ soft-delete bảo lưu vết kiểm toán, tự động che dữ liệu nhạy cảm (CCCD/National ID) trong log.
- **Quản lý Tài khoản (Account Management):** Sinh số tài khoản 12 chữ số kiểm tra thuật toán Luhn, kiểm soát hạn mức giao dịch (`transactionLimit`, `dailyLimit`), phân nhóm số dư (`LOW`, `MEDIUM`, `HIGH`), đóng tài khoản an toàn khi số dư bằng `0`.
- **Xử lý Giao dịch & Chống Deadlock (Transaction Engine):** Hỗ trợ Nạp tiền (`DEPOSIT`), Rút tiền (`WITHDRAWAL`), Chuyển khoản (`TRANSFER`), Thanh toán (`PAYMENT`) và Đảo giao dịch (`REVERSAL`); tính phí đa bậc $O(\log n)$ bằng `TreeMap.floorEntry`; khóa bi quan theo thứ tự ID tài khoản tăng dần kết hợp `lock_timeout = 3s` (đã kiểm chứng với 100 luồng chuyển tiền đồng thời không Deadlock).
- **Bảo vệ Idempotency (`Idempotency-Key`):** Chống thực thi trùng lặp giao dịch ghi bằng khóa tư vấn giao dịch PostgreSQL (`pg_advisory_xact_lock`) và băm SHA-256 canonical payload.
- **Thống kê & Báo cáo (Reporting & Analytics):** Thống kê số lượng tài khoản và giao dịch theo bậc số dư (`GET /api/v1/statistics/balance-tiers`) và phân bố khách hàng theo địa điểm (`GET /api/v1/statistics/customers-by-location`) bằng SQL CTE và Partial Index trên PostgreSQL.

## 2. Công nghệ sử dụng
- **Ngôn ngữ & Nền tảng:** Java 21 LTS (Virtual Threads), Spring Boot 3.5.16 (Web, Data JPA, JDBC Client, Validation, Actuator)
- **Cơ sở dữ liệu & Quản lý Schema:** PostgreSQL 17, Flyway Migration (`V1`–`V6` + repeatable demo seed `R__demo_seed.sql`)
- **Tài liệu API:** springdoc-openapi 2.8.5 (OpenAPI 3 / Swagger UI tại `/swagger-ui.html`)
- **Kiểm thử & Chất lượng:** JUnit 5, Mockito, Testcontainers (PostgreSQL 17), JaCoCo (Coverage ≥ 80% trên CI), Spotless (AOSP), Checkstyle, Docker Compose

## 3. Cấu trúc kiến trúc (Modular Monolith)
```text
backend/src/main/java/com/example/corebanking/
├── customer/     # API, Application, Domain, Infrastructure (Quản lý khách hàng KYC)
├── account/      # API, Application, Domain, Infrastructure (Tài khoản, Luhn, hạn mức, bậc số dư)
├── transaction/  # API, Application, Domain, Infrastructure (Nạp/rút/chuyển/thanh toán/đảo, tính phí)
├── report/       # API, Application, Infrastructure (Thống kê SQL CTE qua JdbcClient)
└── common/       # ProblemDetail (RFC 9457), Idempotency-Key, MaskingUtils, Config
```

## 4. Hướng dẫn khởi chạy nhanh (Quick Start)

### Cách 1: Khởi chạy toàn bộ hệ thống bằng 1 lệnh với Docker Compose (Khuyến nghị)
```bash
# Khởi tạo PostgreSQL 17 + Backend API + tự động nạp dữ liệu mẫu (20 KH, 12 TK, 16 GD)
docker compose up --build
```

### Cách 2: Chạy trực tiếp bằng Maven Wrapper (Local JDK 21)
```bash
cd backend
# 1. Chạy bộ kiểm thử và kiểm tra định dạng Spotless / Checkstyle
./mvnw clean verify

# 2. Khởi chạy ứng dụng với profile local (yêu cầu PostgreSQL 17 tại localhost:5432)
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

### Gọi thử API mẫu
```bash
# 1. Xem thống kê tài khoản & giao dịch theo nhóm số dư (LOW / MEDIUM / HIGH)
curl -s http://localhost:8080/api/v1/statistics/balance-tiers

# 2. Nạp tiền vào tài khoản (kèm Idempotency-Key chống gửi trùng)
curl -X POST http://localhost:8080/api/v1/transactions/deposits \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-deposit-0001" \
  -d '{"accountId":1,"amount":1500000.00,"location":"Hà Nội","description":"Nạp tiền mặt tại quầy"}'
```

- **Swagger UI:** `http://localhost:8080/swagger-ui.html`
- **Actuator Health:** `http://localhost:8080/actuator/health`

## 5. Tài liệu kỹ thuật
- **Ghi chú kiến trúc & phân tích nghiệp vụ theo từng Story:** [docs/dev-notes/README.md](docs/dev-notes/README.md)
- **Nhật ký thay đổi phiên bản (Changelog):** [CHANGELOG.md](CHANGELOG.md)

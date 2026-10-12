# Core Banking System - REST API Service

[![Backend CI](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/ci-backend.yml/badge.svg?branch=dev)](https://github.com/tsennguyen/corebank-java-assessment/actions/workflows/ci-backend.yml)
![Java 21](https://img.shields.io/badge/Java-21_LTS-orange)
![Spring Boot 3.5.16](https://img.shields.io/badge/Spring_Boot-3.5.16-brightgreen)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-blue)
![JaCoCo Coverage](https://img.shields.io/badge/Coverage-≥_80%25-green)

Hệ thống quản lý tài khoản ngân hàng và giao dịch tài chính (Core Banking REST API) xây dựng theo kiến trúc Modular Monolith chuẩn Enterprise. Dự án tập trung vào an toàn dữ liệu tài chính, xử lý giao dịch đồng thời với khóa bi quan (`SELECT FOR UPDATE`), cơ chế định danh lỗi RFC 9457 `ProblemDetail`, bảo vệ thông tin nhạy cảm (KYC/CCCD) và đảm bảo chất lượng qua CI/CD tự động.

## 1. Tính năng cốt lõi (Core Capabilities)
- **Quản lý Khách hàng (Customer Management):** Quản lý hồ sơ định danh KYC, hỗ trợ soft-delete bảo lưu vết kiểm toán, tự động che dữ liệu nhạy cảm (CCCD/National ID) trong log và phản hồi API.
- **Quản lý Tài khoản (Account Management):** Khởi tạo tài khoản thanh toán, kiểm soát hạn mức giao dịch (ngày/lần), phân nhóm số dư theo quy định, đóng tài khoản an toàn khi số dư bằng 0.
- **Xử lý Giao dịch (Transaction Engine):** Nạp tiền, tra cứu lịch sử giao dịch đa tiêu chí, bảo đảm an toàn đồng thời bằng khóa bi quan kết hợp session `lock_timeout`, phân trang ổn định (`ORDER BY createdAt DESC, id DESC`).
- **Chuẩn hóa lỗi & Quan sát (Observability):** Tích hợp RFC 9457 `ProblemDetail` với mã định danh `requestId` cho từng yêu cầu, giám sát sức khỏe dịch vụ qua Spring Boot Actuator.

## 2. Công nghệ sử dụng
- **Ngôn ngữ & Nền tảng:** Java 21 LTS, Spring Boot 3.5.16 (Spring Web, Spring Data JPA, Bean Validation, Actuator)
- **Cơ sở dữ liệu & Quản lý Schema:** PostgreSQL 17, Flyway Migration
- **Tài liệu API:** springdoc-openapi 2.8.5 (OpenAPI 3 / Swagger UI tại `/swagger-ui.html`)
- **Kiểm thử tự động:** JUnit 5, Mockito, Testcontainers (PostgreSQL 17)
- **Kiểm soát chất lượng mã nguồn:** JaCoCo (Coverage ≥ 80% trên CI), Spotless (AOSP style), Checkstyle, GitHub Actions

## 3. Cấu trúc kiến trúc (Modular Monolith)
```text
backend/src/main/java/com/bank/core/
├── customer/     # API, Application, Domain, Infrastructure (Quản lý khách hàng KYC)
├── account/      # API, Application, Domain, Infrastructure (Tài khoản thanh toán, hạn mức)
├── transaction/  # API, Application, Domain, Infrastructure (Giao dịch, nạp tiền, khóa bi quan)
└── common/       # ProblemDetail (RFC 9457), MaskingUtils, Global Exception Handler
```

## 4. Hướng dẫn chạy & kiểm thử

### Yêu cầu môi trường
- JDK 21 LTS
- PostgreSQL 17 (hoặc chạy test tích hợp qua Docker / Testcontainers)

### Lệnh thực thi
```bash
# 1. Chạy toàn bộ Unit & Integration tests, kiểm tra định dạng Spotless & Checkstyle
cd backend
./mvnw clean verify

# 2. Khởi chạy ứng dụng với profile local (yêu cầu PostgreSQL 17 tại localhost:5432)
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run

# 3. Gọi thử API nạp tiền mẫu (khóa bi quan chống tranh chấp số dư)
curl -X POST http://localhost:8080/api/v1/transactions/deposit \
  -H "Content-Type: application/json" \
  -d '{"accountNumber":"1000000001","amount":500000.00,"channel":"BRANCH","referenceNo":"TX-DEMO-001"}'
```

- **Swagger UI:** `http://localhost:8080/swagger-ui.html`
- **Actuator Health:** `http://localhost:8080/actuator/health`

## 5. Tài liệu kỹ thuật
- **Ghi chú kiến trúc & phân tích nghiệp vụ theo từng Story:** [docs/dev-notes/README.md](docs/dev-notes/README.md)
- **Nhật ký thay đổi phiên bản (Changelog):** [CHANGELOG.md](CHANGELOG.md)

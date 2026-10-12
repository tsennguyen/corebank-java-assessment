# Core Banking System - REST API Service

Hệ thống quản lý tài khoản ngân hàng và giao dịch tài chính (Core Banking REST API) phục vụ quản lý khách hàng, tài khoản thanh toán/tiết kiệm, nạp tiền, kiểm soát giao dịch và truy vấn lịch sử.

## 1. Công nghệ sử dụng
- **Ngôn ngữ & Framework:** Java 21 LTS, Spring Boot 3.5.16 (Spring Web, Spring Data JPA, Bean Validation, Actuator)
- **Cơ sở dữ liệu & Migration:** PostgreSQL 17, Flyway
- **Tài liệu API:** springdoc-openapi 2.8.5 (Swagger UI tại `/swagger-ui.html`)
- **Kiểm thử:** JUnit 5, Mockito, Testcontainers PostgreSQL 17
- **Chất lượng mã nguồn & CI:** JaCoCo (Coverage ≥ 80% trên CI), Spotless (AOSP style), Checkstyle, GitHub Actions

## 2. Tiến độ theo đề bài

### Level 1: Nền tảng và quản lý cơ bản
| Mã | Mô tả yêu cầu | Trạng thái | PR / Ghi chú |
|---|---|:---:|---|
| L1-01 | Thiết kế bảng `Customer`, `Account`, `Transaction` | ✅ | [#8](https://github.com/tsennguyen/corebank-java-assessment/pull/8), [#9](https://github.com/tsennguyen/corebank-java-assessment/pull/9), [#10](https://github.com/tsennguyen/corebank-java-assessment/pull/10) |
| L1-02 | Hạn mức giao dịch & ngày mở tài khoản | ✅ | [#9](https://github.com/tsennguyen/corebank-java-assessment/pull/9) |
| L1-03 | Phí giao dịch & địa điểm thực hiện | ✅ | [#10](https://github.com/tsennguyen/corebank-java-assessment/pull/10) |
| L1-04 | API CRUD & tìm kiếm khách hàng | ✅ | [#8](https://github.com/tsennguyen/corebank-java-assessment/pull/8) |
| L1-05 | API CRUD & tìm kiếm tài khoản | ✅ | [#9](https://github.com/tsennguyen/corebank-java-assessment/pull/9) |
| L1-06 | API CRUD & tra cứu giao dịch (nạp tiền, lọc theo TK) | ✅ | [#10](https://github.com/tsennguyen/corebank-java-assessment/pull/10) |
| L1-07 | Kiểm tra hạn mức khi rút tiền / chuyển khoản | ⬜ | Sắp triển khai |
| L1-08 | Tính toán phí giao dịch theo chính sách | ⬜ | Sắp triển khai |
| L1-09 | Thống kê tài khoản & giao dịch theo nhóm số dư | ⬜ | Sắp triển khai |
| L1-10 | Thống kê số lượng khách hàng theo địa điểm | ⬜ | Sắp triển khai |

### Level 2: Quản lý nâng cao và bảo mật
| Mã | Mô tả yêu cầu | Trạng thái | PR / Ghi chú |
|---|---|:---:|---|
| L2-01 | Phân quyền Admin và Customer | ⬜ | Sắp triển khai |
| L2-02 | Spring Security với xác thực JWT | ⬜ | Sắp triển khai |
| L2-03 | Tìm kiếm giao dịch nâng cao (số tiền, ngày, loại) | ⬜ | Sắp triển khai |
| L2-04 | Phân trang và sắp xếp kết quả | 🚧 | Đã có tại KH, TK, GD ([#8](https://github.com/tsennguyen/corebank-java-assessment/pull/8), [#9](https://github.com/tsennguyen/corebank-java-assessment/pull/9), [#10](https://github.com/tsennguyen/corebank-java-assessment/pull/10)) |
| L2-05 | Lịch sử trạng thái tài khoản (`AccountStatusHistory`) | ⬜ | Sắp triển khai |
| L2-06, L2-07 | Giao dịch định kỳ & Cron Scheduler | ⬜ | Sắp triển khai |
| L2-08 | Caching tài khoản & lịch sử giao dịch | ⬜ | Sắp triển khai |

### Level 3: Phân tích & Tối ưu hiệu suất
| Mã | Mô tả yêu cầu | Trạng thái | PR / Ghi chú |
|---|---|:---:|---|
| L3-01, L3-02 | Báo cáo giao dịch theo thời gian & thống kê trung bình/min/max | ⬜ | Sắp triển khai |
| L3-03, L3-04 | Phát hiện giao dịch bất thường & cảnh báo `Alert` | ⬜ | Sắp triển khai |
| L3-05, L3-06 | Bảng `CustomerType` & kiểm soát hạn mức theo loại KH | ⬜ | Sắp triển khai |
| L3-07, L3-08 | Xuất báo cáo Excel/PDF & biểu đồ xu hướng | ⬜ | Sắp triển khai |
| L3-09, L3-10 | Actuator metrics & tối ưu bất đồng bộ / Virtual Threads | ⬜ | Sắp triển khai |

### Hạng mục nâng cao (Engineering Practices)
| Mã | Hạng mục | Trạng thái | PR / Ghi chú |
|---|---|:---:|---|
| NC-01 | Khóa bi quan (`SELECT FOR UPDATE`), an toàn đồng thời | ✅ | [#10](https://github.com/tsennguyen/corebank-java-assessment/pull/10) |
| NC-03 | Flyway database migration baseline & versioning | ✅ | [#4](https://github.com/tsennguyen/corebank-java-assessment/pull/4), [#8](https://github.com/tsennguyen/corebank-java-assessment/pull/8), [#9](https://github.com/tsennguyen/corebank-java-assessment/pull/9), [#10](https://github.com/tsennguyen/corebank-java-assessment/pull/10) |
| NC-04, G-04 | Testcontainers PostgreSQL & JaCoCo Coverage ≥ 80% CI | ✅ | [#3](https://github.com/tsennguyen/corebank-java-assessment/pull/3), [#4](https://github.com/tsennguyen/corebank-java-assessment/pull/4) |
| NC-05 | GitHub Actions CI/CD, Spotless (AOSP) & Checkstyle | ✅ | [#1](https://github.com/tsennguyen/corebank-java-assessment/pull/1), [#3](https://github.com/tsennguyen/corebank-java-assessment/pull/3), [#11](https://github.com/tsennguyen/corebank-java-assessment/pull/11) |
| NC-08 | `ProblemDetail` RFC 9457 & che dữ liệu nhạy cảm (CCCD) | ✅ | [#6](https://github.com/tsennguyen/corebank-java-assessment/pull/6), [#10](https://github.com/tsennguyen/corebank-java-assessment/pull/10) |
| NC-09 | OpenAPI 3 / Swagger documentation & Dev Notes | ✅ | [#7](https://github.com/tsennguyen/corebank-java-assessment/pull/7), [#10](https://github.com/tsennguyen/corebank-java-assessment/pull/10) |
| NC-02, NC-06..13 | Idempotency, Docker, Refresh Token, Deploy, k6, Keyset, ShedLock | ⬜ | Lộ trình các sprint tiếp theo |

## 3. Hướng dẫn chạy thử nghiệm
```bash
# 1. Kiểm tra build và chạy unit tests (bỏ qua Docker tests nếu local không có Docker)
cd backend
./mvnw clean verify

# 2. Khởi chạy ứng dụng với profile local (yêu cầu PostgreSQL 17 tại localhost:5432)
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```
- Swagger UI tài liệu API: `http://localhost:8080/swagger-ui.html`
- Health check Actuator: `http://localhost:8080/actuator/health`

## 4. Tài liệu kỹ thuật
- **Ghi chú phát triển theo từng Story:** [docs/dev-notes/README.md](docs/dev-notes/README.md)
- **Nhật ký thay đổi:** [CHANGELOG.md](CHANGELOG.md)

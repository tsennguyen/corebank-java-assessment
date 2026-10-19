# Story 3.1: JWT Login, Role-Based Access Control & User-Scoped Idempotency

- **PR:** [#25](https://github.com/tsennguyen/corebank-java-assessment/pull/25)
- **Mã yêu cầu:** `L2-01` [ĐỀ], `L2-02` [ĐỀ], `FR-13`, `FR-14`, `FR-25`, `NFR-5`, `NC-02`, `NC-03`, `NC-08`, `E-02`

## 1. Bối cảnh & Bài toán

Mở đầu **Level 2 (Security, Search & Automation)**, hệ thống Core Banking cần bổ sung lớp bảo mật xác thực không trạng thái (Stateless JWT Authentication) và phân quyền theo vai trò (`ADMIN` và `CUSTOMER`) theo yêu cầu `L2-01`, `L2-02`:
- `POST /api/v1/auth/login` xác thực người dùng bằng `username` và `password` (mật khẩu băm bằng BCrypt), phát hành Access Token chuẩn JWT ký bằng thuật toán `HS256` có hiệu lực 15 phút (`900` giây).
- Bảo vệ toàn bộ các API `/api/v1/**`: các thao tác quản trị (tạo/cập nhật khách hàng, tài khoản, giao dịch tài chính, thống kê hệ thống) yêu cầu vai trò `ADMIN`; các thao tác tra cứu tài khoản/lịch sử giao dịch cho phép cả `ADMIN` và `CUSTOMER` (chuẩn bị cho kiểm tra quyền sở hữu ở Story 3.2).
- Mọi phản hồi lỗi bảo mật (`401 Unauthorized`, `403 Forbidden`) sinh ra từ cả tầng Servlet Security Filter lẫn `@PreAuthorize` phải tuân thủ chặt chẽ chuẩn **RFC 9457 Problem Details** (`application/problem+json` kèm `code`, `requestId`, `timestamp`).
- Cô lập khóa chống trùng giao dịch (`Idempotency-Key`) theo từng người dùng đã xác thực (`scope = username`).

## 2. Giải pháp Kỹ thuật đã triển khai

### 2.1. Mô hình Dữ liệu & Khởi tạo (`V7__app_user.sql`, `AppUser`, `AdminBootstrapRunner`)
- Bảng `core.app_user` lưu thông tin tài khoản đăng nhập với 2 ràng buộc toàn vẹn kép ngay tại tầng PostgreSQL và tầng Domain `AppUser`:
  - `chk_app_user_role CHECK (role IN ('ADMIN', 'CUSTOMER'))`
  - `chk_app_user_customer_link CHECK ((role = 'ADMIN' AND customer_id IS NULL) OR (role = 'CUSTOMER' AND customer_id IS NOT NULL))`: đảm bảo quản trị viên không bao giờ gắn nhầm vào hồ sơ khách hàng, và mỗi tài khoản `CUSTOMER` bắt buộc liên kết 1-1 với một bản ghi `core.customer(id)`.
- `AdminBootstrapRunner` (`ApplicationRunner`) tự động khởi tạo hoặc đồng bộ tài khoản `ADMIN` mặc định từ cấu hình `app.security.admin-bootstrap.*` khi ứng dụng khởi động.
- Bản ghi seed lặp lại `R__demo_seed.sql` bổ sung 2 tài khoản `CUSTOMER` mẫu (`cust.an`, `cust.binh`) phục vụ kiểm thử và trình diễn.

### 2.2. Phát hành & Giải mã JWT HS256 (`JwtTokenService`, `SecurityConfig`)
- Sử dụng thư viện chuẩn **Spring Security OAuth2 Resource Server** (`NimbusJwtEncoder` và `NimbusJwtDecoder`) thay vì thêm thư viện bên thứ ba rời rạc:
  - Kiểm tra nghiêm ngặt độ dài khóa bí mật `JWT_SECRET >= 32 bytes (256 bits)` ngay khi khởi tạo Bean.
  - Khóa cứng thuật toán `MacAlgorithm.HS256` ở cả chiều ký và chiều xác thực để ngăn chặn tấn công **Algorithm Confusion** (`alg: none` hoặc chuyển đổi bất đối xứng).
  - Xác thực đồng thời `JwtTimestampValidator` (clock skew 5 giây) và `JwtIssuerValidator` (`iss = "corebank-java-assessment"`).
  - Tuỳ biến `JwtAuthenticationConverter` đặt `principalClaimName = "username"` và chuyển claim `role` thành `SimpleGrantedAuthority("ROLE_" + role)`.

### 2.3. Chống Username Enumeration, Timing Attack & Giới hạn 72 Bytes của BCrypt (`AuthService`)
- Khi đăng nhập thất bại (không tìm thấy `username`, sai mật khẩu, hoặc `enabled = false`), `AuthService.login()` luôn trả về cùng một mã lỗi `401 UNAUTHORIZED` với thông điệp chung `"Invalid username or password"`.
- Khi `username` không tồn tại trong DB, hệ thống vẫn thực thi `passwordEncoder.matches(rawPassword, DUMMY_BCRYPT_HASH)` với một chuỗi băm BCrypt strength 10 định sẵn để cân bằng thời gian CPU (~70ms), triệt tiêu tấn công đo thời gian phản hồi (**Timing Attack**).
- Chủ động kiểm tra `rawPassword.getBytes(StandardCharsets.UTF_8).length <= 72` trước khi gọi `BCryptPasswordEncoder.matches(...)` để tránh `IllegalArgumentException` của Spring Security 6.3+ khi mật khẩu vượt quá giới hạn 72 bytes của thuật toán BCrypt.

### 2.4. Chuẩn hóa lỗi 401/403 RFC 9457 & Cô lập Idempotency Scope
- Xây dựng `ProblemDetailAuthenticationEntryPoint` (`401 UNAUTHORIZED`) và `ProblemDetailAccessDeniedHandler` (`403 ACCESS_DENIED`), đồng thời bổ sung `@ExceptionHandler` cho `AccessDeniedException` và `AuthenticationException` trong `GlobalExceptionHandler`.
- Nhờ `RequestIdFilter` chạy ở `Ordered.HIGHEST_PRECEDENCE` (trước `SecurityFilterChain`), mọi lỗi 401/403 từ bộ lọc bảo mật đều có đầy đủ `requestId` trong cả JSON body và header `X-Request-Id`.
- `TransactionService.resolveCurrentScope()` tự động trích xuất định danh người dùng từ `SecurityContextHolder` làm `scope` cho `IdempotencyService`, và khóa tư vấn PostgreSQL `pg_advisory_xact_lock` băm trên chuỗi kết hợp `currentScope + ":" + trimmedKey`.

## 3. Các phương án đã cân nhắc & Đánh đổi

| Phương án | Ưu điểm | Nhược điểm / Lý do chọn hoặc loại |
|---|---|---|
| **Dùng `spring-boot-starter-oauth2-resource-server` (`NimbusJwtEncoder` / `NimbusJwtDecoder`)** *(Đã chọn)* | Tích hợp nguyên bản với Spring Security 6, được bảo trì đồng bộ theo vòng đời Spring Boot 3.x, hỗ trợ sẵn `JwtValidator` và `@WithMockUser`. | Cần cấu hình `OctetSequenceKey` cho khóa đối xứng `HS256`. |
| **Dùng thư viện ngoài `io.jsonwebtoken:jjwt`** *(Loại)* | Cú pháp builder quen thuộc ở các dự án cũ. | Phải tự viết `OncePerRequestFilter` thủ công, thêm phụ thuộc ngoài không cần thiết. |
| **Gộp bảng `app_user` vào bảng `customer`** *(Loại)* | Bớt 1 bảng DB. | Làm ô nhiễm thực thể nghiệp vụ `Customer` với thông tin đăng nhập và không biểu diễn sạch tài khoản `ADMIN` (vốn không phải khách hàng ngân hàng). |

## 4. Kiểm thử & Xác minh

- **Unit Tests:** `AppUserTest`, `JwtTokenServiceTest`, `AuthServiceTest`, `AuthControllerTest` kiểm tra đầy đủ các nhánh hợp lệ, sai mật khẩu, tài khoản bị khóa, mật khẩu > 72 bytes UTF-8, token hết hạn, sai chữ ký, sai issuer.
- **Integration Tests:** `SecurityIntegrationTest` và `FlywayMigrationIT` chạy trên PostgreSQL 17 Testcontainers xác minh toàn bộ luồng đăng nhập, phân quyền `ADMIN` vs `CUSTOMER`, phản hồi RFC 9457 `ProblemDetail` và cô lập khóa Idempotency giữa các người dùng.
- **Tổng số test:** **293 tests pass**, JaCoCo line coverage đạt **89.5%**.

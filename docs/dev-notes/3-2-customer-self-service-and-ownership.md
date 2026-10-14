# Story 3.2: Customer Self-Service & Ownership Guard (IDOR Protection)

- **PR:** [#27](https://github.com/tsennguyen/corebank-java-assessment/pull/27)
- **Mã yêu cầu:** `L2-01` [ĐỀ], `FR-14`, `NFR-5`, `NC-08`

## 1. Bối cảnh & Bài toán

Tiếp nối hệ thống xác thực JWT HS256 và phân quyền `ADMIN`/`CUSTOMER` ở Story 3.1, hệ thống cần triển khai **cổng tự phục vụ cho khách hàng** (`/api/v1/me`, `/api/v1/me/accounts`) và cơ chế **kiểm soát quyền sở hữu tài nguyên (Ownership Guard)** để ngăn chặn triệt để lỗ hổng **IDOR / Broken Object Level Authorization (OWASP API1)** theo yêu cầu `L2-01`, `FR-14`:
- Khách hàng (`role = CUSTOMER`) được phép tự xem hồ sơ cá nhân (`GET /api/v1/me`) với số CCCD đã che (`MaskingUtils`) và danh sách tài khoản của chính mình (`GET /api/v1/me/accounts`).
- Khi khách hàng gọi `GET /api/v1/accounts/{id}` hoặc `GET /api/v1/accounts/{id}/transactions`, hệ thống chỉ cho phép nếu tài khoản `{id}` thuộc sở hữu của `customerId` trong JWT; nếu cố tình đổi `{id}` sang tài khoản của khách hàng khác thì chặn ngay với `403 ACCESS_DENIED` chuẩn RFC 9457.
- Khi khách hàng gọi `GET /api/v1/transactions/{id}`, hệ thống cho phép truy cập nếu khách hàng sở hữu tài khoản nguồn (`sourceAccountId`) **hoặc** tài khoản đích (`targetAccountId`) của giao dịch đó.
- Khi khách hàng gọi `GET /api/v1/transactions`, nếu truyền `accountId` của người khác thì trả `403 ACCESS_DENIED`; nếu không truyền `accountId`, hệ thống tự động giới hạn kết quả tìm kiếm trong tập tài khoản thuộc sở hữu của khách hàng đó.

## 2. Giải pháp Kỹ thuật đã triển khai

### 2.1. Cổng Tự phục vụ Không Nhận Tham số Định danh từ Client (`MeController`)
- `GET /api/v1/me` và `GET /api/v1/me/accounts` được bảo vệ kép bằng `.requestMatchers("/api/v1/me", "/api/v1/me/**").hasRole("CUSTOMER")` tại `SecurityFilterChain` và `@PreAuthorize("hasRole('CUSTOMER')")` tại `MeController`.
- Định danh khách hàng (`customerId`) được trích xuất trực tiếp từ claim `customerId` bên trong JWT đã xác thực chữ ký HMAC-SHA256 thông qua `OwnershipGuard.requireCustomerId(authentication)` — hoàn toàn không nhận `customerId` từ query parameter hay path variable.
- `AccountService.getAccountsByCustomerId(Long customerId)` trả về `List<AccountResponse>` sắp xếp theo `id DESC` kèm phân nhóm số dư `balanceTier` (`LOW`/`MEDIUM`/`HIGH`), thiết kế sẵn chữ ký method sạch để Story 3.7 gắn `@Cacheable(cacheNames = "accountsByCustomer", key = "#customerId")`.

### 2.2. Bean `@Component("ownership")` (`OwnershipGuard`) & `@PreAuthorize` SpEL
- `OwnershipGuard` cung cấp 2 hàm kiểm tra quyền sở hữu cho biểu thức SpEL của `@PreAuthorize`:
  - `canAccessAccount(Authentication authentication, Long accountId)`: trả về `true` ngay lập tức (O(1), không chạm DB) nếu người gọi có quyền `ROLE_ADMIN`; nếu là `CUSTOMER`, thực thi truy vấn chiếu nhẹ `accountRepository.existsByIdAndCustomerId(accountId, customerId)` tận dụng index `idx_account_customer_id`.
  - `canAccessTransaction(Authentication authentication, Long transactionId)`: kiểm tra giao dịch có `sourceAccountId` hoặc `targetAccountId` nằm trong tập `accountRepository.findIdsByCustomerId(customerId)` hay không, đảm bảo cả người chuyển và người nhận trong giao dịch `TRANSFER` đều xem được chi tiết giao dịch của mình.
- Gắn `@PreAuthorize` tại biên Controller:
  - `AccountController.getAccountById`: `@PreAuthorize("hasRole('ADMIN') or @ownership.canAccessAccount(authentication, #id)")`
  - `AccountTransactionController.getAccountTransactions`: `@PreAuthorize("hasRole('ADMIN') or @ownership.canAccessAccount(authentication, #id)")`
  - `TransactionController.getTransactionById`: `@PreAuthorize("hasRole('ADMIN') or @ownership.canAccessTransaction(authentication, #id)")`
  - `TransactionController.searchTransactions`: `@PreAuthorize("hasRole('ADMIN') or #accountId == null or @ownership.canAccessAccount(authentication, #accountId)")`

### 2.3. Phòng thủ Chiều sâu (Defense-in-Depth) trong `TransactionService.searchTransactions`
- Bên cạnh kiểm tra tại biên Controller, `TransactionService.searchTransactions` tự động kiểm tra ngữ cảnh bảo mật khi người gọi là `ROLE_CUSTOMER`:
  - Nếu khách hàng chưa mở tài khoản nào (`ownedAccountIds.isEmpty()`), trả về ngay trang rỗng `PageResponse` mà không sinh mệnh đề SQL `IN ()` rỗng xuống PostgreSQL.
  - Nếu khách hàng có tài khoản và không truyền `accountId`, tự động gắn `TransactionSpecifications.withAccountIdsIn(ownedAccountIds)` (`source_account_id IN (...) OR target_account_id IN (...)`) để giới hạn phạm vi tìm kiếm.

## 3. Các phương án đã cân nhắc & Đánh đổi

| Phương án | Ưu điểm | Nhược điểm / Lý do chọn hoặc loại |
|---|---|---|
| **Tách riêng `/api/v1/me/accounts` cho Customer + `@PreAuthorize("@ownership...")` trên `/api/v1/accounts/{id}`** *(Đã chọn)* | Rõ ràng về ngữ nghĩa REST, ngăn chặn hoàn toàn việc dò `customerId` qua query param trên `/api/v1/accounts`, và chuẩn bị sẵn điểm neo `@Cacheable` theo `customerId` cho Story 3.7. | Thêm 1 controller nhẹ (`MeController`). |
| **Trả `403 ACCESS_DENIED` thay vì `404 RESOURCE_NOT_FOUND` khi truy cập trái phép** *(Đã chọn)* | Tuân thủ đúng đặc tả `L2-01` và Architecture §3.8, thực thi kiểm tra quyền sở hữu ở biên bảo mật trước khi đi vào logic nghiệp vụ, chứng minh rõ cơ chế RBAC/IDOR hoạt động. | Với hệ thống công khai không yêu cầu đăng nhập đôi khi ẩn bằng 404, nhưng ở đây mọi truy cập đều sau lớp xác thực JWT. |

## 4. Kiểm thử & Xác minh

- **Unit Tests (`OwnershipGuardTest`, `AccountServiceTest`, `TransactionServiceTest`):** Kiểm tra đầy đủ các nhánh `ADMIN`, `CUSTOMER` sở hữu tài khoản, `CUSTOMER` truy cập tài khoản người khác, giao dịch chuyển khoản 2 chiều (nguồn/đích), khách hàng chưa có tài khoản nào, và trích xuất claim `customerId`.
- **Web Slice Tests (`MeControllerTest`, `AccountControllerTest`, `AccountTransactionControllerTest`, `TransactionControllerTest`):** Kiểm증 phản hồi `200 OK` (với CCCD đã che) và `403 ACCESS_DENIED` chuẩn RFC 9457 `ProblemDetail`.
- **Integration Test (`OwnershipIntegrationTest`):** Chạy trên PostgreSQL 17 Testcontainers với dữ liệu mẫu `R__demo_seed.sql` (`admin`, `cust.an`, `cust.binh`), xác minh toàn diện AC 1–5.

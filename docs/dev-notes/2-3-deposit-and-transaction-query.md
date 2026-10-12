# Dev Note 2.3: Deposit and Transaction Query

- **Tóm tắt:** Xây dựng tính năng nạp tiền vào tài khoản và truy vấn lịch sử giao dịch: khóa bi quan chống race condition, mô hình giàu hành vi (Rich Domain Model), lưu vết kiểm toán số dư sau biến động và phân trang tối ưu hóa bằng composite index. Mã yêu cầu: `L1-01`, `L1-03`, `L1-06`. PR: (nhánh `feat/2-3-deposit-and-transaction-query`).
- **Đã làm gì:**
  - Viết Flyway migration `V4__transaction.sql` tạo bảng `core.transaction` kèm ràng buộc toàn vẹn `CHECK (amount > 0)`, `CHECK (fee >= 0)` và các index tối ưu: `(source_account_id, created_at DESC)`, `(target_account_id, created_at DESC)`, `(created_at DESC)`, `(type, created_at DESC)`.
  - Bổ sung phương thức `findByIdForUpdate` với khóa bi quan `@Lock(LockModeType.PESSIMISTIC_WRITE)` và timeout 3000ms vào `AccountRepository`.
  - Triển khai phương thức nghiệp vụ giàu tính đóng gói `Account.credit(Money amount)` bảo vệ tính bất biến của số dư, từ chối nạp tiền vào tài khoản không ở trạng thái `ACTIVE`.
  - Xây dựng domain entity `Transaction`, enum `TransactionType` và `TransactionStatus`.
  - Xây dựng `TransactionReferenceGenerator` sinh mã định danh duy nhất ngẫu nhiên có tiền tố ngày (`TXN-YYYYMMDD-...`).
  - Triển khai `TransactionService` thực hiện nạp tiền nguyên tử trong `@Transactional`, ghi nhận `targetBalanceAfter` và `systemBalanceDelta` phục vụ kiểm toán.
  - Xây dựng `TransactionController` (`POST /deposits`, `GET /{id}`, `GET /`) và mở rộng `AccountController` (`GET /{id}/transactions`).
  - Viết test suite toàn diện: unit tests, WebMvc tests (RFC 9457 ProblemDetail), và integration test `DepositConcurrencyIT` kiểm tra tính bảo toàn số dư khi nhiều luồng cùng nạp tiền đồng thời.
- **Vì sao làm vậy:** Khóa bi quan (`SELECT ... FOR UPDATE`) là bắt buộc đối với các biến động số dư tài khoản ngân hàng để loại trừ hoàn toàn nguy cơ mất mát cập nhật (lost update) khi có nhiều giao dịch diễn ra đồng thời.
- **Phương án khác đã cân nhắc:** Dùng khóa lạc quan (`@Version`); lý do không chọn: với tài khoản có mật độ giao dịch cao ("tài khoản nóng"), khóa lạc quan sẽ gây ra tỉ lệ retry rất lớn làm giảm trải nghiệm người dùng và tăng áp lực lên database.
- **Bẫy cần nhớ:**
  - Tiền tệ luôn sử dụng value object `Money` và kiểu dữ liệu `BigDecimal` scale 2 với `RoundingMode.HALF_UP`.
  - Bảng `core.transaction` sử dụng từ khóa non-reserved `transaction` trong PostgreSQL nên luôn chỉ định rõ schema: `@Table(name = "transaction", schema = "core")`.
- **Cách chạy/test:**
  - `./mvnw test -Dtest=TransactionTest,TransactionServiceTest,TransactionControllerTest,AccountControllerTest,AccountTest`
  - Integration test: `./mvnw test -Dtest=FlywayMigrationIT,DepositConcurrencyIT`

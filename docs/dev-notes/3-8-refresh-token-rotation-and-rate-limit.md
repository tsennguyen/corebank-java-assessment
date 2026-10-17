# Ghi chú Kỹ thuật — Story 3.8: Xoay vòng Refresh Token, Phát hiện Tái sử dụng & Giới hạn Tần suất Đăng nhập (`NC-07`, `FR-15`, `NFR-5`)

## 1. Bối cảnh & Mục tiêu
Access Token JWT (HS256) trong hệ thống có thời gian sống ngắn (**15 phút / `900s`**) để hạn chế cửa sổ rủi ro khi token bị lộ. Tuy nhiên, nếu không có cơ chế làm mới phiên an toàn và bảo vệ endpoint đăng nhập, hệ thống sẽ gặp 3 vấn đề thực tế:
1. Người dùng bị đăng xuất cưỡng bức sau mỗi 15 phút.
2. Nếu cấp Refresh Token dài hạn (7 ngày) mà dùng đi dùng lại một chuỗi cố định hoặc lưu bản rõ (plaintext) trong DB/Redis, kẻ tấn công đánh cắp được Refresh Token có thể duy trì quyền truy cập trái phép suốt 7 ngày.
3. Endpoint `POST /api/v1/auth/login` dễ bị tấn công vét cạn mật khẩu (Brute-Force / Credential Stuffing) hoặc làm nghẽn CPU do hàm băm `BCrypt` (strength 10).

Story 3.8 giải quyết trọn vẹn cả 3 bài toán trên thông qua `RefreshTokenStore` và `LoginRateLimiter`.

## 2. Kiến trúc Xoay vòng Refresh Token & Phát hiện Tái sử dụng (`RefreshTokenStore`)
- **Opaque Token 256-bit & Lưu trữ Băm SHA-256 một chiều:**
  - Khi đăng nhập thành công, hệ thống sinh 32 bytes ngẫu nhiên mật mã học (`SecureRandom`), mã hóa `Base64URL` không padding để tạo `refreshToken`.
  - Hệ thống **không bao giờ lưu bản rõ** của Refresh Token trong bộ nhớ hay Redis; chỉ lưu khóa `rt:{sha256Hex(rawToken)}` gắn với `{userId, familyId, expiresAt}` có TTL **7 ngày (`604800s`)**.
  - `refreshToken` được trả về đồng thời trong JSON `TokenResponse` (phục vụ tích hợp API / Swagger) và trong header `Set-Cookie: refresh_token=<token>; Path=/api/v1/auth; Max-Age=604800; HttpOnly; Secure; SameSite=Strict` (bảo vệ trình duyệt khỏi XSS và CSRF).
- **One-Time Rotation & Reuse Detection theo Họ Token (`familyId`):**
  - Mỗi lần đăng nhập tạo một `familyId` (UUID) mới cho chuỗi phiên đó.
  - Khi client gọi `POST /api/v1/auth/refresh` với token `R1`:
    1. `R1` bị xóa nguyên tử khỏi tập token đang hoạt động (`rt:{hash}`) và được ghi vào tập đã tiêu thụ (`rt:used:{hash} -> familyId`).
    2. Hệ thống kiểm tra lại trạng thái `AppUser` trong PostgreSQL (`user.isEnabled()`); nếu tài khoản đã bị Admin vô hiệu hóa sau khi đăng nhập, toàn bộ `familyId` bị thu hồi ngay lập tức.
    3. Hệ thống phát hành Access Token mới kèm Refresh Token mới `R2` thuộc **cùng `familyId`**.
  - **Phát hiện đánh cắp (Reuse Detection):** Nếu `R1` bị gửi lại lần thứ hai (do kẻ tấn công dùng lại token cũ đã đánh cắp, hoặc do người dùng hợp pháp dùng `R1` sau khi kẻ tấn công đã xoay vòng sang `R2`), `RefreshTokenStore` phát hiện `R1` nằm trong `rt:used:{hash}`:
    - Lập tức đánh dấu thu hồi toàn bộ họ token `rt:revoked:{familyId}` (vô hiệu hóa ngay lập tức `R2` và mọi token con cháu trong họ đó).
    - Xóa cookie `refresh_token` (`Max-Age=0`) và trả về `401 Unauthorized` chuẩn RFC 9457 `ProblemDetail` với mã lỗi `TOKEN_REUSED`.
- **Đăng xuất thu hồi phiên thật (`POST /api/v1/auth/logout`):**
  - Cho phép gọi ngay cả khi Access Token (15 phút) vừa hết hạn, nhận `refreshToken` từ cookie hoặc body, thu hồi toàn bộ `familyId` và xóa cookie `refresh_token` (`204 No Content`).

## 3. Giới hạn Tần suất Đăng nhập Chống Brute-Force (`LoginRateLimiter`)
- Theo dõi số lần đăng nhập thất bại liên tiếp theo khóa chuẩn hóa `rl:login:{username.trim().toLowerCase()}|{clientIp}` trong cửa sổ **5 phút (`300s`)**.
- Kiểm tra `loginRateLimiter.checkAllowed(...)` được đặt ở **dòng đầu tiên** của `AuthService.login()` — **trước khi** gọi `BCryptPasswordEncoder.matches()` hay truy vấn cơ sở dữ liệu. Khi vượt ngưỡng **5 lần sai liên tiếp**, lần thử thứ 6 bị chặn ngay lập tức với `429 Too Many Requests` (`code = "TOO_MANY_REQUESTS"`) kèm header chuẩn HTTP `Retry-After: <seconds>`, bảo vệ CPU khỏi bị tấn công cạn kiệt tài nguyên bằng BCrypt.
- Nếu người dùng đăng nhập thành công trước khi chạm ngưỡng 5 lần, bộ đếm lỗi được xóa sạch (`reset`).

## 4. Hạ tầng Kép Redis + In-Memory Fallback
Cả `RefreshTokenStore` và `LoginRateLimiter` đều hỗ trợ 2 chế độ vận hành:
- Khi `app.redis.enabled=true`: lưu trữ phân tán trên Redis (`StringRedisTemplate`) với TTL tự động và bọc `try/catch (RuntimeException)` để tự động hạ cấp về `ConcurrentHashMap` trong bộ nhớ nếu Redis gặp sự cố kết nối lúc chạy.
- Khi `app.redis.enabled=false`: vận hành đầy đủ 100% tính năng trên bộ nhớ trong mà không cần phụ thuộc hạ tầng ngoài.

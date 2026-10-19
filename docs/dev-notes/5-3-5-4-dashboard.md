# Story 5.3 & 5.4 — Giao diện Điều hành Web (Core Banking Operations Console)

## 1. Tóm tắt
- **Story:** `5.3 — Dashboard Shell & Overview` và `5.4 — Dashboard Transactions, Alerts & Reports`
- **Mã yêu cầu:** `NC-11`, `FR-26`, `L1-06`, `L1-08`, `L1-09`, `L1-10`, `L2-01..L2-04`, `L3-01..L3-08`, `NC-02`, `E-02`
- **PR:** `#44` (`feat/5-3-5-4-dashboard`)
- **Mục tiêu:** Cung cấp giao diện Web điều hành trực quan (`frontend/index.html`, `frontend/app.js` và đóng gói trực tiếp vào `backend/src/main/resources/static/`) kết nối tới các REST API thật của hệ thống để Hội đồng chấm thi trải nghiệm toàn bộ các luồng nghiệp vụ Level 1, Level 2 và Level 3 ngay trên trình duyệt tại `http://localhost:8080/` mà không cần cấu hình Postman hay gõ `curl`.

---

## 2. Đã xây dựng gì

1. **Khung Giao diện, Xác thực JWT & Tự động Làm mới Token (`frontend/index.html`, `frontend/app.js`):**
   - Hỗ trợ đăng nhập nhanh 1-click cho 3 tài khoản mẫu (`admin`, `cust.an`, `cust.binh`) hoặc nhập tài khoản/mật khẩu tùy chọn (`POST /api/v1/auth/login`).
   - Lưu `accessToken` thuần trong bộ nhớ JavaScript (`let accessToken = null`), tuyệt đối không ghi vào `localStorage` hay `sessionStorage` để chống đánh cắp token dài hạn qua XSS.
   - Hàm `apiFetch(url, options)` tự động đính kèm `Authorization: Bearer <accessToken>` và tự động gọi `POST /api/v1/auth/refresh` (kèm cookie `HttpOnly` `refresh_token`) để làm mới phiên và thử lại request khi gặp `401 Unauthorized`.
   - Hiển thị thông báo lỗi chuẩn RFC 9457 `ProblemDetail` (`errorCode`, `detail`, `traceId`).
2. **Tổng quan KPI, Phân quyền Tài khoản & Thống kê (`Overview`):**
   - Tự động gọi `GET /api/v1/accounts` (cho `ADMIN`) hoặc `GET /api/v1/me/accounts` (cho `CUSTOMER`) để hiển thị danh sách tài khoản theo đúng quyền sở hữu (`L2-01`).
   - Tải song song (`Promise.all`) thống kê phân tầng số dư `GET /api/v1/statistics/balance-tiers` (`HIGH`/`MEDIUM`/`LOW`, `L1-09`), thống kê khách hàng theo địa điểm `GET /api/v1/statistics/customers-by-location` (`L1-10`), báo cáo định kỳ Quý `GET /api/v1/reports/transactions?period=QUARTER` (`L3-01`, `L3-02`), chuỗi xu hướng `GET /api/v1/reports/trends` (`L3-08`), và trạng thái `/actuator/health` (`L3-09`).
3. **Thực hiện Giao dịch, Xem trước Phí & Kiểm chứng `Idempotency-Key` (`L1-08`, `NC-02`):**
   - Tự động gọi `GET /api/v1/fees/preview?type=...&amount=...` khi chọn loại giao dịch hoặc nhập số tiền để hiển thị phí dự kiến.
   - Tự sinh UUID (`crypto.randomUUID()`) cho header `Idempotency-Key` và cho phép bấm gửi lại cùng một key để kiểm chứng phản hồi `Idempotent-Replayed: true` (`⚡ [IDEMPOTENT REPLAY - Không trừ tiền lần 2]`).
4. **Tìm kiếm Giao dịch Đa bộ lọc (`L2-03`, `L2-04`):**
   - Hỗ trợ lọc kết hợp theo `accountId`, `type`, `status`, `minAmount`, `maxAmount`, `fromDate`, `toDate` qua `GET /api/v1/transactions`.
5. **Quản lý Cảnh báo Gian lận & Quét Lịch sử (`L3-03`, `L3-04`):**
   - Hiển thị danh sách `GET /api/v1/alerts`, hỗ trợ chuyển trạng thái theo máy trạng thái hữu hạn (`NEW -> INVESTIGATING -> RESOLVED | FALSE_POSITIVE`) qua `POST /api/v1/alerts/{id}/status` kèm `version` kiểm soát đồng thời lạc quan, và kích hoạt quét lịch sử bằng SQL Window Function `POST /api/v1/fraud/scans`.
6. **Xuất Báo cáo Tài chính Excel & PDF (`L3-07`, `L3-08`):**
   - Tải trực tiếp tệp báo cáo `.xlsx` (3 sheets + 3 biểu đồ PNG) và `.pdf` (nhúng phông tiếng Việt `DejaVuSans` + 3 biểu đồ PNG) từ `GET /api/v1/reports/export` qua `Blob` URL có xác thực `Bearer`.

---

## 3. Tại sao chọn thiết kế này & Phương án đã cân nhắc

| Phương án | Ưu điểm | Nhược điểm | Quyết định |
|---|---|---|---|
| **A. Vanilla HTML5/ES6 đóng gói trực tiếp vào `classpath:/static/` + giữ bản gốc tại `frontend/`** | Chạy ngay với 1 lệnh `docker compose up --build` hoặc `./mvnw spring-boot:run` tại `http://localhost:8080/` (cùng origin với API, không vướng CORS preflight hay cookie `SameSite=Strict`), đồng thời vẫn deploy độc lập `frontend/` lên Vercel khi cần | Không dùng framework SPA lớn | **Chọn** — zero-config cho người chấm thi, nhẹ (< 30 KB), khởi động tức thì |
| **B. Ứng dụng SPA tách rời bắt buộc chạy `npm run dev` ở cổng `3000`** | Có bundler riêng | Người chấm phải cài Node.js hoặc chạy thêm container thứ 4; phát sinh cấu hình CORS phức tạp cho cookie `HttpOnly` | Loại cho bản đóng gói 1 lệnh |

---

## 4. Bẫy kỹ thuật (Gotchas) & Cách xử lý

1. **Chống DOM XSS khi render dữ liệu giao dịch/khách hàng vào bảng HTML:**
   - Mọi chuỗi lấy từ JSON API (`tier`, `location`, `referenceNo`, `status`, `ruleCode`, `severity`) đều đi qua hàm `esc(val)` chuyển đổi `&`, `<`, `>`, `"`, `'` trước khi đưa vào `innerHTML`.
2. **Tải file nhị phân (`.xlsx`, `.pdf`) từ endpoint yêu cầu `Authorization: Bearer`:**
   - Thẻ `<a href="...">` thông thường không gửi được header `Authorization: Bearer` (vì token nằm trong biến bộ nhớ JS chứ không nằm trong cookie). Giải pháp: dùng `apiFetch(url)` lấy `await res.blob()`, tạo `URL.createObjectURL(blob)` để kích hoạt tải xuống rồi gọi `URL.revokeObjectURL` giải phóng bộ nhớ ngay.
3. **Đảm bảo `Dockerfile` multi-stage luôn có tài nguyên tĩnh:**
   - Vì `backend/Dockerfile` chạy với build context tại `backend/`, việc đồng bộ `frontend/index.html` và `frontend/app.js` vào `backend/src/main/resources/static/` (và kiểm tra tự động bằng `DeploymentConfigTest`) đảm bảo JAR trong container luôn phục vụ đầy đủ giao diện tại `/`.

---

## 5. Kiểm chứng

```bash
cd backend && ./mvnw -B test -Dtest=DeploymentConfigTest
```
- `DeploymentConfigTest` (`4/4` tests pass) kiểm chứng `frontend/{index.html,app.js}` và `backend/src/main/resources/static/{index.html,app.js}` đồng bộ 100%, tích hợp đầy đủ các API Level 1–3 và hàm `esc()` chống XSS.

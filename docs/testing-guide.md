# Hướng dẫn kiểm thử và chạy demo

Tài liệu này dành cho người muốn tự kiểm tra hệ thống: chạy local, chạy script test toàn bộ API, test tải và đồng thời, thử trên Swagger và trên giao diện web.

| Cách kiểm tra | Công cụ | Thời gian | Kiểm được gì |
|---|---|---|---|
| Test tự động trong code | `./mvnw verify` (chạy trên CI mỗi PR) | vài phút | Hơn 500 test: unit, PostgreSQL 17 thật (Testcontainers), ArchUnit, coverage ≥ 80% |
| Test toàn bộ API qua HTTP | [`scripts/api-test.sh`](../scripts/api-test.sh) | ~10 giây | 48/48 operation trong Swagger, kể cả các case lỗi |
| Test tải và đồng thời | [`scripts/load-test.sh`](../scripts/load-test.sh) | ~10 giây | Tạo hàng loạt khách hàng, chuyển tiền đồng thời hai chiều, tiền không lệch |
| Benchmark 1 triệu dòng | [`perf/seed-large.sql`](../perf/seed-large.sql) + [`perf/RESULTS.md`](../perf/RESULTS.md) | ~5 phút | `EXPLAIN ANALYZE` trước/sau index |
| Thử tay | Swagger UI và giao diện web | tùy ý | Mục 4 và 5 bên dưới |

## 1. Khởi chạy

**Docker Compose** (PostgreSQL 17 + Redis 7 + API):

```bash
docker compose up -d --build
```

**Không Docker** (PostgreSQL 17 cài sẵn, Java 21):

```bash
createdb core_banking
cd backend
SPRING_PROFILES_ACTIVE=local \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/core_banking \
SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD= \
./mvnw spring-boot:run
```

Sau khi chạy:

| Địa chỉ | Nội dung |
|---|---|
| http://localhost:8080/ | Giao diện vận hành (Operations Console) |
| http://localhost:8080/swagger-ui/index.html | Swagger UI |
| http://localhost:8080/actuator/health | Health check |

Tài khoản mẫu (dữ liệu giả lập): `admin` / `Admin@123` (ADMIN), `cust.an` và `cust.binh` / `Customer@123` (CUSTOMER).

## 2. Script test toàn bộ API

```bash
scripts/api-test.sh                                   # mặc định http://localhost:8080
BASE_URL=https://<host> scripts/api-test.sh           # chạy vào môi trường khác
scripts/api-test.sh -v                                # in thêm body response
```

Cần `curl` và `jq`. Mỗi lần chạy tự tạo dữ liệu riêng nên chạy lại bao nhiêu lần cũng được. Script đọc `/v3/api-docs` lúc chạy và báo số operation đã gọi tới, nên khi thêm endpoint mới mà chưa có test thì sẽ hiện trong mục `Not covered`.

| Nhóm | Case thành công | Case lỗi phải bị chặn |
|---|---|---|
| 1. Xác thực | Đăng nhập, xoay vòng refresh token, đăng xuất | Sai mật khẩu 401, thiếu token 401, token giả 401, dùng lại refresh token cũ 401 `TOKEN_REUSED`, refresh sau khi logout 401 |
| 2. Loại khách hàng | Xem, sửa hạn mức `INDIVIDUAL` | Hạn mức ngày nhỏ hơn hạn mức lệnh 400, mã không tồn tại 404 |
| 3. Khách hàng | Tạo, xem, tìm theo tên + địa điểm, sửa | Trùng CCCD 409, CCCD sai định dạng 400, sửa với `version` cũ 409 (optimistic lock), không tồn tại 404 |
| 4. Tài khoản | Mở 3 tài khoản (số 12 chữ số), xem, lọc, sửa hạn mức | Khách hàng không tồn tại 404, hạn mức 0 400, `version` cũ 409 |
| 5. Giao dịch | Nạp, rút, chuyển, thanh toán, xem trước phí, sửa mô tả, đảo giao dịch, tìm kiếm nhiều bộ lọc, phân trang offset và keyset cursor | Gửi lại cùng `Idempotency-Key` trả lại giao dịch cũ (không trừ tiền lần 2), cùng key khác nội dung 422, thiếu key 400, số tiền 0 400, không đủ số dư 422, vượt hạn mức 422, tự chuyển cho mình 400, đảo lần 2 409, cursor giả 400 |
| 6. Trạng thái tài khoản | Khoá, mở khoá, xem lịch sử, đóng tài khoản số dư 0 (vẫn giữ bản ghi `CLOSED`) | Nạp vào tài khoản đang khoá 422, thiếu lý do 400, đóng tài khoản còn tiền 409 |
| 7. Giao dịch định kỳ | Tạo, xem, lọc, tạm dừng, chạy lại, huỷ | Thiếu tài khoản đích 400, tạm dừng 2 lần 409 |
| 8. Quyền khách hàng | `cust.an` xem hồ sơ và tài khoản của mình | Xem tài khoản, lịch sử, giao dịch của người khác 403; gọi API quản trị 403 |
| 9. Phát hiện bất thường | Chuyển ≥ 50 triệu sinh cảnh báo `AMOUNT_THRESHOLD`; 6 lệnh trong 60 giây sinh `VELOCITY`; xử lý cảnh báo `NEW → INVESTIGATING → RESOLVED`; quét lịch sử bằng SQL window function | Nạp tiền (tiền vào) không sinh cảnh báo, quét lại không tạo trùng, chuyển ngược `RESOLVED → INVESTIGATING` 409, trường lạ 400, khách hàng gọi quét 403 |
| 10. Báo cáo | Thống kê nhóm số dư và địa điểm, báo cáo tuần/quý/năm, xu hướng, xuất Excel và PDF (kiểm tra file thật) | `from > to` 400, định dạng CSV 400, khách hàng xuất báo cáo 403 |
| 11. Nhật ký kiểm toán | Xem và lọc theo người thao tác, ngày | |
| 12. Xoá khách hàng | Xoá mềm | Xem lại 404, xoá lần 2 404 |

Kết quả mẫu (local, dữ liệu seed):

```text
Summary
  Checks : 137 passed, 0 failed
  OpenAPI operations covered: 48/48
```

File Excel và PDF xuất ra nằm trong `./api-test-output/`.

## 3. Test tải và đồng thời

```bash
scripts/load-test.sh                                          # 100 khách hàng, 300 lệnh chuyển, 25 luồng
CUSTOMERS=500 TRANSFERS=1000 PARALLEL=50 scripts/load-test.sh
CUSTOMERS=20 TRANSFERS=500 PARALLEL=50 HOT_ACCOUNTS=2 scripts/load-test.sh   # ca khó nhất: 2 tài khoản chuyển qua lại
```

Script chạy 3 pha:

1. Tạo song song N khách hàng, mỗi người một tài khoản và nạp 10.000.000 VND (3 API mỗi người).
2. Bắn T lệnh chuyển tiền đồng thời theo cả hai chiều giữa một nhóm nhỏ tài khoản. Đây là tình huống dễ deadlock nhất (A→B và B→A cùng lúc).
3. Kiểm tra: tổng số dư sau = tổng trước − tổng phí đã thu, không có lỗi 5xx; in độ trễ p50/p95/p99.

Kết quả đo trên MacBook (Intel i7-9750H, PostgreSQL 17 local, không Redis):

| Kịch bản | Kết quả |
|---|---|
| 100 khách hàng, 300 lệnh, 10 tài khoản, 25 luồng | 300/300 thành công, p95 62 ms, tiền khớp từng đồng |
| 500 lệnh dồn vào 2 tài khoản, 50 luồng | 500/500 thành công, 0 deadlock, p95 440 ms (do phải xếp hàng khoá cùng 2 dòng), tiền khớp từng đồng |

Nên chạy test tải ở local hoặc trong cùng mạng với server. Qua bản demo online (đi qua đường hầm HTTPS công cộng) độ trễ do đường truyền chiếm phần lớn, một số kết nối có thể bị cắt (`000`), nhưng kiểm tra tổng tiền vẫn phải khớp.

Lý do không deadlock: mọi lệnh chuyển khoá hai tài khoản theo thứ tự ID tăng dần (`SELECT ... FOR UPDATE`), xem [ADR](adr.md) và `TransferConcurrencyIT`.

## 4. Thử trên Swagger UI

1. Mở `/swagger-ui/index.html`.
2. `POST /api/v1/auth/login` → **Try it out** → body `{"username":"admin","password":"Admin@123"}` → **Execute**. Copy `accessToken`.
3. Bấm **Authorize** (góc phải trên), dán token, **Authorize**. Swagger giữ token cho mọi request sau.
4. Các API ghi tiền (`/transactions/deposits`, `withdrawals`, `transfers`, `payments`) bắt buộc header `Idempotency-Key`: nhập một chuỗi bất kỳ, ví dụ `demo-001`. Bấm **Execute** lần 2 với cùng key: response giống hệt, header `Idempotent-Replayed: true`, số dư không đổi.
5. Muốn thử quyền khách hàng: login `cust.an`, **Authorize** lại bằng token mới, gọi `GET /api/v1/accounts/{id}` với tài khoản của người khác → 403.

Lỗi luôn trả theo chuẩn RFC 9457 (`application/problem+json`) có `code` và `requestId` để tra log.

## 5. Kịch bản thử trên giao diện web

| # | Thao tác | Kết quả mong đợi |
|---|---|---|
| 1 | Bấm đăng nhập nhanh **ADMIN** | Bốn ô tổng quan hiện số tài khoản, tổng số dư, số giao dịch, trạng thái `UP` |
| 2 | Khối giao dịch: chọn Chuyển khoản, nhập 15.000.000, bấm **Xem trước phí** | Hiện phí theo bậc |
| 3 | Bấm **Thực hiện giao dịch** 2 lần với cùng Idempotency-Key | Lần 2 trả lại giao dịch cũ, số dư chỉ trừ 1 lần |
| 4 | Rút số tiền lớn hơn số dư | Báo lỗi `INSUFFICIENT_BALANCE` |
| 5 | Khối cảnh báo: **Tải cảnh báo** rồi **Quét lịch sử** | Thấy 2 cảnh báo mẫu (`AMOUNT_THRESHOLD` HIGH, `VELOCITY` MEDIUM); quét lại không tạo trùng |
| 6 | Khối báo cáo: **Xuất Excel**, **Xuất PDF** | Tải về file `.xlsx` 3 sheet và `.pdf` có biểu đồ, tiếng Việt đúng dấu |
| 7 | Đăng nhập **cust.an** | Chỉ thấy tài khoản của mình; giao dịch từ tài khoản người khác bị 403 |
| 8 | Khối 7: **Truy vấn Keyset Cursor**, **Tải Nhật ký Kiểm toán** | Hai trang lịch sử theo con trỏ; các thao tác vừa làm xuất hiện trong audit log |

Tab **Quản lý Dữ liệu (CRUD)** (ADMIN). Mỗi thao tác hiện request và response thật ở khung nhật ký cuối trang:

| # | Thao tác | Kết quả mong đợi |
|---|---|---|
| 9 | Khối 8: nhập họ tên, CCCD `000` + 9 số, bấm **Lưu khách hàng** | `POST /customers → 201`, CCCD hiển thị đã che |
| 10 | Tạo lại với cùng CCCD | `409 DUPLICATE_RESOURCE` |
| 11 | Bấm **Sửa** một khách hàng, đổi tên, lưu | `PUT → 200`, version tăng 1 |
| 12 | Khối 9: **Mở tài khoản** cho khách hàng vừa tạo | Số tài khoản 12 chữ số |
| 13 | **Khoá** tài khoản rồi nạp tiền vào nó ở tab chính | `422 ACCOUNT_NOT_ACTIVE`; **Mở khoá**, **Lịch sử** thấy đủ các lần chuyển trạng thái |
| 14 | **Đóng** tài khoản còn tiền | `409 ACCOUNT_HAS_BALANCE`; tài khoản số dư 0 thì đóng được |
| 15 | Khối 10: nhập ID giao dịch, **Xem**, **Lưu mô tả**, **Đảo giao dịch** 2 lần | Lần 1 tạo bút toán `REVERSAL`, lần 2 `409 ALREADY_REVERSED` |
| 16 | Khối 11: **Sửa hạn mức** loại `INDIVIDUAL` | Hạn mức hiệu lực = min(hạn mức tài khoản, hạn mức loại khách hàng) |
| 17 | Khối 12: **Tạo lịch định kỳ**, **Tạm dừng**, **Chạy lại**, **Huỷ** | Trạng thái `ACTIVE → PAUSED → ACTIVE → CANCELLED` |
| 18 | Xoá khách hàng còn tài khoản | `409`; xoá khách hàng không còn tài khoản thì thành công (xoá mềm) |

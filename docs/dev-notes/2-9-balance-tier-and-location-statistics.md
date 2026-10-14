# Story 2.9: Thống kê nhóm số dư, khách hàng theo địa điểm và dữ liệu mẫu (`2-9-balance-tier-and-location-statistics`)

> **PR:** [#20](https://github.com/tsennguyen/corebank-java-assessment/pull/20) · **Mã yêu cầu:** `L1-09`, `L1-10`, `G-05`, `E-02`, `FR-11`, `FR-12`, `NFR-11`

## 1. Bối cảnh & Mục tiêu
Hoàn thiện 2 API báo cáo thống kê cốt lõi của Level 1 (`L1-09`, `L1-10`) cùng tập dữ liệu mẫu giả lập tự động nạp khi khởi động ở môi trường `local`/`prod`:
1. **`GET /api/v1/statistics/balance-tiers` (`L1-09`):** Thống kê số lượng tài khoản đang hoạt động (`status <> 'CLOSED'`) và tổng số giao dịch gắn với các tài khoản đó theo 3 nhóm số dư `LOW`, `MEDIUM`, `HIGH`.
2. **`GET /api/v1/statistics/customers-by-location` (`L1-10`):** Thống kê số lượng khách hàng chưa bị xóa mềm (`deleted_at IS NULL`) theo từng tỉnh/thành phố, sắp xếp giảm dần theo số lượng.
3. **Flyway Repeatable Seed (`R__demo_seed.sql`, `E-02`, `NFR-11`):** Khởi tạo 20 khách hàng giả lập, 12 tài khoản ngân hàng trải đều 3 bậc số dư và 16 giao dịch lịch sử rải đều 12 tháng.

## 2. Quyết định Thiết kế & Kiến trúc
- **Tổng hợp trực tiếp tại PostgreSQL qua `JdbcClient` (`G-05`):**
  - Thay vì kéo danh sách `Account` và `Transaction` lên bộ nhớ JVM rồi lặp bằng Java Stream (gây `OutOfMemoryError` hoặc $O(N)$ round-trips khi dữ liệu lên hàng triệu bản ghi), `StatisticsRepository` thực thi duy nhất **1 câu truy vấn CTE** kết hợp `CASE WHEN`, `UNION ALL` (gom cả hai chiều `source_account_id` và `target_account_id`, chỉ tính các giao dịch hợp lệ `status = 'COMPLETED' AND type <> 'REVERSAL'`) và `GROUP BY`.
  - Sử dụng bảng hằng số ảo `WITH tiers(tier, sort_order) AS (VALUES ('LOW', 1), ('MEDIUM', 2), ('HIGH', 3))` kết hợp `LEFT JOIN` và `EnumMap` ở tầng `StatisticsService` để đảm bảo API luôn trả đủ 3 phần tử theo thứ tự `LOW -> MEDIUM -> HIGH`, kể cả khi một nhóm có `0` tài khoản.
- **Tận dụng Partial Index & Chuẩn hóa khoảng trắng cho thống kê địa điểm (`L1-10`, `G-05`):**
  - Truy vấn `WHERE c.deleted_at IS NULL GROUP BY COALESCE(NULLIF(TRIM(c.location), ''), 'UNKNOWN')` kết hợp chuẩn hóa `trim()` ngay ở tầng Domain `Customer` giúp gom nhóm nhất quán và khớp với partial index `idx_customer_location ON core.customer (location) WHERE deleted_at IS NULL` (đã tạo ở migration `V2`).
- **Cô lập dữ liệu mẫu theo Profile (`NFR-11`, `E-02`):**
  - Cấu hình `spring.flyway.locations=classpath:db/migration,classpath:db/seed` được bật trong `application-local.yml` (và qua biến môi trường `FLYWAY_LOCATIONS` cho môi trường demo). Trong `application-test.yml` và mặc định của `application-prod.yml`, Flyway chỉ quét `classpath:db/migration` để giữ môi trường sạch tuyệt đối.
  - Mọi số tài khoản seed (`100800000018`..`100800000125`) đều tuân thủ thuật toán kiểm tra Luhn (`AccountNumberGenerator.calculateLuhnCheckDigit`), đồng bộ sequence động bằng `PERFORM setval(..., GREATEST(...))` và bảo toàn phương trình cân bằng sổ cái $\sum \text{account.balance} = \sum \text{transaction.system\_balance\_delta} = 1{,}401{,}193{,}000.00\text{ VND}$.

## 3. Các phương án đã cân nhắc
| Phương án | Ưu điểm | Nhược điểm | Quyết định |
|---|---|---|---|
| **A. JPA Entity + Java Stream grouping** | Dễ viết theo hướng đối tượng thuần túy | Tốn RAM $O(N)$, dễ dính lỗi N+1 khi đếm giao dịch của từng tài khoản | Loại bỏ (vi phạm `G-05` và `performance.md`) |
| **B. 1 câu SQL CTE qua `JdbcClient` + ngưỡng từ `BalanceTierProperties`** | Thực thi 1 round-trip tại DB, tận dụng index, ngưỡng `mediumThreshold`/`highThreshold` nhất quán 100% với `BalanceTierPolicy` | Câu SQL dài hơn | **Chọn** |

## 4. Hố bẫy & Kinh nghiệm (Gotchas)
1. **Cách tính giao dịch chuyển khoản nội bộ và loại trừ giao dịch đảo (`REVERSAL`):** Một giao dịch `TRANSFER` có cả `source_account_id` và `target_account_id`. Nhờ CTE `tx_per_account` dùng `UNION ALL` giữa cột nguồn và cột đích với điều kiện `status = 'COMPLETED' AND type <> 'REVERSAL'` rồi mới `JOIN` với `classified_accounts` (`WHERE status <> 'CLOSED'`), mỗi tài khoản được đếm đúng số giao dịch hoàn tất mà nó tham gia, không bị đếm khống gấp đôi khi có giao dịch bị đảo, đồng thời giao dịch lịch sử của các tài khoản đã đóng (`CLOSED`) tự động bị loại trừ.
2. **Đồng bộ `SELECT COALESCE(NULLIF(TRIM(...)))` và `GROUP BY`:** Việc kết hợp `NULLIF(TRIM(c.location), '')` với `COALESCE(..., 'UNKNOWN')` ở cả `SELECT` và `GROUP BY` đảm bảo cả giá trị `NULL`, chuỗi rỗng hay chuỗi có khoảng trắng thừa đều được gom nhóm chuẩn xác.

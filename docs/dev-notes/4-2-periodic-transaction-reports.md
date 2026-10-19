# Dev Note — Story 4.2: Báo cáo giao dịch định kỳ theo Tuần, Quý, Năm (`L3-01`, `L3-02`, `G-05`)

## 1. Bối cảnh và yêu cầu
Trong vận hành Core Banking, ban quản trị cần báo cáo tổng hợp giao dịch theo từng kỳ kinh doanh (`WEEK`, `QUARTER`, `YEAR`) trong khoảng ngày tùy chọn (`[from, to]`), trả về đầy đủ 6 chỉ số định lượng cho mỗi bucket thời gian:
- Số lượng giao dịch hợp lệ (`txCount`)
- Tổng doanh số giao dịch (`totalAmount`)
- Giá trị giao dịch trung bình (`averageAmount` — `L3-02`)
- Giao dịch lớn nhất (`maxAmount` — `L3-02`)
- Giao dịch nhỏ nhất (`minAmount` — `L3-02`)
- Tổng phí thu được (`totalFee`)

Đồng thời, báo cáo phải loại trừ hoàn toàn các giao dịch đã bị đảo (`status = 'REVERSED'`) và bút toán đảo (`type = 'REVERSAL'`), cắt kỳ chính xác theo múi giờ nghiệp vụ Việt Nam (`Asia/Ho_Chi_Minh`), và chạy tổng hợp trực tiếp trong PostgreSQL mà không tải dòng giao dịch thô lên bộ nhớ JVM (`G-05`, `NFR-2`).

## 2. Thiết kế và quyết định kỹ thuật

### 2.1. Tổng hợp trực tiếp trong PostgreSQL bằng `date_trunc` và `JdbcClient` (`ReportRepository`)
Thay vì dùng JPA tải danh sách `Transaction` lên Heap rồi tính toán bằng Java Stream (gây `OutOfMemoryError` khi kỳ báo cáo có hàng trăm nghìn giao dịch), `ReportRepository` thực thi duy nhất **1 câu truy vấn SQL tổng hợp**:

```sql
SELECT (date_trunc(CAST(:unit AS TEXT), t.created_at AT TIME ZONE CAST(:zone AS TEXT)))::date AS period_start,
       COUNT(*) AS tx_count,
       COALESCE(SUM(t.amount), 0.00) AS total_amount,
       COALESCE(ROUND(AVG(t.amount), 2), 0.00) AS average_amount,
       COALESCE(MAX(t.amount), 0.00) AS max_amount,
       COALESCE(MIN(t.amount), 0.00) AS min_amount,
       COALESCE(SUM(t.fee), 0.00) AS total_fee
FROM core.transaction t
WHERE t.status = 'COMPLETED'
  AND t.type <> 'REVERSAL'
  AND t.created_at >= :fromInstant
  AND t.created_at < :toExclusiveInstant
  AND (CAST(:type AS VARCHAR) IS NULL OR t.type = CAST(:type AS VARCHAR))
GROUP BY 1
ORDER BY 1 ASC
```

- **Chống SQL Injection trên đơn vị kỳ (`:unit`):** Tham số `period` trên HTTP query chỉ nhận giá trị từ enum `ReportPeriod` (`WEEK("week")`, `QUARTER("quarter")`, `YEAR("year")`). Chỉ chuỗi hằng từ `period.sqlUnit()` mới được truyền vào `date_trunc`.
- **Chuẩn hóa số học tiền tệ (`scale = 2`, `HALF_UP`):** Hàm `AVG(t.amount)` của PostgreSQL trên cột `NUMERIC(19,2)` trả về số thập phân dài (ví dụ `2333333.333333333333`). Câu truy vấn gọi `ROUND(AVG(t.amount), 2)` ngay tại SQL và chuẩn hóa lại `.setScale(2, RoundingMode.HALF_UP)` khi ánh xạ sang `TransactionReportBucketResponse`.

### 2.2. Quy đổi biên thời gian và cắt kỳ theo múi giờ `Asia/Ho_Chi_Minh` (`ReportService`)
- Cột `created_at` trong bảng `core.transaction` lưu kiểu `TIMESTAMPTZ` (UTC). Nếu gọi `date_trunc('quarter', t.created_at)` trực tiếp mà không quy đổi múi giờ, một giao dịch phát sinh lúc `2026-03-31T23:30:00Z` (tức `06:30` sáng ngày `01/04/2026` giờ Việt Nam — đầu Quý 2) sẽ bị xếp nhầm vào Quý 1.
- Biểu thức `t.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh'` chuyển thời điểm về giờ địa phương Việt Nam trước khi `date_trunc` cắt về đầu tuần (thứ Hai theo chuẩn ISO-8601), đầu quý (`01-01`, `04-01`, `07-01`, `10-01`) hoặc đầu năm (`01-01`).
- Khoảng ngày đầu vào `[from, to]` được `ReportService` quy đổi sang nửa khoảng `[from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())` để giữ nguyên tính chất SARGable (tận dụng trực tiếp chỉ mục B-tree trên cột `created_at`).

### 2.3. Kiểm soát biên đầu vào và bảo vệ tài nguyên (`<= 366` ngày)
- `ReportService.validateDateRange(from, to)` kiểm tra:
  1. `from` và `to` không được `null`.
  2. `!to.isBefore(from)` (nếu `to < from` → trả `400 VALIDATION_FAILED`).
  3. `ChronoUnit.DAYS.between(from, to) <= 366` (chặn truy vấn quét khoảng thời gian nhiều năm không giới hạn làm ảnh hưởng tài nguyên DB).
- Nếu client truyền `type = REVERSAL`, `ReportService` từ chối ngay với `400 VALIDATION_FAILED` vì giao dịch đảo chiều không phải doanh số nghiệp vụ.
- Phương thức `validateDateRange` và `getPeriodicTransactionReport` được thiết kế dùng chung cho `ReportDataService` (Story 4.5) và `ReportExporter` (Story 4.6 / 4.7).

## 3. Hiệu năng truy vấn và kiểm chứng chỉ mục (`performance.md`)

### 3.1. Số lượng câu lệnh SQL
Kiểm chứng tự động trong `PeriodicTransactionReportIT`:

| Luồng nghiệp vụ | Số câu SQL (`JdbcClient` / `HibernateStatistics`) | Giải thích |
|---|---|---|
| `GET /api/v1/reports/transactions?period=QUARTER&from=...&to=...` | **1 câu SQL (`JdbcClient`) · 0 câu JPA** | Toàn bộ 6 phép tính (`COUNT`, `SUM`, `AVG`, `MAX`, `MIN`, `SUM(fee)`) chạy trong 1 round-trip duy nhất tới PostgreSQL; không khởi tạo bất kỳ JPA entity nào |

### 3.2. Kế hoạch thực thi (`EXPLAIN (ANALYZE, BUFFERS)`)
Truy vấn báo cáo kỳ tận dụng chỉ mục kết hợp `idx_tx_status_created ON core.transaction (status, created_at DESC)` (hoặc `idx_tx_type_created ON core.transaction (type, created_at DESC)` khi lọc theo `type`):

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT (date_trunc('quarter', t.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh'))::date AS period_start,
       COUNT(*) AS tx_count,
       COALESCE(SUM(t.amount), 0.00) AS total_amount,
       COALESCE(ROUND(AVG(t.amount), 2), 0.00) AS average_amount,
       COALESCE(MAX(t.amount), 0.00) AS max_amount,
       COALESCE(MIN(t.amount), 0.00) AS min_amount,
       COALESCE(SUM(t.fee), 0.00) AS total_fee
FROM core.transaction t
WHERE t.status = 'COMPLETED'
  AND t.type <> 'REVERSAL'
  AND t.created_at >= '2026-01-01T00:00:00+07:00'
  AND t.created_at < '2026-07-01T00:00:00+07:00'
GROUP BY 1
ORDER BY 1 ASC;
-- GroupAggregate
--   Group Key: ((date_trunc('quarter'::text, (t.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh'::text)))::date)
--   -> Sort
--        -> Index Scan using idx_tx_status_created on transaction t
--             Index Cond: (((status)::text = 'COMPLETED'::text) AND (created_at >= '2025-12-31 17:00:00+00'::timestamptz) AND (created_at < '2026-06-30 17:00:00+00'::timestamptz))
--             Filter: ((type)::text <> 'REVERSAL'::text)
```

## 4. Các phương án đã cân nhắc (Alternatives Considered)

1. **Dùng Spring Data JPA `@Query` hoặc Specification tải `List<Transaction>` lên Java rồi gom nhóm bằng `Collectors.groupingBy`:**
   - *Bác bỏ:* Với 100.000 – 1.000.000 giao dịch trong năm, việc nạp toàn bộ thực thể lên bộ nhớ ứng dụng gây tốn băng thông mạng và `OutOfMemoryError` trên container giới hạn 512MB RAM (`NFR-10`). Đẩy `GROUP BY date_trunc(...)` xuống PostgreSQL giúp kích thước dữ liệu trả về chỉ còn $O(\text{số bucket})$ (tối đa 53 dòng cho báo cáo tuần trong 1 năm).
2. **Tạo bảng Materialized View cố định cho từng kỳ:**
   - *Bác bỏ:* Materialized View cần cơ chế `REFRESH MATERIALIZED VIEW` định kỳ (gây trễ dữ liệu thực tế sau khi vừa nạp/chuyển tiền) và không linh hoạt khi Admin lọc theo khoảng ngày `[from, to]` kèm bộ lọc `type` tùy ý. Truy vấn trực tiếp qua chỉ mục `(status, created_at DESC)` vừa phản ánh số liệu thời gian thực vừa đạt thời gian phản hồi tính bằng mili-giây.

## 5. Bẫy kỹ thuật và bài học (Gotchas)

- **Bẫy kiểu tham số `NULL` trong PostgreSQL JDBC (`PSQLException: could not determine data type of parameter`):** Khi tham số tùy chọn `type` là `null`, nếu viết `:type IS NULL OR t.type = :type` mà không chỉ định kiểu SQL (`Types.VARCHAR`) và `CAST(:type AS VARCHAR)`, PostgreSQL driver sẽ báo lỗi không suy luận được kiểu của tham số ẩn danh. Trong `ReportRepository`, chúng tôi vừa truyền `Types.VARCHAR` tường minh vừa dùng `CAST(:type AS VARCHAR)` trong SQL.
- **Bẫy `AT TIME ZONE` trong biểu thức `WHERE` làm hỏng Index Scan:** Nếu viết `WHERE (t.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date BETWEEN :from AND :to`, PostgreSQL phải tính biểu thức trên từng dòng và không dùng được chỉ mục B-tree trên `created_at`. Thay vào đó, chỉ dùng `AT TIME ZONE` ở mệnh đề `SELECT` / `GROUP BY`, còn mệnh đề `WHERE` giữ nguyên `t.created_at >= :fromInstant AND t.created_at < :toExclusiveInstant` (đã quy đổi sang `OffsetDateTime` UTC từ đầu ngày giờ Việt Nam ở Java).

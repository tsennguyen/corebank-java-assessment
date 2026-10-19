# Dev Note — Story 4.5: Chuỗi xu hướng giao dịch, phí, số dư cuối kỳ & Vẽ biểu đồ Headless (`L3-08`, `L3-10`, `FR-20`, `ADR-013`, `ADR-014`)

## 1. Bối cảnh và yêu cầu
Để phục vụ trang quản trị phân tích xu hướng (`GET /api/v1/reports/trends`) và bộ xuất báo cáo trực quan nhúng biểu đồ (`PDF` / `XLSX` / `HTML` ở Story 4.6 & 4.7), hệ thống cần:
1. **Truy vấn chuỗi thời gian liên tục (`DAY`, `WEEK`, `MONTH`)** trong khoảng ngày `[from, to]` (tối đa `366` ngày, theo múi giờ `Asia/Ho_Chi_Minh`) trả về danh sách `TrendPointResponse(bucket, txCount, totalAmount, totalFee, closingBalance)`.
2. **Tái tạo số dư cuối kỳ (`closingBalance`) chính xác về mặt toán học ngay trong PostgreSQL (`L3-08`, `ADR-013`):**
   - **Toàn hệ thống (`accountId = null`):** Vì sổ cái giao dịch là bất biến và mỗi dòng trong `core.transaction` lưu `system_balance_delta` (`+amount` khi `DEPOSIT`, `-amount - fee` khi `WITHDRAWAL`/`PAYMENT`, `-fee` khi `TRANSFER`, và bù trừ ngược khi `REVERSAL`), số dư cuối mỗi bucket $b$ phải bằng:
     $$\text{closingBalance}(b) = \sum_{a \in \text{account}} \text{balance}(a) - \sum_{t.\text{created\_at} \ge \text{toExclusive}} t.\text{system\_balance\_delta} - \sum_{\text{bucket}(t) > b} t.\text{system\_balance\_delta}$$
   - **Một tài khoản cụ thể (`accountId != null`):** Lấy `balance_after` của giao dịch cuối cùng thuộc tài khoản đó trong mỗi bucket (`ORDER BY created_at DESC, id DESC`); với các bucket trống không phát sinh giao dịch, kế thừa (`forward-fill`) số dư cuối kỳ của bucket liền trước (hoặc số dư đầu kỳ trước `fromInstant`).
3. **Tải dữ liệu báo cáo song song trên Java 21 Virtual Threads (`L3-10`, `ReportDataService`):** Khi chuẩn bị dữ liệu cho bộ xuất báo cáo (`ReportBundle`), hai truy vấn độc lập (`periodicSummary` và `trendSeries`) được chạy đồng thời bằng `CompletableFuture.supplyAsync(..., virtualThreadExecutor)`.
4. **Vẽ 3 biểu đồ PNG `800x400` ở chế độ Headless (`ADR-014`, `ChartRenderer`):** Sử dụng `JFreeChart 1.5.5` với `-Djava.awt.headless=true` để kết xuất ảnh PNG hợp lệ (kể cả khi danh sách điểm xu hướng rỗng) trong container Alpine JRE không có X11.

## 2. Thiết kế và quyết định kỹ thuật

### 2.1. Tái tạo chuỗi số dư cuối kỳ toàn hệ thống bằng `generate_series` và SQL Window Function (`ReportRepository`)
Thay vì chạy $N$ câu truy vấn cho $N$ ngày hoặc tải toàn bộ lịch sử giao dịch lên JVM, `ReportRepository.findSystemTrendSeries` thực thi **1 câu truy vấn SQL duy nhất** kết hợp 4 CTE (`buckets`, `tx_by_bucket`, `post_range_delta`, `current_system_balance`) và hàm cửa sổ tích lũy ngược (`ROWS BETWEEN 1 FOLLOWING AND UNBOUNDED FOLLOWING`):

```sql
WITH buckets AS (
    SELECT gs::date AS bucket
    FROM generate_series(
        date_trunc(CAST(:unit AS TEXT), CAST(:fromDate AS timestamp)),
        date_trunc(CAST(:unit AS TEXT), CAST(:toDate AS timestamp)),
        CAST(:step AS interval)
    ) AS gs
),
tx_by_bucket AS (
    SELECT (date_trunc(CAST(:unit AS TEXT), t.created_at AT TIME ZONE CAST(:zone AS TEXT)))::date AS bucket,
           COUNT(*) FILTER (WHERE t.status = 'COMPLETED' AND t.type <> 'REVERSAL') AS tx_count,
           COALESCE(SUM(t.amount) FILTER (WHERE t.status = 'COMPLETED' AND t.type <> 'REVERSAL'), 0.00) AS total_amount,
           COALESCE(SUM(t.fee) FILTER (WHERE t.status = 'COMPLETED' AND t.type <> 'REVERSAL'), 0.00) AS total_fee,
           COALESCE(SUM(t.system_balance_delta), 0.00) AS bucket_delta
    FROM core.transaction t
    WHERE t.created_at >= :fromInstant
      AND t.created_at < :toExclusiveInstant
    GROUP BY 1
),
post_range_delta AS (
    SELECT COALESCE(SUM(t.system_balance_delta), 0.00) AS after_to_delta
    FROM core.transaction t
    WHERE t.created_at >= :toExclusiveInstant
),
current_system_balance AS (
    SELECT COALESCE(SUM(a.balance), 0.00) AS total_balance
    FROM core.account a
)
SELECT b.bucket AS bucket,
       COALESCE(tb.tx_count, 0) AS tx_count,
       COALESCE(tb.total_amount, 0.00) AS total_amount,
       COALESCE(tb.total_fee, 0.00) AS total_fee,
       (csb.total_balance
        - prd.after_to_delta
        - COALESCE(SUM(COALESCE(tb.bucket_delta, 0.00)) OVER (
            ORDER BY b.bucket ASC
            ROWS BETWEEN 1 FOLLOWING AND UNBOUNDED FOLLOWING
          ), 0.00)
       ) AS closing_balance
FROM buckets b
LEFT JOIN tx_by_bucket tb ON tb.bucket = b.bucket
CROSS JOIN current_system_balance csb
CROSS JOIN post_range_delta prd
ORDER BY b.bucket ASC
```

- **Tách biệt chỉ số doanh số và biến động số dư:** Trong `tx_by_bucket`, các chỉ số `tx_count`, `total_amount`, `total_fee` dùng mệnh đề `FILTER (WHERE t.status = 'COMPLETED' AND t.type <> 'REVERSAL')` để loại trừ giao dịch đã đảo và bút toán đảo; trong khi `SUM(t.system_balance_delta)` cộng cả giao dịch gốc (`REVERSED`) lẫn bút toán đảo (`REVERSAL`) vì hai dòng này có `system_balance_delta` đối dấu tại đúng thời điểm phát sinh.
- **Bù trừ giao dịch phát sinh sau kỳ báo cáo (`post_range_delta`):** Khi Admin xem lại báo cáo quá khứ, mọi biến động phát sinh từ `:toExclusiveInstant` tới hiện tại đều được trừ lùi qua `prd.after_to_delta`, đảm bảo `closingBalance` của một kỳ đã khép lại là bất biến theo thời gian.

### 2.2. Chuỗi xu hướng theo từng tài khoản với `DISTINCT ON` và Forward-Fill (`findAccountTrendSeries`)
- Với một `accountId` cụ thể, CTE `last_tx_per_bucket` sử dụng `SELECT DISTINCT ON (bucket) ... ORDER BY bucket ASC, t.created_at DESC, t.id DESC` để lấy chính xác `balance_after` của giao dịch cuối cùng trong mỗi bucket.
- Nếu bucket không có giao dịch (`bucket_closing_balance IS NULL`), tầng ánh xạ kết quả kế thừa (`carryBalance`) từ bucket liền trước; nếu ngay những bucket đầu tiên trong khoảng `[from, to]` chưa có giao dịch, số dư khởi tạo (`initial_balance`) được suy ra từ giao dịch gần nhất trước `fromInstant` hoặc suy ngược từ số dư đầu tiên trong kỳ.

### 2.3. Điều phối tải dữ liệu song song trên Virtual Threads (`ReportDataService`)
- `ReportDataService.loadReportBundle(period, from, to, type, accountId)` sử dụng `@Qualifier("applicationTaskExecutor")` (executor Virtual Threads cấu hình trong `AsyncConfig`) kết hợp `CompletableFuture.supplyAsync(...)` để chạy song song `getPeriodicTransactionReport` và `getTrendSeries`.
- Mọi ngoại lệ nghiệp vụ (`BusinessRuleException`, `ResourceNotFoundException`) phát sinh trong nhánh bất đồng bộ đều được bóc tách từ `CompletionException` để `GlobalExceptionHandler` trả về đúng RFC 9457 `ProblemDetail`.

### 2.4. Kết xuất biểu đồ PNG `800x400` Headless (`ChartRenderer`)
- `ChartRenderer` đặt `System.setProperty("java.awt.headless", "true")` và `ChartFactory.setChartTheme(StandardChartTheme.createLegacyTheme())` ngay trong `static` initializer, kết xuất 3 biểu đồ PNG `800x400`:
  1. `renderTransactionVolumeChart` — Doanh số giao dịch theo kỳ (`totalAmount`, VND)
  2. `renderBalanceTrendChart` — Xu hướng số dư cuối kỳ (`closingBalance`, VND)
  3. `renderFeeTrendChart` — Doanh thu phí giao dịch theo kỳ (`totalFee`, VND)
- Khi danh sách `trendPoints` rỗng hoặc `null`, `ChartRenderer` vẫn sinh ảnh PNG `800x400` hợp lệ kèm chú thích `"No data in selected period"` để bộ xuất PDF/Excel/HTML không bao giờ gặp lỗi ảnh hỏng.

## 3. Hiệu năng truy vấn và kiểm chứng chỉ mục (`performance.md`)

### 3.1. Số lượng câu lệnh SQL
Kiểm chứng tự động trong `TrendReportIT`:

| Luồng nghiệp vụ | Số câu SQL (`JdbcClient` / `HibernateStatistics`) | Giải thích |
|---|---|---|
| `GET /api/v1/reports/trends` (toàn hệ thống, `accountId = null`) | **1 câu SQL (`JdbcClient`) · 0 câu JPA** | Gộp `generate_series`, `GROUP BY`, `post_range_delta`, `SUM(balance)` và `SUM(...) OVER (...)` trong 1 câu truy vấn duy nhất |
| `GET /api/v1/reports/trends?accountId=1` (theo tài khoản) | **2 câu SQL (`JdbcClient`) · 0 câu JPA** | 1 câu `SELECT EXISTS(SELECT 1 FROM core.account WHERE id = :accountId)` + 1 câu CTE `generate_series` + `DISTINCT ON` |
| `ReportDataService.loadReportBundle(...)` | **2–3 câu SQL chạy song song trên Virtual Threads · 0 câu JPA** | Chạy đồng thời `getPeriodicTransactionReport` và `getTrendSeries` qua `CompletableFuture.supplyAsync(..., virtualThreadExecutor)` |

### 3.2. Kế hoạch thực thi (`EXPLAIN (ANALYZE, BUFFERS)`)
Kiểm chứng trên PostgreSQL 17 Testcontainers trong `TrendReportIT` xác nhận PostgreSQL sử dụng `WindowAgg` kết hợp `Index Scan` trên bảng `core.transaction`:

```sql
WindowAgg  (cost=104.22..126.75 rows=1000 width=116) (actual time=0.142..0.148 rows=3 loops=1)
  Buffers: shared hit=8
  InitPlan 1 (returns $0)
    -> Aggregate  (cost=1.15..1.16 rows=1 width=32) (actual time=0.014..0.015 rows=1 loops=1)
         -> Index Only Scan using account_pkey on account a
  InitPlan 2 (returns $1)
    -> Aggregate  (cost=8.31..8.32 rows=1 width=32) (actual time=0.018..0.018 rows=1 loops=1)
         -> Index Scan using idx_tx_status_created on transaction t
              Index Cond: (created_at >= '2027-10-04 00:00:00+07'::timestamptz)
  -> Merge Left Join  (cost=94.75..102.25 rows=1000 width=108) (actual time=0.101..0.105 rows=3 loops=1)
       Merge Cond: ((gs.gs)::date = tb.bucket)
       -> Sort  (cost=59.83..62.33 rows=1000 width=4) (actual time=0.041..0.042 rows=3 loops=1)
            -> Function Scan on generate_series gs  (actual time=0.025..0.026 rows=3 loops=1)
       -> Sort  (cost=34.92..35.42 rows=200 width=108) (actual time=0.054..0.055 rows=1 loops=1)
            -> Subquery Scan on tb  (actual time=0.046..0.047 rows=1 loops=1)
                 -> GroupAggregate  (actual time=0.045..0.046 rows=1 loops=1)
                      -> Sort  (actual time=0.036..0.037 rows=1 loops=1)
                           -> Index Scan using idx_tx_status_created on transaction t_1
                                Index Cond: ((created_at >= '2027-10-01 00:00:00+07'::timestamptz) AND (created_at < '2027-10-04 00:00:00+07'::timestamptz))
Planning Time: 0.312 ms
Execution Time: 0.215 ms
```

## 4. Các phương án đã cân nhắc (Alternatives Considered)

1. **Chạy vòng lặp $N$ câu truy vấn `SELECT SUM(...) WHERE created_at <= bucket_end` cho từng ngày:**
   - *Bác bỏ:* Với khoảng báo cáo 366 ngày (`granularity = DAY`), cách này gây ra 366 round-trip tới PostgreSQL (bẫy $N+1$ theo trục thời gian). Dùng `generate_series` kết hợp hàm cửa sổ `SUM(...) OVER (ROWS BETWEEN 1 FOLLOWING AND UNBOUNDED FOLLOWING)` rút gọn toàn bộ tính toán xuống **1 câu SQL duy nhất**.
2. **Dùng thư viện vẽ biểu đồ phụ thuộc trình duyệt headless (Puppeteer / Playwright) hoặc JavaFX:**
   - *Bác bỏ:* Cài đặt Chromium vào Docker image làm phình kích thước image thêm hàng trăm MB và tiêu tốn nhiều RAM (vi phạm giới hạn container 512MB `NFR-10`). `JFreeChart 1.5.5` chạy thuần trên `java.awt.BufferedImage` ở chế độ headless, kết xuất 3 ảnh PNG `800x400` chỉ trong vài chục mili-giây với dung lượng bộ nhớ tối thiểu.

## 5. Bẫy kỹ thuật và bài học (Gotchas)

- **Bẫy lệch số dư khi lọc cứng `WHERE status = 'COMPLETED' AND type <> 'REVERSAL'` trên toàn CTE `tx_by_bucket`:** Nếu lọc bỏ các dòng `REVERSED` và `REVERSAL` ở mệnh đề `WHERE` chung của `tx_by_bucket`, một giao dịch phát sinh ở ngày $D_1$ nhưng bị đảo (`REVERSAL`) ở ngày $D_2$ sẽ bị mất dấu biến động tại $D_1$ và $D_2$. Giải pháp là dùng `FILTER (WHERE t.status = 'COMPLETED' AND t.type <> 'REVERSAL')` riêng cho `tx_count`, `total_amount`, `total_fee`, còn `SUM(t.system_balance_delta)` vẫn tính trên mọi dòng để phản ánh trung thực số dư sổ cái tại cuối ngày $D_1$ và sự kiện hoàn tiền tại ngày $D_2$.
- **Bẫy thứ tự giao dịch trùng mốc thời gian (`created_at`) trong cùng một mili-giây:** Trong `findAccountTrendSeries`, mệnh đề `DISTINCT ON (bucket)` luôn đi kèm `ORDER BY bucket ASC, t.created_at DESC, t.id DESC` (thêm `t.id DESC` làm khóa phá hòa) để đảm bảo chọn đúng giao dịch sau cùng ngay cả khi hai giao dịch có cùng `created_at`.

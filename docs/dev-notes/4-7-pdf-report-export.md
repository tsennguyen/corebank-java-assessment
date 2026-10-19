# Dev Note — Story 4.7: Xuất báo cáo tài chính PDF (`OpenPDF`), Nhúng phông chữ Unicode tiếng Việt (`DejaVuSans`) & Biểu đồ xu hướng (`L3-07`, `L3-08`, `FR-21`, `ADR-014`)

## 1. Bối cảnh và yêu cầu
Theo yêu cầu `L3-07`, `L3-08` và `FR-21`, bên cạnh định dạng bảng tính Excel (`.xlsx`), Admin cần tải xuống báo cáo tài chính định dạng PDF (`.pdf`) qua endpoint `GET /api/v1/reports/export?format=PDF&period=QUARTER&from=...&to=...` để phục vụ in ấn, trình ký và lưu trữ đối soát:
1. **Tiêu đề & Siêu dữ liệu:** `"Core Banking System — Báo cáo tài chính"` cùng kỳ báo cáo (`WEEK`, `QUARTER`, `YEAR`), khoảng thời gian `from..to` và bộ lọc loại giao dịch.
2. **Bảng tổng hợp theo kỳ (`Periodic Summary`):** Đầy đủ 7 cột (`periodStart`, `txCount`, `totalAmount`, `averageAmount`, `maxAmount`, `minAmount`, `totalFee`).
3. **3 Biểu đồ xu hướng (`800x400` PNG):** Nhúng trực tiếp 3 ảnh PNG (Doanh số giao dịch, Số dư cuối kỳ, Doanh thu phí) kết xuất từ `ChartRenderer`.
4. **Bảng chi tiết giao dịch (`Transaction Details`):** Giới hạn tối đa **1.000 giao dịch mới nhất** (`LIMIT 1000`) kèm dòng ghi chú hướng dẫn tải định dạng Excel (`.xlsx`) nếu cần toàn bộ dữ liệu thô quy mô lớn.

Ràng buộc kỹ thuật quan trọng:
- **Hiển thị đầy đủ tiếng Việt có dấu:** Phông chữ mặc định của chuẩn PDF (`Helvetica` / `WinAnsiEncoding`) không có glyph cho các ký tự tiếng Việt thuộc bảng mã Unicode tổ hợp/dựng sẵn (`Báo cáo tài chính`, `TP. Hồ Chí Minh`, `Đà Nẵng`, `Chuyển khoản`). Nếu không nhúng phông chữ TrueType Unicode (`IDENTITY_H`), các ký tự có dấu sẽ bị mất hoặc biến thành ô vuông trên mọi container Linux headless.
- **Bảo vệ bộ nhớ và thời gian phản hồi:** Khác với `.xlsx` dùng `SXSSFWorkbook(100)` flush XML ra đĩa, bảng `PdfPTable` của PDF cần tính toán bố cục dòng trước khi ngắt trang. Do đó, giới hạn bảng chi tiết PDF ở **1.000 dòng mới nhất** (`MAX_PDF_DETAIL_ROWS = 1000`, truyền thẳng xuống mệnh đề `LIMIT 1000` của SQL cursor) đảm bảo file PDF ~20–30 trang được kết xuất trong `< 500 ms` với bộ nhớ Heap cực thấp.

## 2. Thiết kế và quyết định kỹ thuật

### 2.1. Hoàn thiện mẫu thiết kế Template Method (`ReportExporter<C>` $\rightarrow$ `PdfReportExporter`)
- `PdfReportExporter` kế thừa lớp khung `ReportExporter<PdfReportExporter.PdfExportContext>` (đã thiết kế ở Story 4.6) và đăng ký tự động vào `ReportExportService` qua cơ chế tiêm phụ thuộc `List<ReportExporter<?>>` của Spring.
- Không cần sửa đổi `ReportController` hay `ReportExportService`: khi `PdfReportExporter` có mặt trong Spring Context, yêu cầu `GET /api/v1/reports/export?format=PDF` tự động được định tuyến tới `PdfReportExporter` với header `Content-Type: application/pdf` và `Content-Disposition: attachment; filename="core-banking-report-<period>-<from>-<to>.pdf"`.
- Toàn bộ trạng thái của từng lần xuất (`Document`, `PdfWriter`, 5 đối tượng `Font`) được đóng gói trong record bất biến `PdfExportContext` cục bộ theo từng luồng, đảm bảo an toàn tuyệt đối khi nhiều Admin tải PDF đồng thời trên Java 21 Virtual Threads.

### 2.2. Nhúng phông chữ Unicode TrueType (`DejaVuSans.ttf` & `DejaVuSans-Bold.ttf`) với `BaseFont.IDENTITY_H`
- Đóng gói sẵn 2 file phông chữ mã nguồn mở `fonts/DejaVuSans.ttf` và `fonts/DejaVuSans-Bold.ttf` trong `src/main/resources/fonts/`.
- Khi khởi tạo bean `PdfReportExporter`, byte mảng của 2 phông chữ được nạp một lần duy nhất từ classpath và truyền vào `BaseFont.createFont("DejaVuSans.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, regularFontBytes, null)`.
- Chế độ `BaseFont.IDENTITY_H` + `BaseFont.EMBEDDED` nhúng tập con (subset) glyph Unicode trực tiếp vào file PDF đầu ra, đảm bảo mọi trình đọc PDF (trên macOS, Windows, iOS, Android, Linux) hiển thị và trích xuất văn bản tiếng Việt (`PdfTextExtractor`) chính xác 100%.

### 2.3. Giới hạn 1.000 dòng ngay từ tầng SQL (`LIMIT 1000`) kết hợp chốt chặn ở tầng Exporter
- `PdfReportExporter.writeDetails(...)` gọi `reportDataService.streamTransactionsInPeriod(from, to, type, MAX_PDF_DETAIL_ROWS, consumer)`.
- Tầng `ReportRepository` gắn trực tiếp `LIMIT 1000` vào câu lệnh SQL có chỉ mục `(status, created_at DESC)`, giúp PostgreSQL dừng quét chỉ mục ngay sau 1.000 dòng đầu tiên thay vì đọc hết 100.000 dòng rồi mới cắt ở tầng Java.
- Đồng thời, callback trong `PdfReportExporter` vẫn kiểm tra chốt chặn `rowCount.incrementAndGet() <= MAX_PDF_DETAIL_ROWS` và in dòng ghi chú `TRUNCATION_NOTE` ở cuối bảng.

## 3. Hiệu năng truy vấn và kiểm chứng chỉ mục (`performance.md`)

### 3.1. Số lượng câu lệnh SQL và bộ nhớ
Kiểm chứng tự động trong `PdfReportExporterTest` và `PdfReportExportIT`:

| Kịch bản kiểm chứng | Số câu SQL (`HibernateStatistics` / JDBC) | Đặc tính bộ nhớ & Thời gian |
|---|---|---|
| `GET /api/v1/reports/export?format=PDF&period=QUARTER` (`PdfReportExportIT`) | **3 câu SQL JDBC · 0 câu JPA** | 2 câu SQL tổng hợp chạy song song trên Virtual Threads (`periodic` + `trends`) + 1 câu SQL cursor có `LIMIT 1000` |
| Tập dữ liệu `> 1.000` dòng (`PdfReportExporterTest`) | Dừng ngay ở `1.000` dòng (`MAX_PDF_DETAIL_ROWS`) | Bảng chi tiết giữ tối đa 1.000 dòng + dòng ghi chú hướng dẫn tải `.xlsx` |

### 3.2. Kế hoạch thực thi (`EXPLAIN (ANALYZE, BUFFERS)`)
Kiểm chứng trong `PdfReportExportIT` trên PostgreSQL 17 Testcontainers xác nhận truy vấn chi tiết giao dịch PDF với `LIMIT 1000` sử dụng **`Index Scan` trên `idx_tx_status_created`**:

```sql
Limit  (cost=8.32..8.33 rows=2 width=148) (actual time=0.035..0.036 rows=2 loops=1)
  Buffers: shared hit=4
  -> Sort  (cost=8.32..8.33 rows=2 width=148) (actual time=0.034..0.035 rows=2 loops=1)
        Sort Key: created_at DESC, id DESC
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=4
        -> Index Scan using idx_tx_status_created on transaction t  (cost=0.15..8.31 rows=2 width=148) (actual time=0.021..0.023 rows=2 loops=1)
              Index Cond: (((status)::text = 'COMPLETED'::text) AND (created_at >= '2028-01-01 00:00:00+07'::timestamptz) AND (created_at < '2028-07-01 00:00:00+07'::timestamptz))
              Filter: ((type)::text <> 'REVERSAL'::text)
Planning Time: 0.182 ms
Execution Time: 0.061 ms
```

## 4. Các phương án đã cân nhắc (Alternatives Considered)

1. **Dùng phông chữ chuẩn Type-1 của PDF (`FontFactory.HELVETICA`) thay vì nhúng TTF:**
   - *Bác bỏ:* `Helvetica` sử dụng bảng mã `Cp1252` (`WinAnsiEncoding`), làm rơi mất các ký tự tiếng Việt như `ư`, `ơ`, `đ`, `ệ`, `ẩ` hoặc hiển thị sai dấu. Nhúng `DejaVuSans.ttf` với `BaseFont.IDENTITY_H` chỉ làm tăng kích thước file PDF thêm ~20–30 KB (do OpenPDF tự động chỉ nhúng subset các ký tự xuất hiện trong báo cáo) nhưng đảm bảo hiển thị tiếng Việt hoàn hảo.
2. **Xuất không giới hạn 100.000 dòng vào bảng PDF (như Excel):**
   - *Bác bỏ:* Một file PDF chứa 100.000 dòng giao dịch sẽ dài hơn **2.500 trang A4**, vô nghĩa về mặt đọc/in ấn của con người và buộc `PdfPTable` giữ cấu trúc trang lớn trong RAM. Thiết kế đúng chuẩn ngân hàng là PDF dùng cho báo cáo tóm tắt điều hành + 1.000 giao dịch gần nhất, còn nhu cầu kết xuất toàn bộ 100.000 dòng phục vụ phân tích dữ liệu được đáp ứng bởi định dạng Excel streaming (`SXSSFWorkbook`).

## 5. Bẫy kỹ thuật và bài học (Gotchas)

- **Bẫy nạp tài nguyên phông chữ trong Spring Boot Fat JAR:** Nếu truyền đường dẫn tương đối trên đĩa cho `BaseFont.createFont`, quá trình khởi tạo sẽ thất bại khi ứng dụng đóng gói thành file `.jar` chạy trong Docker container. Giải pháp an toàn nhất là đọc `InputStream` qua `ClassLoader.getResourceAsStream("fonts/DejaVuSans.ttf")` thành `byte[]` ngay khi khởi tạo bean và truyền trực tiếp mảng byte này vào `BaseFont.createFont(..., regularFontBytes, null)`.
- **Bố cục bảng 10 cột trên khổ giấy dọc:** Bảng chi tiết giao dịch có tới 10 cột (`referenceNo`, `type`, `status`, `sourceAccountId`, `targetAccountId`, `amount`, `fee`, `location`, `description`, `createdAt`). Sử dụng khổ giấy ngang `PageSize.A4.rotate()` (Landscape) giúp các cột số tiền, mã tham chiếu và diễn giải tiếng Việt hiển thị thoáng, không bị gãy dòng vụn vặt.

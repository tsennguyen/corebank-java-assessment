# Dev Note — Story 4.6: Xuất báo cáo tài chính Excel Streaming (`SXSSFWorkbook`) & Nhúng biểu đồ xu hướng (`L3-07`, `L3-08`, `G-05`, `FR-21`, `NFR-2`, `ADR-014`)

## 1. Bối cảnh và yêu cầu
Theo yêu cầu `L3-07`, `L3-08` và `G-05` (*tư duy tối ưu hệ thống cho cả khối lượng nhỏ và khối lượng lớn*), Admin cần tải xuống báo cáo tài chính định dạng Excel (`.xlsx`) qua endpoint `GET /api/v1/reports/export?format=XLSX&period=QUARTER&from=...&to=...` gồm đủ 3 sheet:
1. **`Tong hop`:** Tiêu đề `"Core Banking System — Báo cáo tài chính"`, thông tin kỳ báo cáo và bảng số liệu tổng hợp theo kỳ (`periodStart`, `txCount`, `totalAmount`, `averageAmount`, `maxAmount`, `minAmount`, `totalFee`).
2. **`Chi tiet`:** Danh sách từng giao dịch hoàn tất trong kỳ (`referenceNo`, `type`, `status`, `sourceAccountId`, `targetAccountId`, `amount`, `fee`, `location`, `description`, `createdAt`).
3. **`Bieu do`:** Nhúng trực tiếp **3 ảnh PNG `800x400`** biểu đồ xu hướng (Doanh số giao dịch, Số dư cuối kỳ, Doanh thu phí) từ `ChartRenderer`.

Ràng buộc kỹ thuật quan trọng:
- Khi kỳ báo cáo có tới **100.000 dòng giao dịch**, hệ thống không được nạp toàn bộ danh sách giao dịch vào bộ nhớ Heap (tránh `OutOfMemoryError` trên container giới hạn 512MB RAM).
- Mọi lỗi đầu vào (`format` không hợp lệ, `from > to`, khoảng thời gian `> 366` ngày, hoặc `type = REVERSAL`) phải được từ chối với HTTP `400 VALIDATION_FAILED` (`application/problem+json`) **trước khi** HTTP `200 OK` và `StreamingResponseBody` bắt đầu truyền byte.
- Các ô văn bản do người dùng nhập (`description`, `location`, `referenceNo`) phải được bảo vệ chống lỗ hổng **CSV/Excel Formula Injection**.

## 2. Thiết kế và quyết định kỹ thuật

### 2.1. Mẫu thiết kế Template Method an toàn đa luồng (`ReportExporter<C>`)
- Lớp trừu tượng `ReportExporter<C>` định nghĩa quy trình khung bất biến `final void export(ReportExportCriteria, OutputStream)` dùng chung cho cả `ExcelReportExporter` (Story 4.6) và `PdfReportExporter` (Story 4.7):
  1. Tải song song bảng tổng hợp kỳ và chuỗi xu hướng trên Java 21 Virtual Threads (`ReportDataService.loadReportBundle`).
  2. Kết xuất 3 biểu đồ PNG `800x400` ở chế độ headless (`ChartRenderer.renderAllCharts`).
  3. Khởi tạo ngữ cảnh xuất theo từng request (`createContext`), lần lượt gọi `writeHeader` $\rightarrow$ `writeSummary` $\rightarrow$ `writeCharts` $\rightarrow$ `writeDetails` $\rightarrow$ `finish`.
  4. Luôn dọn dẹp tài nguyên tạm trong khối `finally` (`cleanup(context)`).
- Vì các exporter là singleton `@Component` của Spring và phục vụ nhiều request đồng thời trên Virtual Threads, toàn bộ trạng thái (`SXSSFWorkbook`, `SXSSFSheet`, `CellStyle`) được đóng gói trong record `ExcelExportContext` cục bộ của từng lời gọi hàm, hoàn toàn không dùng biến thực thể khả biến trên bean.

### 2.2. Streaming 3 tầng không tràn bộ nhớ (`JdbcTemplate` Cursor $\rightarrow$ `SXSSFWorkbook(100)` $\rightarrow$ `StreamingResponseBody`)
1. **Tầng Database (`ReportRepository.streamTransactionsInPeriod`):**
   - Thực thi trong `@Transactional(readOnly = true)` (đảm bảo `autoCommit = false` trên kết nối HikariCP) kết hợp `PreparedStatement` cấu hình `ResultSet.TYPE_FORWARD_ONLY`, `ResultSet.CONCUR_READ_ONLY` và `ps.setFetchSize(1000)`.
   - PostgreSQL JDBC Driver (`pgjdbc`) mở con trỏ phía server (Server Cursor) và chỉ lấy từng lô 1.000 bản ghi qua mạng, đẩy trực tiếp từng `TransactionExportRow` vào callback `RowCallbackHandler` thay vì cấp phát `List<TransactionExportRow>`.
2. **Tầng tạo file Excel (`ExcelReportExporter`):**
   - Khởi tạo `new SXSSFWorkbook(100)` (chỉ giữ cửa sổ trượt **100 dòng** trong bộ nhớ Heap; các dòng cũ tự động được flush ra file XML tạm trên đĩa).
   - Đặt độ rộng cột cố định (`setColumnWidth`) thay vì gọi `autoSizeColumn` (vốn phải quét và giữ kích thước phông chữ của toàn bộ 100.000 dòng).
   - Trong khối `finally`, bắt buộc gọi `workbook.dispose()` trước `workbook.close()` để xóa sạch các file XML tạm trong `/tmp/poifiles`.
3. **Tầng HTTP (`ReportController.exportReport`):**
   - Kiểm tra hợp lệ đồng bộ qua `ReportExportService.validateAndBuildCriteria(...)` trước khi khởi tạo `ResponseEntity<StreamingResponseBody>`, đảm bảo mọi lỗi `400 VALIDATION_FAILED` trả về đúng chuẩn RFC 9457 `ProblemDetail`.

### 2.3. Chống lỗ hổng CSV/Excel Formula Injection (`sanitizeFormulaCell`)
- Mọi chuỗi văn bản ghi vào ô Excel nếu bắt đầu bằng `=`, `+`, `-`, `@`, `\t` hoặc `\r` đều được tiền tố hóa bằng dấu nháy đơn `'` (ví dụ `=HYPERLINK("x")` $\rightarrow$ `'=HYPERLINK("x")`) và ép kiểu `CellType.STRING` tường minh, ngăn Excel/LibreOffice thực thi công thức độc hại khi kế toán mở file.

## 3. Hiệu năng truy vấn và kiểm chứng chỉ mục (`performance.md`)

### 3.1. Số lượng câu lệnh SQL và bộ nhớ
Kiểm chứng tự động trong `ExcelReportExporterTest` và `ExcelReportExportIT`:

| Kịch bản kiểm chứng | Số câu SQL (`HibernateStatistics` / JDBC) | Đặc tính bộ nhớ & Thời gian |
|---|---|---|
| `GET /api/v1/reports/export?format=XLSX&period=QUARTER` (`ExcelReportExportIT`) | **3 câu SQL JDBC · 0 câu JPA** | 2 câu SQL tổng hợp chạy song song trên Virtual Threads (`periodic` + `trends`) + 1 câu SQL cursor streaming (`fetchSize = 1000`) |
| Xuất **100.000 dòng** giao dịch chi tiết (`ExcelReportExporterTest`) | Streaming qua `RowCallbackHandler` | Giữ đúng **100 dòng** trên Heap (`SXSSFWorkbook(100)`), hoàn tất xuất 100.000 dòng + 3 ảnh PNG trong ~2 giây, `dispose()` dọn sạch file tạm |

### 3.2. Kế hoạch thực thi (`EXPLAIN (ANALYZE, BUFFERS)`)
Kiểm chứng trên PostgreSQL 17 Testcontainers trong `ExcelReportExportIT` xác nhận câu truy vấn streaming chi tiết giao dịch sử dụng **`Index Scan` trên chỉ mục phức hợp `idx_tx_status_created` (`(status, created_at DESC)`)**:

```sql
Sort  (cost=8.32..8.33 rows=2 width=148) (actual time=0.038..0.039 rows=2 loops=1)
  Sort Key: created_at DESC, id DESC
  Sort Method: quicksort  Memory: 25kB
  Buffers: shared hit=4
  -> Index Scan using idx_tx_status_created on transaction t  (cost=0.15..8.31 rows=2 width=148) (actual time=0.024..0.026 rows=2 loops=1)
       Index Cond: (((status)::text = 'COMPLETED'::text) AND (created_at >= '2028-01-01 00:00:00+07'::timestamptz) AND (created_at < '2028-07-01 00:00:00+07'::timestamptz))
       Filter: ((type)::text <> 'REVERSAL'::text)
Planning Time: 0.195 ms
Execution Time: 0.068 ms
```

## 4. Các phương án đã cân nhắc (Alternatives Considered)

1. **Dùng `XSSFWorkbook` truyền thống và `List<Transaction>` từ Spring Data JPA:**
   - *Bác bỏ:* Với 100.000 dòng giao dịch, `XSSFWorkbook` giữ toàn bộ cây DOM `XmlBeans` (hơn 1.000.000 nút XML ô dữ liệu) trong bộ nhớ Heap, tiêu tốn 300–500MB RAM chỉ cho 1 request và dễ dàng đánh sập container 512MB khi có 2 Admin xuất báo cáo cùng lúc. `SXSSFWorkbook(100)` kết hợp JDBC cursor giữ mức tiêu thụ Heap cố định $O(1)$ theo cửa sổ 100 dòng.
2. **Đặt logic kiểm tra `from/to` bên trong lambda của `StreamingResponseBody`:**
   - *Bác bỏ:* Khi lambda `StreamingResponseBody.writeTo(OutputStream)` được gọi, Spring MVC đã ghi HTTP status `200 OK` và header `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` xuống socket. Nếu ném ngoại lệ ở thời điểm đó, client nhận file `.xlsx` bị hỏng thay vì JSON `400 ProblemDetail`. Do đó toàn bộ bước validate được thực thi đồng bộ trong `ReportExportService.validateAndBuildCriteria(...)` trước khi trả về `ResponseEntity`.

## 5. Bẫy kỹ thuật và bài học (Gotchas)

- **Bẫy `pgjdbc` bỏ qua `setFetchSize(1000)` khi `autoCommit = true`:** Theo tài liệu chính thức của PostgreSQL JDBC Driver, `PreparedStatement.setFetchSize(n)` hoàn toàn bị phớt lờ và driver vẫn tải toàn bộ `ResultSet` vào RAM nếu kết nối đang ở chế độ `autoCommit = true`. Bọc `ReportService.streamTransactionsInPeriod` trong `@Transactional(readOnly = true)` buộc HikariCP/Spring tắt `autoCommit` trong phạm vi giao dịch chỉ đọc, kích hoạt Server Cursor thật sự.
- **Bẫy rò rỉ file tạm `/tmp/poifiles` của `SXSSFWorkbook`:** Phương thức `SXSSFWorkbook.close()` chỉ đóng luồng workbook mà **không** xóa các file `.xml` tạm đã flush ra đĩa cho từng sheet. Phải gọi tường minh `workbook.dispose()` trong khối `finally` của `ReportExporter.cleanup(...)`.

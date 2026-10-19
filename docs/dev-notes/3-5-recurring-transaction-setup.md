# Story 3.5: Thiết lập & Quản lý Vòng đời Lệnh Giao dịch Định kỳ (Recurring Transaction Setup)

- **Mã yêu cầu:** `L2-06` [ĐỀ], `FR-16`, `FR-2`, `NFR-1`, `G-05`
- **Migration:** `V11__recurring_transaction.sql`

---

## 1. Bối cảnh & Bài toán

Theo yêu cầu `L2-06` và `FR-16`, hệ thống Core Banking cần cho phép quản trị viên thiết lập và điều khiển vòng đời các lệnh giao dịch định kỳ (`DEPOSIT`, `PAYMENT`, `TRANSFER`) theo tần suất hằng ngày (`DAILY`), hằng tuần (`WEEKLY`) hoặc hằng tháng (`MONTHLY`) với các yêu cầu kỹ thuật trọng tâm:

1. **Ràng buộc nghiệp vụ chặt chẽ theo loại giao dịch và trạng thái tài khoản:**
   - `DEPOSIT` chỉ có `targetAccountId`; `PAYMENT` chỉ có `sourceAccountId`; `TRANSFER` bắt buộc có cả `sourceAccountId` và `targetAccountId` khác nhau (`400 SAME_ACCOUNT_TRANSFER` nếu trùng).
   - Mọi tài khoản tham chiếu phải tồn tại (`404 RESOURCE_NOT_FOUND`) và đang ở trạng thái `ACTIVE` (`422 ACCOUNT_NOT_ACTIVE` nếu `LOCKED` hoặc `CLOSED`).
2. **Thuật toán tính chu kỳ `MONTHLY` bám ngày neo gốc (Anchor-Day Clamping):**
   - Lệnh hàng tháng bắt đầu ngày `31/01` khi sang tháng 2 phải tự kẹp về `28/02` (hoặc `29/02` năm nhuận), nhưng sang tháng 3 phải **khôi phục về ngày `31/03`** và tháng 4 kẹp về `30/04` — không được trôi cố định về ngày 28.
3. **Máy trạng thái vòng đời (`ACTIVE`, `PAUSED`, `CANCELLED`, `COMPLETED`) & Bảo vệ đóng tài khoản:**
   - Cho phép tạm dừng (`POST /{id}/pause`), tiếp tục (`POST /{id}/resume` kèm cơ chế chống bão giao dịch bù `catch-up storm`), và hủy (`DELETE /{id}`).
   - Chặn đóng tài khoản (`DELETE /api/v1/accounts/{id}` hoặc `POST /api/v1/accounts/{id}/status` sang `CLOSED`) nếu tài khoản vẫn còn lệnh định kỳ `ACTIVE` (`409 ACCOUNT_HAS_ACTIVE_RECURRING`) mà không tạo phụ thuộc vòng giữa module `account` và `recurring`.

---

## 2. Giải pháp Kỹ thuật đã Triển khai

### 2.1. Bảng `core.recurring_transaction` & Partial Index `idx_rec_due` (`V11__recurring_transaction.sql`)

```sql
CREATE INDEX IF NOT EXISTS idx_rec_due
    ON core.recurring_transaction (next_run_at ASC, id ASC)
    WHERE status = 'ACTIVE';
```

- **Partial Index (`WHERE status = 'ACTIVE'`):** Khi hệ thống vận hành lâu dài, phần lớn bản ghi sẽ chuyển sang trạng thái kết thúc (`COMPLETED` hoặc `CANCELLED`). Nhờ đánh Partial Index chỉ trên các dòng `status = 'ACTIVE'`, kích thước cây B-Tree luôn cực nhỏ (chỉ chứa các lệnh đang hoạt động), giúp tiến trình lập lịch ở Story 3.6 quét `WHERE status = 'ACTIVE' AND next_run_at <= now() ORDER BY next_run_at ASC, id ASC` trong thời gian $< 1\text{ ms}$.
- **Ràng buộc toàn vẹn mức CSDL (`chk_rec_accounts_by_type`, `chk_rec_dates`):** Đảm bảo ngay cả khi thao tác SQL trực tiếp cũng không thể chèn bản ghi thiếu tài khoản theo `type` hoặc `end_date < start_date`.

### 2.2. Thuật toán `RecurringFrequency.MONTHLY` với Ngày Neo Gốc (`anchorDayOfMonth`)

```java
YearMonth nextMonth = YearMonth.from(currentDate).plusMonths(1);
int clampedDay = Math.min(anchorDayOfMonth, nextMonth.lengthOfMonth());
return nextMonth.atDay(clampedDay);
```

- Thay vì cộng dồn `currentDate.plusMonths(1)` (vốn làm `2026-01-31 -> 2026-02-28 -> 2026-03-28`), phương thức `RecurringFrequency.MONTHLY.nextDate(currentDate, startDate.getDayOfMonth())` luôn lấy `startDate.getDayOfMonth()` làm ngày neo gốc và kẹp theo `nextMonth.lengthOfMonth()`.
- Kết quả kiểm thử (`RecurringFrequencyTest`):
  - `2026-01-31 -> 2026-02-28 -> 2026-03-31 -> 2026-04-30 -> 2026-05-31`
  - Năm nhuận: `2028-01-31 -> 2028-02-29 -> 2028-03-31`.

### 2.3. Tách phụ thuộc Module qua `AccountClosureGuard` (`recurring → account`)

- Module `account` định nghĩa port interface `AccountClosureGuard` (`validateCanCloseAccount(Long accountId)`).
- Module `recurring` cung cấp bean `RecurringAccountClosureGuard` kiểm tra `recurringTransactionRepository.existsByAccountIdAndStatus(accountId, RecurringStatus.ACTIVE)`.
- Nhờ Inversion of Control (IoC), `account` hoàn toàn không import bất kỳ class nào từ `recurring` hay `transaction` (được kiểm chứng tự động bởi `ModuleDependencyArchitectureTest`).

### 2.4. Số lượng câu lệnh SQL & Kế hoạch thực thi (`EXPLAIN (ANALYZE, BUFFERS)`)

1. **Số câu lệnh SQL trên API tìm kiếm (`GET /api/v1/recurring-transactions`):**
   - Kiểm chứng tự động bằng `HibernateStatistics` trong `RecurringTransactionSetupIntegrationTest`:
     ```java
     assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(2L);
     ```
   - Thực tế thực thi đúng **1 câu SQL** `SELECT ... FROM core.recurring_transaction WHERE status = ? AND (source_account_id = ? OR target_account_id = ?) ORDER BY id DESC LIMIT 20` khi tổng số bản ghi nhỏ hơn kích thước trang (và tối đa **2 câu SQL** khi cộng thêm `SELECT count(*)`), hoàn toàn không có N+1 query.

2. **Kế hoạch thực thi `EXPLAIN (ANALYZE, BUFFERS)` với Partial Index `idx_rec_due` trên PostgreSQL 17:**
   ```sql
   EXPLAIN (ANALYZE, BUFFERS)
   SELECT * FROM core.recurring_transaction
   WHERE status = 'ACTIVE' AND next_run_at <= CURRENT_TIMESTAMP
   ORDER BY next_run_at ASC, id ASC
   LIMIT 50;
   ```
   ```text
   Limit  (cost=0.14..8.16 rows=1 width=142) (actual time=0.019..0.020 rows=1 loops=1)
     Buffers: shared hit=2
     ->  Index Scan using idx_rec_due on recurring_transaction  (cost=0.14..8.16 rows=1 width=142) (actual time=0.018..0.018 rows=1 loops=1)
           Index Cond: (next_run_at <= CURRENT_TIMESTAMP)
           Buffers: shared hit=2
   Planning Time: 0.088 ms
   Execution Time: 0.036 ms
   ```
   PostgreSQL sử dụng trực tiếp partial index `idx_rec_due` (không cần bước Sort hay Filter bổ sung vì điều kiện `WHERE status = 'ACTIVE'` và thứ tự `(next_run_at ASC, id ASC)` đã nằm sẵn trong cấu trúc B-Tree của partial index).

---

## 3. Các Bẫy (Gotchas) đã Xử lý

1. **Chống "Bão giao dịch bù" (Catch-up Storm) khi `resume` lệnh định kỳ đã `PAUSED` lâu ngày:**
   - Nếu một lệnh `DAILY` bị tạm dừng từ 15 ngày trước và khi Admin bấm `POST /{id}/resume` mà giữ nguyên `nextRunAt` cũ trong quá khứ, bộ lập lịch sẽ bắn dồn dập 15 giao dịch liên tiếp.
   - Giải pháp: Trong `RecurringTransaction.resume(Clock)`, nếu `nextRunAt.isBefore(clock.instant())`, hệ thống dịch chuyển `nextRunAt = clock.instant()` để chỉ chạy kỳ hiện tại rồi tiến đều sang kỳ tương lai kế tiếp; đồng thời từ chối `resume` (`409 INVALID_STATUS_TRANSITION`) nếu ngày hiện tại đã vượt quá `endDate`.
2. **Kiểm tra lại trạng thái tài khoản khi `resume`:**
   - Trong lúc lệnh định kỳ đang `PAUSED`, tài khoản nguồn hoặc đích có thể đã bị khóa (`LOCKED`) hoặc đóng (`CLOSED`). `RecurringTransactionService.resumeRecurringTransaction` khóa bi quan các tài khoản liên quan theo thứ tự ID tăng dần và kiểm tra `account.isActive()`, trả `422 ACCOUNT_NOT_ACTIVE` nếu tài khoản không còn hoạt động.

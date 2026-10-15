# Story 3.6: Bộ Lập lịch Thực thi Giao dịch Định kỳ (Recurring Transaction Scheduler)

- **Mã yêu cầu:** `L2-07` [ĐỀ], `FR-17`, `NFR-1`, `ADR-011`
- **Migration:** Không cần migration mới (sử dụng bảng `core.recurring_transaction`, partial index `idx_rec_due` và khóa ngoại `fk_tx_recurring` từ `V11__recurring_transaction.sql`)

---

## 1. Bối cảnh & Bài toán

Theo yêu cầu `L2-07` và `FR-17`, hệ thống cần một tiến trình lập lịch chạy tự động theo chu kỳ (`@Scheduled` cron, mặc định mỗi phút theo múi giờ nghiệp vụ `Asia/Ho_Chi_Minh`) để quét và thực thi các lệnh giao dịch định kỳ đang `ACTIVE` đã đến hạn (`next_run_at <= clock.instant()`) với 3 yêu cầu cốt lõi:

1. **Thực thi chính xác đúng 1 lần mỗi kỳ (Exactly-Once per Period) ngay cả khi chạy nhiều instance đồng thời:**
   - Khi mở rộng ngang nhiều instance ứng dụng cùng chạy cron job, hai instance không được phép nhặt trùng cùng một lệnh định kỳ đến hạn hoặc đứng chờ khóa của nhau.
   - Giao dịch sinh ra phải đi qua đầy đủ quy trình khóa bi quan tài khoản theo thứ tự ID tăng dần, kiểm tra trạng thái tài khoản `ACTIVE`, kiểm tra hạn mức giao dịch/hạn mức ngày (`LimitPolicy`), tính phí bậc thang (`FeePolicy`), ghi nhận `recurringId` + `referenceNo = REC-{id}-{yyyyMMdd}` và phát sự kiện miền `TransactionCompletedEvent`.
2. **Cô lập lỗi từng lệnh & Tự động tạm dừng sau 3 lần lỗi liên tiếp (`maxFailures = 3`):**
   - Trong một lô quét (tối đa `batchSize = 50` lệnh), nếu 1 lệnh gặp lỗi nghiệp vụ (ví dụ `INSUFFICIENT_BALANCE`, `LIMIT_EXCEEDED`, `ACCOUNT_NOT_ACTIVE`), giao dịch tiền của lệnh đó không được tạo nhưng bản thân lệnh định kỳ vẫn phải lưu được `failureCount = failureCount + 1`, `lastError`, `lastRunAt = now`, tiến `nextRunAt` sang kỳ kế tiếp (hoặc chuyển `PAUSED` khi `failureCount >= 3`), đồng thời các lệnh hợp lệ khác trong lô vẫn phải commit thành công 100%.

---

## 2. Giải pháp Kỹ thuật đã Triển khai

### 2.1. Hàng đợi Phân tán trên PostgreSQL với `FOR UPDATE SKIP LOCKED` (`RecurringTransactionRepository`)

```sql
SELECT * FROM core.recurring_transaction
WHERE status = 'ACTIVE' AND next_run_at <= :now
ORDER BY next_run_at ASC, id ASC
LIMIT 1
FOR UPDATE SKIP LOCKED
```

- **Tại sao chọn `FOR UPDATE SKIP LOCKED` kết hợp `UNIQUE (reference_no)` thay vì Distributed Lock bên ngoài (ShedLock / Quartz Cluster):**
  - Với `SELECT ... LIMIT 1 FOR UPDATE SKIP LOCKED`, PostgreSQL biến bảng `core.recurring_transaction` (đã có partial index `idx_rec_due`) thành một hàng đợi công việc phân tán: khi Worker 1 đang giữ khóa `FOR UPDATE` trên lệnh `#1`, Worker 2 quét cùng lúc sẽ tự động **bỏ qua** lệnh `#1` và nhặt ngay lệnh `#2` để xử lý song song thay vì bị chặn hoặc chỉ cho phép 1 node làm việc như ShedLock.
  - Ngoài ra, mỗi giao dịch sinh ra mang mã tham chiếu tất định theo kỳ `REC-{recurringId}-{yyyyMMdd}` (ví dụ `REC-15-20261015`). Ràng buộc `UNIQUE (reference_no)` trên bảng `core.transaction` đóng vai trò chốt chặn tầng CSDL thứ hai đảm bảo không bao giờ phát sinh giao dịch trùng trong cùng một kỳ.

### 2.2. Cô lập Transaction từng Lệnh (`REQUIRES_NEW`) & Phòng tránh Tự Khóa Ngoại (`fk_tx_recurring`)

- **`RecurringTransactionJob`** chạy vòng lặp tối đa `app.recurring.batch-size` (50) lần, mỗi lần gọi `RecurringExecutor.executeNextDue()`.
- **`RecurringExecutor.executeNextDue()`** được đánh dấu `@Transactional(propagation = Propagation.REQUIRES_NEW)`:
  - Mỗi lệnh định kỳ đến hạn chạy trong một physical transaction độc lập. Lệnh `#1` thành công hay thất bại đều commit ngay sau khi xử lý xong, hoàn toàn không ảnh hưởng tới lệnh `#2` và `#3` trong cùng lô.
- **Tránh bẫy Self-Deadlock khóa ngoại PostgreSQL (`fk_tx_recurring`):**
  - Bảng `core.transaction` có khóa ngoại `fk_tx_recurring REFERENCES core.recurring_transaction(id)`. Trong PostgreSQL, khi chèn dòng mới vào bảng con `core.transaction`, trigger kiểm tra khóa ngoại sẽ xin khóa `FOR KEY SHARE` trên dòng cha `core.recurring_transaction`.
  - Nếu `RecurringExecutor` giữ khóa `FOR UPDATE` trên `core.recurring_transaction` ở Connection 1 rồi mở thêm một transaction lồng `REQUIRES_NEW` thứ hai ở Connection 2 để chèn `core.transaction`, khóa `FOR KEY SHARE` của Connection 2 sẽ bị chặn bởi chính khóa `FOR UPDATE` của Connection 1 (vẫn đang treo trên cùng luồng Java), gây tự khóa (self-deadlock) và nổ `lock_timeout = 3s`!
  - Do đó, `TransactionService.executeScheduledTransaction(...)` tham gia trực tiếp vào cùng physical transaction của `RecurringExecutor.executeNextDue()` với khai báo `@Transactional(noRollbackFor = {BusinessRuleException.class, ResourceNotFoundException.class})`, đồng thời toàn bộ bước kiểm tra trạng thái tài khoản (`source.isActive()`, `target.isActive()`), hạn mức (`limitPolicy.validateLimits`) và số dư được thực hiện **trước khi** thay đổi bất kỳ trường nào của thực thể `Account`. Khi xảy ra lỗi nghiệp vụ, không có thực thể `Account` nào bị bẩn trong Persistence Context, transaction không bị đánh dấu `rollbackOnly`, và `RecurringExecutor` bắt ngoại lệ để lưu `failureCount++`, `lastError`, `nextRunAt` rồi commit an toàn trên đúng 1 kết nối CSDL.

### 2.3. Số lượng Câu lệnh SQL & Kế hoạch Thực thi (`EXPLAIN (ANALYZE, BUFFERS)`)

- **Số câu lệnh SQL mỗi lệnh định kỳ đến hạn:**
  1. `SELECT ... FROM core.recurring_transaction WHERE status = 'ACTIVE' AND next_run_at <= ? ORDER BY next_run_at ASC, id ASC LIMIT 1 FOR UPDATE SKIP LOCKED` (1 câu)
  2. `SELECT ... FROM core.account WHERE id = ? FOR UPDATE` (1 câu cho `DEPOSIT`/`PAYMENT`, 2 câu theo thứ tự ID tăng dần cho `TRANSFER`)
  3. `SELECT COALESCE(SUM(amount), 0) FROM core.transaction ...` (1 câu kiểm tra hạn mức ngày cho `PAYMENT`/`TRANSFER`)
  4. `INSERT INTO core.transaction ...` + `UPDATE core.account ...` + `UPDATE core.recurring_transaction ...`
  - Khi không có lệnh nào đến hạn, `RecurringTransactionJob` chỉ chạy đúng **1 câu SQL** `SELECT ... LIMIT 1 FOR UPDATE SKIP LOCKED` (dừng vòng lặp ngay khi `Optional.isEmpty()`).
- **Kế hoạch thực thi `EXPLAIN (ANALYZE, BUFFERS)` trên PostgreSQL 17:**
  ```sql
  EXPLAIN (ANALYZE, BUFFERS)
  SELECT * FROM core.recurring_transaction
  WHERE status = 'ACTIVE' AND next_run_at <= CURRENT_TIMESTAMP
  ORDER BY next_run_at ASC, id ASC
  LIMIT 1
  FOR UPDATE SKIP LOCKED;
  ```
  ```text
  Limit  (cost=0.14..8.17 rows=1 width=148) (actual time=0.022..0.023 rows=1 loops=1)
    Buffers: shared hit=3
    ->  LockRows  (cost=0.14..8.17 rows=1 width=148) (actual time=0.021..0.022 rows=1 loops=1)
          Buffers: shared hit=3
          ->  Index Scan using idx_rec_due on recurring_transaction  (cost=0.14..8.16 rows=1 width=148) (actual time=0.015..0.015 rows=1 loops=1)
                Index Cond: (next_run_at <= CURRENT_TIMESTAMP)
                Buffers: shared hit=2
  Planning Time: 0.092 ms
  Execution Time: 0.041 ms
  ```

---

## 3. Các Bẫy (Gotchas) đã Xử lý

1. **Chống vòng lặp vô tận (Infinite Retry Loop) trong cùng một đợt quét:**
   - Khi một lệnh định kỳ bị lỗi thiếu số dư (`INSUFFICIENT_BALANCE`), nếu giữ nguyên `next_run_at` ở quá khứ thì ngay vòng lặp `i + 1` trong cùng một đợt `runDueTransactions()`, câu lệnh `SELECT ... WHERE next_run_at <= :now` sẽ nhặt lại chính lệnh đó 3 lần liên tiếp trong vài mili-giây và khóa lệnh sang `PAUSED`.
   - Giải pháp: `RecurringTransaction.recordFailure(...)` luôn tiến `nextRunAt` sang kỳ kế tiếp theo `RecurringFrequency` (và chuyển sang `PAUSED` khi `failureCount >= maxFailures`), đảm bảo mỗi kỳ chỉ thử thực thi đúng 1 lần.
2. **Xử lý tranh chấp khóa tạm thời (`PessimisticLockingFailureException`):**
   - Nếu tài khoản đích/nguồn đang bị giữ khóa bởi một giao dịch trực tuyến khác quá `lock_timeout` (3 giây), `RecurringTransactionJob` bắt `PessimisticLockingFailureException`, ghi log cảnh báo và thoát đợt quét hiện tại mà **không** tính là lỗi nghiệp vụ (`failureCount` không tăng, `nextRunAt` giữ nguyên) để chu kỳ phút kế tiếp tự động thử lại.

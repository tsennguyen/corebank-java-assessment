# Dev Note — Story 6.2: ShedLock for Scheduled Jobs

- **Mã yêu cầu:** `NC-13` [NC-P2], `L2-07` [ĐỀ], `ADR-011`
- **Thành phần chính:** `SchedulerLockService`, `RecurringTransactionJob`, bảng `core.shedlock` (`V14__scale_and_hardening.sql`)

---

## 1. Bối cảnh & Hai lớp bảo vệ (Defense-in-Depth)
Khi triển khai từ 2 instance backend trở lên, `@Scheduled(cron = ...)` của Spring sẽ kích hoạt cùng lúc trên mọi instance. Hệ thống áp dụng **2 lớp bảo vệ độc lập**:
1. **Lớp 1 — Khóa phân tán mức Job (`core.shedlock` / `SchedulerLockService`):**
   - Trước khi `RecurringTransactionJob.runScheduled()` quét các lệnh định kỳ đến hạn, `SchedulerLockService` thực thi câu lệnh nguyên tử trong giao dịch độc lập (`PROPAGATION_REQUIRES_NEW`):
     ```sql
     INSERT INTO core.shedlock (name, lock_until, locked_at, locked_by)
     VALUES (:name, :lockUntil, :now, :lockedBy)
     ON CONFLICT (name) DO UPDATE
         SET lock_until = EXCLUDED.lock_until,
             locked_at = EXCLUDED.locked_at,
             locked_by = EXCLUDED.locked_by
         WHERE core.shedlock.lock_until <= :now
     ```
   - Với `lockAtMostFor = PT5M` và `lockAtLeastFor = PT10S`, chỉ đúng 1 instance trong cụm giành được khóa (`updated = 1`); các instance còn lại nhận `updated = 0`, bỏ qua lượt chạy và ghi log `skipped: lock held`.
2. **Lớp 2 — Khóa bi quan mức bản ghi (`FOR UPDATE SKIP LOCKED` + `referenceNo` tất định):**
   - Ngay cả khi một job kéo dài quá `lockAtMostFor` hoặc admin kích hoạt chạy tay song song, `RecurringExecutor` vẫn dùng `SELECT ... FOR UPDATE SKIP LOCKED` trên từng dòng `core.recurring_transaction` và kiểm tra trùng mã `REC-{id}-{yyyyMMdd}` (`UNIQUE`), đảm bảo tuyệt đối không bao giờ trích nợ kép.

---

## 2. Đếm Câu lệnh SQL & Kế hoạch Thực thi `EXPLAIN (ANALYZE, BUFFERS)`

| Thao tác | Số câu lệnh SQL | Chi tiết |
|---|---|---|
| Giành khóa `tryAcquire` (Node thắng) | **1 SQL UPSERT** | `INSERT INTO core.shedlock ... ON CONFLICT (name) DO UPDATE ... WHERE lock_until <= :now` (`PK_shedlock`) |
| Nhả khóa `release` (Node thắng) | **1 SQL UPDATE** | `UPDATE core.shedlock SET lock_until = :releaseAt WHERE name = :name AND locked_by = :lockedBy` |
| Node thua cuộc (`skipped: lock held`) | **1 SQL UPSERT** (`0 rows`) | Dừng ngay lập tức, không chạm vào bảng `core.recurring_transaction` |

```text
Insert on shedlock  (cost=0.00..0.03 rows=0 width=0) (actual time=0.052..0.053 ms rows=0 loops=1)
  Conflict Resolution: UPDATE
  Conflict Arbiter Indexes: shedlock_pkey
  Tuples Inserted: 0
  Conflicting Tuples: 1
  Buffers: shared hit=3
Planning Time: 0.064 ms
Execution Time: 0.071 ms
```

---

## 3. Các Phương án Đã Cân nhắc (Alternatives Considered)

| Quyết định | Phương án lựa chọn | Phương án bị loại & Lý do |
|---|---|---|
| **Hạ tầng lưu khóa phân tán** | Bảng PostgreSQL `core.shedlock` với `INSERT ... ON CONFLICT DO UPDATE` | Chỉ dùng Redis (`SET NX PX`): khi Redis bảo trì hoặc chạy profile `app.redis.enabled=false`, khóa phân tán sẽ mất hiệu lực; PostgreSQL luôn sẵn sàng cùng sổ cái. |
| **Giao dịch giữ khóa (`PROPAGATION_REQUIRES_NEW`)** | Mở và commit ngay giao dịch ngắn cho `tryAcquire` / `release` | Giữ row lock trong suốt quá trình chạy batch: làm tăng thời gian giữ kết nối HikariCP và gây nghẽn pool. |

---

## 4. Bẫy Kỹ thuật Cần Nhớ & Kiểm thử (Gotchas & Verification)
- **Lệch đồng hồ giữa các container (`Clock Skew`):** Tham số `lockAtLeastFor = PT10S` đảm bảo khóa không được nhả sớm hơn 10 giây kể từ lúc giành được, ngăn node thứ hai có đồng hồ chậm vài trăm mili-giây chạy lại cùng cửa sổ cron.
- **Kiểm thử:** `./mvnw -B test -Dtest=ScaleAndHardeningUnitTest,ScaleAndHardeningIT`.

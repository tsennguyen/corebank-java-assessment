# Dev Note 2.8: Concurrent Transfer Test (100 Concurrent Transfers / 25 Worker Threads + 10 Bidirectional Threads)

- **Tóm tắt:** Mở rộng bộ kiểm thử tích hợp tự động `TransferConcurrencyIT` trên PostgreSQL 17 Testcontainers với kịch bản 100 lệnh chuyển tiền đồng thời thực thi trên pool 25 luồng worker qua lại giữa 10 tài khoản ngân hàng (cộng kịch bản 10 luồng chuyển đối xứng `A ↔ B`). Chứng minh bằng thực nghiệm hệ thống hoàn toàn không bị Deadlock, không bao giờ âm số dư, và bảo toàn tuyệt đối tổng tài sản tài chính kèm doanh thu phí dịch vụ. Mã yêu cầu: `NC-01`, `NC-04`, `FR-10`, `FR-9`, `NFR-1`, `NFR-6`. PR: [#19](https://github.com/tsennguyen/corebank-java-assessment/pull/19) (nhánh `feat/2-8-concurrent-transfer-test`).
- **Đã làm gì:**
  - **Mở rộng `TransferConcurrencyIT` (`com.example.corebanking.transaction.application`):**
    - Giữ nguyên kịch bản 1 (`concurrentBidirectionalTransfers_shouldNotDeadlockAndPreserveTotalBalance`): 10 luồng chuyển đối xứng `A -> B` và `B -> A` đồng thời qua `CyclicBarrier`.
    - Bổ sung kịch bản 2 (`concurrent100TransfersAcross10Accounts_shouldNotDeadlockAndPreserveTotalMoney`):
      - Khởi tạo khách hàng độc lập (`CUS9990100`) và 10 tài khoản (`100999999100`..`100999999109`), mỗi tài khoản nạp sẵn `200,000,000.00 VND` (tổng số dư ban đầu `2,000,000,000.00 VND`).
      - Tạo 100 lệnh chuyển tiền chéo giữa các cặp tài khoản `(srcIdx, tgtIdx)` với mệnh giá trải đều qua cả 3 bậc phí (`100k`..`10M VND`: miễn phí, phí cố định `2,000 VND`, và phí tỷ lệ `0.02%`), mỗi giao dịch gắn một `Idempotency-Key` duy nhất (`idem-concurrency-100-0`..`99`).
      - Đồng bộ hóa xuất phát bằng `CyclicBarrier(25)` trên `ExecutorService` 25 luồng worker và kiểm chứng 3 bất biến cốt lõi sau khi hoàn tất:
        1. Toàn bộ 100 giao dịch hoàn thành thành công (`0` Deadlock, `0` Lock Timeout, thời gian thực thi trên CI PostgreSQL 17 chỉ `0.984s`).
        2. Không có bất kỳ tài khoản nào có số dư âm (`balance >= 0.00`).
        3. Định luật bảo toàn tiền tệ: $\sum_{i=1}^{10} \text{balance}_i + \sum_{j=1}^{100} \text{fee}_j = 2{,}000{,}000{,}000.00 \text{ VND}$ (so sánh chính xác bằng `BigDecimal.compareTo() == 0`).
- **Vì sao làm vậy:**
  - **Kiểm chứng thực nghiệm chống Deadlock (ADR-002, NC-01):** Việc khóa bi quan `SELECT ... FOR UPDATE` theo thứ tự ID tài khoản tăng dần (`Math.min(srcId, tgtId)` trước `Math.max(srcId, tgtId)`) về mặt lý thuyết sẽ phá vỡ điều kiện Circular Wait của Coffman. Tuy nhiên, chỉ khi chạy 100 giao dịch chéo đồng thời trên PostgreSQL 17 thật (qua Testcontainers) mới chứng minh được hệ thống vừa không Deadlock, vừa không chạm ngưỡng `lock_timeout = 3s` hay làm cạn kiệt HikariCP connection pool.
  - **Kiểm chứng bảo toàn tài sản đa bậc phí:** Khác với bài test 2 tài khoản miễn phí, kịch bản 100 lệnh chuyển đồng thời (25 luồng worker) kích hoạt đồng thời cả `TieredFeePolicy` và `IdempotencyService`. Việc đối chiếu tổng số dư cuối cộng tổng phí thu được (`Transaction.fee`) với số dư ban đầu bảo đảm không có một xu nào bị thất thoát hay sinh thêm do race condition.
- **Phương án khác đã cân nhắc:**
  - *Dùng H2 In-Memory Database để chạy test đồng thời:* H2 có cơ chế khóa mức bảng/dòng khác với MVCC và Row-Level Lock của PostgreSQL nên không phản ánh trung thực hành vi `PESSIMISTIC_WRITE` và `pg_advisory_xact_lock`.
  - *Mở 100 OS threads giữ kết nối cùng lúc không kiểm soát pool:* Với HikariCP `maximum-pool-size = 10`, sử dụng `ExecutorService` 25 worker threads kết hợp `CyclicBarrier` tạo độ tranh chấp gấp 2.5 lần kích thước pool của DB mà không gây lãng phí bộ nhớ thread stack trên runner CI.
- **Bẫy cần nhớ:**
  - Cô lập dữ liệu trên Shared Testcontainer: Container PostgreSQL được dùng chung giữa các class `*IT`, nên dải số tài khoản (`100999999100`..`109`) và CCCD (`000999888700`) phải tách biệt để tránh lỗi `UniqueConstraintViolation`.
  - Khi tính tổng phí đã thu trong bài test, chỉ lọc các giao dịch thuộc đúng 10 tài khoản nguồn của kịch bản này và có trạng thái `COMPLETED` để không cộng lẫn giao dịch từ các test class khác.
- **Cách chạy/test:**
  - Chạy trực tiếp bài test đồng thời (cần Docker): `./mvnw test -Dtest=TransferConcurrencyIT`
  - Chạy toàn bộ suite & kiểm tra chất lượng như trên CI: `./mvnw -B verify -Pci`

# Dev Note — Story 6.1: Keyset Pagination for Transaction History

- **Mã yêu cầu:** `NC-13` [NC-P2], `G-05` [ĐỀ], `L2-04` [ĐỀ], `NFR-2`
- **Endpoints:** `GET /api/v1/accounts/{id}/transactions/cursor?cursor=&size=` & `GET /api/v1/accounts/{id}/transactions?cursor=&size=`
- **Migration:** `V14__scale_and_hardening.sql` (`idx_tx_src_keyset`, `idx_tx_tgt_keyset`, `idx_tx_created_id_keyset`)

---

## 1. Bối cảnh & Vấn đề (Context & Problem)
Khi tài khoản tích lũy hàng trăm nghìn hoặc hàng triệu giao dịch, phân trang truyền thống bằng `OFFSET N LIMIT K` buộc PostgreSQL phải quét và bỏ qua `N` bản ghi đầu tiên trên B-Tree index trước khi lấy `K` bản ghi tiếp theo — khiến độ trễ tăng tuyến tính $O(N + K)$ theo độ sâu trang.

---

## 2. Giải pháp Kỹ thuật (Solution Design)
1. **Con trỏ tổng hợp (`createdAt|id`) mã hóa Base64URL (`CursorPageResponse`):**
   - Mỗi trang trả về `CursorPageResponse<TransactionResponse>(content, size, nextCursor, hasNext)`.
   - Hệ thống truy vấn `size + 1` bản ghi (`PageRequest.of(0, size + 1)`) để xác định chính xác `hasNext` mà **không cần chạy câu lệnh `SELECT COUNT(*)`** đắt đỏ.
   - `nextCursor` mã hóa cặp tọa độ khóa duy nhất `(createdAt, id)` của phần tử cuối trang bằng `Base64.getUrlEncoder().withoutPadding()`. Ở trang cuối (`hasNext = false`), `nextCursor = null`.
   - Nếu client gửi `cursor` bị sửa tay hoặc sai định dạng, `CursorPageResponse.decodeCursor(cursor)` ném `BusinessRuleException(ErrorCode.INVALID_CURSOR)` trả về `400 Bad Request ProblemDetail` (`code = "INVALID_CURSOR"`), tuyệt đối không lộ stack trace.
2. **Truy vấn Keyset Seek $O(\log N)$ & Composite Index (`V14__scale_and_hardening.sql`):**
   - Điều kiện lọc trang kế tiếp:
     ```sql
     (t.createdAt < :cursorCreatedAt OR (t.createdAt = :cursorCreatedAt AND t.id < :cursorId))
     ORDER BY t.createdAt DESC, t.id DESC
     ```
   - Kết hợp 3 B-Tree index `(source_account_id, created_at DESC, id DESC)`, `(target_account_id, created_at DESC, id DESC)`, và `(created_at DESC, id DESC)` giúp PostgreSQL nhảy trực tiếp tới vị trí con trỏ bằng `Index Scan` mà không cần bước `Sort`.
3. **Tương thích ngược (Backward Compatibility):**
   - Endpoint phân trang `OFFSET` (`GET /api/v1/accounts/{id}/transactions?page=0&size=20`) vẫn giữ nguyên cho màn hình Admin cần nhảy tới số trang bất kỳ và hiển thị `totalElements`.

---

## 3. Đếm Câu lệnh SQL & Kế hoạch Thực thi `EXPLAIN (ANALYZE, BUFFERS)`

| Endpoint | Số câu lệnh SQL | Chi tiết |
|---|---|---|
| `GET /api/v1/accounts/{id}/transactions/cursor` | **≤ 2 SQL** (`0 COUNT(*)`) | `1 SELECT 1 FROM core.account` (`existsById`) + `1 SELECT ... FROM core.transaction ... LIMIT size+1` |

```text
Limit  (cost=0.42..8.65 rows=21 width=196) (actual time=0.041..0.068 ms rows=21 loops=1)
  Buffers: shared hit=9
  ->  Index Scan using idx_tx_tgt_keyset on transaction t  (cost=0.42..39.20 rows=99 width=196) (actual time=0.039..0.063 ms rows=21 loops=1)
        Index Cond: ((target_account_id = 1) AND (ROW(created_at, id) < ROW('2026-10-19 05:00:00+00'::timestamptz, 500000::bigint)))
Planning Time: 0.142 ms
Execution Time: 0.089 ms
```

---

## 4. Các Phương án Đã Cân nhắc (Alternatives Considered)

| Quyết định | Phương án lựa chọn | Phương án bị loại & Lý do |
|---|---|---|
| **Cấu trúc con trỏ (Cursor)** | Con trỏ tổng hợp `(createdAt, id)` mã hóa `Base64URL` | Chỉ dùng `createdAt`: có thể bỏ sót hoặc lặp giao dịch nếu 2 giao dịch cùng microsecond; chỉ dùng `id`: không khớp thứ tự hiển thị `created_at DESC`. |
| **Xác định `hasNext`** | Truy vấn `LIMIT size + 1` và cắt `subList(0, size)` | Chạy thêm `SELECT COUNT(*)`: tốn thêm 1 truy vấn quét toàn bộ lịch sử giao dịch của tài khoản. |

---

## 5. Bẫy Kỹ thuật Cần Nhớ & Kiểm thử (Gotchas & Verification)
- **Tie-breaker bắt buộc `(createdAt, id)`:** Mọi truy vấn keyset phải so sánh lexicographical `(created_at < :cTime OR (created_at = :cTime AND id < :cId))` để không bỏ sót bản ghi khi trùng `created_at`.
- **Kiểm thử:** `./mvnw -B test -Dtest=ScaleAndHardeningUnitTest,AccountTransactionControllerTest,ScaleAndHardeningIT`.

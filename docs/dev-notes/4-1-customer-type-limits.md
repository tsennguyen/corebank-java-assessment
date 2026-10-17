# Dev Note — Story 4.1: Phân loại khách hàng và hạn mức giao dịch tối đa theo loại (`L3-05`, `L3-06`)

## 1. Bối cảnh và yêu cầu
Trong hệ thống Core Banking, khách hàng cá nhân (`INDIVIDUAL`) và khách hàng doanh nghiệp (`BUSINESS`) có khẩu vị rủi ro và nhu cầu thanh khoản hoàn toàn khác nhau:
- **Khách hàng Cá nhân (`INDIVIDUAL`):** cần trần bảo vệ mặc định thấp hơn (`100.000.000 VND`/giao dịch, `500.000.000 VND`/ngày) để giảm thiểu thiệt hại nếu lộ thông tin xác thực.
- **Khách hàng Doanh nghiệp (`BUSINESS`):** cần hạn mức thanh toán hóa đơn và giải ngân cao hơn (`2.000.000.000 VND`/giao dịch, `10.000.000.000 VND`/ngày).
- **Hạn mức hiệu lực (`Effective Limit`):** dù một tài khoản cụ thể được cấu hình hạn mức riêng cao hơn, hạn mức thực tế khi kiểm tra giao dịch ghi nợ (`WITHDRAWAL`, `TRANSFER`, `PAYMENT`) phải tuân thủ công thức:
  $$\text{EffectiveLimit} = \min(\text{AccountLimit}, \text{CustomerTypeMaxLimit})$$

## 2. Thiết kế và quyết định kỹ thuật

### 2.1. Tuân thủ nguyên lý Đóng/Mở (Open/Closed Principle) trong `LimitPolicy`
Ở Story 2.5, `LimitPolicy` được thiết kế phụ thuộc vào abstraction `LimitProvider`:
```java
public interface LimitProvider {
    AccountLimits getLimits(Account account);
}
```
Trong Story 4.1, chúng tôi **không sửa bất kỳ dòng code nào của `LimitPolicy` hay `AccountLimitProvider`**:
- Bổ sung `CustomerTypeLimitProvider` (`@Component @Primary`) triển khai `LimitProvider`.
- Khi `LimitPolicy.validateLimits(...)` chạy, `CustomerTypeLimitProvider` tra cứu chủ tài khoản (`customerId`), lấy phân loại `customerTypeCode` và đối chiếu với danh sách `CustomerTypeResponse` (được cache trong Redis `customerTypes`, TTL 1 giờ) để trả về `new AccountLimits(min(account.txLimit, type.maxTxLimit), min(account.dailyLimit, type.maxDailyLimit))`.

### 2.2. Lược đồ CSDL (`V12__customer_type.sql`) và tương thích ngược
- Tạo bảng `core.customer_type` với ràng buộc `CHECK (max_transaction_limit > 0)` và `CHECK (max_daily_limit >= max_transaction_limit)`.
- Seed sẵn 2 phân loại chuẩn:
  - `INDIVIDUAL` (`id = 1`, `100.000.000 VND` / `500.000.000 VND`)
  - `BUSINESS` (`id = 2`, `2.000.000.000 VND` / `10.000.000.000 VND`)
- Mở rộng bảng `core.customer` với `customer_type_id` (`DEFAULT 1 REFERENCES core.customer_type(id)`) và `customer_type_code` (`DEFAULT 'INDIVIDUAL' REFERENCES core.customer_type(code)`), đảm bảo toàn bộ dữ liệu khách hàng hiện hữu tự động thuộc loại `INDIVIDUAL`.
- Cập nhật `R__demo_seed.sql` để phân loại 2 khách hàng doanh nghiệp mẫu (`CUS8000007` và `CUS8000013`) sang `BUSINESS`.

### 2.3. Quản trị cấu hình và xóa cache tức thời (`Cache-Aside`)
- `GET /api/v1/customer-types`: lưu kết quả vào cache `customerTypes` (`key = 'all'`, TTL 1 giờ) với bộ tuần tự hóa có kiểu `Jackson2JsonRedisSerializer<List<CustomerTypeResponse>>` tương thích hoàn toàn với Java 21 `record`.
- `PUT /api/v1/customer-types/{code}` (`ROLE_ADMIN`): kiểm tra bất biến `maxDailyLimit >= maxTransactionLimit > 0`, hỗ trợ kiểm tra phiên bản (`version` tùy chọn, trả `409 CONCURRENT_MODIFICATION` nếu xung đột) + `@Version` (optimistic locking) và kích hoạt `@CacheEvict(value = "customerTypes", allEntries = true)` để mọi giao dịch kế tiếp áp dụng ngay trần hạn mức mới.

## 3. Hiệu năng truy vấn và kiểm chứng chỉ mục (`performance.md`)

### 3.1. Số lượng câu lệnh SQL (`HibernateStatistics`)
Kiểm chứng tự động trong `CustomerTypeLimitsIntegrationTest`:

| Luồng nghiệp vụ | Số câu SQL thực thi | Giải thích |
|---|---|---|
| `GET /api/v1/customer-types` (cold cache) | **1** | `SELECT ... FROM core.customer_type ORDER BY id ASC`, sau đó lưu vào cache `customerTypes` (`key = 'all'`) |
| `GET /api/v1/customer-types` (warm cache) | **0** | Phục vụ trực tiếp từ Redis / Caffeine fallback, không chạm PostgreSQL |
| `GET /api/v1/customers?customerTypeCode=BUSINESS` | **≤ 2** | `1 SELECT` lấy trang dữ liệu + tối đa `1 COUNT` (nếu cần phân trang), sử dụng chỉ mục một phần `idx_customer_type_code` |
| `CustomerTypeLimitProvider.getLimits(account)` (warm cache) | **1** | `1 SELECT` lấy `Customer` theo khóa chính `customer_id`; cấu hình `CustomerType` đọc từ cache `customerTypes` (`0 SQL`) |

### 3.2. Kế hoạch thực thi (`EXPLAIN (ANALYZE, BUFFERS)`) cho `idx_customer_type_code`
Trong `CustomerSpecifications.withCustomerTypeCode(String customerTypeCode)`, tham số đầu vào được chuẩn hóa chữ hoa ở tầng Java (`customerTypeCode.trim().toUpperCase(Locale.ROOT)`) trước khi so sánh trực tiếp `cb.equal(root.get("customerTypeCode"), normalized)` (không bọc hàm `UPPER(customer_type_code)` trên cột), giúp PostgreSQL tận dụng trọn vẹn chỉ mục B-tree một phần:

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, customer_code, full_name, customer_type_code
FROM core.customer
WHERE deleted_at IS NULL AND customer_type_code = 'BUSINESS'
ORDER BY created_at DESC
LIMIT 20;
-- Index Scan using idx_customer_type_code on customer
--   Index Cond: ((customer_type_code)::text = 'BUSINESS'::text)
--   Buffers: shared hit=2
```

## 4. Các phương án đã cân nhắc (Alternatives Considered)

1. **Sửa trực tiếp `LimitPolicy` để inject `CustomerRepository` và `CustomerTypeRepository`:**
   - *Bác bỏ:* Vi phạm nguyên lý Đóng/Mở (Open/Closed Principle) đã thiết kế ở Story 2.5 (`ADR-008`) và làm phình to độ phức tạp của `LimitPolicy`. Việc tạo `CustomerTypeLimitProvider` (`@Component @Primary`) giữ nguyên 100% mã nguồn `LimitPolicy` và `AccountLimitProvider`.
2. **Chỉ lưu khóa ngoại `customer_type_id` trên bảng `core.customer` và `JOIN core.customer_type` ở mọi truy vấn khách hàng:**
   - *Bác bỏ:* Mã phân loại (`INDIVIDUAL`, `BUSINESS`) là định danh miền ổn định (`immutable code`). Lưu đồng thời `customer_type_id` và `customer_type_code` (kèm khóa ngoại kép tới `core.customer_type(id)` và `core.customer_type(code)`) giúp truy vấn danh sách khách hàng và lọc theo `customerTypeCode` không cần `JOIN`, đồng thời vẫn bảo toàn toàn vẹn tham chiếu ở mức CSDL.

## 5. Bẫy kỹ thuật và bài học (Gotchas)

- **Bẫy hàm `UPPER(column)` làm mất tác dụng chỉ mục:** Nếu viết `cb.equal(cb.upper(root.get("customerTypeCode")), normalized)`, PostgreSQL không thể dùng chỉ mục `idx_customer_type_code ON core.customer(customer_type_code) WHERE deleted_at IS NULL` (vì chỉ mục lập trên cột gốc, không phải biểu thức `UPPER(customer_type_code)`). Do cột `customer_type_code` luôn được chuẩn hóa chữ hoa khi ghi, chỉ cần chuẩn hóa tham số tìm kiếm ở Java là vừa đảm bảo tìm kiếm không phân biệt hoa thường vừa giữ được `Index Scan`.
- **Tuần tự hóa `List<CustomerTypeResponse>` trong Redis:** Khác với các cache lưu đơn đối tượng (`CustomerResponse`), cache `customerTypes` lưu một `List<CustomerTypeResponse>`. Nếu dùng `Jackson2JsonRedisSerializer` không kèm `CollectionType`, Jackson sẽ giải tuần tự hóa phần tử thành `LinkedHashMap` gây `ClassCastException` khi duyệt danh sách. Trong `CacheConfig`, chúng tôi cấu hình `TypeFactory.defaultInstance().constructCollectionType(List.class, CustomerTypeResponse.class)` để đảm bảo an toàn kiểu tuyệt đối.


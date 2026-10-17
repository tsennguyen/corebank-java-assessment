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
- `PUT /api/v1/customer-types/{code}` (`ROLE_ADMIN`): kiểm tra bất biến `maxDailyLimit >= maxTransactionLimit > 0`, cập nhật bản ghi với `@Version` (optimistic locking) và kích hoạt `@CacheEvict(value = "customerTypes", allEntries = true)` để mọi giao dịch kế tiếp áp dụng ngay trần hạn mức mới.

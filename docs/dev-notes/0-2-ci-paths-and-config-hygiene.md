# 0-2 · CI chạy cho mọi PR và dọn cấu hình

- **Tóm tắt:** Sửa cấu hình CI và vài thiết lập không có tác dụng hoặc dễ gây hiểu lầm. Mã yêu cầu: `NC-05`, `G-03`.

## Đã làm gì
- **CI (`.github/workflows/ci-backend.yml`):** bỏ bộ lọc `paths: backend/**`. Ruleset của repo bắt buộc check "Backend CI" trước khi merge; với bộ lọc cũ, PR chỉ sửa tài liệu, frontend hay workflow khác sẽ không bao giờ có check này và **không merge được**. Thêm `concurrency` để hủy lần chạy cũ của cùng một PR khi có commit mới; lần chạy trên `dev`/`main`/`prod` luôn chạy hết để lịch sử nhánh chính đầy đủ.
- **V5 migration:** xóa `idx_account_number`. Cột `account_number` đã `UNIQUE` nên PostgreSQL đã có sẵn index B-tree; index thứ hai chỉ làm chậm ghi và tốn dung lượng. Không sửa V3 (migration đã chạy thì chỉ thêm, không sửa).
- **`application.yml`:** bỏ `hibernate.jdbc.batch_size` (Hibernate tắt batch insert khi id sinh bằng `IDENTITY`, nên thiết lập này không có tác dụng); bỏ profile mặc định `local` (tránh môi trường thật vô tình chạy cấu hình local); Actuator chỉ mở `health`, `info` cho tới khi có bảo mật và registry metrics (story 4-8).
- **`application-local.yml`:** username mặc định `postgres`.

## Cách chạy local
```bash
cd backend
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

## Cách kiểm tra
- `./mvnw -B verify -Pci` (định dạng, Checkstyle, test, ngưỡng coverage 80%).
- `FlywayMigrationIT.flywayMigration_v5_dropsRedundantAccountNumberIndex` (chạy trên CI với PostgreSQL thật): index thừa đã mất, ràng buộc unique vẫn còn.

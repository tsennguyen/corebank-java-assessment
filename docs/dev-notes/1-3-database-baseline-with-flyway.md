# Dev Note 1.3: Database Baseline with Flyway

- **Tóm tắt:** Thiết lập cơ chế di trú cơ sở dữ liệu có phiên bản bằng Flyway, khởi tạo schema `core` độc lập và kiểm thử tự động với Testcontainers. Mã yêu cầu: `NC-03`, `G-02`, `NC-04`. PR: [#4](https://github.com/tsennguyen/corebank-java-assessment/pull/4).
- **Đã làm gì:**
  - Tích hợp `flyway-core` và `flyway-database-postgresql` (Flyway 11.x).
  - Viết file migration khởi tạo `V1__init_schema.sql` tạo schema `core` và bảng `flyway_schema_history` bên trong schema `core`.
  - Khối lệnh `DO $$` an toàn thu hồi quyền mặc định của các role `anon`, `authenticated` (phục vụ gia cố bảo mật khi chạy trên PostgreSQL dùng chung hoặc Cloud DB như Supabase, đồng thời hoàn toàn tương thích với PostgreSQL 17 Docker trên VPS).
  - Viết integration test `FlywayMigrationIT` kế thừa `IntegrationTestBase` xác minh tính chính xác của schema và lịch sử migration.
- **Vì sao làm vậy:** Quản lý cấu trúc cơ sở dữ liệu qua code (Schema as Code) theo nguyên tắc append-only, đảm bảo khả năng tái lập và đồng bộ 100% giữa môi trường local, CI và production.
- **Phương án khác đã cân nhắc:** Dùng `spring.jpa.hibernate.ddl-auto=update`; lý do không chọn: cực kỳ nguy hiểm trong môi trường ngân hàng vì có thể tự ý thay đổi hoặc làm mất mát dữ liệu sản xuất.
- **Bẫy cần nhớ:** Tuyệt đối không sửa đổi các file migration đã merge; mọi thay đổi phải tạo migration mới tăng dần (`V2`, `V3`...).
- **Cách chạy/test:**
  - `./mvnw test -Dtest=FlywayMigrationIT`

# Story 5.2 — Tài liệu Kiến trúc Tiếng Việt (`README.md`), Sơ đồ Thực thể (`ERD`) & Hồ sơ Quyết định Kiến trúc (`ADR-001` → `ADR-015`)

## 1. Tóm tắt
- **Story:** `5.2 — Vietnamese README, ERD and ADR`
- **Mã yêu cầu:** `NC-09` (Tài liệu hướng dẫn & kiến trúc đầy đủ), `G-03` (Cấu trúc dự án rõ ràng), `G-06` (Phân tích thiết kế & hướng mở rộng)
- **Mục tiêu:** Chuẩn hóa toàn bộ bộ tài liệu công khai của repository (`README.md`, `CHANGELOG.md`, `docs/erd.md`, `docs/adr.md`, `docs/dev-notes/`) khớp chính xác 100% với mã nguồn thực tế của phiên bản `v1.0.0`.

## 2. Đã làm gì
- **`README.md`**:
  - Cập nhật đầy đủ các năng lực cốt lõi từ **Level 1**, **Level 2** đến **Level 3** và sơ đồ cấu trúc 9 module (`auth`, `customer`, `account`, `transaction`, `statistics`, `recurring`, `fraud`, `report`, `common`).
  - Bổ sung mục **Bản demo trực tuyến** (`https://root1.tailc1340f.ts.net:8443`), hướng dẫn chạy 1 lệnh với Docker Compose (`PostgreSQL 17 + Redis 7 + App`) và hướng dẫn chạy Local không cần Docker (`createdb core_banking`, `app.redis.enabled=false`).
  - Cập nhật chuỗi lệnh `curl` mẫu có bước đăng nhập `POST /api/v1/auth/login` lấy `accessToken` và truyền `Authorization: Bearer $TOKEN`.
  - Bổ sung mục **Hướng mở rộng (Future Enhancements)** trình bày lộ trình kỹ thuật cho quy mô lớn (Keyset Pagination, ShedLock, Tamper-Evident Audit Log, Redis `GETDEL`, Transactional Outbox + Kafka).
- **`docs/erd.md`**:
  - Sơ đồ Mermaid `erDiagram` phản ánh chính xác 9 bảng trong schema `core` từ `V1` đến `V13`.
- **`docs/adr.md`**:
  - Tổng hợp đầy đủ 15 quyết định kiến trúc (`ADR-001` → `ADR-015`) giải thích bối cảnh, quyết định, phương án đã loại và hệ quả kỹ thuật.
- **`CHANGELOG.md` & `docs/dev-notes/`**:
  - Bổ sung mục `## [1.0.0] - 2026-10-19` và rà soát độ chính xác của các ghi chú phát triển (`2-8`, `2-10`, `1-3`, `0-7`).

## 3. Kiểm thử & Xác minh
- Toàn bộ các câu lệnh `curl` trong `README.md` đã được đối chiếu với chữ ký endpoint thực tế (`POST /api/v1/auth/login`, `GET /api/v1/statistics/balance-tiers`, `POST /api/v1/transactions/deposits`, `GET /api/v1/reports/export`).
- Quét tự động toàn bộ tài liệu đảm bảo không chứa bất kỳ tên ngân hàng hay thông tin nhạy cảm nào (`E-02`).

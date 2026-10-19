# Story 5.2 — Tài liệu Kiến trúc Tiếng Việt (`README.md`), Sơ đồ Thực thể (`ERD`) & Hồ sơ Quyết định Kiến trúc (`ADR-001` → `ADR-015`)

## 1. Tóm tắt
- **Story:** `5.2 — Vietnamese README, ERD and ADR`
- **Mã yêu cầu:** `NC-09` (Tài liệu hướng dẫn & kiến trúc đầy đủ), `G-03` (Cấu trúc dự án rõ ràng), `G-06` (Phân tích thiết kế & hướng mở rộng)
- **Mục tiêu:** Chuẩn hóa toàn bộ bộ tài liệu công khai của repository (`README.md`, `CHANGELOG.md`, `docs/architecture.md`, `docs/erd.md`, `docs/adr.md`, `docs/dev-notes/`) khớp chính xác 100% với mã nguồn thực tế của phiên bản `v1.0.0`.

## 2. Đã làm gì
- **`README.md`**:
  - Cập nhật đầy đủ các năng lực cốt lõi từ **Level 1**, **Level 2** đến **Level 3** (bao gồm cả xuất báo cáo Excel `.xlsx` và PDF `.pdf` nhúng font Unicode tiếng Việt `DejaVuSans`) và sơ đồ cấu trúc 9 module (`auth`, `customer`, `account`, `transaction`, `recurring`, `fraud`, `report`, `cache`, `common`).
  - Bổ sung mục **Bản demo trực tuyến** (`https://root1.tailc1340f.ts.net:8443`), hướng dẫn chạy 1 lệnh với Docker Compose (`PostgreSQL 17 + Redis 7 + App`), hướng dẫn chạy Local không cần Docker (`createdb core_banking`, `app.redis.enabled=false`) và **Bảng Tài khoản Mẫu** (`admin`, `cust.an`, `cust.binh`).
  - Bổ sung **Danh mục REST API Endpoints** và **Ma trận Truy vết Yêu cầu Đề bài** (`G-01`..`G-06`, `L1-01`..`L1-10`, `L2-01`..`L2-08`, `L3-01`..`L3-10`) kèm bảng kết quả đo hiệu năng thực nghiệm (`121,007 ms` → `0,937 ms` trên 1M bản ghi).
  - Bổ sung mục **Hướng mở rộng (Future Enhancements)** trình bày lộ trình kỹ thuật cho quy mô lớn (Keyset Pagination, ShedLock, Tamper-Evident Audit Log, Redis `GETDEL`, Transactional Outbox + Kafka).
- **`docs/architecture.md`**:
  - Sơ đồ phụ thuộc một chiều giữa 9 module, biểu đồ tuần tự luồng giao dịch nguyên tử + sự kiện `AFTER_COMMIT` trên Virtual Threads, và sơ đồ triển khai.
- **`docs/erd.md`**:
  - Sơ đồ Mermaid `erDiagram` phản ánh chính xác 9 bảng trong schema `core` từ `V1` đến `V13`, kèm **Danh mục Chỉ mục (B-tree, Partial, Covering Indexes)** và **Danh mục Ràng buộc Toàn vẹn (`CHECK`, `UNIQUE`, `FOREIGN KEY`)**.
- **`docs/adr.md`**:
  - Tổng hợp đầy đủ 15 quyết định kiến trúc (`ADR-001` → `ADR-015`) giải thích bối cảnh, quyết định, phương án đã loại và hệ quả kỹ thuật.
- **`CHANGELOG.md` & `docs/dev-notes/`**:
  - Bổ sung mục `## [1.0.0] - 2026-10-19` đầy đủ tới PR `#42` và rà soát độ chính xác của các ghi chú phát triển (`2-8`, `2-10`, `1-3`, `0-7`).

## 3. Kiểm thử & Xác minh
- Toàn bộ các câu lệnh `curl` và bảng API trong `README.md` đã được đối chiếu với chữ ký endpoint thực tế trong Controller và `V1`–`V13`.
- Quét tự động toàn bộ tài liệu đảm bảo không chứa bất kỳ tên ngân hàng hay thông tin nhạy cảm nào (`E-01`, `E-02`).

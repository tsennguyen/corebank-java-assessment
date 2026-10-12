# 0-3 · Hoàn thiện README công khai chuẩn Enterprise

- **Tóm tắt:** Chuẩn hóa toàn diện file `README.md` của repo nộp bài thi, loại bỏ các bảng tiến độ đề bài nội bộ, tập trung vào năng lực cốt lõi (Core Capabilities), kiến trúc Modular Monolith và hướng dẫn chạy thực tế. Mã yêu cầu: `NC-09`, `E-02`.

## Đã làm gì
- **Sửa lỗi Markdown & Quy chuẩn định danh (A3, E-02):** Khắc phục lỗi escaping backtick (\`) làm vỡ giao diện trên GitHub. Loại bỏ mọi tên ngân hàng, người thật hoặc tổ chức thật, đảm bảo tuân thủ 100% quy tắc E-02.
- **Công nghệ trung thực:** Chỉ liệt kê chính xác các công nghệ và thư viện đang có trong `pom.xml` (Java 21 LTS, Spring Boot 3.5.16, PostgreSQL 17, Flyway, Testcontainers, JaCoCo, Spotless, Checkstyle). Tuyệt đối không khẳng định các công nghệ thuộc kế hoạch tương lai (Redis, JWT, Kafka...) như đã hoàn thành.
- **Năng lực cốt lõi (Core Capabilities):** Thay thế bảng theo dõi đề thi thiển cận bằng việc trình bày các năng lực nghiệp vụ theo chuẩn Enterprise:
  1. *Quản lý Khách hàng:* Định danh KYC, soft delete giữ vết kiểm toán, che dữ liệu nhạy cảm (CCCD/National ID) trong log và API.
  2. *Quản lý Tài khoản:* Hạn mức giao dịch (ngày/lần), phân loại nhóm số dư, đóng tài khoản an toàn khi số dư bằng 0.
  3. *Xử lý Giao dịch:* Nạp tiền, tra cứu lịch sử đa tiêu chí, bảo đảm an toàn đồng thời bằng khóa bi quan (`SELECT FOR UPDATE`) kết hợp `lock_timeout 3s`, phân trang ổn định (`ORDER BY createdAt DESC, id DESC`).
  4. *Chuẩn hóa lỗi & Quan sát:* Chuẩn RFC 9457 `ProblemDetail` với `requestId` truy vết, Actuator health monitoring.
- **Cấu trúc kiến trúc (Modular Monolith):** Sơ đồ cây trực quan 4 module chính (`customer`, `account`, `transaction`, `common`).
- **Hướng dẫn chạy & Demo cURL:** Lệnh build/test chuẩn (`./mvnw clean verify`), chạy profile `local`, lệnh cURL nạp tiền mẫu để người chấm copy chạy thử ngay.
- **Độ dài & Định dạng:** Gọn gàng đúng 60 dòng (dưới ngưỡng 120 dòng của Acceptance Criteria).

## Phương án khác đã cân nhắc
- *Giữ bảng tiến độ Level 1 / Level 2 / Level 3 trong README:* Ban đầu đã tạo bảng mã đề thi, nhưng đây là thông tin quản trị dự án nội bộ. Một repository nộp bài chuyên nghiệp cần trình bày sản phẩm như một Enterprise Core Banking Service thực thụ, còn chi tiết ma trận truy vết đề thi đã được quản lý chặt chẽ trong workspace và Dev Notes.

## Bẫy thường gặp
- Tự động liệt kê công nghệ từ bản thiết kế ý niệm (PRD) vào README khiến hội đồng phản biện trừ điểm uy tín khi kiểm tra `pom.xml` thực tế chưa có.
- Trình bày dạng checklist bài tập học sinh thay vì sản phẩm kỹ thuật phần mềm hoàn chỉnh.

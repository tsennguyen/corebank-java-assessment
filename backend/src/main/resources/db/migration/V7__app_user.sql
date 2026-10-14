-- V7__app_user.sql: Application user table for JWT authentication and role-based access control (L2-01, L2-02)

CREATE TABLE core.app_user (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    customer_id BIGINT UNIQUE REFERENCES core.customer(id),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    CONSTRAINT chk_app_user_role CHECK (role IN ('ADMIN', 'CUSTOMER')),
    CONSTRAINT chk_app_user_customer_link CHECK (
        (role = 'ADMIN' AND customer_id IS NULL)
        OR (role = 'CUSTOMER' AND customer_id IS NOT NULL)
    )
);

CREATE INDEX idx_app_user_customer_id ON core.app_user (customer_id) WHERE customer_id IS NOT NULL;

COMMENT ON TABLE core.app_user IS 'Tài khoản đăng nhập hệ thống (ADMIN và CUSTOMER) phục vụ xác thực JWT và phân quyền';
COMMENT ON COLUMN core.app_user.username IS 'Tên đăng nhập duy nhất';
COMMENT ON COLUMN core.app_user.password_hash IS 'Mật khẩu đã băm bằng BCrypt (strength 10)';
COMMENT ON COLUMN core.app_user.role IS 'Vai trò người dùng: ADMIN hoặc CUSTOMER';
COMMENT ON COLUMN core.app_user.customer_id IS 'Khóa ngoại liên kết 1-1 tới core.customer (bắt buộc với CUSTOMER, NULL với ADMIN)';
COMMENT ON COLUMN core.app_user.enabled IS 'Trạng thái kích hoạt của tài khoản đăng nhập';
COMMENT ON COLUMN core.app_user.last_login_at IS 'Thời điểm đăng nhập thành công gần nhất';

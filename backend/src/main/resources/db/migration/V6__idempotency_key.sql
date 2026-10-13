-- V6__idempotency_key.sql: Support idempotency key for financial transactions (NC-02)

CREATE TABLE core.idempotency_key (
    idem_key VARCHAR(128) NOT NULL,
    scope VARCHAR(64) NOT NULL DEFAULT 'DEFAULT',
    request_hash VARCHAR(64) NOT NULL,
    response_status INT NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_idempotency_key PRIMARY KEY (idem_key, scope)
);

CREATE INDEX idx_idempotency_expires_at ON core.idempotency_key (expires_at);

COMMENT ON TABLE core.idempotency_key IS 'Lưu trữ Idempotency Key để chống lặp giao dịch và hỗ trợ gửi lại an toàn';
COMMENT ON COLUMN core.idempotency_key.idem_key IS 'Khóa duy nhất do client gửi lên trong header Idempotency-Key';
COMMENT ON COLUMN core.idempotency_key.scope IS 'Phạm vi người dùng (username hoặc DEFAULT)';
COMMENT ON COLUMN core.idempotency_key.request_hash IS 'Mã băm SHA-256 của HTTP method + URI path + request body';
COMMENT ON COLUMN core.idempotency_key.response_status IS 'HTTP Status code trả về cho client (ví dụ 201)';
COMMENT ON COLUMN core.idempotency_key.response_body IS 'Dữ liệu JSON response đã serialize để phát lại (replay)';
COMMENT ON COLUMN core.idempotency_key.expires_at IS 'Thời điểm hết hạn của key (mặc định sau 24h)';

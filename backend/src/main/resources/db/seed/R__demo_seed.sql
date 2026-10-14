-- R__demo_seed.sql: Idempotent synthetic seed data for local/prod demo environments (E-02, NFR-11)
-- Never executed in 'test' profile (spring.flyway.locations=classpath:db/migration).

-- 1. Seed 20 synthetic customers across 5 major cities/provinces
INSERT INTO core.customer (
    customer_code, full_name, national_id, email, phone, address, location, date_of_birth, created_at
) VALUES
    ('CUS8000001', 'Nguyễn Văn An', '000101000001', 'nguyenvanan.demo@example.com', '0900000001', 'Số 10 Đường Mẫu A, Quận Hoàn Kiếm', 'Hà Nội', '1988-03-15', CURRENT_TIMESTAMP - INTERVAL '350 days'),
    ('CUS8000002', 'Trần Thị Bình', '000101000002', 'tranthibinh.demo@example.com', '0900000002', 'Số 22 Đường Mẫu B, Quận Cầu Giấy', 'Hà Nội', '1992-07-20', CURRENT_TIMESTAMP - INTERVAL '340 days'),
    ('CUS8000003', 'Lê Hoàng Cường', '000101000003', 'lehoangcuong.demo@example.com', '0900000003', 'Số 35 Đường Mẫu C, Quận Đống Đa', 'Hà Nội', '1985-11-02', CURRENT_TIMESTAMP - INTERVAL '330 days'),
    ('CUS8000004', 'Phạm Minh Dũng', '000101000004', 'phamminhdung.demo@example.com', '0900000004', 'Số 48 Đường Mẫu D, Quận Ba Đình', 'Hà Nội', '1990-01-12', CURRENT_TIMESTAMP - INTERVAL '310 days'),
    ('CUS8000005', 'Hoàng Thu Hà', '000101000005', 'hoangthuha.demo@example.com', '0900000005', 'Số 15 Đường Mẫu E, Quận Thanh Xuân', 'Hà Nội', '1995-05-25', CURRENT_TIMESTAMP - INTERVAL '290 days'),
    ('CUS8000006', 'Vũ Quốc Huy', '000101000006', 'vuquochuy.demo@example.com', '0900000006', 'Số 88 Đường Mẫu F, Quận Hai Bà Trưng', 'Hà Nội', '1983-09-18', CURRENT_TIMESTAMP - INTERVAL '275 days'),
    ('CUS8000007', 'Công ty TNHH Thương Mại Mẫu Bắc Bộ', '000101000007', 'contact.bacbo@example.com', '0900000007', 'Khu Công Nghiệp Mẫu 1, Quận Nam Từ Liêm', 'Hà Nội', '1980-01-01', CURRENT_TIMESTAMP - INTERVAL '260 days'),
    ('CUS8000008', 'Đặng Thị Lan', '000101000008', 'dangthilan.demo@example.com', '0900000008', 'Số 12 Đường Mẫu G, Quận 1', 'Hồ Chí Minh', '1991-04-10', CURRENT_TIMESTAMP - INTERVAL '245 days'),
    ('CUS8000009', 'Bùi Văn Minh', '000101000009', 'buivanminh.demo@example.com', '0900000009', 'Số 45 Đường Mẫu H, Quận 3', 'Hồ Chí Minh', '1987-08-30', CURRENT_TIMESTAMP - INTERVAL '230 days'),
    ('CUS8000010', 'Đỗ Ngọc Nam', '000101000010', 'dongocnam.demo@example.com', '0900000010', 'Số 76 Đường Mẫu I, Quận 7', 'Hồ Chí Minh', '1993-12-05', CURRENT_TIMESTAMP - INTERVAL '210 days'),
    ('CUS8000011', 'Ngô Thanh Nga', '000101000011', 'ngothanhnga.demo@example.com', '0900000011', 'Số 102 Đường Mẫu K, Quận Bình Thạnh', 'Hồ Chí Minh', '1996-02-14', CURRENT_TIMESTAMP - INTERVAL '195 days'),
    ('CUS8000012', 'Dương Gia Phúc', '000101000012', 'duonggiaphuc.demo@example.com', '0900000012', 'Số 55 Đường Mẫu L, Thành phố Thủ Đức', 'Hồ Chí Minh', '1989-06-22', CURRENT_TIMESTAMP - INTERVAL '180 days'),
    ('CUS8000013', 'Công ty Cổ Phần Giải Pháp Mẫu Phương Nam', '000101000013', 'info.phuongnam@example.com', '0900000013', 'Tòa Nhà Mẫu Tower, Quận 1', 'Hồ Chí Minh', '1982-01-01', CURRENT_TIMESTAMP - INTERVAL '165 days'),
    ('CUS8000014', 'Lý Anh Quân', '000101000014', 'lyanhquan.demo@example.com', '0900000014', 'Số 19 Đường Mẫu M, Quận Hải Châu', 'Đà Nẵng', '1994-10-08', CURRENT_TIMESTAMP - INTERVAL '150 days'),
    ('CUS8000015', 'Mai Thị Quỳnh', '000101000015', 'maithiquynh.demo@example.com', '0900000015', 'Số 31 Đường Mẫu N, Quận Sơn Trà', 'Đà Nẵng', '1997-03-27', CURRENT_TIMESTAMP - INTERVAL '135 days'),
    ('CUS8000016', 'Trịnh Xuân Sơn', '000101000016', 'trinhxuanson.demo@example.com', '0900000016', 'Số 64 Đường Mẫu P, Quận Thanh Khê', 'Đà Nẵng', '1986-07-19', CURRENT_TIMESTAMP - INTERVAL '120 days'),
    ('CUS8000017', 'Đinh Thảo Trang', '000101000017', 'dinhthaotrang.demo@example.com', '0900000017', 'Số 28 Đường Mẫu Q, Quận Ngô Quyền', 'Hải Phòng', '1992-11-11', CURRENT_TIMESTAMP - INTERVAL '95 days'),
    ('CUS8000018', 'Lương Thế Vinh', '000101000018', 'luongthevinh.demo@example.com', '0900000018', 'Số 90 Đường Mẫu R, Quận Lê Chân', 'Hải Phòng', '1984-05-03', CURRENT_TIMESTAMP - INTERVAL '75 days'),
    ('CUS8000019', 'Phan Tuyết Yến', '000101000019', 'phantuyetyen.demo@example.com', '0900000019', 'Số 14 Đường Mẫu S, Quận Ninh Kiều', 'Cần Thơ', '1993-09-09', CURRENT_TIMESTAMP - INTERVAL '50 days'),
    ('CUS8000020', 'Hồ Tuấn Kiệt', '000101000020', 'hotuankiet.demo@example.com', '0900000020', 'Số 42 Đường Mẫu T, Quận Cái Răng', 'Cần Thơ', '1990-12-30', CURRENT_TIMESTAMP - INTERVAL '30 days')
ON CONFLICT (customer_code) DO NOTHING;

-- 2. Seed synthetic accounts across LOW (< 10M), MEDIUM (10M - < 100M), and HIGH (>= 100M) tiers
INSERT INTO core.account (
    account_number, customer_id, currency, balance, transaction_limit, daily_limit, opened_date, status, created_at
)
SELECT v.acc_num, c.id, 'VND', v.balance, v.tx_limit, v.daily_limit, v.opened_date, 'ACTIVE', v.created_at
FROM (
    VALUES
        -- LOW tier accounts (< 10,000,000 VND)
        ('100800000018', 'CUS8000001', 3500000.00::numeric(19,2), 20000000.00::numeric(19,2), 50000000.00::numeric(19,2), (CURRENT_DATE - 340), (CURRENT_TIMESTAMP - INTERVAL '340 days')),
        ('100800000026', 'CUS8000002', 7200000.00::numeric(19,2), 20000000.00::numeric(19,2), 50000000.00::numeric(19,2), (CURRENT_DATE - 320), (CURRENT_TIMESTAMP - INTERVAL '320 days')),
        ('100800000034', 'CUS8000003', 1500000.00::numeric(19,2), 20000000.00::numeric(19,2), 50000000.00::numeric(19,2), (CURRENT_DATE - 300), (CURRENT_TIMESTAMP - INTERVAL '300 days')),
        ('100800000042', 'CUS8000004', 9000000.00::numeric(19,2), 20000000.00::numeric(19,2), 50000000.00::numeric(19,2), (CURRENT_DATE - 280), (CURRENT_TIMESTAMP - INTERVAL '280 days')),
        -- MEDIUM tier accounts (10,000,000 - < 100,000,000 VND)
        ('100800000059', 'CUS8000005', 25000000.00::numeric(19,2), 50000000.00::numeric(19,2), 200000000.00::numeric(19,2), (CURRENT_DATE - 260), (CURRENT_TIMESTAMP - INTERVAL '260 days')),
        ('100800000067', 'CUS8000008', 48000000.00::numeric(19,2), 50000000.00::numeric(19,2), 200000000.00::numeric(19,2), (CURRENT_DATE - 230), (CURRENT_TIMESTAMP - INTERVAL '230 days')),
        ('100800000075', 'CUS8000009', 65000000.00::numeric(19,2), 50000000.00::numeric(19,2), 200000000.00::numeric(19,2), (CURRENT_DATE - 200), (CURRENT_TIMESTAMP - INTERVAL '200 days')),
        ('100800000083', 'CUS8000014', 15000000.00::numeric(19,2), 50000000.00::numeric(19,2), 200000000.00::numeric(19,2), (CURRENT_DATE - 140), (CURRENT_TIMESTAMP - INTERVAL '140 days')),
        -- HIGH tier accounts (>= 100,000,000 VND)
        ('100800000091', 'CUS8000007', 386996000.00::numeric(19,2), 500000000.00::numeric(19,2), 2000000000.00::numeric(19,2), (CURRENT_DATE - 250), (CURRENT_TIMESTAMP - INTERVAL '250 days')),
        ('100800000109', 'CUS8000013', 534997000.00::numeric(19,2), 500000000.00::numeric(19,2), 2000000000.00::numeric(19,2), (CURRENT_DATE - 160), (CURRENT_TIMESTAMP - INTERVAL '160 days')),
        ('100800000117', 'CUS8000017', 125000000.00::numeric(19,2), 100000000.00::numeric(19,2), 500000000.00::numeric(19,2), (CURRENT_DATE - 90), (CURRENT_TIMESTAMP - INTERVAL '90 days')),
        ('100800000125', 'CUS8000019', 180000000.00::numeric(19,2), 100000000.00::numeric(19,2), 500000000.00::numeric(19,2), (CURRENT_DATE - 45), (CURRENT_TIMESTAMP - INTERVAL '45 days'))
) AS v(acc_num, cust_code, balance, tx_limit, daily_limit, opened_date, created_at)
JOIN core.customer c ON c.customer_code = v.cust_code
ON CONFLICT (account_number) DO NOTHING;

-- 3. Seed synthetic historical transactions across 12 months (strictly consistent with account balances)
INSERT INTO core.transaction (
    reference_no, type, source_account_id, target_account_id, amount, fee,
    source_balance_after, target_balance_after, system_balance_delta,
    location, description, status, created_by, created_at
)
SELECT v.ref_no, v.tx_type, src.id, tgt.id, v.amount, v.fee,
       v.src_bal_after, v.tgt_bal_after, v.sys_delta,
       v.location, v.description, 'COMPLETED', 'SYSTEM_SEED', v.created_at
FROM (
    VALUES
        ('TXN-SEED-0001', 'DEPOSIT', NULL, '100800000091', 400000000.00::numeric(19,2), 0.00::numeric(19,2), NULL::numeric(19,2), 400000000.00::numeric(19,2), 400000000.00::numeric(19,2), 'Hà Nội', 'Nạp vốn kinh doanh ban đầu', CURRENT_TIMESTAMP - INTERVAL '240 days'),
        ('TXN-SEED-0002', 'DEPOSIT', NULL, '100800000109', 550000000.00::numeric(19,2), 0.00::numeric(19,2), NULL::numeric(19,2), 550000000.00::numeric(19,2), 550000000.00::numeric(19,2), 'Hồ Chí Minh', 'Nạp tiền mặt tại quầy giao dịch', CURRENT_TIMESTAMP - INTERVAL '155 days'),
        ('TXN-SEED-0003', 'DEPOSIT', NULL, '100800000059', 30000000.00::numeric(19,2), 0.00::numeric(19,2), NULL::numeric(19,2), 30000000.00::numeric(19,2), 30000000.00::numeric(19,2), 'Hà Nội', 'Nạp tiền tiết kiệm cá nhân', CURRENT_TIMESTAMP - INTERVAL '210 days'),
        ('TXN-SEED-0004', 'DEPOSIT', NULL, '100800000067', 50000000.00::numeric(19,2), 0.00::numeric(19,2), NULL::numeric(19,2), 50000000.00::numeric(19,2), 50000000.00::numeric(19,2), 'Hồ Chí Minh', 'Nạp tiền mở tài khoản', CURRENT_TIMESTAMP - INTERVAL '190 days'),
        ('TXN-SEED-0005', 'TRANSFER', '100800000091', '100800000018', 5000000.00::numeric(19,2), 2000.00::numeric(19,2), 394998000.00::numeric(19,2), 5000000.00::numeric(19,2), -2000.00::numeric(19,2), 'Hà Nội', 'Thanh toán lương tháng 03', CURRENT_TIMESTAMP - INTERVAL '180 days'),
        ('TXN-SEED-0006', 'TRANSFER', '100800000091', '100800000026', 8000000.00::numeric(19,2), 2000.00::numeric(19,2), 386996000.00::numeric(19,2), 8000000.00::numeric(19,2), -2000.00::numeric(19,2), 'Hà Nội', 'Thanh toán phụ cấp dự án', CURRENT_TIMESTAMP - INTERVAL '150 days'),
        ('TXN-SEED-0007', 'WITHDRAWAL', '100800000018', NULL, 1498000.00::numeric(19,2), 2000.00::numeric(19,2), 3500000.00::numeric(19,2), NULL::numeric(19,2), -1500000.00::numeric(19,2), 'Hà Nội', 'Rút tiền mặt tại ATM', CURRENT_TIMESTAMP - INTERVAL '120 days'),
        ('TXN-SEED-0008', 'PAYMENT', '100800000026', NULL, 800000.00::numeric(19,2), 0.00::numeric(19,2), 7200000.00::numeric(19,2), NULL::numeric(19,2), -800000.00::numeric(19,2), 'Hà Nội', 'Thanh toán hóa đơn điện nước', CURRENT_TIMESTAMP - INTERVAL '90 days'),
        ('TXN-SEED-0009', 'TRANSFER', '100800000109', '100800000083', 15000000.00::numeric(19,2), 3000.00::numeric(19,2), 534997000.00::numeric(19,2), 15000000.00::numeric(19,2), -3000.00::numeric(19,2), 'Đà Nẵng', 'Tạm ứng hợp đồng dịch vụ', CURRENT_TIMESTAMP - INTERVAL '60 days'),
        ('TXN-SEED-0010', 'PAYMENT', '100800000059', NULL, 5000000.00::numeric(19,2), 0.00::numeric(19,2), 25000000.00::numeric(19,2), NULL::numeric(19,2), -5000000.00::numeric(19,2), 'Hà Nội', 'Thanh toán cước viễn thông', CURRENT_TIMESTAMP - INTERVAL '30 days'),
        ('TXN-SEED-0011', 'WITHDRAWAL', '100800000067', NULL, 1998000.00::numeric(19,2), 2000.00::numeric(19,2), 48000000.00::numeric(19,2), NULL::numeric(19,2), -2000000.00::numeric(19,2), 'Hồ Chí Minh', 'Rút tiền chi tiêu cá nhân', CURRENT_TIMESTAMP - INTERVAL '15 days'),
        ('TXN-SEED-0012', 'DEPOSIT', NULL, '100800000125', 180000000.00::numeric(19,2), 0.00::numeric(19,2), NULL::numeric(19,2), 180000000.00::numeric(19,2), 180000000.00::numeric(19,2), 'Cần Thơ', 'Nạp tiền gửi kỳ hạn linh hoạt', CURRENT_TIMESTAMP - INTERVAL '5 days'),
        ('TXN-SEED-0013', 'DEPOSIT', NULL, '100800000034', 1500000.00::numeric(19,2), 0.00::numeric(19,2), NULL::numeric(19,2), 1500000.00::numeric(19,2), 1500000.00::numeric(19,2), 'Hà Nội', 'Nạp tiền mở tài khoản thanh toán', CURRENT_TIMESTAMP - INTERVAL '295 days'),
        ('TXN-SEED-0014', 'DEPOSIT', NULL, '100800000042', 9000000.00::numeric(19,2), 0.00::numeric(19,2), NULL::numeric(19,2), 9000000.00::numeric(19,2), 9000000.00::numeric(19,2), 'Hà Nội', 'Nạp tiền tiết kiệm tích lũy', CURRENT_TIMESTAMP - INTERVAL '275 days'),
        ('TXN-SEED-0015', 'DEPOSIT', NULL, '100800000075', 65000000.00::numeric(19,2), 0.00::numeric(19,2), NULL::numeric(19,2), 65000000.00::numeric(19,2), 65000000.00::numeric(19,2), 'Hồ Chí Minh', 'Nạp tiền đầu tư cá nhân', CURRENT_TIMESTAMP - INTERVAL '198 days'),
        ('TXN-SEED-0016', 'DEPOSIT', NULL, '100800000117', 125000000.00::numeric(19,2), 0.00::numeric(19,2), NULL::numeric(19,2), 125000000.00::numeric(19,2), 125000000.00::numeric(19,2), 'Hải Phòng', 'Nạp tiền kinh doanh thương mại', CURRENT_TIMESTAMP - INTERVAL '85 days')
) AS v(ref_no, tx_type, src_acc_num, tgt_acc_num, amount, fee, src_bal_after, tgt_bal_after, sys_delta, location, description, created_at)
LEFT JOIN core.account src ON src.account_number = v.src_acc_num
LEFT JOIN core.account tgt ON tgt.account_number = v.tgt_acc_num
ON CONFLICT (reference_no) DO NOTHING;

-- 4. Synchronize sequences idempotently so newly generated codes/numbers never collide with seed baseline
DO $$
BEGIN
    PERFORM setval('core.customer_code_seq', GREATEST((SELECT last_value FROM core.customer_code_seq), 20), true);
    PERFORM setval('core.account_number_seq', GREATEST((SELECT last_value FROM core.account_number_seq), 12), true);
END $$;

-- 5. Seed synthetic CUSTOMER login accounts (default demo password: Customer@123 hashed with BCrypt strength 10)
INSERT INTO core.app_user (
    username, password_hash, role, customer_id, enabled, created_at
)
SELECT v.username, v.password_hash, 'CUSTOMER', c.id, TRUE, v.created_at
FROM (
    VALUES
        ('cust.an', '$2a$10$3CQZNBArpgzgErJJdek7LuIOynWuxt9ELxJSkKVk6gxjvlQXNhMty', 'CUS8000001', CURRENT_TIMESTAMP - INTERVAL '340 days'),
        ('cust.binh', '$2a$10$3CQZNBArpgzgErJJdek7LuIOynWuxt9ELxJSkKVk6gxjvlQXNhMty', 'CUS8000002', CURRENT_TIMESTAMP - INTERVAL '320 days')
) AS v(username, password_hash, cust_code, created_at)
JOIN core.customer c ON c.customer_code = v.cust_code
ON CONFLICT (username) DO NOTHING;

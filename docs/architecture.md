# Kiến trúc Hệ thống — Core Banking System (`v1.0.0`)

Tài liệu này mô tả chi tiết kiến trúc **Modular Monolith (Package-by-Feature)**, luồng xử lý giao dịch tiền tệ nguyên tử kết hợp sự kiện bất đồng bộ trên **Java 21 Virtual Threads**, và sơ đồ triển khai của hệ thống.

- **Sơ đồ Thực thể Liên kết (ERD):** xem [`docs/erd.md`](erd.md)
- **Hồ sơ Quyết định Kiến trúc (ADR-001..ADR-015):** xem [`docs/adr.md`](adr.md)
- **Ghi chú Kỹ thuật từng tính năng (Dev Notes):** xem [`docs/dev-notes/README.md`](dev-notes/README.md)

---

## 1. Sơ đồ Phụ thuộc Module (Modular Monolith — 9 Domain Modules)

Hệ thống được chia thành 9 module nghiệp vụ độc lập bên trong `com.example.corebanking`. Phụ thuộc giữa các module luôn **một chiều (acyclic)**; đối với các ràng buộc chéo (như kiểm tra lịch giao dịch định kỳ trước khi đóng tài khoản), module bên dưới định nghĩa **Port Interface** (`AccountClosureGuard`) để đảo chiều phụ thuộc (Dependency Inversion).

```mermaid
flowchart TD
    subgraph API_Security["Tầng Bảo mật & Xác thực"]
        AUTH["auth (JWT HS256, Refresh Token Rotation, Rate Limiter, OwnershipGuard)"]
    end

    subgraph Core_Ledger["Tầng Sổ cái Cốt lõi (Level 1)"]
        CUST["customer (Customer, CustomerType, Soft Delete, Masking)"]
        ACC["account (Account, Status FSM, AccountStatusHistory, ClosureGuard)"]
        TX["transaction (TransactionService, Pessimistic Lock, TieredFeePolicy, LimitPolicy, Idempotency)"]
    end

    subgraph Automation_Analytics["Tầng Tự động hóa, Cảnh báo & Báo cáo (Level 2 & 3)"]
        REC["recurring (RecurringTransactionJob, SKIP LOCKED, RecurringExecutor)"]
        FRAUD["fraud (AnomalyRuleChain, AmountThresholdRule, VelocityRule ZSET, FraudScanRepository)"]
        REP["report (StatisticsRepository, ReportRepository, ChartRenderer, SXSSF/PDF ReportExporter)"]
        CACHE["cache (RedisCacheManager, Caffeine Fallback, ResilientCacheErrorHandler, AFTER_COMMIT Eviction)"]
    end

    COMMON["common (Money, BusinessRuleException, GlobalExceptionHandler RFC 9457, AsyncConfig)"]

    ACC --> CUST
    TX --> ACC
    TX --> CUST
    REC --> TX
    REC -. "implements AccountClosureGuard" .-> ACC
    FRAUD -. "@TransactionalEventListener(AFTER_COMMIT) + @Async" .-> TX
    CACHE -. "@TransactionalEventListener(AFTER_COMMIT)" .-> TX
    CACHE -. "@TransactionalEventListener(AFTER_COMMIT)" .-> ACC
    REP --> TX
    REP --> ACC
    REP --> CUST
    AUTH --> CUST
    AUTH --> ACC
    AUTH --> TX
    CUST --> COMMON
    ACC --> COMMON
    TX --> COMMON
```

---

## 2. Luồng Giao dịch Nguyên tử & Xử lý Sự kiện Sau Commit (`AFTER_COMMIT`)

Mọi biến động số dư (`DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, `PAYMENT`, `REVERSAL`) đều tuân thủ nghiêm ngặt ranh giới giao dịch ACID của PostgreSQL 17:

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client / Admin
    participant Filter as SecurityFilterChain (JWT)
    participant Idem as IdempotencyService (Advisory Lock)
    participant TxSvc as TransactionService (@Transactional)
    participant PG as PostgreSQL 17 (schema core)
    participant Cache as CacheEvictionListener (AFTER_COMMIT)
    participant Fraud as FraudEventListener (@Async Virtual Thread)
    participant Redis as Redis 7 / Caffeine

    Client->>Filter: POST /api/v1/transactions/transfers (Bearer JWT + Idempotency-Key)
    Filter->>Idem: executeIdempotent(scope, key, requestHash)
    Idem->>PG: pg_advisory_xact_lock(hashtext(scope), hashtext(key))
    Idem->>PG: SELECT * FROM core.idempotency_key WHERE scope=? AND idem_key=?
    alt Key đã tồn tại & cùng requestHash
        Idem-->>Client: HTTP 201 + Header "Idempotent-Replayed: true"
    else Key mới
        Idem->>TxSvc: transfer(request)
        TxSvc->>PG: SELECT * FROM core.account WHERE id = min(A,B) FOR UPDATE
        TxSvc->>PG: SELECT * FROM core.account WHERE id = max(A,B) FOR UPDATE
        TxSvc->>TxSvc: Kiểm tra trạng thái ACTIVE, tính phí bậc thang (TreeMap), kiểm tra hạn mức min(Account, CustomerType)
        TxSvc->>PG: UPDATE core.account SET balance = ... (A & B)
        TxSvc->>PG: INSERT INTO core.transaction (..., system_balance_delta)
        Idem->>PG: INSERT INTO core.idempotency_key (response_body, expires_at)
        TxSvc->>PG: COMMIT TRANSACTION
        par Xóa Cache ngay sau khi COMMIT thành công
            TxSvc--)Cache: TransactionCompletedEvent (AFTER_COMMIT)
            Cache->>Redis: Evict recentTransactions(A, B) & accountsByCustomer
        and Đánh giá Bất thường Bất đồng bộ trên Virtual Thread
            TxSvc--)Fraud: TransactionCompletedEvent (AFTER_COMMIT, @Async vt-async-*)
            Fraud->>Redis: ZADD / ZREMRANGEBYSCORE vel:{sourceAccountId}
            Fraud->>PG: INSERT INTO core.alert (...) ON CONFLICT DO NOTHING (REQUIRES_NEW)
        end
        Idem-->>Client: HTTP 201 Created (TransactionResponse)
    end
```

---

## 3. Sơ đồ Triển khai (Deployment & Live Demo Topology)

```mermaid
flowchart LR
    subgraph Internet["Người chấm / Hội đồng"]
        Browser["Trình duyệt (Swagger UI / Dashboard)"]
        CLI["curl / Postman / k6"]
    end

    subgraph Edge["Cổng Bảo mật HTTPS"]
        Funnel["Tailscale Funnel TLS :8443 (hoặc Render / Vercel HTTPS)"]
        Caddy["Caddy Gate Reverse Proxy (X-Demo-Key / Cookie Gate + HSTS)"]
    end

    subgraph Runtime["Docker Compose / Cloud Runtime"]
        App["Spring Boot 3.5.16 (Temurin 21 JRE, Virtual Threads, non-root appuser, fontconfig)"]
        PG[("PostgreSQL 17 Schema: core (Flyway V1..V13)")]
        Redis[("Redis 7 Alpine (Cache, Rate Limit, Refresh Token, Velocity ZSET) + Fallback Caffeine")]
    end

    Browser --> Funnel
    CLI --> Funnel
    Funnel --> Caddy
    Caddy --> App
    App --> PG
    App --> Redis
```

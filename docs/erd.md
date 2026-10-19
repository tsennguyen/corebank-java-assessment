# Sơ đồ Thực thể Liên kết (Entity-Relationship Diagram — Schema `core`)

Sơ đồ dưới đây phản ánh chính xác cấu trúc các bảng trong schema `core` trên PostgreSQL 17 được quản lý bởi Flyway (`V1__init_schema.sql` → `V13__alert.sql`).

```mermaid
erDiagram
    CUSTOMER_TYPE ||--o{ CUSTOMER : "classifies (customer_type_code)"
    CUSTOMER ||--o{ ACCOUNT : "owns (customer_id)"
    CUSTOMER ||--o| APP_USER : "links optional (customer_id)"
    ACCOUNT ||--o{ ACCOUNT_STATUS_HISTORY : "audits (account_id)"
    ACCOUNT ||--o{ TRANSACTION : "debits (source_account_id)"
    ACCOUNT ||--o{ TRANSACTION : "credits (target_account_id)"
    TRANSACTION ||--o| TRANSACTION : "reverses (reversal_of_id)"
    RECURRING_TRANSACTION ||--o{ TRANSACTION : "triggers (recurring_id)"
    ACCOUNT ||--o{ RECURRING_TRANSACTION : "funds (source_account_id)"
    ACCOUNT ||--o{ RECURRING_TRANSACTION : "receives (target_account_id)"
    TRANSACTION ||--o{ ALERT : "raises (transaction_id)"
    ACCOUNT ||--o{ ALERT : "monitors (account_id)"

    CUSTOMER_TYPE {
        varchar(30) code PK "INDIVIDUAL | BUSINESS (V12)"
        varchar(100) name
        numeric(19_2) max_transaction_limit
        numeric(19_2) max_daily_limit
        timestamptz updated_at
        bigint version
    }

    CUSTOMER {
        bigserial id PK "V2"
        varchar(32) customer_code UK
        varchar(150) full_name
        varchar(20) national_id UK "Where deleted_at IS NULL"
        varchar(20) phone_number
        varchar(150) email
        varchar(100) location
        varchar(30) customer_type_code FK "Default INDIVIDUAL (V12)"
        timestamptz created_at
        timestamptz updated_at
        timestamptz deleted_at "Soft delete"
        bigint version
    }

    ACCOUNT {
        bigserial id PK "V3"
        varchar(20) account_number UK "12-digit Luhn"
        bigint customer_id FK
        varchar(10) currency "VND"
        numeric(19_2) balance "CHECK >= 0"
        varchar(20) status "ACTIVE | LOCKED | CLOSED"
        numeric(19_2) transaction_limit
        numeric(19_2) daily_limit
        date opened_date
        timestamptz closed_at
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }

    ACCOUNT_STATUS_HISTORY {
        bigserial id PK "V10"
        bigint account_id FK
        varchar(20) from_status
        varchar(20) to_status
        varchar(500) reason
        varchar(100) changed_by
        timestamptz changed_at
    }

    TRANSACTION {
        bigserial id PK "V4"
        varchar(64) reference_no UK
        varchar(20) type "DEPOSIT | WITHDRAWAL | TRANSFER | PAYMENT | REVERSAL"
        varchar(20) status "COMPLETED | REVERSED"
        numeric(19_2) amount "CHECK > 0"
        numeric(19_2) fee "CHECK >= 0"
        bigint source_account_id FK
        bigint target_account_id FK
        numeric(19_2) source_balance_after
        numeric(19_2) target_balance_after
        numeric(19_2) system_balance_delta
        varchar(100) location
        varchar(500) description
        bigint reversal_of_id FK "UK where not null"
        bigint recurring_id FK "V11"
        timestamptz created_at
        bigint version
    }

    IDEMPOTENCY_KEY {
        bigserial id PK "V6"
        varchar(128) scope "Authenticated username"
        varchar(128) idempotency_key "UK(scope, idempotency_key)"
        varchar(64) request_hash "SHA-256 canonical payload"
        integer response_status
        text response_body
        timestamptz created_at
        timestamptz expires_at "24h TTL"
    }

    APP_USER {
        bigserial id PK "V7"
        varchar(64) username UK
        varchar(255) password_hash "BCrypt"
        varchar(20) role "ADMIN | TELLER | CUSTOMER"
        bigint customer_id FK "Nullable (for CUSTOMER)"
        boolean enabled
        timestamptz created_at
    }

    RECURRING_TRANSACTION {
        bigserial id PK "V11"
        varchar(20) type "DEPOSIT | PAYMENT | TRANSFER"
        bigint source_account_id FK
        bigint target_account_id FK
        numeric(19_2) amount
        varchar(20) frequency "DAILY | WEEKLY | MONTHLY"
        date start_date
        date end_date
        timestamptz next_run_at "Partial index idx_rec_due"
        varchar(20) status "ACTIVE | PAUSED | COMPLETED | CANCELLED"
        integer failure_count
        varchar(500) last_error
        varchar(100) created_by
        bigint version
    }

    ALERT {
        bigserial id PK "V13"
        bigint transaction_id FK "UK(transaction_id, rule_code)"
        bigint account_id FK
        varchar(40) rule_code "AMOUNT_THRESHOLD | VELOCITY"
        varchar(20) severity "MEDIUM | HIGH"
        varchar(500) reason
        varchar(20) status "NEW | INVESTIGATING | RESOLVED | FALSE_POSITIVE"
        varchar(500) resolution_note
        varchar(100) resolved_by
        timestamptz resolved_at
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }
```

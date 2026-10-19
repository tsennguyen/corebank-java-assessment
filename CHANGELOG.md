# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-10-19

### Added
- **Level 2 — Security, Search & Automation:**
  - Stateless JWT authentication (`HS256`), role-based access control (`ADMIN`, `CUSTOMER`), user-scoped `Idempotency-Key` isolation, and `AdminBootstrapRunner` (`V7__app_user.sql`, #25).
  - Level 1 concurrency, idempotency 64-bit advisory lock, `PATCH` immutable-field protection (`@JsonAnySetter`), and partial covering index `idx_tx_source_completed_created_amount` (`V8`, #26).
  - Customer self-service endpoints (`GET /api/v1/me`, `GET /api/v1/me/accounts`) and `OwnershipGuard` IDOR protection across accounts and transactions (#27).
  - Multi-filter transaction search (`status`, `minAmount`, `maxAmount`, `fromDate`, `toDate` in `Asia/Ho_Chi_Minh`) with composite indexes `idx_tx_status_created` and `idx_tx_amount_created` (`V9`, #28).
  - Account status lifecycle FSM (`ACTIVE ⇄ LOCKED → CLOSED`) with immutable audit trail `core.account_status_history` (`V10`, #29).
  - Recurring transaction setup (`DAILY`, `WEEKLY`, `MONTHLY` with end-of-month anchor clamping), closure guard (`409 ACCOUNT_HAS_ACTIVE_RECURRING`), and multi-worker scheduler using `FOR UPDATE SKIP LOCKED` + `REQUIRES_NEW` transaction isolation (`V11`, #30, #31).
  - Two-tier query caching (`Redis 7` + automatic `Caffeine` / DB fallback via `ResilientCacheErrorHandler`) and `AFTER_COMMIT` cache eviction (#32).
  - Opaque 256-bit refresh token rotation (`SHA-256` hashed storage), token family reuse detection (`401 TOKEN_REUSED`), and pre-BCrypt login rate limiting (`429 TOO_MANY_REQUESTS` with `Retry-After`) (#33).
- **Level 3 — Analytics, Fraud Detection, Report Exports & Observability:**
  - Customer classification (`INDIVIDUAL`, `BUSINESS`) with configurable ceiling limits and `CustomerTypeLimitProvider` enforcing $\min(\text{accountLimit}, \text{customerTypeMaxLimit})$ (`V12`, #34).
  - Periodic transaction reports (`WEEK`, `QUARTER`, `YEAR`) aggregated via PostgreSQL `date_trunc` in `Asia/Ho_Chi_Minh` (#35).
  - Real-time anomaly detection on Java 21 Virtual Threads (`@Async` + `AFTER_COMMIT`) for `AMOUNT_THRESHOLD` ($\ge 50\text{M VND}$) and `VELOCITY` ($> 5\text{ outgoing tx} / 60\text{s}$ via Redis `ZSET` / `ArrayDeque` + `ReentrantLock`) (`V13__alert.sql`, #36).
  - Alert management API (`GET/POST /api/v1/alerts` with status FSM `NEW -> INVESTIGATING -> RESOLVED | FALSE_POSITIVE`) and historical batch fraud scan (`POST /api/v1/fraud/scans`) using PostgreSQL `RANGE ... PRECEDING` window functions (#37).
  - System and per-account trend series (`GET /api/v1/reports/trends` using `generate_series` and reverse cumulative window functions) and headless `JFreeChart` PNG rendering (`800x400`) (#38).
  - Streaming Excel report export (`GET /api/v1/reports/export?format=XLSX`, 3 sheets `Tong hop`, `Chi tiet`, `Bieu do` with embedded PNG charts) using `SXSSFWorkbook(100)`, JDBC cursor streaming (`fetchSize = 1000`), and `StreamingResponseBody` (#39).
  - Streaming PDF report export (`GET /api/v1/reports/export?format=PDF`) using `OpenPDF 2.0.3` with embedded `DejaVuSans` Vietnamese Unicode fonts, 3 embedded PNG trend charts, and a 1,000-row detail table cap (#41).
  - Spring Boot Actuator security hardening, custom Micrometer business metrics (`banking.transactions`, `banking.alerts.raised`, `banking.recurring.executed`), Java 21 Virtual Threads anti-pinning verification (`ReentrantLock`), and bounded HikariCP pool sizing (#40).
- **Delivery, Operations Console, Performance Benchmark, Scale & Security Hardening:**
  - Production release `v1.0.0` demo readiness in `R__demo_seed.sql`: pre-seeded high-value outgoing transfer (`TXN-SEED-0017`, `75,000,000 VND`) and 6-transfer velocity burst (`TXN-SEED-0018`..`TXN-SEED-0023`) with initial `AMOUNT_THRESHOLD` and `VELOCITY` alerts in `core.alert` and exact ledger invariant conservation (`1,401,171,000.00 VND`) (#57).
  - Keyset cursor pagination (`GET /api/v1/accounts/{id}/transactions/cursor`, `CursorPageResponse` Base64URL `(createdAt, id)` seek $O(\log N)$ with zero `COUNT(*)`), cluster-safe distributed scheduler locking (`SchedulerLockService` on `core.shedlock` with `ON CONFLICT DO UPDATE`), same-transaction append-only audit log (`core.audit_log` protected by PostgreSQL trigger `trg_audit_log_append_only` and PII masking), and atomic Redis token rotation (`GETDEL`) + Lua login rate-limiting script (`V14__scale_and_hardening.sql`, #56).
  - Large-scale performance benchmark suite (`perf/seed-large.sql` generating 10k customers, 10k accounts, and 1,000,000 transactions via PostgreSQL 17 `generate_series`, `perf/k6-transactions-search.js` 50-VU load test, and `perf/RESULTS.md` documenting a 129.1x `EXPLAIN (ANALYZE, BUFFERS)` speedup from `121.007 ms` to `0.937 ms` and `0.179 ms` `Index Only Scan`) (#55).
  - Interactive Web Operations Console (`frontend/index.html`, `frontend/app.js`, and embedded Spring Boot static assets) supporting 1-click role switching (`ADMIN`/`CUSTOMER`), in-memory JWT + automatic `401` refresh rotation, fee preview, idempotency replay verification, multi-filter transaction search, fraud alert FSM + historical window scan, and authenticated Excel/PDF report downloads (#44).
  - Automated GitHub Pages JaCoCo coverage report publication (`coverage-pages.yml`), CodeQL SAST & Dependency Vulnerability Review (`security.yml`), and weekly Dependabot configuration (`dependabot.yml`) (#43).
  - Complete Vietnamese technical documentation (`README.md`, `docs/architecture.md`, `docs/erd.md`, `docs/adr.md` `ADR-001`–`ADR-015`, and `docs/dev-notes/`) (#42).

## [0.1.0] - 2026-10-14

### Added
- Spring Boot 3.5.16 / Java 21 project skeleton with Spotless, Checkstyle and JaCoCo (#1).
- GitHub Actions backend CI with style checks and an 80% line-coverage gate (#3, #11).
- Flyway baseline in schema `core`, Testcontainers PostgreSQL 17 integration tests (#4).
- RFC 9457 `ProblemDetail` error model, request ID propagation and sensitive-data masking (#6, #13).
- OpenAPI 3 / Swagger UI documentation (#7).
- Customer management API: create, read, update with optimistic locking (`@Version`), soft delete, search (#8, #13).
- Account management API: Luhn-checked account numbers, transaction/daily limits, close guard, balance tiers, search (#9, #13).
- Deposit API with pessimistic locking (`SELECT ... FOR UPDATE`) and 3s lock timeout, transaction query and account history (#10).
- Configurable multi-tier transaction fee policy (`TreeMap.floorEntry` $O(\log n)$) and stateless fee preview endpoint `GET /api/v1/fees/preview` (#14).
- Withdrawal, transfer, and payment APIs with pre-debit transaction/daily limit enforcement (`Asia/Ho_Chi_Minh`) and deadlock-free ascending account ID lock ordering (#15).
- Transaction metadata update (`PATCH /api/v1/transactions/{id}`) and double-entry compensating reversal (`DELETE /api/v1/transactions/{id}`) (#16).
- RDBMS-backed `Idempotency-Key` protection with PostgreSQL transaction-scoped advisory locks, SHA-256 canonical payload hashing, response replay (`Idempotent-Replayed: true`), and 24h TTL cleanup scheduler (#17).
- Concurrent multi-account transfer stress test on PostgreSQL 17 Testcontainers (`TransferConcurrencyIT`: 100 concurrent transfers across 25 worker threads over 10 accounts plus 10 bidirectional threads) verifying zero deadlocks and strict conservation of funds + fees (#19).
- SQL-aggregated balance tier statistics (`GET /api/v1/statistics/balance-tiers`), customer location statistics (`GET /api/v1/statistics/customers-by-location`), and idempotent demo seed data (`R__demo_seed.sql`) (#20).
- Multi-stage layered `backend/Dockerfile` (Temurin 21 JRE, non-root `appuser`, `fontconfig`), 1-command `docker-compose.yml` stack, `render.yaml` Blueprint, and `.github/workflows/cd.yml` container smoke test (#21).

### Changed
- CI reports the required status check on every pull request; superseded PR runs are cancelled (#11).
- Explicit Spring profile activation (`local` or `prod`); Actuator exposes only `health` and `info` until metrics are secured (#11).

### Removed
- Redundant `idx_account_number` index in `V5` (covered by unique constraint) and ineffective `hibernate.jdbc.batch_size` setting on `IDENTITY` entities (#11).

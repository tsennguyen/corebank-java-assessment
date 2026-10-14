# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
- 100-thread concurrent multi-account transfer stress test on PostgreSQL 17 Testcontainers (`TransferConcurrencyIT`) verifying zero deadlocks and strict conservation of funds + fees (#19).
- SQL-aggregated balance tier statistics (`GET /api/v1/statistics/balance-tiers`), customer location statistics (`GET /api/v1/statistics/customers-by-location`), and idempotent demo seed data (`R__demo_seed.sql`) (#20).
- Multi-stage layered `backend/Dockerfile` (Temurin 21 JRE, non-root `appuser`, `fontconfig`), 1-command `docker-compose.yml` stack, `render.yaml` Blueprint, and `.github/workflows/cd.yml` container smoke test (#21).

### Changed
- CI reports the required status check on every pull request; superseded PR runs are cancelled (#11).
- Explicit Spring profile activation (`local` or `prod`); Actuator exposes only `health` and `info` until metrics are secured (#11).

### Removed
- Redundant `idx_account_number` index in `V5` (covered by unique constraint) and ineffective `hibernate.jdbc.batch_size` setting on `IDENTITY` entities (#11).

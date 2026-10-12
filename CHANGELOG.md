# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Spring Boot 3.5.16 / Java 21 project skeleton with Spotless, Checkstyle and JaCoCo (#1).
- GitHub Actions backend CI with style checks and an 80% line-coverage gate (#3).
- Flyway baseline in schema `core`, Testcontainers PostgreSQL 17 integration tests (#4).
- RFC 9457 ProblemDetail error model, request id propagation and sensitive-data masking (#6).
- OpenAPI 3 / Swagger UI documentation (#7).
- Customer management API: create, read, update with optimistic locking, soft delete, search (#8).
- Account management API: Luhn-checked account numbers, limits, close, balance tiers, search (#9).
- Deposit API with pessimistic locking and 3s lock timeout, transaction query and account history (#10).

### Changed
- CI now reports the required check on every pull request (no path filter); superseded PR runs are cancelled.
- No default Spring profile: set `SPRING_PROFILES_ACTIVE=local` (or `prod`) explicitly.
- Actuator exposes only `health` and `info` until metrics are secured.

### Removed
- Redundant `idx_account_number` index (V5); the unique constraint already indexes the column.
- Ineffective `hibernate.jdbc.batch_size` setting (insert batching is disabled for IDENTITY ids).

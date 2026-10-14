package com.example.corebanking.common.domain;

/**
 * Standard banking domain error codes mapped to HTTP status codes, default titles, and RFC 9457 URN
 * problem types.
 */
public enum ErrorCode {

    // 400 Bad Request
    VALIDATION_FAILED(400, "Validation failed"),
    IDEMPOTENCY_KEY_MISSING(400, "Idempotency key missing"),
    INVALID_SORT_FIELD(400, "Invalid sort field"),
    SAME_ACCOUNT_TRANSFER(400, "Source and destination accounts cannot be identical"),
    IMMUTABLE_FIELD(400, "Field is immutable and cannot be updated"),

    // 401 Unauthorized
    UNAUTHORIZED(401, "Authentication required"),
    TOKEN_REUSED(401, "Refresh token has already been used or revoked"),

    // 403 Forbidden
    ACCESS_DENIED(403, "Access denied"),

    // 404 Not Found
    RESOURCE_NOT_FOUND(404, "Resource not found"),

    // 409 Conflict
    DUPLICATE_RESOURCE(409, "Resource already exists"),
    INVALID_STATUS_TRANSITION(409, "Invalid status transition"),
    ALREADY_REVERSED(409, "Transaction has already been reversed"),
    ACCOUNT_HAS_BALANCE(409, "Account balance must be zero before closing"),
    CUSTOMER_HAS_ACTIVE_ACCOUNTS(409, "Customer has active or open accounts and cannot be deleted"),
    LOCK_TIMEOUT(409, "Resource lock acquisition timed out"),
    CONCURRENT_MODIFICATION(409, "Concurrent modification conflict detected"),

    // 422 Unprocessable Entity
    LIMIT_EXCEEDED(422, "Transaction limit exceeded"),
    DAILY_LIMIT_EXCEEDED(422, "Daily transaction limit exceeded"),
    INSUFFICIENT_BALANCE(422, "Insufficient account balance"),
    ACCOUNT_NOT_ACTIVE(422, "Account is not active"),
    IDEMPOTENCY_KEY_REUSED(422, "Idempotency key reused with different payload"),

    // 429 Too Many Requests
    TOO_MANY_REQUESTS(429, "Too many requests"),

    // 500 Internal Server Error
    INTERNAL_ERROR(500, "Internal server error");

    private final int httpStatus;
    private final String defaultTitle;

    ErrorCode(int httpStatus, String defaultTitle) {
        this.httpStatus = httpStatus;
        this.defaultTitle = defaultTitle;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultTitle() {
        return defaultTitle;
    }

    public String getProblemTypeUri() {
        return "urn:problem-type:" + name().toLowerCase().replace('_', '-');
    }
}

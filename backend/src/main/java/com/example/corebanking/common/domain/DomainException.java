package com.example.corebanking.common.domain;

/**
 * Base abstract runtime exception for domain business rules and invariant failures. Decoupled from
 * web frameworks to maintain clean hexagonal/clean architecture boundaries.
 */
public abstract class DomainException extends RuntimeException {

    private final ErrorCode errorCode;

    protected DomainException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected DomainException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

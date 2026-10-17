package com.example.corebanking.auth.domain;

import com.example.corebanking.common.domain.DomainException;
import com.example.corebanking.common.domain.ErrorCode;

/**
 * Domain exception thrown when login attempts exceed the configured rate limit window ({@code 429
 * TOO_MANY_REQUESTS}, NC-07, NFR-5).
 */
public class RateLimitExceededException extends DomainException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(ErrorCode.TOO_MANY_REQUESTS, message);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

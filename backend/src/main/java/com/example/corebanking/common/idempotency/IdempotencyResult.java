package com.example.corebanking.common.idempotency;

/**
 * Result wrapper for idempotent operation execution containing the response data and a flag
 * indicating whether the response was replayed from a previously cached idempotent execution.
 *
 * @param <T> type of the response payload
 */
public record IdempotencyResult<T>(T data, boolean replayed) {

    public static <T> IdempotencyResult<T> executed(T data) {
        return new IdempotencyResult<>(data, false);
    }

    public static <T> IdempotencyResult<T> replayed(T data) {
        return new IdempotencyResult<>(data, true);
    }
}

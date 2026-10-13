package com.example.corebanking.transaction.domain;

/** Lifecycle status of a financial transaction. */
public enum TransactionStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REVERSED
}

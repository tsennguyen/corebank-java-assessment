package com.example.corebanking.recurring.domain;

import com.example.corebanking.transaction.domain.TransactionType;

/** Supported transaction types for scheduled recurring instructions (L2-06, FR-16). */
public enum RecurringTransactionType {
    DEPOSIT(TransactionType.DEPOSIT),
    PAYMENT(TransactionType.PAYMENT),
    TRANSFER(TransactionType.TRANSFER);

    private final TransactionType transactionType;

    RecurringTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
    }

    /** Returns the corresponding financial {@link TransactionType}. */
    public TransactionType toTransactionType() {
        return transactionType;
    }
}

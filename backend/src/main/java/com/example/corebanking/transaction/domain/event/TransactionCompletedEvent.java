package com.example.corebanking.transaction.domain.event;

import com.example.corebanking.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Domain event published when a financial transaction is successfully processed and committed.
 */
public record TransactionCompletedEvent(
        Long transactionId,
        String referenceNo,
        TransactionType type,
        BigDecimal amount,
        BigDecimal fee,
        Long sourceAccountId,
        Long targetAccountId,
        Instant timestamp) {}

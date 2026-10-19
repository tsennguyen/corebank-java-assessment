package com.example.corebanking.transaction.domain.event;

import java.time.Instant;

/**
 * Domain event published when non-financial metadata (description, location) of a transaction is
 * updated via PATCH (L1-06, L2-08).
 */
public record TransactionMetadataUpdatedEvent(
        Long transactionId, Long sourceAccountId, Long targetAccountId, Instant timestamp) {}

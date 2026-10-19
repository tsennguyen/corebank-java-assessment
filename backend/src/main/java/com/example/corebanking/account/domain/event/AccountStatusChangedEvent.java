package com.example.corebanking.account.domain.event;

import com.example.corebanking.account.domain.AccountStatus;
import java.time.Instant;

/**
 * Domain event published whenever an account transitions between lifecycle states ({@code ACTIVE},
 * {@code LOCKED}, {@code CLOSED}) (L2-05, FR-3).
 */
public record AccountStatusChangedEvent(
        Long accountId,
        Long customerId,
        AccountStatus fromStatus,
        AccountStatus toStatus,
        String reason,
        String changedBy,
        Instant timestamp) {}

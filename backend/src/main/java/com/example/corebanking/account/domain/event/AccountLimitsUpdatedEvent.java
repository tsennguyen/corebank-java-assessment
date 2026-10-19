package com.example.corebanking.account.domain.event;

import java.time.Instant;

/**
 * Domain event published when an account's per-transaction or daily limits are modified (L1-05,
 * L2-08).
 */
public record AccountLimitsUpdatedEvent(Long accountId, Long customerId, Instant timestamp) {}

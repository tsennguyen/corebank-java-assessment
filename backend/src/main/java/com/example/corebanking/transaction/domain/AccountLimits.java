package com.example.corebanking.transaction.domain;

import java.math.BigDecimal;

/** Effective transaction limit and daily limit for an account. */
public record AccountLimits(BigDecimal transactionLimit, BigDecimal dailyLimit) {}

package com.example.corebanking.transaction.domain;

import java.math.BigDecimal;

/**
 * Value object representing a fee calculation tier.
 *
 * @param minAmount minimum amount threshold (inclusive) for this tier
 * @param flatFee flat fee charged in this tier (defaults to zero)
 * @param ratePercent percentage rate charged in this tier (defaults to zero, e.g. 0.02 for 0.02%)
 * @param maxFee maximum fee cap for this tier (nullable)
 */
public record FeeTier(
        BigDecimal minAmount, BigDecimal flatFee, BigDecimal ratePercent, BigDecimal maxFee) {

    public FeeTier {
        minAmount = minAmount != null ? minAmount : BigDecimal.ZERO;
        flatFee = flatFee != null ? flatFee : BigDecimal.ZERO;
        ratePercent = ratePercent != null ? ratePercent : BigDecimal.ZERO;
    }
}

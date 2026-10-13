package com.example.corebanking.transaction.domain;

import com.example.corebanking.common.domain.Money;

/** Domain strategy interface for evaluating and calculating transaction fees. */
public interface FeePolicy {

    /**
     * Calculates transaction fee according to the configured fee policy.
     *
     * @param type transaction type
     * @param amount transaction amount (must be strictly positive)
     * @return calculated fee as Money (scale 2, RoundingMode.HALF_UP)
     */
    Money calculateFee(TransactionType type, Money amount);
}

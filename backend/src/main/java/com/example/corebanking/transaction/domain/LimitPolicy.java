package com.example.corebanking.transaction.domain;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.Money;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Domain policy evaluating and enforcing per-transaction and daily spending limits. */
@Component
public class LimitPolicy {

    private final LimitProvider limitProvider;

    public LimitPolicy(LimitProvider limitProvider) {
        this.limitProvider = limitProvider;
    }

    /**
     * Validates that the requested amount respects both the per-transaction limit and the aggregate
     * daily spending limit.
     *
     * @param account source account to evaluate
     * @param amount principal transaction amount
     * @param currentDailySpent aggregate outgoing principal amount already processed today
     */
    public void validateLimits(Account account, Money amount, Money currentDailySpent) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currentDailySpent, "currentDailySpent must not be null");

        AccountLimits limits = limitProvider.getLimits(account);
        BigDecimal transactionLimit = limits.transactionLimit();
        BigDecimal dailyLimit = limits.dailyLimit();

        if (transactionLimit != null && amount.getAmount().compareTo(transactionLimit) > 0) {
            throw new BusinessRuleException(
                    ErrorCode.LIMIT_EXCEEDED,
                    "Transaction amount "
                            + amount.getAmount()
                            + " exceeds per-transaction limit of "
                            + transactionLimit);
        }

        BigDecimal totalDaily = currentDailySpent.getAmount().add(amount.getAmount());
        if (dailyLimit != null && totalDaily.compareTo(dailyLimit) > 0) {
            throw new BusinessRuleException(
                    ErrorCode.DAILY_LIMIT_EXCEEDED,
                    "Total daily spending " + totalDaily + " exceeds daily limit of " + dailyLimit);
        }
    }
}

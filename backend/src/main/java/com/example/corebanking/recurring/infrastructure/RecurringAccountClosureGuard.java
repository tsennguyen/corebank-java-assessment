package com.example.corebanking.recurring.infrastructure;

import com.example.corebanking.account.application.AccountClosureGuard;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.recurring.domain.RecurringStatus;
import org.springframework.stereotype.Component;

/**
 * Prevents closing an account that still has {@code ACTIVE} recurring transaction instructions
 * (L2-06, FR-2, FR-16).
 */
@Component
public class RecurringAccountClosureGuard implements AccountClosureGuard {

    private final RecurringTransactionRepository recurringTransactionRepository;

    public RecurringAccountClosureGuard(
            RecurringTransactionRepository recurringTransactionRepository) {
        this.recurringTransactionRepository = recurringTransactionRepository;
    }

    @Override
    public void validateCanCloseAccount(Long accountId) {
        if (recurringTransactionRepository.existsByAccountIdAndStatus(
                accountId, RecurringStatus.ACTIVE)) {
            throw new BusinessRuleException(
                    ErrorCode.ACCOUNT_HAS_ACTIVE_RECURRING,
                    "Account still has active recurring transactions and cannot be closed");
        }
    }
}

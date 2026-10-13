package com.example.corebanking.account.infrastructure;

import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.customer.application.CustomerDeletionGuard;
import org.springframework.stereotype.Component;

/** Validates that a customer has no open/active accounts before permitting deletion. */
@Component
public class AccountCustomerDeletionGuard implements CustomerDeletionGuard {

    private final AccountRepository accountRepository;

    public AccountCustomerDeletionGuard(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public void validateCanDeleteCustomer(Long customerId) {
        if (accountRepository.existsByCustomerIdAndStatusNot(customerId, AccountStatus.CLOSED)) {
            throw new BusinessRuleException(
                    ErrorCode.CUSTOMER_HAS_ACTIVE_ACCOUNTS,
                    "Customer still has non-closed accounts and cannot be deleted");
        }
    }
}

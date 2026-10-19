package com.example.corebanking.account.application;

/**
 * Port interface allowing cross-module validation before closing an account without introducing
 * circular package dependencies between {@code account} and {@code recurring} (L2-06, FR-2, FR-16).
 */
public interface AccountClosureGuard {

    /**
     * Asserts that the specified account can be safely closed.
     *
     * @param accountId ID of the account being closed
     * @throws com.example.corebanking.common.domain.BusinessRuleException if the account still has
     *     active recurring transactions
     */
    void validateCanCloseAccount(Long accountId);
}

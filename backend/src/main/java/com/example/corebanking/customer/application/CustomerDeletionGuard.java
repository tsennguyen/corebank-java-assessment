package com.example.corebanking.customer.application;

/**
 * Port interface allowing cross-module validation before customer deletion without introducing
 * circular package dependencies.
 */
public interface CustomerDeletionGuard {

    /**
     * Asserts that the customer can be safely soft-deleted.
     *
     * @param customerId ID of customer to be deleted
     * @throws com.example.corebanking.common.domain.BusinessRuleException if customer has open
     *     accounts
     */
    void validateCanDeleteCustomer(Long customerId);
}

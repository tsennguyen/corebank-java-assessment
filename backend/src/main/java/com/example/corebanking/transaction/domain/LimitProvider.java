package com.example.corebanking.transaction.domain;

import com.example.corebanking.account.domain.Account;

/** Strategy interface for resolving effective limits of an account. */
public interface LimitProvider {

    /**
     * Resolves transaction and daily limits for the specified account.
     *
     * @param account account entity
     * @return resolved account limits
     */
    AccountLimits getLimits(Account account);
}

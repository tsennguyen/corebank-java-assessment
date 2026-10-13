package com.example.corebanking.transaction.domain;

import com.example.corebanking.account.domain.Account;
import org.springframework.stereotype.Component;

/** Default limit provider obtaining limits directly configured on the account entity. */
@Component
public class AccountLimitProvider implements LimitProvider {

    @Override
    public AccountLimits getLimits(Account account) {
        return new AccountLimits(account.getTransactionLimit(), account.getDailyLimit());
    }
}

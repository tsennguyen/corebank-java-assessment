package com.example.corebanking.account.infrastructure;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.domain.AccountStatus;
import org.springframework.data.jpa.domain.Specification;

/** Reusable JPA specifications for querying Account entities. */
public final class AccountSpecifications {

    private AccountSpecifications() {
        // Utility class
    }

    public static Specification<Account> withCustomerId(Long customerId) {
        return (root, query, cb) ->
                customerId == null
                        ? cb.conjunction()
                        : cb.equal(root.get("customerId"), customerId);
    }

    public static Specification<Account> withStatus(AccountStatus status) {
        return (root, query, cb) ->
                status == null ? cb.conjunction() : cb.equal(root.get("status"), status);
    }

    public static Specification<Account> withAccountNumber(String accountNumber) {
        return (root, query, cb) -> {
            if (accountNumber == null || accountNumber.isBlank()) {
                return cb.conjunction();
            }
            return cb.equal(root.get("accountNumber"), accountNumber.trim());
        };
    }
}

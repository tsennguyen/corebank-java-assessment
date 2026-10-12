package com.example.corebanking.transaction.infrastructure;

import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.domain.TransactionType;
import org.springframework.data.jpa.domain.Specification;

/** JPA specifications for dynamic transaction search queries. */
public final class TransactionSpecifications {

    private TransactionSpecifications() {}

    /** Matches transactions where either source or target account matches accountId. */
    public static Specification<Transaction> withAccountId(Long accountId) {
        if (accountId == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) ->
                cb.or(
                        cb.equal(root.get("sourceAccountId"), accountId),
                        cb.equal(root.get("targetAccountId"), accountId));
    }

    /** Matches transactions by transaction type. */
    public static Specification<Transaction> withType(TransactionType type) {
        if (type == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }
}

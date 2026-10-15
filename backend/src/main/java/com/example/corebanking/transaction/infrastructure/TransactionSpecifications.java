package com.example.corebanking.transaction.infrastructure;

import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.domain.TransactionType;
import java.util.Set;
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

    /**
     * Matches transactions where either source or target account belongs to the allowed account ID
     * set (L2-01, FR-14).
     */
    public static Specification<Transaction> withAccountIdsIn(Set<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) ->
                cb.or(
                        root.get("sourceAccountId").in(accountIds),
                        root.get("targetAccountId").in(accountIds));
    }

    /** Matches transactions by transaction type. */
    public static Specification<Transaction> withType(TransactionType type) {
        if (type == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }
}

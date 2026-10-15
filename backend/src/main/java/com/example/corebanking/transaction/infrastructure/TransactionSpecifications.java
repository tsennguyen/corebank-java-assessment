package com.example.corebanking.transaction.infrastructure;

import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.domain.TransactionStatus;
import com.example.corebanking.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;

/** JPA specifications for dynamic transaction search queries (L2-03, FR-8). */
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

    /** Matches transactions by transaction status (COMPLETED, REVERSED). */
    public static Specification<Transaction> withStatus(TransactionStatus status) {
        if (status == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /** Matches transactions with amount greater than or equal to minAmount. */
    public static Specification<Transaction> withMinAmount(BigDecimal minAmount) {
        if (minAmount == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("amount"), minAmount);
    }

    /** Matches transactions with amount less than or equal to maxAmount. */
    public static Specification<Transaction> withMaxAmount(BigDecimal maxAmount) {
        if (maxAmount == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("amount"), maxAmount);
    }

    /** Matches transactions created at or after the given inclusive lower-bound instant. */
    public static Specification<Transaction> withCreatedAtFrom(Instant fromInstant) {
        if (fromInstant == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), fromInstant);
    }

    /** Matches transactions created strictly before the given exclusive upper-bound instant. */
    public static Specification<Transaction> withCreatedAtBefore(Instant toExclusiveInstant) {
        if (toExclusiveInstant == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.lessThan(root.get("createdAt"), toExclusiveInstant);
    }
}

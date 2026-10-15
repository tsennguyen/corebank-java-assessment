package com.example.corebanking.recurring.infrastructure;

import com.example.corebanking.recurring.domain.RecurringFrequency;
import com.example.corebanking.recurring.domain.RecurringStatus;
import com.example.corebanking.recurring.domain.RecurringTransaction;
import com.example.corebanking.recurring.domain.RecurringTransactionType;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable JPA Specifications for filtering {@link RecurringTransaction} records (L2-06, FR-16).
 */
public final class RecurringTransactionSpecifications {

    private RecurringTransactionSpecifications() {}

    /** Filters recurring transactions by lifecycle status. */
    public static Specification<RecurringTransaction> withStatus(RecurringStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    /**
     * Filters recurring transactions where {@code accountId} matches either {@code sourceAccountId}
     * or {@code targetAccountId}.
     */
    public static Specification<RecurringTransaction> withAccountId(Long accountId) {
        return (root, query, cb) ->
                accountId == null
                        ? null
                        : cb.or(
                                cb.equal(root.get("sourceAccountId"), accountId),
                                cb.equal(root.get("targetAccountId"), accountId));
    }

    /** Filters recurring transactions by instruction type. */
    public static Specification<RecurringTransaction> withType(RecurringTransactionType type) {
        return (root, query, cb) -> type == null ? null : cb.equal(root.get("type"), type);
    }

    /** Filters recurring transactions by execution frequency. */
    public static Specification<RecurringTransaction> withFrequency(RecurringFrequency frequency) {
        return (root, query, cb) ->
                frequency == null ? null : cb.equal(root.get("frequency"), frequency);
    }
}

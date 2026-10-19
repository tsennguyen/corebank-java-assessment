package com.example.corebanking.fraud.infrastructure;

import com.example.corebanking.fraud.domain.Alert;
import com.example.corebanking.fraud.domain.AlertRuleCode;
import com.example.corebanking.fraud.domain.AlertSeverity;
import com.example.corebanking.fraud.domain.AlertStatus;
import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

/**
 * JPA specifications for dynamic filtering of suspicious transaction alerts in {@code core.alert}
 * (L3-04, FR-23).
 */
public final class AlertSpecifications {

    private AlertSpecifications() {}

    /** Matches alerts by investigation status ({@code NEW}, {@code INVESTIGATING}, etc.). */
    public static Specification<Alert> withStatus(AlertStatus status) {
        if (status == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /** Matches alerts by anomaly rule code ({@code AMOUNT_THRESHOLD}, {@code VELOCITY}). */
    public static Specification<Alert> withRuleCode(AlertRuleCode ruleCode) {
        if (ruleCode == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("ruleCode"), ruleCode);
    }

    /** Matches alerts by severity ({@code MEDIUM}, {@code HIGH}). */
    public static Specification<Alert> withSeverity(AlertSeverity severity) {
        if (severity == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("severity"), severity);
    }

    /** Matches alerts by source account ID. */
    public static Specification<Alert> withAccountId(Long accountId) {
        if (accountId == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("accountId"), accountId);
    }

    /** Matches alerts created at or after the given inclusive lower-bound instant. */
    public static Specification<Alert> withCreatedAtFrom(Instant fromInstant) {
        if (fromInstant == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), fromInstant);
    }

    /** Matches alerts created strictly before the given exclusive upper-bound instant. */
    public static Specification<Alert> withCreatedAtBefore(Instant toExclusiveInstant) {
        if (toExclusiveInstant == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.lessThan(root.get("createdAt"), toExclusiveInstant);
    }
}

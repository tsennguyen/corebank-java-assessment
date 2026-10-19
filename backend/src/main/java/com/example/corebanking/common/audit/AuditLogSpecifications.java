package com.example.corebanking.common.audit;

import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;

/** JPA Specifications for multi-criteria filtering on {@link AuditLog} (Story 6.3). */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {}

    public static Specification<AuditLog> withActor(@Nullable String actor) {
        return (root, query, cb) -> {
            if (actor == null || actor.isBlank()) {
                return cb.conjunction();
            }
            return cb.equal(cb.lower(root.get("actor")), actor.trim().toLowerCase());
        };
    }

    public static Specification<AuditLog> withAction(@Nullable String action) {
        return (root, query, cb) -> {
            if (action == null || action.isBlank()) {
                return cb.conjunction();
            }
            return cb.equal(cb.upper(root.get("action")), action.trim().toUpperCase());
        };
    }

    public static Specification<AuditLog> withEntityType(@Nullable String entityType) {
        return (root, query, cb) -> {
            if (entityType == null || entityType.isBlank()) {
                return cb.conjunction();
            }
            return cb.equal(cb.upper(root.get("entityType")), entityType.trim().toUpperCase());
        };
    }

    public static Specification<AuditLog> withCreatedAtFrom(@Nullable Instant from) {
        return (root, query, cb) -> {
            if (from == null) {
                return cb.conjunction();
            }
            return cb.greaterThanOrEqualTo(root.get("createdAt"), from);
        };
    }

    public static Specification<AuditLog> withCreatedAtBefore(@Nullable Instant toExclusive) {
        return (root, query, cb) -> {
            if (toExclusive == null) {
                return cb.conjunction();
            }
            return cb.lessThan(root.get("createdAt"), toExclusive);
        };
    }
}

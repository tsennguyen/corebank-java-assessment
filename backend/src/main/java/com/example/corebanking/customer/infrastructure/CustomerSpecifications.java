package com.example.corebanking.customer.infrastructure;

import com.example.corebanking.customer.domain.Customer;
import org.springframework.data.jpa.domain.Specification;

/** Reusable JPA specifications for querying and filtering Customer entities. */
public final class CustomerSpecifications {

    private static final char LIKE_ESCAPE_CHAR = '\\';

    private CustomerSpecifications() {
        // Utility class
    }

    /** Matches only active customers that have not been soft-deleted. */
    public static Specification<Customer> activeOnly() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    /** Case-insensitive partial match on full name with escaped SQL LIKE wildcards (R3-11). */
    public static Specification<Customer> withName(String name) {
        return (root, query, cb) -> {
            if (name == null || name.isBlank()) {
                return cb.conjunction();
            }
            String escaped = escapeLikePattern(name.toLowerCase().trim());
            return cb.like(cb.lower(root.get("fullName")), "%" + escaped + "%", LIKE_ESCAPE_CHAR);
        };
    }

    /**
     * Case-insensitive partial match on location (province/city) with escaped SQL LIKE wildcards
     * (R3-11).
     */
    public static Specification<Customer> withLocation(String location) {
        return (root, query, cb) -> {
            if (location == null || location.isBlank()) {
                return cb.conjunction();
            }
            String escaped = escapeLikePattern(location.toLowerCase().trim());
            return cb.like(cb.lower(root.get("location")), "%" + escaped + "%", LIKE_ESCAPE_CHAR);
        };
    }

    static String escapeLikePattern(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

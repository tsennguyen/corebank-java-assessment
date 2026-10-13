package com.example.corebanking.customer.infrastructure;

import com.example.corebanking.customer.domain.Customer;
import org.springframework.data.jpa.domain.Specification;

/** Reusable JPA specifications for querying and filtering Customer entities. */
public final class CustomerSpecifications {

    private CustomerSpecifications() {
        // Utility class
    }

    /** Matches only active customers that have not been soft-deleted. */
    public static Specification<Customer> activeOnly() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    /** Case-insensitive partial match on full name. */
    public static Specification<Customer> withName(String name) {
        return (root, query, cb) -> {
            if (name == null || name.isBlank()) {
                return cb.conjunction();
            }
            return cb.like(cb.lower(root.get("fullName")), "%" + name.toLowerCase().trim() + "%");
        };
    }

    /** Case-insensitive partial match on location (province/city). */
    public static Specification<Customer> withLocation(String location) {
        return (root, query, cb) -> {
            if (location == null || location.isBlank()) {
                return cb.conjunction();
            }
            return cb.like(
                    cb.lower(root.get("location")), "%" + location.toLowerCase().trim() + "%");
        };
    }
}

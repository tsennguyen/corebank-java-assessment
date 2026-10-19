package com.example.corebanking.customer.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CustomerSpecifications} wildcard escaping (R3-11). */
class CustomerSpecificationsTest {

    @Test
    @DisplayName(
            "escapeLikePattern escapes backslash, percent, and underscore SQL wildcards (R3-11)")
    void escapeLikePattern_shouldEscapeSqlWildcards() {
        assertThat(CustomerSpecifications.escapeLikePattern("nguyen")).isEqualTo("nguyen");
        assertThat(CustomerSpecifications.escapeLikePattern("100%")).isEqualTo("100\\%");
        assertThat(CustomerSpecifications.escapeLikePattern("a_b")).isEqualTo("a\\_b");
        assertThat(CustomerSpecifications.escapeLikePattern("a\\b%c_d"))
                .isEqualTo("a\\\\b\\%c\\_d");
    }

    @Test
    @DisplayName("withName and withLocation handle null and blank strings safely")
    void specifications_whenNullOrBlank_shouldReturnNonNullSpec() {
        assertThat(CustomerSpecifications.withName(null)).isNotNull();
        assertThat(CustomerSpecifications.withName("   ")).isNotNull();
        assertThat(CustomerSpecifications.withLocation(null)).isNotNull();
        assertThat(CustomerSpecifications.withLocation("   ")).isNotNull();
    }
}

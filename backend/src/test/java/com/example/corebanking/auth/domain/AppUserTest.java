package com.example.corebanking.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppUserTest {

    @Test
    @DisplayName("createAdmin creates enabled ADMIN user with null customerId")
    void createAdmin_shouldCreateValidAdminUser() {
        AppUser admin = AppUser.createAdmin("  admin  ", "$2a$10$hashedPassword");

        assertThat(admin.getUsername()).isEqualTo("admin");
        assertThat(admin.getPasswordHash()).isEqualTo("$2a$10$hashedPassword");
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getCustomerId()).isNull();
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.getLastLoginAt()).isNull();
    }

    @Test
    @DisplayName("createCustomer creates enabled CUSTOMER user linked to customerId")
    void createCustomer_shouldCreateValidCustomerUser() {
        AppUser customer = AppUser.createCustomer("cust.an", "$2a$10$hashedPassword", 42L);

        assertThat(customer.getUsername()).isEqualTo("cust.an");
        assertThat(customer.getPasswordHash()).isEqualTo("$2a$10$hashedPassword");
        assertThat(customer.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(customer.getCustomerId()).isEqualTo(42L);
        assertThat(customer.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("createCustomer rejects null customerId with VALIDATION_FAILED")
    void createCustomer_whenCustomerIdNull_shouldThrowException() {
        assertThatThrownBy(() -> AppUser.createCustomer("cust.an", "$2a$10$hash", null))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("createAdmin rejects blank username or blank passwordHash")
    void createAdmin_whenBlankUsernameOrPassword_shouldThrowException() {
        assertThatThrownBy(() -> AppUser.createAdmin("   ", "$2a$10$hash"))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> AppUser.createAdmin("admin", "  "))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("state mutators update passwordHash, lastLoginAt, and enabled flag")
    void stateMutators_shouldUpdateFieldsCorrectly() {
        AppUser user = AppUser.createAdmin("admin", "$2a$10$oldHash");
        Instant now = Instant.parse("2026-10-14T04:00:00Z");

        user.updatePasswordHash("$2a$10$newHash");
        user.recordLogin(now);
        user.disable();
        assertThat(user.getPasswordHash()).isEqualTo("$2a$10$newHash");
        assertThat(user.getLastLoginAt()).isEqualTo(now);
        assertThat(user.isEnabled()).isFalse();

        user.enable();
        assertThat(user.isEnabled()).isTrue();

        assertThatThrownBy(() -> user.updatePasswordHash(""))
                .isInstanceOf(BusinessRuleException.class);
    }
}

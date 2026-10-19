package com.example.corebanking.auth.domain;

import com.example.corebanking.common.domain.BaseEntity;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Domain entity representing an authenticated system user (ADMIN or CUSTOMER) mapped to {@code
 * core.app_user} (L2-01, L2-02).
 */
@Entity
@Table(name = "app_user", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "customer_id", unique = true)
    private Long customerId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    private AppUser(String username, String passwordHash, Role role, Long customerId) {
        if (username == null || username.isBlank()) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Username must not be blank");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Password hash must not be blank");
        }
        if (role == null) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Role must not be null");
        }
        if (role == Role.ADMIN && customerId != null) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "ADMIN user must not be linked to a customerId");
        }
        if (role == Role.CUSTOMER && customerId == null) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "CUSTOMER user must be linked to a customerId");
        }
        this.username = username.trim();
        this.passwordHash = passwordHash;
        this.role = role;
        this.customerId = customerId;
        this.enabled = true;
    }

    /** Creates an ADMIN user account (not linked to any customer). */
    public static AppUser createAdmin(String username, String passwordHash) {
        return new AppUser(username, passwordHash, Role.ADMIN, null);
    }

    /** Creates a CUSTOMER user account linked to a specific customer record. */
    public static AppUser createCustomer(String username, String passwordHash, Long customerId) {
        return new AppUser(username, passwordHash, Role.CUSTOMER, customerId);
    }

    /** Updates the BCrypt password hash of the user. */
    public void updatePasswordHash(String newPasswordHash) {
        if (newPasswordHash == null || newPasswordHash.isBlank()) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Password hash must not be blank");
        }
        this.passwordHash = newPasswordHash;
    }

    /** Records the timestamp of a successful authentication. */
    public void recordLogin(Instant loginTimestamp) {
        this.lastLoginAt = loginTimestamp;
    }

    /** Enables the user account. */
    public void enable() {
        this.enabled = true;
    }

    /** Disables the user account. */
    public void disable() {
        this.enabled = false;
    }
}

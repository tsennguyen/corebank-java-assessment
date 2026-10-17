package com.example.corebanking.customer.domain;

import com.example.corebanking.common.domain.BaseEntity;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * CustomerType domain entity representing a customer classification (e.g. {@code INDIVIDUAL},
 * {@code BUSINESS}) and its maximum allowed per-transaction and daily outgoing limits (L3-05,
 * L3-06, FR-18).
 */
@Entity
@Table(name = "customer_type", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerType extends BaseEntity {

    public static final String CODE_INDIVIDUAL = "INDIVIDUAL";
    public static final String CODE_BUSINESS = "BUSINESS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 32, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "max_transaction_limit", nullable = false)
    private BigDecimal maxTransactionLimit;

    @Column(name = "max_daily_limit", nullable = false)
    private BigDecimal maxDailyLimit;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Domain constructor for creating a customer type classification. */
    public CustomerType(
            String code, String name, BigDecimal maxTransactionLimit, BigDecimal maxDailyLimit) {
        this(null, code, name, maxTransactionLimit, maxDailyLimit);
    }

    /** Domain constructor with explicit ID for deterministic seeding/testing. */
    public CustomerType(
            Long id,
            String code,
            String name,
            BigDecimal maxTransactionLimit,
            BigDecimal maxDailyLimit) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("CustomerType code must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("CustomerType name must not be blank");
        }
        this.id = id;
        this.code = code.trim().toUpperCase(Locale.ROOT);
        this.name = name.trim();
        this.version = 0L;
        validateAndSetLimits(maxTransactionLimit, maxDailyLimit);
    }

    /**
     * Updates the maximum per-transaction and daily limits for this customer type. Enforces {@code
     * maxDailyLimit >= maxTransactionLimit > 0}.
     */
    public void updateLimits(BigDecimal maxTransactionLimit, BigDecimal maxDailyLimit) {
        validateAndSetLimits(maxTransactionLimit, maxDailyLimit);
    }

    /** Updates the display name (if non-blank) and maximum per-transaction and daily limits. */
    public void updateNameAndLimits(
            String name, BigDecimal maxTransactionLimit, BigDecimal maxDailyLimit) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
        validateAndSetLimits(maxTransactionLimit, maxDailyLimit);
    }

    private void validateAndSetLimits(BigDecimal txLimit, BigDecimal dLimit) {
        Objects.requireNonNull(txLimit, "maxTransactionLimit must not be null");
        Objects.requireNonNull(dLimit, "maxDailyLimit must not be null");

        BigDecimal scaledTxLimit = txLimit.setScale(2, RoundingMode.HALF_UP);
        BigDecimal scaledDailyLimit = dLimit.setScale(2, RoundingMode.HALF_UP);

        if (scaledTxLimit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "Max transaction limit must be greater than zero: " + scaledTxLimit);
        }
        if (scaledDailyLimit.compareTo(scaledTxLimit) < 0) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "Max daily limit ("
                            + scaledDailyLimit
                            + ") must be greater than or equal to max transaction limit ("
                            + scaledTxLimit
                            + ")");
        }

        this.maxTransactionLimit = scaledTxLimit;
        this.maxDailyLimit = scaledDailyLimit;
    }
}

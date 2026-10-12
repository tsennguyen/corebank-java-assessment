package com.example.corebanking.account.domain;

import com.example.corebanking.common.domain.BaseEntity;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Account domain entity enforcing banking invariants: Non-negative balance, limit constraints
 * (dailyLimit >= transactionLimit > 0), explicit status lifecycle, and optimistic locking.
 */
@Entity
@Table(name = "account", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseEntity {

    public static final String DEFAULT_CURRENCY = "VND";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "account_number",
            nullable = false,
            unique = true,
            length = 32,
            updatable = false)
    private String accountNumber;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "balance", nullable = false)
    private BigDecimal balance;

    @Column(name = "transaction_limit", nullable = false)
    private BigDecimal transactionLimit;

    @Column(name = "daily_limit", nullable = false)
    private BigDecimal dailyLimit;

    @Column(name = "opened_date", nullable = false, updatable = false)
    private LocalDate openedDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Domain constructor for opening a new bank account. */
    public Account(
            String accountNumber,
            Long customerId,
            BigDecimal transactionLimit,
            BigDecimal dailyLimit,
            LocalDate openedDate) {
        this.accountNumber =
                Objects.requireNonNull(accountNumber, "accountNumber must not be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.openedDate = Objects.requireNonNull(openedDate, "openedDate must not be null");
        this.currency = DEFAULT_CURRENCY;
        this.balance = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.status = AccountStatus.ACTIVE;
        this.version = 0L;

        validateAndSetLimits(transactionLimit, dailyLimit);
    }

    /**
     * Updates per-transaction and daily transaction limits. Enforces dailyLimit >= transactionLimit
     * > 0.
     */
    public void updateLimits(BigDecimal transactionLimit, BigDecimal dailyLimit) {
        if (isClosed()) {
            throw new IllegalStateException("Cannot update limits on a closed account");
        }
        validateAndSetLimits(transactionLimit, dailyLimit);
    }

    /**
     * Closes the account if balance is exactly zero. Throws ACCOUNT_HAS_BALANCE (409) if balance >
     * 0.
     */
    public void close(Instant closedAtTimestamp) {
        if (isClosed()) {
            return;
        }
        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessRuleException(
                    ErrorCode.ACCOUNT_HAS_BALANCE,
                    "Account balance must be zero before closing. Current balance: " + balance);
        }
        this.status = AccountStatus.CLOSED;
        this.closedAt =
                Objects.requireNonNull(closedAtTimestamp, "closedAtTimestamp must not be null");
    }

    public boolean isActive() {
        return this.status == AccountStatus.ACTIVE;
    }

    public boolean isClosed() {
        return this.status == AccountStatus.CLOSED;
    }

    /**
     * Credits (deposits) funds into the account. Enforces that the account is active and the amount
     * is positive.
     */
    public void credit(Money depositAmount) {
        Objects.requireNonNull(depositAmount, "depositAmount must not be null");
        if (!depositAmount.isPositive()) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Deposit amount must be greater than zero");
        }
        if (!isActive()) {
            throw new BusinessRuleException(
                    ErrorCode.ACCOUNT_NOT_ACTIVE,
                    "Cannot credit account in status: " + this.status);
        }
        this.balance =
                this.balance.add(depositAmount.getAmount()).setScale(2, RoundingMode.HALF_UP);
    }

    private void validateAndSetLimits(BigDecimal transactionLimit, BigDecimal dailyLimit) {
        Objects.requireNonNull(transactionLimit, "transactionLimit must not be null");
        Objects.requireNonNull(dailyLimit, "dailyLimit must not be null");

        if (transactionLimit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Transaction limit must be greater than zero");
        }
        if (dailyLimit.compareTo(transactionLimit) < 0) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "Daily limit ("
                            + dailyLimit
                            + ") must be greater than or equal to transaction limit ("
                            + transactionLimit
                            + ")");
        }
        this.transactionLimit = transactionLimit.setScale(2, RoundingMode.HALF_UP);
        this.dailyLimit = dailyLimit.setScale(2, RoundingMode.HALF_UP);
    }
}

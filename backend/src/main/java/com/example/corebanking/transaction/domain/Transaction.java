package com.example.corebanking.transaction.domain;

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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Domain entity representing an immutable financial transaction in the core banking system.
 * Captures balance audit trail (source/target balances after event) and total system balance delta.
 */
@Entity
@Table(name = "transaction", schema = "core")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference_no", nullable = false, unique = true, length = 64, updatable = false)
    private String referenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32, updatable = false)
    private TransactionType type;

    @Column(name = "source_account_id", updatable = false)
    private Long sourceAccountId;

    @Column(name = "target_account_id", updatable = false)
    private Long targetAccountId;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "fee", nullable = false, updatable = false)
    private BigDecimal fee;

    @Column(name = "source_balance_after", updatable = false)
    private BigDecimal sourceBalanceAfter;

    @Column(name = "target_balance_after", updatable = false)
    private BigDecimal targetBalanceAfter;

    @Column(name = "system_balance_delta", nullable = false, updatable = false)
    private BigDecimal systemBalanceDelta;

    @Column(name = "location", length = 255)
    private String location;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TransactionStatus status;

    @Column(name = "reversal_of_id", unique = true)
    private Long reversalOfId;

    @Column(name = "recurring_id", updatable = false)
    private Long recurringId;

    @Column(name = "created_by", length = 64, updatable = false)
    private String createdBy;

    /** Factory method creating a valid deposit transaction record. */
    public static Transaction createDeposit(
            String referenceNo,
            Long targetAccountId,
            BigDecimal amount,
            BigDecimal targetBalanceAfter,
            String location,
            String description) {
        Objects.requireNonNull(referenceNo, "referenceNo must not be null");
        Objects.requireNonNull(targetAccountId, "targetAccountId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(targetBalanceAfter, "targetBalanceAfter must not be null");

        BigDecimal scaledAmount = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal scaledBalanceAfter = targetBalanceAfter.setScale(2, RoundingMode.HALF_UP);
        BigDecimal zeroFee = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        return Transaction.builder()
                .referenceNo(referenceNo)
                .type(TransactionType.DEPOSIT)
                .status(TransactionStatus.COMPLETED)
                .sourceAccountId(null)
                .targetAccountId(targetAccountId)
                .amount(scaledAmount)
                .fee(zeroFee)
                .sourceBalanceAfter(null)
                .targetBalanceAfter(scaledBalanceAfter)
                .systemBalanceDelta(scaledAmount)
                .location(location)
                .description(description)
                .build();
    }

    /** Factory method creating a valid withdrawal transaction record. */
    public static Transaction createWithdrawal(
            String referenceNo,
            Long sourceAccountId,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal sourceBalanceAfter,
            String location,
            String description) {
        Objects.requireNonNull(referenceNo, "referenceNo must not be null");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(sourceBalanceAfter, "sourceBalanceAfter must not be null");

        BigDecimal scaledAmount = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal scaledFee =
                (fee != null ? fee : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal scaledBalanceAfter = sourceBalanceAfter.setScale(2, RoundingMode.HALF_UP);
        BigDecimal systemDelta = scaledAmount.add(scaledFee).negate();

        return Transaction.builder()
                .referenceNo(referenceNo)
                .type(TransactionType.WITHDRAWAL)
                .status(TransactionStatus.COMPLETED)
                .sourceAccountId(sourceAccountId)
                .targetAccountId(null)
                .amount(scaledAmount)
                .fee(scaledFee)
                .sourceBalanceAfter(scaledBalanceAfter)
                .targetBalanceAfter(null)
                .systemBalanceDelta(systemDelta)
                .location(location)
                .description(description)
                .build();
    }

    /** Factory method creating a valid transfer transaction record. */
    public static Transaction createTransfer(
            String referenceNo,
            Long sourceAccountId,
            Long targetAccountId,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal sourceBalanceAfter,
            BigDecimal targetBalanceAfter,
            String location,
            String description) {
        Objects.requireNonNull(referenceNo, "referenceNo must not be null");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId must not be null");
        Objects.requireNonNull(targetAccountId, "targetAccountId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(sourceBalanceAfter, "sourceBalanceAfter must not be null");
        Objects.requireNonNull(targetBalanceAfter, "targetBalanceAfter must not be null");

        BigDecimal scaledAmount = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal scaledFee =
                (fee != null ? fee : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal scaledSourceBalanceAfter = sourceBalanceAfter.setScale(2, RoundingMode.HALF_UP);
        BigDecimal scaledTargetBalanceAfter = targetBalanceAfter.setScale(2, RoundingMode.HALF_UP);
        BigDecimal systemDelta = scaledFee.negate();

        return Transaction.builder()
                .referenceNo(referenceNo)
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .sourceAccountId(sourceAccountId)
                .targetAccountId(targetAccountId)
                .amount(scaledAmount)
                .fee(scaledFee)
                .sourceBalanceAfter(scaledSourceBalanceAfter)
                .targetBalanceAfter(scaledTargetBalanceAfter)
                .systemBalanceDelta(systemDelta)
                .location(location)
                .description(description)
                .build();
    }

    /** Factory method creating a valid payment transaction record. */
    public static Transaction createPayment(
            String referenceNo,
            Long sourceAccountId,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal sourceBalanceAfter,
            String location,
            String description) {
        Objects.requireNonNull(referenceNo, "referenceNo must not be null");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(sourceBalanceAfter, "sourceBalanceAfter must not be null");

        BigDecimal scaledAmount = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal scaledFee =
                (fee != null ? fee : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal scaledBalanceAfter = sourceBalanceAfter.setScale(2, RoundingMode.HALF_UP);
        BigDecimal systemDelta = scaledAmount.add(scaledFee).negate();

        return Transaction.builder()
                .referenceNo(referenceNo)
                .type(TransactionType.PAYMENT)
                .status(TransactionStatus.COMPLETED)
                .sourceAccountId(sourceAccountId)
                .targetAccountId(null)
                .amount(scaledAmount)
                .fee(scaledFee)
                .sourceBalanceAfter(scaledBalanceAfter)
                .targetBalanceAfter(null)
                .systemBalanceDelta(systemDelta)
                .location(location)
                .description(description)
                .build();
    }

    /**
     * Updates mutable transaction metadata (description and location only).
     *
     * @param newDescription updated description string
     * @param newLocation updated location string
     */
    public void updateMetadata(String newDescription, String newLocation) {
        if (this.status != TransactionStatus.COMPLETED) {
            throw new BusinessRuleException(
                    ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot update metadata on transaction with status: " + this.status);
        }
        if (newDescription != null) {
            this.description = newDescription;
        }
        if (newLocation != null) {
            this.location = newLocation;
        }
    }

    /** Marks this transaction as reversed. */
    public void markReversed() {
        if (this.status == TransactionStatus.REVERSED) {
            throw new BusinessRuleException(
                    ErrorCode.ALREADY_REVERSED, "Transaction has already been reversed");
        }
        if (this.type == TransactionType.REVERSAL) {
            throw new BusinessRuleException(
                    ErrorCode.INVALID_STATUS_TRANSITION, "Cannot reverse a reversal transaction");
        }
        if (this.status != TransactionStatus.COMPLETED) {
            throw new BusinessRuleException(
                    ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot reverse transaction with status: " + this.status);
        }
        this.status = TransactionStatus.REVERSED;
    }

    /** Factory method creating a reversal for a DEPOSIT transaction. */
    public static Transaction createDepositReversal(
            String referenceNo,
            Transaction original,
            BigDecimal targetBalanceAfter,
            String location,
            String description) {
        Objects.requireNonNull(referenceNo, "referenceNo must not be null");
        Objects.requireNonNull(original, "original must not be null");
        Objects.requireNonNull(targetBalanceAfter, "targetBalanceAfter must not be null");

        BigDecimal scaledBalance = targetBalanceAfter.setScale(2, RoundingMode.HALF_UP);
        String effectiveDesc =
                description != null ? description : "Reversal of " + original.getReferenceNo();
        String effectiveLoc = location != null ? location : original.getLocation();

        return Transaction.builder()
                .referenceNo(referenceNo)
                .type(TransactionType.REVERSAL)
                .status(TransactionStatus.COMPLETED)
                .sourceAccountId(original.getTargetAccountId())
                .targetAccountId(null)
                .amount(original.getAmount())
                .fee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .sourceBalanceAfter(scaledBalance)
                .targetBalanceAfter(null)
                .systemBalanceDelta(original.getAmount().negate())
                .reversalOfId(original.getId())
                .location(effectiveLoc)
                .description(effectiveDesc)
                .build();
    }

    /** Factory method creating a reversal for a WITHDRAWAL transaction. */
    public static Transaction createWithdrawalReversal(
            String referenceNo,
            Transaction original,
            BigDecimal sourceBalanceAfter,
            String location,
            String description) {
        Objects.requireNonNull(referenceNo, "referenceNo must not be null");
        Objects.requireNonNull(original, "original must not be null");
        Objects.requireNonNull(sourceBalanceAfter, "sourceBalanceAfter must not be null");

        BigDecimal scaledBalance = sourceBalanceAfter.setScale(2, RoundingMode.HALF_UP);
        String effectiveDesc =
                description != null ? description : "Reversal of " + original.getReferenceNo();
        String effectiveLoc = location != null ? location : original.getLocation();
        BigDecimal systemDelta =
                original.getAmount().add(original.getFee()).setScale(2, RoundingMode.HALF_UP);

        return Transaction.builder()
                .referenceNo(referenceNo)
                .type(TransactionType.REVERSAL)
                .status(TransactionStatus.COMPLETED)
                .sourceAccountId(null)
                .targetAccountId(original.getSourceAccountId())
                .amount(original.getAmount())
                .fee(original.getFee())
                .sourceBalanceAfter(null)
                .targetBalanceAfter(scaledBalance)
                .systemBalanceDelta(systemDelta)
                .reversalOfId(original.getId())
                .location(effectiveLoc)
                .description(effectiveDesc)
                .build();
    }

    /** Factory method creating a reversal for a PAYMENT transaction. */
    public static Transaction createPaymentReversal(
            String referenceNo,
            Transaction original,
            BigDecimal sourceBalanceAfter,
            String location,
            String description) {
        Objects.requireNonNull(referenceNo, "referenceNo must not be null");
        Objects.requireNonNull(original, "original must not be null");
        Objects.requireNonNull(sourceBalanceAfter, "sourceBalanceAfter must not be null");

        BigDecimal scaledBalance = sourceBalanceAfter.setScale(2, RoundingMode.HALF_UP);
        String effectiveDesc =
                description != null ? description : "Reversal of " + original.getReferenceNo();
        String effectiveLoc = location != null ? location : original.getLocation();
        BigDecimal systemDelta =
                original.getAmount().add(original.getFee()).setScale(2, RoundingMode.HALF_UP);

        return Transaction.builder()
                .referenceNo(referenceNo)
                .type(TransactionType.REVERSAL)
                .status(TransactionStatus.COMPLETED)
                .sourceAccountId(null)
                .targetAccountId(original.getSourceAccountId())
                .amount(original.getAmount())
                .fee(original.getFee())
                .sourceBalanceAfter(null)
                .targetBalanceAfter(scaledBalance)
                .systemBalanceDelta(systemDelta)
                .reversalOfId(original.getId())
                .location(effectiveLoc)
                .description(effectiveDesc)
                .build();
    }

    /** Factory method creating a reversal for a TRANSFER transaction. */
    public static Transaction createTransferReversal(
            String referenceNo,
            Transaction original,
            BigDecimal sourceBalanceAfter,
            BigDecimal targetBalanceAfter,
            String location,
            String description) {
        Objects.requireNonNull(referenceNo, "referenceNo must not be null");
        Objects.requireNonNull(original, "original must not be null");
        Objects.requireNonNull(sourceBalanceAfter, "sourceBalanceAfter must not be null");
        Objects.requireNonNull(targetBalanceAfter, "targetBalanceAfter must not be null");

        BigDecimal scaledSourceBalance = sourceBalanceAfter.setScale(2, RoundingMode.HALF_UP);
        BigDecimal scaledTargetBalance = targetBalanceAfter.setScale(2, RoundingMode.HALF_UP);
        String effectiveDesc =
                description != null ? description : "Reversal of " + original.getReferenceNo();
        String effectiveLoc = location != null ? location : original.getLocation();

        return Transaction.builder()
                .referenceNo(referenceNo)
                .type(TransactionType.REVERSAL)
                .status(TransactionStatus.COMPLETED)
                .sourceAccountId(original.getTargetAccountId())
                .targetAccountId(original.getSourceAccountId())
                .amount(original.getAmount())
                .fee(original.getFee())
                .sourceBalanceAfter(scaledSourceBalance)
                .targetBalanceAfter(scaledTargetBalance)
                .systemBalanceDelta(original.getFee())
                .reversalOfId(original.getId())
                .location(effectiveLoc)
                .description(effectiveDesc)
                .build();
    }
}

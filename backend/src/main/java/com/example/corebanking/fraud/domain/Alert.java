package com.example.corebanking.fraud.domain;

import com.example.corebanking.common.domain.BaseEntity;
import com.example.corebanking.common.domain.InvalidStatusTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Domain entity representing a suspicious transaction alert persisted in {@code core.alert} (L3-03,
 * L3-04, FR-22, FR-23, ADR-012).
 */
@Entity
@Table(name = "alert", schema = "core")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Alert extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private Long transactionId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_code", nullable = false, length = 32, updatable = false)
    private AlertRuleCode ruleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16, updatable = false)
    private AlertSeverity severity;

    @Column(name = "reason", nullable = false, length = 500, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AlertStatus status;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "handled_by", length = 64)
    private String handledBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Factory method creating a new unprocessed alert. */
    public static Alert createNew(
            Long transactionId,
            Long accountId,
            AlertRuleCode ruleCode,
            AlertSeverity severity,
            String reason) {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(ruleCode, "ruleCode must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        return Alert.builder()
                .transactionId(transactionId)
                .accountId(accountId)
                .ruleCode(ruleCode)
                .severity(severity)
                .reason(reason)
                .status(AlertStatus.NEW)
                .version(0L)
                .build();
    }

    /**
     * Transitions the alert to {@code targetStatus} according to {@link
     * AlertStatus#canTransitionTo(AlertStatus)}.
     *
     * @param targetStatus target alert status
     * @param actor username of the administrator handling the alert
     * @param note optional investigation or resolution note
     * @throws InvalidStatusTransitionException (HTTP 409) when the transition is invalid
     */
    public void transitionStatus(AlertStatus targetStatus, String actor, String note) {
        if (targetStatus == null || !this.status.canTransitionTo(targetStatus)) {
            throw new InvalidStatusTransitionException(
                    String.format(
                            "Cannot transition alert status from %s to %s",
                            this.status, targetStatus));
        }
        this.status = targetStatus;
        this.handledBy = actor;
        if (note != null && !note.isBlank()) {
            this.resolutionNote = note.trim();
        }
    }
}

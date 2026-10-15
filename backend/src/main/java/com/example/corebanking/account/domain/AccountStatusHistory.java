package com.example.corebanking.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Immutable audit record capturing every account status transition (including initial account
 * opening where {@code fromStatus} is {@code null}) (L2-05, FR-3).
 */
@Entity
@Table(name = "account_status_history", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, updatable = false)
    private AccountStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20, updatable = false)
    private AccountStatus toStatus;

    @Column(name = "reason", nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "changed_by", nullable = false, length = 100, updatable = false)
    private String changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    public AccountStatusHistory(
            Long accountId,
            AccountStatus fromStatus,
            AccountStatus toStatus,
            String reason,
            String changedBy,
            Instant changedAt) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.fromStatus = fromStatus;
        this.toStatus = Objects.requireNonNull(toStatus, "toStatus must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.changedBy = Objects.requireNonNull(changedBy, "changedBy must not be null");
        this.changedAt = Objects.requireNonNull(changedAt, "changedAt must not be null");
    }
}

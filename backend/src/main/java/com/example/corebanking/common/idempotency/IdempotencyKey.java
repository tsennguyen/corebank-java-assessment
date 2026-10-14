package com.example.corebanking.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Entity storing idempotency key records to prevent double execution and support safe request
 * replay.
 */
@Entity
@Table(name = "idempotency_key", schema = "core")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

    @EmbeddedId private IdempotencyKeyId id;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public static IdempotencyKey of(
            String idemKey,
            String scope,
            String requestHash,
            int responseStatus,
            String responseBody,
            Instant createdAt,
            Instant expiresAt) {
        return IdempotencyKey.builder()
                .id(new IdempotencyKeyId(idemKey, scope))
                .requestHash(requestHash)
                .responseStatus(responseStatus)
                .responseBody(responseBody)
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .build();
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }
}

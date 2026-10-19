package com.example.corebanking.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * Immutable append-only audit log entity persisted in {@code core.audit_log} (Story 6.3: NC-13,
 * L2-01).
 */
@Entity
@Immutable
@Table(name = "audit_log", schema = "core")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor", nullable = false, length = 100, updatable = false)
    private String actor;

    @Column(name = "action", nullable = false, length = 64, updatable = false)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 64, updatable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 64, updatable = false)
    private String entityId;

    @Column(name = "before_json", columnDefinition = "TEXT", updatable = false)
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "TEXT", updatable = false)
    private String afterJson;

    @Column(name = "request_id", length = 64, updatable = false)
    private String requestId;

    @Column(name = "ip", length = 64, updatable = false)
    private String ip;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
        // Required by JPA
    }

    public AuditLog(
            String actor,
            String action,
            String entityType,
            String entityId,
            String beforeJson,
            String afterJson,
            String requestId,
            String ip,
            Instant createdAt) {
        this.actor = actor;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.requestId = requestId;
        this.ip = ip;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getIp() {
        return ip;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

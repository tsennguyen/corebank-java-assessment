package com.example.corebanking.common.audit;

import java.time.Instant;

/**
 * Response DTO representing an immutable audit log entry returned by {@code GET
 * /api/v1/admin/audit-logs} (Story 6.3: NC-13, L2-01).
 */
public record AuditLogResponse(
        Long id,
        String actor,
        String action,
        String entityType,
        String entityId,
        String beforeJson,
        String afterJson,
        String requestId,
        String ip,
        Instant createdAt) {

    public static AuditLogResponse from(AuditLog entity) {
        return new AuditLogResponse(
                entity.getId(),
                entity.getActor(),
                entity.getAction(),
                entity.getEntityType(),
                entity.getEntityId(),
                entity.getBeforeJson(),
                entity.getAfterJson(),
                entity.getRequestId(),
                entity.getIp(),
                entity.getCreatedAt());
    }
}

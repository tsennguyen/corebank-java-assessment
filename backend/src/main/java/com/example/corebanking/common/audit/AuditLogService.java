package com.example.corebanking.common.audit;

import com.example.corebanking.account.domain.event.AccountLimitsUpdatedEvent;
import com.example.corebanking.account.domain.event.AccountStatusChangedEvent;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.util.MaskingUtils;
import com.example.corebanking.transaction.domain.TransactionType;
import com.example.corebanking.transaction.domain.event.TransactionCompletedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Application service recording and querying immutable audit logs for sensitive administrative
 * actions (account limit changes, account status transitions, transaction reversals, and fraud
 * alert status changes) within the originating database transaction (Story 6.3: NC-13, L2-01).
 *
 * <p>All sensitive fields ({@code accountNumber}, {@code nationalId}) in {@code before_json} and
 * {@code after_json} are masked via {@link MaskingUtils} prior to persistence.
 */
@Service
@Transactional(readOnly = true)
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    public static final String ACTION_ACCOUNT_LIMITS_UPDATED = "ACCOUNT_LIMITS_UPDATED";
    public static final String ACTION_ACCOUNT_STATUS_CHANGED = "ACCOUNT_STATUS_CHANGED";
    public static final String ACTION_TRANSACTION_REVERSED = "TRANSACTION_REVERSED";
    public static final String ACTION_ALERT_STATUS_CHANGED = "ALERT_STATUS_CHANGED";

    private static final String DEFAULT_ACTOR = "SYSTEM";
    private static final String DEFAULT_IP = "127.0.0.1";
    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("id", "actor", "action", "entityType", "entityId", "createdAt");

    private final AuditLogRepository auditLogRepository;
    @Nullable private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AuditLogService(
            AuditLogRepository auditLogRepository,
            @Nullable JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Synchronous in-transaction listener recording audit trail when account limits are updated.
     * Executes inside {@code AccountService.updateLimits}'s active transaction so the audit row
     * commits or rolls back atomically with the account update.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountLimitsUpdated(AccountLimitsUpdatedEvent event) {
        AccountIdentitySnapshot snapshot = lookupAccountIdentity(event.accountId());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("accountId", event.accountId());
        if (snapshot.maskedAccountNumber() != null) {
            before.put("accountNumber", snapshot.maskedAccountNumber());
        }
        if (snapshot.maskedNationalId() != null) {
            before.put("customerNationalId", snapshot.maskedNationalId());
        }

        Map<String, Object> after = new LinkedHashMap<>(before);
        if (snapshot.transactionLimit() != null) {
            after.put("transactionLimit", snapshot.transactionLimit());
        }
        if (snapshot.dailyLimit() != null) {
            after.put("dailyLimit", snapshot.dailyLimit());
        }

        recordAudit(
                resolveCurrentActor(),
                ACTION_ACCOUNT_LIMITS_UPDATED,
                "ACCOUNT",
                String.valueOf(event.accountId()),
                toJson(before),
                toJson(after),
                event.timestamp() != null ? event.timestamp() : clock.instant());
    }

    /**
     * Synchronous in-transaction listener recording audit trail when an account's lifecycle status
     * changes ({@code ACTIVE -> LOCKED}, {@code LOCKED -> ACTIVE}, {@code * -> CLOSED}). Ignores
     * initial account creation ({@code fromStatus == null}).
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountStatusChanged(AccountStatusChangedEvent event) {
        if (event.fromStatus() == null) {
            return;
        }
        AccountIdentitySnapshot snapshot = lookupAccountIdentity(event.accountId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("accountId", event.accountId());
        if (snapshot.maskedAccountNumber() != null) {
            before.put("accountNumber", snapshot.maskedAccountNumber());
        }
        if (snapshot.maskedNationalId() != null) {
            before.put("customerNationalId", snapshot.maskedNationalId());
        }
        before.put("status", event.fromStatus().name());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("accountId", event.accountId());
        if (snapshot.maskedAccountNumber() != null) {
            after.put("accountNumber", snapshot.maskedAccountNumber());
        }
        if (snapshot.maskedNationalId() != null) {
            after.put("customerNationalId", snapshot.maskedNationalId());
        }
        after.put("status", event.toStatus().name());
        after.put("reason", event.reason());

        String actor =
                (event.changedBy() != null && !event.changedBy().isBlank())
                        ? event.changedBy()
                        : resolveCurrentActor();

        recordAudit(
                actor,
                ACTION_ACCOUNT_STATUS_CHANGED,
                "ACCOUNT",
                String.valueOf(event.accountId()),
                toJson(before),
                toJson(after),
                event.timestamp() != null ? event.timestamp() : clock.instant());
    }

    /**
     * Synchronous in-transaction listener recording audit trail when a financial transaction is
     * reversed ({@code type == REVERSAL}).
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTransactionCompleted(TransactionCompletedEvent event) {
        if (event.type() != TransactionType.REVERSAL) {
            return;
        }
        Long originalTxId = lookupReversalOfId(event.transactionId());
        AccountIdentitySnapshot sourceSnap =
                event.sourceAccountId() != null
                        ? lookupAccountIdentity(event.sourceAccountId())
                        : AccountIdentitySnapshot.empty();
        AccountIdentitySnapshot targetSnap =
                event.targetAccountId() != null
                        ? lookupAccountIdentity(event.targetAccountId())
                        : AccountIdentitySnapshot.empty();

        Map<String, Object> before = new LinkedHashMap<>();
        if (originalTxId != null) {
            before.put("originalTransactionId", originalTxId);
        }
        before.put("status", "COMPLETED");
        if (sourceSnap.maskedAccountNumber() != null) {
            before.put("sourceAccountNumber", sourceSnap.maskedAccountNumber());
        }
        if (targetSnap.maskedAccountNumber() != null) {
            before.put("targetAccountNumber", targetSnap.maskedAccountNumber());
        }

        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("status", "REVERSED");
        after.put("reversalTransactionId", event.transactionId());
        after.put("reversalReferenceNo", event.referenceNo());
        after.put("amount", event.amount());

        recordAudit(
                resolveCurrentActor(),
                ACTION_TRANSACTION_REVERSED,
                "TRANSACTION",
                String.valueOf(originalTxId != null ? originalTxId : event.transactionId()),
                toJson(before),
                toJson(after),
                event.timestamp() != null ? event.timestamp() : clock.instant());
    }

    /**
     * Records an audit log entry inside the active transaction when a fraud alert status is updated
     * by an administrator.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAlertStatusChange(
            Long alertId,
            @Nullable Long accountId,
            @Nullable Long transactionId,
            String fromStatus,
            String toStatus,
            @Nullable String note,
            String actor,
            Instant timestamp) {
        AccountIdentitySnapshot snapshot =
                accountId != null
                        ? lookupAccountIdentity(accountId)
                        : AccountIdentitySnapshot.empty();

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("alertId", alertId);
        if (transactionId != null) {
            before.put("transactionId", transactionId);
        }
        if (snapshot.maskedAccountNumber() != null) {
            before.put("accountNumber", snapshot.maskedAccountNumber());
        }
        if (snapshot.maskedNationalId() != null) {
            before.put("customerNationalId", snapshot.maskedNationalId());
        }
        before.put("status", fromStatus);

        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("status", toStatus);
        if (note != null && !note.isBlank()) {
            after.put("resolutionNote", note);
        }

        recordAudit(
                (actor != null && !actor.isBlank()) ? actor : resolveCurrentActor(),
                ACTION_ALERT_STATUS_CHANGED,
                "ALERT",
                String.valueOf(alertId),
                toJson(before),
                toJson(after),
                timestamp != null ? timestamp : clock.instant());
    }

    /**
     * Persists an immutable {@link AuditLog} entry within the caller's active database transaction.
     */
    @Transactional
    public AuditLog recordAudit(
            String actor,
            String action,
            String entityType,
            String entityId,
            @Nullable String beforeJson,
            @Nullable String afterJson,
            Instant createdAt) {
        String effectiveActor =
                (actor != null && !actor.isBlank()) ? actor.trim() : resolveCurrentActor();
        String requestId = RequestIdFilter.getCurrentRequestId();
        String clientIp = resolveCurrentClientIp();
        Instant effectiveCreatedAt = createdAt != null ? createdAt : clock.instant();

        AuditLog entry =
                new AuditLog(
                        effectiveActor,
                        action,
                        entityType,
                        entityId,
                        beforeJson,
                        afterJson,
                        requestId,
                        clientIp,
                        effectiveCreatedAt);
        AuditLog saved = auditLogRepository.save(entry);
        log.debug(
                "Recorded audit log [id={}, actor={}, action={}, entityType={}, entityId={}]",
                saved.getId(),
                saved.getActor(),
                saved.getAction(),
                saved.getEntityType(),
                saved.getEntityId());
        return saved;
    }

    /**
     * Searches audit logs with optional filters for {@code actor}, {@code action}, {@code
     * entityType}, and date/timestamp bounds ({@code from}, {@code to}), returning a paginated
     * response ordered by {@code createdAt DESC, id DESC} by default (Story 6.3).
     */
    public PageResponse<AuditLogResponse> searchAuditLogs(
            @Nullable String actor,
            @Nullable String action,
            @Nullable String entityType,
            @Nullable LocalDate fromDate,
            @Nullable LocalDate toDate,
            @Nullable String from,
            @Nullable String to,
            Pageable pageable) {
        Pageable effectivePageable = resolvePageable(pageable);
        validatePaginationAndSort(effectivePageable);

        ZoneId businessZone = clock.getZone();
        Instant fromInstant = resolveFromBound(fromDate, from, businessZone);
        Instant toExclusiveInstant = resolveToExclusiveBound(toDate, to, businessZone);

        if (fromInstant != null
                && toExclusiveInstant != null
                && !fromInstant.isBefore(toExclusiveInstant)) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "from date/timestamp must not be after to");
        }

        Specification<AuditLog> spec =
                AuditLogSpecifications.withActor(actor)
                        .and(AuditLogSpecifications.withAction(action))
                        .and(AuditLogSpecifications.withEntityType(entityType))
                        .and(AuditLogSpecifications.withCreatedAtFrom(fromInstant))
                        .and(AuditLogSpecifications.withCreatedAtBefore(toExclusiveInstant));

        Page<AuditLog> page = auditLogRepository.findAll(spec, effectivePageable);
        return PageResponse.from(page, AuditLogResponse::from);
    }

    private AccountIdentitySnapshot lookupAccountIdentity(Long accountId) {
        if (jdbcClient == null || accountId == null) {
            return AccountIdentitySnapshot.empty();
        }
        try {
            return jdbcClient
                    .sql(
                            """
SELECT a.account_number, a.transaction_limit, a.daily_limit, c.national_id
FROM core.account a
LEFT JOIN core.customer c ON c.id = a.customer_id
WHERE a.id = :accountId
""")
                    .param("accountId", accountId)
                    .query(
                            (rs, rowNum) ->
                                    new AccountIdentitySnapshot(
                                            MaskingUtils.accountNumber(
                                                    rs.getString("account_number")),
                                            MaskingUtils.nationalId(rs.getString("national_id")),
                                            rs.getBigDecimal("transaction_limit"),
                                            rs.getBigDecimal("daily_limit")))
                    .optional()
                    .orElseGet(AccountIdentitySnapshot::empty);
        } catch (RuntimeException ex) {
            log.debug(
                    "Unable to enrich account identity for audit log [accountId={}]: {}",
                    accountId,
                    ex.getMessage());
            return AccountIdentitySnapshot.empty();
        }
    }

    @Nullable
    private Long lookupReversalOfId(Long reversalTxId) {
        if (jdbcClient == null || reversalTxId == null) {
            return null;
        }
        try {
            return jdbcClient
                    .sql("SELECT reversal_of_id FROM core.transaction WHERE id = :id")
                    .param("id", reversalTxId)
                    .query(Long.class)
                    .optional()
                    .orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException ex) {
            return map.toString();
        }
    }

    private String resolveCurrentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            if (authentication.getPrincipal() instanceof Jwt jwt) {
                String username = jwt.getClaimAsString("username");
                if (username != null && !username.isBlank()) {
                    return username.trim();
                }
            }
            if (authentication.getName() != null
                    && !authentication.getName().isBlank()
                    && !"anonymousUser".equals(authentication.getName())) {
                return authentication.getName().trim();
            }
        }
        return DEFAULT_ACTOR;
    }

    private String resolveCurrentClientIp() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest request = servletAttrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
            if (request.getRemoteAddr() != null && !request.getRemoteAddr().isBlank()) {
                return request.getRemoteAddr();
            }
        }
        return DEFAULT_IP;
    }

    private Pageable resolvePageable(Pageable pageable) {
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        } else {
            boolean hasIdSort = sort.stream().anyMatch(order -> "id".equals(order.getProperty()));
            if (!hasIdSort) {
                sort = sort.and(Sort.by(Sort.Order.desc("id")));
            }
        }
        if (pageable.isUnpaged()) {
            return Pageable.unpaged(sort);
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    private void validatePaginationAndSort(Pageable pageable) {
        if (!pageable.isUnpaged()) {
            if (pageable.getPageSize() > 100 || pageable.getPageSize() < 1) {
                throw new BusinessRuleException(
                        ErrorCode.VALIDATION_FAILED, "Page size must be between 1 and 100");
            }
            if (pageable.getPageNumber() < 0) {
                throw new BusinessRuleException(
                        ErrorCode.VALIDATION_FAILED, "Page number must not be negative");
            }
        }
        for (Sort.Order order : pageable.getSort()) {
            if (!ALLOWED_SORT_FIELDS.contains(order.getProperty())) {
                throw new BusinessRuleException(
                        ErrorCode.INVALID_SORT_FIELD,
                        "Invalid sort field '"
                                + order.getProperty()
                                + "'. Allowed fields: "
                                + ALLOWED_SORT_FIELDS);
            }
        }
    }

    @Nullable
    private Instant resolveFromBound(
            @Nullable LocalDate fromDate, @Nullable String from, ZoneId zone) {
        if (fromDate != null) {
            return fromDate.atStartOfDay(zone).toInstant();
        }
        if (from == null || from.isBlank()) {
            return null;
        }
        String trimmed = from.trim();
        try {
            return LocalDate.parse(trimmed).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            return parseInstantOrThrow(trimmed, "from");
        }
    }

    @Nullable
    private Instant resolveToExclusiveBound(
            @Nullable LocalDate toDate, @Nullable String to, ZoneId zone) {
        if (toDate != null) {
            return toDate.plusDays(1).atStartOfDay(zone).toInstant();
        }
        if (to == null || to.isBlank()) {
            return null;
        }
        String trimmed = to.trim();
        try {
            return LocalDate.parse(trimmed).plusDays(1).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            return parseInstantOrThrow(trimmed, "to").plus(1, ChronoUnit.MICROS);
        }
    }

    private Instant parseInstantOrThrow(String value, String fieldName) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex1) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ex2) {
                throw new BusinessRuleException(
                        ErrorCode.VALIDATION_FAILED,
                        "Invalid '"
                                + fieldName
                                + "' timestamp or date '"
                                + value
                                + "'. Expected ISO-8601 Instant or YYYY-MM-DD");
            }
        }
    }

    private record AccountIdentitySnapshot(
            @Nullable String maskedAccountNumber,
            @Nullable String maskedNationalId,
            @Nullable BigDecimal transactionLimit,
            @Nullable BigDecimal dailyLimit) {
        static AccountIdentitySnapshot empty() {
            return new AccountIdentitySnapshot(null, null, null, null);
        }
    }
}

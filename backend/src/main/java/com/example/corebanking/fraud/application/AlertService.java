package com.example.corebanking.fraud.application;

import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.fraud.api.dto.AlertResponse;
import com.example.corebanking.fraud.api.dto.FraudScanRequest;
import com.example.corebanking.fraud.api.dto.FraudScanResponse;
import com.example.corebanking.fraud.api.dto.UpdateAlertStatusRequest;
import com.example.corebanking.fraud.domain.Alert;
import com.example.corebanking.fraud.domain.AlertRuleCode;
import com.example.corebanking.fraud.domain.AlertSeverity;
import com.example.corebanking.fraud.domain.AlertStatus;
import com.example.corebanking.fraud.domain.AnomalyFinding;
import com.example.corebanking.fraud.domain.FraudProperties;
import com.example.corebanking.fraud.domain.TransactionSnapshot;
import com.example.corebanking.fraud.infrastructure.AlertRepository;
import com.example.corebanking.fraud.infrastructure.AlertSpecifications;
import com.example.corebanking.fraud.infrastructure.FraudScanRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * Application service managing suspicious transaction alerts: idempotent real-time persistence,
 * paginated multi-filter search, FSM status transitions ({@code NEW -> INVESTIGATING -> RESOLVED |
 * FALSE_POSITIVE}, {@code NEW -> FALSE_POSITIVE}), and historical batch scans using PostgreSQL
 * window functions (L3-03, L3-04, FR-22, FR-23, ADR-012).
 */
@Service
@Transactional(readOnly = true)
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    public static final String ALERTS_RAISED_METRIC = "banking.alerts.raised";
    private static final String DEFAULT_ACTOR = "SYSTEM";
    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("id", "createdAt", "severity", "status");
    private static final long MAX_SCAN_RANGE_DAYS = 366L;

    private static final String INSERT_ALERT_IDEMPOTENT_SQL =
            """
INSERT INTO core.alert (
    transaction_id, account_id, rule_code, severity, reason, status, created_at, updated_at, version
)
SELECT t.id, a.id, :ruleCode, :severity, :reason, 'NEW', :now, :now, 0
FROM core.transaction t
JOIN core.account a ON a.id = :accountId
WHERE t.id = :transactionId
ON CONFLICT (transaction_id, rule_code) DO NOTHING
""";

    private final JdbcClient jdbcClient;
    @Nullable private final AlertRepository alertRepository;
    @Nullable private final FraudScanRepository fraudScanRepository;
    private final FraudProperties fraudProperties;
    private final Clock clock;
    @Nullable private final MeterRegistry meterRegistry;

    public AlertService(JdbcClient jdbcClient, Clock clock, @Nullable MeterRegistry meterRegistry) {
        this(jdbcClient, null, null, new FraudProperties(), clock, meterRegistry);
    }

    @Autowired
    public AlertService(
            JdbcClient jdbcClient,
            @Nullable AlertRepository alertRepository,
            @Nullable FraudScanRepository fraudScanRepository,
            @Nullable FraudProperties fraudProperties,
            Clock clock,
            @Nullable MeterRegistry meterRegistry) {
        this.jdbcClient = jdbcClient;
        this.alertRepository = alertRepository;
        this.fraudScanRepository = fraudScanRepository;
        this.fraudProperties = fraudProperties != null ? fraudProperties : new FraudProperties();
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Raises a new alert for the given transaction snapshot and anomaly finding if an alert with
     * the same {@code (transaction_id, rule_code)} does not already exist.
     *
     * @param snapshot completed transaction snapshot
     * @param finding anomaly rule evaluation finding
     * @return {@code true} if a new alert row was inserted; {@code false} if deduplicated by {@code
     *     ON CONFLICT DO NOTHING}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean raise(TransactionSnapshot snapshot, AnomalyFinding finding) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(finding, "finding must not be null");
        return raise(snapshot.transactionId(), snapshot.accountId(), finding);
    }

    /**
     * Raises a new alert for the given {@code transactionId}, {@code accountId}, and {@link
     * AnomalyFinding} idempotently.
     *
     * @param transactionId transaction identifier
     * @param accountId source account identifier
     * @param finding anomaly finding details
     * @return {@code true} if inserted; {@code false} if already present
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean raise(Long transactionId, Long accountId, AnomalyFinding finding) {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(finding, "finding must not be null");

        Instant now = clock.instant();
        OffsetDateTime nowUtc = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        int inserted =
                jdbcClient
                        .sql(INSERT_ALERT_IDEMPOTENT_SQL)
                        .param("transactionId", transactionId)
                        .param("accountId", accountId)
                        .param("ruleCode", finding.ruleCode().name())
                        .param("severity", finding.severity().name())
                        .param("reason", finding.reason())
                        .param("now", nowUtc)
                        .update();

        if (inserted > 0) {
            incrementAlertsRaisedMetric(finding.ruleCode(), 1);
            log.warn(
                    "Fraud alert raised [txnId={}, accountId={}, rule={}, severity={}, reason={}]",
                    transactionId,
                    accountId,
                    finding.ruleCode(),
                    finding.severity(),
                    finding.reason());
            return true;
        }

        log.debug(
                "Duplicate fraud alert suppressed by ON CONFLICT DO NOTHING [txnId={}, rule={}]",
                transactionId,
                finding.ruleCode());
        return false;
    }

    /**
     * Searches alerts with optional filters for status, rule code, severity, account ID, and date
     * bounds, returning a deterministic paginated response (L3-04, FR-23).
     */
    public PageResponse<AlertResponse> searchAlerts(
            @Nullable AlertStatus status,
            @Nullable AlertRuleCode ruleCode,
            @Nullable AlertSeverity severity,
            @Nullable Long accountId,
            @Nullable LocalDate fromDate,
            @Nullable LocalDate toDate,
            @Nullable String from,
            @Nullable String to,
            Pageable pageable) {
        if (alertRepository == null) {
            throw new IllegalStateException("AlertRepository is not configured");
        }
        Pageable effectivePageable = resolvePageable(pageable);
        validatePaginationAndSort(effectivePageable);

        ZoneId businessZone = clock.getZone();
        Instant fromInstant = resolveFromFilterBound(fromDate, from, businessZone);
        Instant toExclusiveInstant = resolveToExclusiveFilterBound(toDate, to, businessZone);

        if (fromInstant != null
                && toExclusiveInstant != null
                && !fromInstant.isBefore(toExclusiveInstant)) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "from date/timestamp must not be after to");
        }

        Specification<Alert> spec =
                AlertSpecifications.withStatus(status)
                        .and(AlertSpecifications.withRuleCode(ruleCode))
                        .and(AlertSpecifications.withSeverity(severity))
                        .and(AlertSpecifications.withAccountId(accountId))
                        .and(AlertSpecifications.withCreatedAtFrom(fromInstant))
                        .and(AlertSpecifications.withCreatedAtBefore(toExclusiveInstant));

        Page<Alert> page = alertRepository.findAll(spec, effectivePageable);
        return PageResponse.from(page, AlertResponse::from);
    }

    /**
     * Retrieves alert details by internal identifier.
     *
     * @param id alert ID
     * @return alert response DTO
     * @throws ResourceNotFoundException (HTTP 404) if not found
     */
    public AlertResponse getAlertById(Long id) {
        if (alertRepository == null) {
            throw new IllegalStateException("AlertRepository is not configured");
        }
        Alert alert =
                alertRepository
                        .findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Alert", id));
        return AlertResponse.from(alert);
    }

    /**
     * Transitions an alert's lifecycle status according to {@link
     * AlertStatus#canTransitionTo(AlertStatus)} and records the handling administrator and optional
     * resolution note (L3-04, FR-23).
     *
     * @param id alert ID
     * @param request target status, optional note, and optional version
     * @return updated alert response
     */
    @Transactional
    public AlertResponse updateStatus(Long id, UpdateAlertStatusRequest request) {
        if (alertRepository == null) {
            throw new IllegalStateException("AlertRepository is not configured");
        }
        if (request == null || request.targetStatus() == null) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "targetStatus must not be null");
        }
        Alert alert =
                alertRepository
                        .findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Alert", id));

        if (request.version() != null && !Objects.equals(alert.getVersion(), request.version())) {
            log.warn(
                    "Optimistic concurrency conflict on Alert [id={}]: expected version={},"
                            + " actual={}",
                    alert.getId(),
                    request.version(),
                    alert.getVersion());
            throw new BusinessRuleException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "Alert has been modified by another concurrent transaction. Expected version "
                            + request.version()
                            + " but found "
                            + alert.getVersion());
        }

        AlertStatus previousStatus = alert.getStatus();
        String actor = resolveCurrentActor();
        Instant now = clock.instant();

        alert.transitionStatus(request.targetStatus(), actor, request.note(), now);
        Alert saved = alertRepository.saveAndFlush(alert);
        recordAlertAuditIfPresent(saved, previousStatus, actor, now);
        log.info(
                "Updated fraud alert status [alertId={}, txnId={}, from={}, to={}, actor={}]",
                saved.getId(),
                saved.getTransactionId(),
                previousStatus,
                saved.getStatus(),
                actor);
        return AlertResponse.from(saved);
    }

    /**
     * Executes a historical batch scan over completed outgoing transactions in the requested window
     * using SQL window functions for velocity bursts and threshold predicates for high-value
     * transactions, inserting any missing alerts idempotently (L3-03, L3-04, FR-22, G-05).
     *
     * @param request scan boundary request
     * @return summary of newly created alerts
     */
    @Transactional
    public FraudScanResponse scanHistoricalTransactions(FraudScanRequest request) {
        if (fraudScanRepository == null) {
            throw new IllegalStateException("FraudScanRepository is not configured");
        }
        if (request == null
                || request.from() == null
                || request.from().isBlank()
                || request.to() == null
                || request.to().isBlank()) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "Both 'from' and 'to' scan boundaries are required");
        }

        ZoneId businessZone = clock.getZone();
        Instant scannedFrom = parseScanStartInstant(request.from(), businessZone);
        Instant scannedTo = parseScanEndInstant(request.to(), businessZone);

        if (scannedTo.isBefore(scannedFrom)) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "Scan end timestamp 'to' must not be before 'from'");
        }
        long rangeDays = Duration.between(scannedFrom, scannedTo).toDays();
        if (rangeDays > MAX_SCAN_RANGE_DAYS) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "Fraud scan range must not exceed " + MAX_SCAN_RANGE_DAYS + " days");
        }

        Instant now = clock.instant();
        int amountAlerts =
                fraudScanRepository.scanAmountThresholdAnomalies(
                        scannedFrom, scannedTo, fraudProperties.getAmountThreshold(), now);
        int velocityAlerts =
                fraudScanRepository.scanVelocityAnomalies(
                        scannedFrom,
                        scannedTo,
                        fraudProperties.getVelocity().getWindowSeconds(),
                        fraudProperties.getVelocity().getMaxCount(),
                        now);

        if (amountAlerts > 0) {
            incrementAlertsRaisedMetric(AlertRuleCode.AMOUNT_THRESHOLD, amountAlerts);
        }
        if (velocityAlerts > 0) {
            incrementAlertsRaisedMetric(AlertRuleCode.VELOCITY, velocityAlerts);
        }

        int totalCreated = amountAlerts + velocityAlerts;
        log.info(
                "Completed historical fraud scan [from={}, to={}, totalCreated={},"
                        + " amountAlerts={}, velocityAlerts={}]",
                scannedFrom,
                scannedTo,
                totalCreated,
                amountAlerts,
                velocityAlerts);

        return new FraudScanResponse(
                totalCreated, amountAlerts, velocityAlerts, scannedFrom, scannedTo);
    }

    private void incrementAlertsRaisedMetric(AlertRuleCode ruleCode, int count) {
        if (meterRegistry != null && count > 0) {
            meterRegistry.counter(ALERTS_RAISED_METRIC, "rule", ruleCode.name()).increment(count);
        }
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
    private Instant resolveFromFilterBound(
            @Nullable LocalDate fromDate, @Nullable String from, ZoneId zone) {
        if (fromDate != null) {
            return fromDate.atStartOfDay(zone).toInstant();
        }
        if (from == null || from.isBlank()) {
            return null;
        }
        return parseScanStartInstant(from, zone);
    }

    @Nullable
    private Instant resolveToExclusiveFilterBound(
            @Nullable LocalDate toDate, @Nullable String to, ZoneId zone) {
        if (toDate != null) {
            return toDate.plusDays(1).atStartOfDay(zone).toInstant();
        }
        if (to == null || to.isBlank()) {
            return null;
        }
        String trimmed = to.trim();
        try {
            LocalDate parsedDate = LocalDate.parse(trimmed);
            return parsedDate.plusDays(1).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // Fall through to Instant / OffsetDateTime parsing
        }
        return parseInstantOrThrow(trimmed, "to").plus(1, ChronoUnit.MICROS);
    }

    private Instant parseScanStartInstant(String rawFrom, ZoneId zone) {
        String trimmed = rawFrom.trim();
        try {
            LocalDate parsedDate = LocalDate.parse(trimmed);
            return parsedDate.atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            return parseInstantOrThrow(trimmed, "from");
        }
    }

    private Instant parseScanEndInstant(String rawTo, ZoneId zone) {
        String trimmed = rawTo.trim();
        try {
            LocalDate parsedDate = LocalDate.parse(trimmed);
            return parsedDate
                    .plusDays(1)
                    .atStartOfDay(zone)
                    .toInstant()
                    .minus(1, ChronoUnit.MICROS);
        } catch (DateTimeParseException ignored) {
            return parseInstantOrThrow(trimmed, "to");
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

    private String resolveCurrentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            if (authentication.getPrincipal() instanceof Jwt jwt) {
                String username = jwt.getClaimAsString("username");
                if (username != null && !username.isBlank()) {
                    return username;
                }
            }
            if (authentication.getName() != null
                    && !authentication.getName().isBlank()
                    && !"anonymousUser".equals(authentication.getName())) {
                return authentication.getName();
            }
        }
        return DEFAULT_ACTOR;
    }

    @Autowired(required = false)
    @Nullable
    private com.example.corebanking.common.audit.AuditLogService auditLogService;

    private void recordAlertAuditIfPresent(
            Alert saved, AlertStatus previousStatus, String actor, Instant now) {
        if (auditLogService != null) {
            auditLogService.recordAlertStatusChange(
                    saved.getId(),
                    saved.getAccountId(),
                    saved.getTransactionId(),
                    previousStatus.name(),
                    saved.getStatus().name(),
                    saved.getResolutionNote(),
                    actor,
                    now);
        }
    }
}

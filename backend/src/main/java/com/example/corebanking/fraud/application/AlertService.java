package com.example.corebanking.fraud.application;

import com.example.corebanking.fraud.domain.AnomalyFinding;
import com.example.corebanking.fraud.domain.TransactionSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service persisting suspicious transaction alerts idempotently via PostgreSQL {@code
 * INSERT ... ON CONFLICT (transaction_id, rule_code) DO NOTHING} and recording Micrometer alert
 * metrics (L3-03, L3-04, FR-22, ADR-012).
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    public static final String ALERTS_RAISED_METRIC = "banking.alerts.raised";

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
    private final Clock clock;
    @Nullable private final MeterRegistry meterRegistry;

    @Autowired
    public AlertService(JdbcClient jdbcClient, Clock clock, @Nullable MeterRegistry meterRegistry) {
        this.jdbcClient = jdbcClient;
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
            if (meterRegistry != null) {
                meterRegistry
                        .counter(ALERTS_RAISED_METRIC, "rule", finding.ruleCode().name())
                        .increment();
            }
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
}

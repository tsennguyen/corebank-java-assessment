package com.example.corebanking.fraud.application;

import com.example.corebanking.fraud.domain.AnomalyFinding;
import com.example.corebanking.fraud.domain.AnomalyRule;
import com.example.corebanking.fraud.domain.TransactionSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Non-short-circuiting Chain of Responsibility orchestrator that evaluates every registered {@link
 * AnomalyRule} against an outgoing transaction snapshot so a single transaction can raise multiple
 * independent alerts (e.g. both {@code AMOUNT_THRESHOLD} and {@code VELOCITY}) while isolating rule
 * failures (L3-03, L3-04, L3-10, FR-22, ADR-012).
 */
@Component
public class AnomalyRuleChain {

    private static final Logger log = LoggerFactory.getLogger(AnomalyRuleChain.class);

    public static final String EVALUATION_ERRORS_METRIC = "fraud.evaluation.errors";

    private final List<AnomalyRule> rules;
    private final AlertService alertService;
    @Nullable private final MeterRegistry meterRegistry;

    @Autowired
    public AnomalyRuleChain(
            List<AnomalyRule> rules,
            AlertService alertService,
            @Nullable MeterRegistry meterRegistry) {
        this.rules = rules != null ? List.copyOf(rules) : List.of();
        this.alertService = alertService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Evaluates all registered anomaly rules against {@code snapshot} without stopping early and
     * persists any findings via {@link AlertService}.
     *
     * @param snapshot completed transaction snapshot
     * @return list of findings produced across all rules
     */
    public List<AnomalyFinding> evaluate(TransactionSnapshot snapshot) {
        if (snapshot == null || !snapshot.isOutgoing()) {
            return List.of();
        }

        List<AnomalyFinding> findings = new ArrayList<>();
        for (AnomalyRule rule : rules) {
            String ruleTag =
                    rule.ruleCode() != null
                            ? rule.ruleCode().name()
                            : rule.getClass().getSimpleName();
            try {
                Optional<AnomalyFinding> findingOpt = rule.evaluate(snapshot);
                if (findingOpt != null && findingOpt.isPresent()) {
                    AnomalyFinding finding = findingOpt.get();
                    findings.add(finding);
                    alertService.raise(snapshot, finding);
                }
            } catch (Exception ex) {
                if (meterRegistry != null) {
                    meterRegistry.counter(EVALUATION_ERRORS_METRIC, "rule", ruleTag).increment();
                }
                log.error(
                        "Error evaluating anomaly rule [rule={}, txnId={}, accountId={}]: {}",
                        ruleTag,
                        snapshot.transactionId(),
                        snapshot.accountId(),
                        ex.getMessage(),
                        ex);
            }
        }
        return findings;
    }
}

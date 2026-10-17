package com.example.corebanking.fraud.domain;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Anomaly detection rule raising a {@link AlertSeverity#HIGH} alert whenever an outgoing
 * transaction amount meets or exceeds the configured {@code app.fraud.amount-threshold} (default
 * 50,000,000.00 VND) (L3-03, L3-04, FR-22, ADR-012).
 */
@Component
@Order(1)
public class AmountThresholdRule implements AnomalyRule {

    private final FraudProperties fraudProperties;

    public AmountThresholdRule(FraudProperties fraudProperties) {
        this.fraudProperties = fraudProperties;
    }

    @Override
    public AlertRuleCode ruleCode() {
        return AlertRuleCode.AMOUNT_THRESHOLD;
    }

    @Override
    public Optional<AnomalyFinding> evaluate(TransactionSnapshot snapshot) {
        if (snapshot == null || !snapshot.isOutgoing()) {
            return Optional.empty();
        }
        BigDecimal threshold = fraudProperties.getAmountThreshold();
        if (snapshot.amount().compareTo(threshold) >= 0) {
            String reason =
                    String.format(
                            "Outgoing %s amount %s meets or exceeds anomaly threshold %s",
                            snapshot.type(),
                            snapshot.amount().toPlainString(),
                            threshold.toPlainString());
            return Optional.of(
                    new AnomalyFinding(AlertRuleCode.AMOUNT_THRESHOLD, AlertSeverity.HIGH, reason));
        }
        return Optional.empty();
    }
}

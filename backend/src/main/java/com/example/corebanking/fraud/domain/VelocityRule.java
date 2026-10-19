package com.example.corebanking.fraud.domain;

import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Anomaly detection rule raising a {@link AlertSeverity#MEDIUM} alert when an account executes more
 * than {@code app.fraud.velocity.max-count} (default 5) outgoing transactions within the configured
 * sliding window {@code app.fraud.velocity.window-seconds} (default 60 seconds) (L3-03, L3-04,
 * FR-22, ADR-012).
 */
@Component
@Order(2)
public class VelocityRule implements AnomalyRule {

    private final VelocityWindow velocityWindow;
    private final FraudProperties fraudProperties;

    public VelocityRule(VelocityWindow velocityWindow, FraudProperties fraudProperties) {
        this.velocityWindow = velocityWindow;
        this.fraudProperties = fraudProperties;
    }

    @Override
    public AlertRuleCode ruleCode() {
        return AlertRuleCode.VELOCITY;
    }

    @Override
    public Optional<AnomalyFinding> evaluate(TransactionSnapshot snapshot) {
        if (snapshot == null || !snapshot.isOutgoing()) {
            return Optional.empty();
        }
        int maxCount = Math.max(1, fraudProperties.getVelocity().getMaxCount());
        long windowSeconds = Math.max(1L, fraudProperties.getVelocity().getWindowSeconds());
        int currentCount =
                velocityWindow.recordAndCount(snapshot.accountId(), snapshot.transactionId());

        if (currentCount > maxCount) {
            String reason =
                    String.format(
                            "Account %d executed %d outgoing transactions within %d seconds"
                                    + " (threshold > %d)",
                            snapshot.accountId(), currentCount, windowSeconds, maxCount);
            return Optional.of(
                    new AnomalyFinding(AlertRuleCode.VELOCITY, AlertSeverity.MEDIUM, reason));
        }
        return Optional.empty();
    }
}

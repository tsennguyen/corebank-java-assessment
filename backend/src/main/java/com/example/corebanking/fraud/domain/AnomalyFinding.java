package com.example.corebanking.fraud.domain;

import java.util.Objects;

/**
 * Immutable finding produced when an {@link AnomalyRule} detects a suspicious transaction (L3-03,
 * L3-04, FR-22, ADR-012).
 */
public record AnomalyFinding(AlertRuleCode ruleCode, AlertSeverity severity, String reason) {

    public AnomalyFinding {
        Objects.requireNonNull(ruleCode, "ruleCode must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}

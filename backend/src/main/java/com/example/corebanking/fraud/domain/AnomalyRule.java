package com.example.corebanking.fraud.domain;

import java.util.Optional;

/**
 * Strategy / Chain-of-Responsibility contract for evaluating whether a completed outgoing
 * transaction exhibits suspicious behavior (L3-03, FR-22, ADR-012).
 */
public interface AnomalyRule {

    /** Unique rule identifier used for persistence and metrics tagging. */
    AlertRuleCode ruleCode();

    /**
     * Evaluates the given transaction snapshot and returns an {@link AnomalyFinding} if the rule is
     * triggered.
     *
     * @param snapshot completed transaction snapshot
     * @return non-empty finding when suspicious, or {@link Optional#empty()} otherwise
     */
    Optional<AnomalyFinding> evaluate(TransactionSnapshot snapshot);
}

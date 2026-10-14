package com.example.corebanking.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * JUnit 5 execution condition that disables integration test classes when Docker is not available.
 * Enables local development builds without a Docker daemon while allowing full container testing in
 * CI.
 */
public class DisabledIfNoDockerCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("Docker is available");
    private static final ConditionEvaluationResult DISABLED =
            ConditionEvaluationResult.disabled(
                    "Docker is not available - skipping integration test");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        try {
            return DockerClientFactory.instance().isDockerAvailable() ? ENABLED : DISABLED;
        } catch (Throwable t) {
            return DISABLED;
        }
    }
}

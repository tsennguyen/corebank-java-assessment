package com.example.corebanking.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.common.domain.InvalidStatusTransitionException;
import com.example.corebanking.fraud.application.AlertService;
import com.example.corebanking.fraud.application.AnomalyRuleChain;
import com.example.corebanking.fraud.domain.Alert;
import com.example.corebanking.fraud.domain.AlertRuleCode;
import com.example.corebanking.fraud.domain.AlertSeverity;
import com.example.corebanking.fraud.domain.AlertStatus;
import com.example.corebanking.fraud.domain.AmountThresholdRule;
import com.example.corebanking.fraud.domain.AnomalyFinding;
import com.example.corebanking.fraud.domain.AnomalyRule;
import com.example.corebanking.fraud.domain.FraudProperties;
import com.example.corebanking.fraud.domain.TransactionSnapshot;
import com.example.corebanking.fraud.domain.VelocityRule;
import com.example.corebanking.fraud.infrastructure.SlidingWindowVelocityTracker;
import com.example.corebanking.transaction.domain.TransactionType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * Unit tests for {@link AmountThresholdRule}, {@link VelocityRule}, {@link
 * SlidingWindowVelocityTracker} (with controllable fake {@link Clock}, {@code ArrayDeque} fallback,
 * and Redis {@code ZSET}), {@link AnomalyRuleChain}, and {@link Alert} state transitions (Story
 * 4.3: L3-03, L3-04, L3-10, FR-22).
 */
class AnomalyRulesAndChainTest {

    @Test
    @DisplayName(
            "AmountThresholdRule raises HIGH alert at or above 50,000,000 VND for outgoing"
                    + " transactions and ignores DEPOSIT/REVERSAL (AC 1)")
    void amountThresholdRule_whenAmountBelowAndAtAndAboveThreshold_shouldEvaluateCorrectly() {
        FraudProperties properties = new FraudProperties();
        properties.setAmountThreshold(new BigDecimal("50000000.00"));
        AmountThresholdRule rule = new AmountThresholdRule(properties);

        Instant now = Instant.parse("2026-10-17T10:00:00Z");

        // 1. Outgoing below threshold (49,999,999.99) -> empty
        TransactionSnapshot below =
                new TransactionSnapshot(
                        1L,
                        "TXN-1",
                        TransactionType.WITHDRAWAL,
                        10L,
                        new BigDecimal("49999999.99"),
                        now);
        assertThat(rule.evaluate(below)).isEmpty();

        // 2. Outgoing at exact threshold (50,000,000.00) -> HIGH AMOUNT_THRESHOLD alert
        TransactionSnapshot atThreshold =
                new TransactionSnapshot(
                        2L,
                        "TXN-2",
                        TransactionType.TRANSFER,
                        10L,
                        new BigDecimal("50000000.00"),
                        now);
        Optional<AnomalyFinding> atFinding = rule.evaluate(atThreshold);
        assertThat(atFinding).isPresent();
        assertThat(atFinding.get().ruleCode()).isEqualTo(AlertRuleCode.AMOUNT_THRESHOLD);
        assertThat(atFinding.get().severity()).isEqualTo(AlertSeverity.HIGH);

        // 3. Outgoing above threshold (60,000,000.00) -> HIGH AMOUNT_THRESHOLD alert
        TransactionSnapshot above =
                new TransactionSnapshot(
                        3L,
                        "TXN-3",
                        TransactionType.PAYMENT,
                        10L,
                        new BigDecimal("60000000.00"),
                        now);
        Optional<AnomalyFinding> aboveFinding = rule.evaluate(above);
        assertThat(aboveFinding).isPresent();
        assertThat(aboveFinding.get().ruleCode()).isEqualTo(AlertRuleCode.AMOUNT_THRESHOLD);
        assertThat(aboveFinding.get().severity()).isEqualTo(AlertSeverity.HIGH);
        assertThat(aboveFinding.get().reason()).contains("60000000.00").contains("50000000.00");

        // 4. Incoming DEPOSIT of 100,000,000.00 -> ignored
        TransactionSnapshot deposit =
                new TransactionSnapshot(
                        4L,
                        "TXN-4",
                        TransactionType.DEPOSIT,
                        null,
                        new BigDecimal("100000000.00"),
                        now);
        assertThat(rule.evaluate(deposit)).isEmpty();
    }

    @Test
    @DisplayName(
            "VelocityRule with fake Clock and ArrayDeque raises MEDIUM alert on 6th outgoing tx in"
                    + " 60s and evicts expired entries after 60s (AC 2)")
    void velocityRule_withFakeClockAndArrayDeque_shouldRaiseAlertOn6thTxAndEvictAfter60Seconds() {
        FraudProperties properties = new FraudProperties();
        properties.getVelocity().setMaxCount(5);
        properties.getVelocity().setWindow(Duration.ofSeconds(60));

        MutableClock fakeClock = new MutableClock(Instant.parse("2026-10-17T10:00:00Z"));
        SlidingWindowVelocityTracker tracker =
                new SlidingWindowVelocityTracker(properties, fakeClock, null, false);
        VelocityRule rule = new VelocityRule(tracker, properties);

        Long accountId = 101L;

        // Transactions 1..5 at t = 0s, 10s, 20s, 30s, 40s -> all within 5/60s limit
        for (long i = 1; i <= 5; i++) {
            TransactionSnapshot tx =
                    new TransactionSnapshot(
                            i,
                            "TXN-" + i,
                            TransactionType.TRANSFER,
                            accountId,
                            new BigDecimal("100000.00"),
                            fakeClock.instant());
            assertThat(rule.evaluate(tx)).as("Tx #%d should not trigger alert", i).isEmpty();
            fakeClock.advance(Duration.ofSeconds(10));
        }

        // Duplicate evaluation of tx #5 at t = 50s -> idempotent per transactionId, count stays 5
        TransactionSnapshot duplicateTx5 =
                new TransactionSnapshot(
                        5L,
                        "TXN-5",
                        TransactionType.TRANSFER,
                        accountId,
                        new BigDecimal("100000.00"),
                        fakeClock.instant());
        assertThat(rule.evaluate(duplicateTx5)).isEmpty();

        // Transaction 6 at t = 50s (within 60s of tx 1..5) -> triggers VELOCITY (MEDIUM)
        TransactionSnapshot tx6 =
                new TransactionSnapshot(
                        6L,
                        "TXN-6",
                        TransactionType.WITHDRAWAL,
                        accountId,
                        new BigDecimal("100000.00"),
                        fakeClock.instant());
        Optional<AnomalyFinding> finding6 = rule.evaluate(tx6);
        assertThat(finding6).isPresent();
        assertThat(finding6.get().ruleCode()).isEqualTo(AlertRuleCode.VELOCITY);
        assertThat(finding6.get().severity()).isEqualTo(AlertSeverity.MEDIUM);
        assertThat(finding6.get().reason()).contains("6 outgoing transactions within 60 seconds");

        // Advance clock by 25s -> t = 75s (tx 1 at 0s and tx 2 at 10s are now > 60s old and
        // evicted; remaining in [15s, 75s] are tx 3, 4, 5, 6 = 4 transactions + new tx 7 = 5 <= 5)
        fakeClock.advance(Duration.ofSeconds(25));
        TransactionSnapshot tx7 =
                new TransactionSnapshot(
                        7L,
                        "TXN-7",
                        TransactionType.PAYMENT,
                        accountId,
                        new BigDecimal("100000.00"),
                        fakeClock.instant());
        assertThat(rule.evaluate(tx7)).isEmpty();
    }

    @Test
    @DisplayName(
            "SlidingWindowVelocityTracker uses Redis ZSET when enabled and degrades gracefully to"
                    + " ArrayDeque when Redis throws RuntimeException (AC 2)")
    @SuppressWarnings("unchecked")
    void slidingWindowVelocityTracker_whenRedisEnabled_shouldUseZSetAndFallbackOnRedisFailure() {
        FraudProperties properties = new FraudProperties();
        properties.getVelocity().setMaxCount(5);
        properties.getVelocity().setWindowSeconds(60L);

        MutableClock fakeClock = new MutableClock(Instant.parse("2026-10-17T12:00:00Z"));
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard("vel:202")).thenReturn(6L);

        SlidingWindowVelocityTracker tracker =
                new SlidingWindowVelocityTracker(properties, fakeClock, redisTemplate, true);

        // 1. Redis healthy -> returns ZCARD count (6) and sets 60s TTL
        int count = tracker.recordAndCount(202L, 9001L);
        assertThat(count).isEqualTo(6);
        verify(zSetOps)
                .removeRangeByScore(eq("vel:202"), eq(Double.NEGATIVE_INFINITY), anyDouble());
        verify(zSetOps).add(eq("vel:202"), eq("9001"), anyDouble());
        verify(redisTemplate).expire("vel:202", Duration.ofSeconds(60));

        // 2. Redis throws RuntimeException -> falls back to in-memory ArrayDeque without failing
        when(zSetOps.zCard(anyString())).thenThrow(new RuntimeException("Redis connection lost"));
        int fallbackCount = tracker.recordAndCount(202L, 9002L);
        assertThat(fallbackCount).isEqualTo(2); // 9001 + 9002 in ArrayDeque

        tracker.clearInMemoryWindows();
        assertThat(tracker.recordAndCount(202L, 9003L)).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "AnomalyRuleChain executes all rules without short-circuiting so a single transaction"
                    + " can raise 2 Alerts, and isolates failing rules with metric counter (AC 4)")
    void anomalyRuleChain_shouldRunAllRulesAndIsolateRuleFailures() {
        FraudProperties properties = new FraudProperties();
        properties.setAmountThreshold(new BigDecimal("50000000.00"));
        properties.getVelocity().setMaxCount(2);
        properties.getVelocity().setWindowSeconds(60L);

        MutableClock fakeClock = new MutableClock(Instant.parse("2026-10-17T14:00:00Z"));
        SlidingWindowVelocityTracker tracker =
                new SlidingWindowVelocityTracker(properties, fakeClock, null, false);

        AmountThresholdRule amountRule = new AmountThresholdRule(properties);
        VelocityRule velocityRule = new VelocityRule(tracker, properties);
        AnomalyRule brokenRule =
                new AnomalyRule() {
                    @Override
                    public AlertRuleCode ruleCode() {
                        return AlertRuleCode.VELOCITY;
                    }

                    @Override
                    public Optional<AnomalyFinding> evaluate(TransactionSnapshot snapshot) {
                        throw new IllegalStateException("Simulated rule failure");
                    }
                };

        AlertService alertService = mock(AlertService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AnomalyRuleChain chain =
                new AnomalyRuleChain(
                        List.of(brokenRule, amountRule, velocityRule), alertService, meterRegistry);

        // Pre-record 2 transactions so the 3rd triggers VelocityRule (> 2) AND AmountThresholdRule
        tracker.recordAndCount(303L, 1001L);
        tracker.recordAndCount(303L, 1002L);

        TransactionSnapshot tx3 =
                new TransactionSnapshot(
                        1003L,
                        "TXN-1003",
                        TransactionType.TRANSFER,
                        303L,
                        new BigDecimal("60000000.00"),
                        fakeClock.instant());

        List<AnomalyFinding> findings = chain.evaluate(tx3);

        // Both AmountThresholdRule and VelocityRule executed and produced findings despite
        // brokenRule throwing first
        assertThat(findings).hasSize(2);
        assertThat(findings)
                .extracting(AnomalyFinding::ruleCode)
                .containsExactly(AlertRuleCode.AMOUNT_THRESHOLD, AlertRuleCode.VELOCITY);
        verify(alertService, times(2)).raise(eq(tx3), any(AnomalyFinding.class));

        // Metric fraud.evaluation.errors incremented for the failing rule
        assertThat(
                        meterRegistry
                                .counter(
                                        AnomalyRuleChain.EVALUATION_ERRORS_METRIC,
                                        "rule",
                                        "VELOCITY")
                                .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName(
            "Alert and AlertStatus enforce valid state transitions and reject NEW -> RESOLVED or"
                    + " RESOLVED -> NEW with InvalidStatusTransitionException")
    void alertStatus_stateTransitions_shouldEnforceAllowedTransitions() {
        Alert alert =
                Alert.createNew(
                        500L,
                        10L,
                        AlertRuleCode.AMOUNT_THRESHOLD,
                        AlertSeverity.HIGH,
                        "Amount exceeds threshold");
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.NEW);

        // Direct NEW -> RESOLVED is forbidden
        assertThatThrownBy(() -> alert.transitionStatus(AlertStatus.RESOLVED, "admin", "Skip"))
                .isInstanceOf(InvalidStatusTransitionException.class);

        // NEW -> INVESTIGATING -> RESOLVED is valid
        alert.transitionStatus(AlertStatus.INVESTIGATING, "admin", "Checking customer call");
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.INVESTIGATING);
        assertThat(alert.getHandledBy()).isEqualTo("admin");
        assertThat(alert.getResolutionNote()).isEqualTo("Checking customer call");

        alert.transitionStatus(AlertStatus.RESOLVED, "admin", "Confirmed legitimate");
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(alert.getResolutionNote()).isEqualTo("Confirmed legitimate");

        // Terminal RESOLVED -> NEW or FALSE_POSITIVE is forbidden
        assertThatThrownBy(() -> alert.transitionStatus(AlertStatus.NEW, "admin", "Reopen"))
                .isInstanceOf(InvalidStatusTransitionException.class);

        // NEW -> FALSE_POSITIVE is valid directly
        Alert fpAlert =
                Alert.createNew(
                        501L, 10L, AlertRuleCode.VELOCITY, AlertSeverity.MEDIUM, "Velocity");
        fpAlert.transitionStatus(AlertStatus.FALSE_POSITIVE, "admin", "Payroll batch");
        assertThat(fpAlert.getStatus()).isEqualTo(AlertStatus.FALSE_POSITIVE);
    }

    @Test
    @DisplayName(
            "AsyncConfig Virtual Thread executor runs FraudEventListener on a Virtual Thread and"
                    + " caller returns immediately without waiting for slow rule (AC 4, L3-10)")
    void asyncVirtualThreadExecutor_whenRuleBlocks_shouldReturnImmediatelyOnCallerThread()
            throws Exception {
        java.util.concurrent.CountDownLatch ruleStarted =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch allowRuleToFinish =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch ruleFinished =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean ranOnVirtualThread =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        AnomalyRule slowRule =
                new AnomalyRule() {
                    @Override
                    public AlertRuleCode ruleCode() {
                        return AlertRuleCode.AMOUNT_THRESHOLD;
                    }

                    @Override
                    public Optional<AnomalyFinding> evaluate(TransactionSnapshot snapshot) {
                        ranOnVirtualThread.set(Thread.currentThread().isVirtual());
                        ruleStarted.countDown();
                        try {
                            allowRuleToFinish.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        ruleFinished.countDown();
                        return Optional.of(
                                new AnomalyFinding(
                                        AlertRuleCode.AMOUNT_THRESHOLD,
                                        AlertSeverity.HIGH,
                                        "Slow rule completed"));
                    }
                };

        AlertService alertService = mock(AlertService.class);
        AnomalyRuleChain chain =
                new AnomalyRuleChain(List.of(slowRule), alertService, new SimpleMeterRegistry());
        com.example.corebanking.fraud.application.FraudEventListener targetListener =
                new com.example.corebanking.fraud.application.FraudEventListener(chain);

        com.example.corebanking.common.config.AsyncConfig asyncConfig =
                new com.example.corebanking.common.config.AsyncConfig();
        org.springframework.aop.interceptor.AsyncExecutionInterceptor interceptor =
                new org.springframework.aop.interceptor.AsyncExecutionInterceptor(
                        asyncConfig.getAsyncExecutor(),
                        asyncConfig.getAsyncUncaughtExceptionHandler());
        org.springframework.aop.framework.ProxyFactory proxyFactory =
                new org.springframework.aop.framework.ProxyFactory(targetListener);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(interceptor);
        com.example.corebanking.fraud.application.FraudEventListener asyncProxy =
                (com.example.corebanking.fraud.application.FraudEventListener)
                        proxyFactory.getProxy();

        com.example.corebanking.transaction.domain.event.TransactionCompletedEvent event =
                new com.example.corebanking.transaction.domain.event.TransactionCompletedEvent(
                        777L,
                        "TXN-777",
                        TransactionType.WITHDRAWAL,
                        new BigDecimal("60000000.00"),
                        new BigDecimal("12000.00"),
                        10L,
                        null,
                        Instant.parse("2026-10-17T15:00:00Z"));

        // Invoke async listener -> returns immediately while slowRule is still blocked on latch
        asyncProxy.onTransactionCompleted(event);
        assertThat(ruleStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(ruleFinished.getCount())
                .as("Caller must return before slow rule finishes")
                .isEqualTo(1L);

        // Release slow rule and verify it ran on a Java 21 Virtual Thread and raised the alert
        allowRuleToFinish.countDown();
        assertThat(ruleFinished.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(ranOnVirtualThread.get()).isTrue();
        assertThat(targetListener.wasLastExecutedOnVirtualThread()).isTrue();
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            current.updateAndGet(now -> now.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}

package com.example.corebanking.fraud.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.InvalidStatusTransitionException;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.fraud.api.dto.AlertResponse;
import com.example.corebanking.fraud.api.dto.FraudScanRequest;
import com.example.corebanking.fraud.api.dto.FraudScanResponse;
import com.example.corebanking.fraud.api.dto.UpdateAlertStatusRequest;
import com.example.corebanking.fraud.application.AlertService;
import com.example.corebanking.fraud.infrastructure.AlertRepository;
import com.example.corebanking.fraud.infrastructure.FraudScanRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Unit tests for {@link AlertStatus} 4x4 state transition matrix and {@link AlertService}
 * management and historical scan logic (Story 4.4: L3-03, L3-04, FR-22, FR-23).
 */
class AlertStatusTest {

    static Stream<Arguments> transitionMatrix() {
        return Stream.of(
                // From NEW
                Arguments.of(AlertStatus.NEW, AlertStatus.NEW, false),
                Arguments.of(AlertStatus.NEW, AlertStatus.INVESTIGATING, true),
                Arguments.of(AlertStatus.NEW, AlertStatus.RESOLVED, false),
                Arguments.of(AlertStatus.NEW, AlertStatus.FALSE_POSITIVE, true),
                // From INVESTIGATING
                Arguments.of(AlertStatus.INVESTIGATING, AlertStatus.NEW, false),
                Arguments.of(AlertStatus.INVESTIGATING, AlertStatus.INVESTIGATING, false),
                Arguments.of(AlertStatus.INVESTIGATING, AlertStatus.RESOLVED, true),
                Arguments.of(AlertStatus.INVESTIGATING, AlertStatus.FALSE_POSITIVE, true),
                // From RESOLVED (terminal)
                Arguments.of(AlertStatus.RESOLVED, AlertStatus.NEW, false),
                Arguments.of(AlertStatus.RESOLVED, AlertStatus.INVESTIGATING, false),
                Arguments.of(AlertStatus.RESOLVED, AlertStatus.RESOLVED, false),
                Arguments.of(AlertStatus.RESOLVED, AlertStatus.FALSE_POSITIVE, false),
                // From FALSE_POSITIVE (terminal)
                Arguments.of(AlertStatus.FALSE_POSITIVE, AlertStatus.NEW, false),
                Arguments.of(AlertStatus.FALSE_POSITIVE, AlertStatus.INVESTIGATING, false),
                Arguments.of(AlertStatus.FALSE_POSITIVE, AlertStatus.RESOLVED, false),
                Arguments.of(AlertStatus.FALSE_POSITIVE, AlertStatus.FALSE_POSITIVE, false));
    }

    @ParameterizedTest(name = "{0} -> {1} should be {2}")
    @MethodSource("transitionMatrix")
    @DisplayName("AlertStatus.canTransitionTo enforces full 4x4 FSM transition matrix (AC 2)")
    void canTransitionTo_matrixTest(AlertStatus from, AlertStatus to, boolean expectedAllowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expectedAllowed);
        assertThat(from.canTransitionTo(null)).isFalse();
    }

    @Test
    @DisplayName(
            "AlertService.updateStatus transitions NEW -> INVESTIGATING -> RESOLVED and rejects"
                    + " NEW -> RESOLVED with 409 INVALID_STATUS_TRANSITION (AC 2)")
    void updateStatus_whenValidAndInvalidTransitions_shouldUpdateOrThrow409() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        AlertRepository alertRepository = mock(AlertRepository.class);
        FraudScanRepository fraudScanRepository = mock(FraudScanRepository.class);
        FraudProperties fraudProperties = new FraudProperties();
        Instant fixedNow = Instant.parse("2026-10-18T07:00:00Z");
        Clock clock = Clock.fixed(fixedNow, ZoneId.of("Asia/Ho_Chi_Minh"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AlertService alertService =
                new AlertService(
                        jdbcClient,
                        alertRepository,
                        fraudScanRepository,
                        fraudProperties,
                        clock,
                        meterRegistry);

        Alert alert =
                Alert.createNew(
                        101L,
                        10L,
                        AlertRuleCode.VELOCITY,
                        AlertSeverity.MEDIUM,
                        "Account 10 executed 6 outgoing transactions within 60 seconds");

        when(alertRepository.findById(1L)).thenReturn(Optional.of(alert));
        when(alertRepository.saveAndFlush(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. Direct NEW -> RESOLVED must fail with InvalidStatusTransitionException (409)
        assertThatThrownBy(
                        () ->
                                alertService.updateStatus(
                                        1L,
                                        new UpdateAlertStatusRequest(
                                                AlertStatus.RESOLVED, "Skip investigation")))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("Cannot transition alert status from NEW to RESOLVED");

        // 2. NEW -> INVESTIGATING succeeds
        AlertResponse investigating =
                alertService.updateStatus(
                        1L,
                        new UpdateAlertStatusRequest(
                                AlertStatus.INVESTIGATING, "  Contacting customer  "));
        assertThat(investigating.status()).isEqualTo(AlertStatus.INVESTIGATING);
        assertThat(investigating.resolutionNote()).isEqualTo("Contacting customer");
        assertThat(investigating.updatedAt()).isEqualTo(fixedNow);

        // 3. INVESTIGATING -> RESOLVED succeeds
        AlertResponse resolved =
                alertService.updateStatus(
                        1L,
                        new UpdateAlertStatusRequest(
                                AlertStatus.RESOLVED, "Customer confirmed transfer"));
        assertThat(resolved.status()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(resolved.resolutionNote()).isEqualTo("Customer confirmed transfer");

        // 4. RESOLVED -> NEW must fail with InvalidStatusTransitionException (409)
        assertThatThrownBy(
                        () ->
                                alertService.updateStatus(
                                        1L,
                                        new UpdateAlertStatusRequest(
                                                AlertStatus.NEW, "Reopen terminal alert")))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("Cannot transition alert status from RESOLVED to NEW");

        // 5. Stale optimistic locking version -> 409 CONCURRENT_MODIFICATION
        assertThatThrownBy(
                        () ->
                                alertService.updateStatus(
                                        1L,
                                        new UpdateAlertStatusRequest(
                                                AlertStatus.FALSE_POSITIVE, "Stale version", 99L)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));

        // 6. Missing alert -> 404 ResourceNotFoundException
        when(alertRepository.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                alertService.updateStatus(
                                        999L,
                                        new UpdateAlertStatusRequest(
                                                AlertStatus.INVESTIGATING, "Check")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName(
            "AlertService.searchAlerts validates sort whitelist, page size <= 100, and date range"
                    + " bounds (AC 1)")
    @SuppressWarnings("unchecked")
    void searchAlerts_whenValidAndInvalidParams_shouldReturnPageOrThrow400() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        AlertRepository alertRepository = mock(AlertRepository.class);
        FraudScanRepository fraudScanRepository = mock(FraudScanRepository.class);
        Clock clock =
                Clock.fixed(Instant.parse("2026-10-18T07:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

        AlertService alertService =
                new AlertService(
                        jdbcClient,
                        alertRepository,
                        fraudScanRepository,
                        new FraudProperties(),
                        clock,
                        null);

        Alert sample =
                Alert.createNew(
                        202L,
                        20L,
                        AlertRuleCode.AMOUNT_THRESHOLD,
                        AlertSeverity.HIGH,
                        "Amount exceeds threshold");
        when(alertRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sample)));

        PageResponse<AlertResponse> page =
                alertService.searchAlerts(
                        AlertStatus.NEW,
                        AlertRuleCode.AMOUNT_THRESHOLD,
                        AlertSeverity.HIGH,
                        20L,
                        LocalDate.of(2026, 10, 1),
                        LocalDate.of(2026, 10, 18),
                        null,
                        null,
                        PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt"))));
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).ruleCode()).isEqualTo(AlertRuleCode.AMOUNT_THRESHOLD);

        // Invalid sort field -> 400 INVALID_SORT_FIELD
        assertThatThrownBy(
                        () ->
                                alertService.searchAlerts(
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        PageRequest.of(
                                                0, 20, Sort.by(Sort.Order.desc("unknownField")))))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.INVALID_SORT_FIELD));

        // Page size > 100 -> 400 VALIDATION_FAILED
        assertThatThrownBy(
                        () ->
                                alertService.searchAlerts(
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        PageRequest.of(0, 101)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        // fromDate > toDate -> 400 VALIDATION_FAILED
        assertThatThrownBy(
                        () ->
                                alertService.searchAlerts(
                                        null,
                                        null,
                                        null,
                                        null,
                                        LocalDate.of(2026, 10, 18),
                                        LocalDate.of(2026, 10, 1),
                                        null,
                                        null,
                                        PageRequest.of(0, 20)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName(
            "AlertService.scanHistoricalTransactions parses Instant and LocalDate bounds, records"
                    + " metrics, and enforces <= 366 days range (AC 3)")
    void
            scanHistoricalTransactions_whenValidAndInvalidBounds_shouldDelegateToRepositoryAndRecordMetrics() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        AlertRepository alertRepository = mock(AlertRepository.class);
        FraudScanRepository fraudScanRepository = mock(FraudScanRepository.class);
        FraudProperties fraudProperties = new FraudProperties();
        fraudProperties.setAmountThreshold(new BigDecimal("50000000.00"));
        fraudProperties.getVelocity().setMaxCount(5);
        fraudProperties.getVelocity().setWindowSeconds(60L);

        Instant fixedNow = Instant.parse("2026-10-18T08:00:00Z");
        Clock clock = Clock.fixed(fixedNow, ZoneId.of("Asia/Ho_Chi_Minh"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AlertService alertService =
                new AlertService(
                        jdbcClient,
                        alertRepository,
                        fraudScanRepository,
                        fraudProperties,
                        clock,
                        meterRegistry);

        Instant fromInstant = Instant.parse("2026-10-01T00:00:00Z");
        Instant toInstant = Instant.parse("2026-10-15T00:00:00Z");

        when(fraudScanRepository.scanAmountThresholdAnomalies(
                        eq(fromInstant),
                        eq(toInstant),
                        eq(new BigDecimal("50000000.00")),
                        eq(fixedNow)))
                .thenReturn(1);
        when(fraudScanRepository.scanVelocityAnomalies(
                        eq(fromInstant), eq(toInstant), eq(60L), eq(5), eq(fixedNow)))
                .thenReturn(2);

        FraudScanResponse response =
                alertService.scanHistoricalTransactions(
                        new FraudScanRequest("2026-10-01T00:00:00Z", "2026-10-15T00:00:00Z"));

        assertThat(response.alertsCreated()).isEqualTo(3);
        assertThat(response.amountThresholdAlerts()).isEqualTo(1);
        assertThat(response.velocityAlerts()).isEqualTo(2);
        assertThat(response.scannedFrom()).isEqualTo(fromInstant);
        assertThat(response.scannedTo()).isEqualTo(toInstant);

        assertThat(
                        meterRegistry
                                .counter(
                                        AlertService.ALERTS_RAISED_METRIC,
                                        "rule",
                                        "AMOUNT_THRESHOLD")
                                .count())
                .isEqualTo(1.0);
        assertThat(
                        meterRegistry
                                .counter(AlertService.ALERTS_RAISED_METRIC, "rule", "VELOCITY")
                                .count())
                .isEqualTo(2.0);

        verify(fraudScanRepository)
                .scanAmountThresholdAnomalies(
                        fromInstant, toInstant, new BigDecimal("50000000.00"), fixedNow);
        verify(fraudScanRepository).scanVelocityAnomalies(fromInstant, toInstant, 60L, 5, fixedNow);

        // Range > 366 days -> 400 VALIDATION_FAILED
        assertThatThrownBy(
                        () ->
                                alertService.scanHistoricalTransactions(
                                        new FraudScanRequest("2024-01-01", "2026-10-18")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}

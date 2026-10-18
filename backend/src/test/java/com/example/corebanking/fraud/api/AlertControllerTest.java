package com.example.corebanking.fraud.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.auth.infrastructure.SecurityConfig;
import com.example.corebanking.common.api.GlobalExceptionHandler;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.config.ClockConfig;
import com.example.corebanking.common.domain.InvalidStatusTransitionException;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.fraud.api.dto.AlertResponse;
import com.example.corebanking.fraud.api.dto.FraudScanRequest;
import com.example.corebanking.fraud.api.dto.FraudScanResponse;
import com.example.corebanking.fraud.api.dto.UpdateAlertStatusRequest;
import com.example.corebanking.fraud.application.AlertService;
import com.example.corebanking.fraud.domain.AlertRuleCode;
import com.example.corebanking.fraud.domain.AlertSeverity;
import com.example.corebanking.fraud.domain.AlertStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WebMvc slice tests for {@link AlertController} and {@link FraudScanController} (Story 4.4: L3-03,
 * L3-04, FR-22, FR-23).
 */
@WebMvcTest({AlertController.class, FraudScanController.class})
@Import({
    ClockConfig.class,
    GlobalExceptionHandler.class,
    RequestIdFilter.class,
    SecurityConfig.class
})
@WithMockUser(roles = "ADMIN")
class AlertControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private AlertService alertService;

    @Test
    @DisplayName("GET /api/v1/alerts returns 200 PageResponse for ADMIN (AC 1)")
    void searchAlerts_whenAdmin_shouldReturn200PageResponse() throws Exception {
        AlertResponse alertResponse =
                new AlertResponse(
                        1L,
                        101L,
                        10L,
                        AlertRuleCode.VELOCITY,
                        AlertSeverity.MEDIUM,
                        "Account 10 executed 6 outgoing transactions within 60 seconds",
                        AlertStatus.NEW,
                        null,
                        null,
                        Instant.parse("2026-10-18T06:00:00Z"),
                        Instant.parse("2026-10-18T06:00:00Z"),
                        0L);

        when(alertService.searchAlerts(
                        eq(AlertStatus.NEW),
                        eq(AlertRuleCode.VELOCITY),
                        any(),
                        eq(10L),
                        any(),
                        any(),
                        eq("2026-10-01"),
                        eq("2026-10-18"),
                        any()))
                .thenReturn(new PageResponse<>(List.of(alertResponse), 0, 20, 1L, 1));

        mockMvc.perform(
                        get("/api/v1/alerts")
                                .param("status", "NEW")
                                .param("ruleCode", "VELOCITY")
                                .param("accountId", "10")
                                .param("from", "2026-10-01")
                                .param("to", "2026-10-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].ruleCode").value("VELOCITY"))
                .andExpect(jsonPath("$.content[0].status").value("NEW"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName(
            "GET /api/v1/alerts/{id} returns 200 when found and 404 RESOURCE_NOT_FOUND when"
                    + " missing (AC 1)")
    void getAlertById_whenFoundAndNotFound_shouldReturn200Or404() throws Exception {
        AlertResponse alertResponse =
                new AlertResponse(
                        5L,
                        205L,
                        12L,
                        AlertRuleCode.AMOUNT_THRESHOLD,
                        AlertSeverity.HIGH,
                        "Outgoing TRANSFER amount 80000000.00 meets or exceeds anomaly threshold",
                        AlertStatus.INVESTIGATING,
                        "Checking with customer",
                        "admin",
                        Instant.parse("2026-10-18T06:00:00Z"),
                        Instant.parse("2026-10-18T06:10:00Z"),
                        1L);

        when(alertService.getAlertById(5L)).thenReturn(alertResponse);
        when(alertService.getAlertById(999L))
                .thenThrow(new ResourceNotFoundException("Alert", 999L));

        mockMvc.perform(get("/api/v1/alerts/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.ruleCode").value("AMOUNT_THRESHOLD"))
                .andExpect(jsonPath("$.status").value("INVESTIGATING"));

        mockMvc.perform(get("/api/v1/alerts/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName(
            "POST /api/v1/alerts/{id}/status returns 200 on valid transition and 409"
                    + " INVALID_STATUS_TRANSITION on illegal jump (AC 2)")
    void updateStatus_whenValidAndInvalidTransition_shouldReturn200Or409() throws Exception {
        AlertResponse updated =
                new AlertResponse(
                        1L,
                        101L,
                        10L,
                        AlertRuleCode.VELOCITY,
                        AlertSeverity.MEDIUM,
                        "Reason",
                        AlertStatus.INVESTIGATING,
                        "Investigating call log",
                        "admin",
                        Instant.parse("2026-10-18T06:00:00Z"),
                        Instant.parse("2026-10-18T06:05:00Z"),
                        1L);

        when(alertService.updateStatus(eq(1L), any(UpdateAlertStatusRequest.class)))
                .thenReturn(updated);
        when(alertService.updateStatus(eq(2L), any(UpdateAlertStatusRequest.class)))
                .thenThrow(
                        new InvalidStatusTransitionException(
                                "Cannot transition alert status from NEW to RESOLVED"));

        mockMvc.perform(
                        post("/api/v1/alerts/1/status")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
{"targetStatus": "INVESTIGATING", "note": "Investigating call log"}
"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVESTIGATING"))
                .andExpect(jsonPath("$.handledBy").value("admin"))
                .andExpect(jsonPath("$.resolutionNote").value("Investigating call log"));

        mockMvc.perform(
                        post("/api/v1/alerts/2/status")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"targetStatus": "RESOLVED", "note": "Direct jump"}
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName(
            "POST /api/v1/fraud/scans and alias /api/v1/alerts/scan return 200 FraudScanResponse"
                    + " (AC 3)")
    void scanHistoricalTransactions_whenValidRequest_shouldReturn200() throws Exception {
        FraudScanResponse scanResponse =
                new FraudScanResponse(
                        3,
                        1,
                        2,
                        Instant.parse("2026-10-01T00:00:00Z"),
                        Instant.parse("2026-10-15T00:00:00Z"));

        when(alertService.scanHistoricalTransactions(any(FraudScanRequest.class)))
                .thenReturn(scanResponse);

        String body =
                """
                {"from": "2026-10-01T00:00:00Z", "to": "2026-10-15T00:00:00Z"}
                """;

        mockMvc.perform(
                        post("/api/v1/fraud/scans")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsCreated").value(3))
                .andExpect(jsonPath("$.amountThresholdAlerts").value(1))
                .andExpect(jsonPath("$.velocityAlerts").value(2));

        mockMvc.perform(
                        post("/api/v1/alerts/scan")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsCreated").value(3));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("CUSTOMER role receives 403 ACCESS_DENIED on alert and fraud scan endpoints")
    void alertAndFraudScanEndpoints_whenCustomerRole_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(
                        post("/api/v1/fraud/scans")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"from": "2026-10-01", "to": "2026-10-15"}
                                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(alertService);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Unauthenticated request receives 401 UNAUTHORIZED")
    void alertEndpoints_whenAnonymous_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(alertService);
    }
}

package com.example.corebanking.recurring.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.auth.infrastructure.SecurityConfig;
import com.example.corebanking.common.api.GlobalExceptionHandler;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.config.ClockConfig;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.recurring.api.dto.CreateRecurringTransactionRequest;
import com.example.corebanking.recurring.api.dto.RecurringTransactionResponse;
import com.example.corebanking.recurring.application.RecurringTransactionService;
import com.example.corebanking.recurring.domain.RecurringFrequency;
import com.example.corebanking.recurring.domain.RecurringStatus;
import com.example.corebanking.recurring.domain.RecurringTransactionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RecurringTransactionController.class)
@Import({
    ClockConfig.class,
    GlobalExceptionHandler.class,
    RequestIdFilter.class,
    SecurityConfig.class
})
@WithMockUser(roles = "ADMIN")
class RecurringTransactionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private RecurringTransactionService recurringTransactionService;

    private RecurringTransactionResponse sampleResponse(Long id, RecurringStatus status) {
        return new RecurringTransactionResponse(
                id,
                RecurringTransactionType.PAYMENT,
                10L,
                null,
                new BigDecimal("5000000.00"),
                "Hà Nội",
                "Thanh toán hóa đơn điện nước định kỳ",
                RecurringFrequency.MONTHLY,
                LocalDate.of(2026, 10, 15),
                LocalDate.of(2027, 10, 15),
                Instant.parse("2026-10-15T03:00:00Z"),
                null,
                status,
                0,
                null,
                "admin",
                Instant.parse("2026-10-15T03:00:00Z"),
                Instant.parse("2026-10-15T03:00:00Z"),
                0L);
    }

    @Test
    @DisplayName("POST /api/v1/recurring-transactions with valid payload should return 201 Created")
    void createRecurringTransaction_whenValid_shouldReturn201WithLocation() throws Exception {
        CreateRecurringTransactionRequest request =
                new CreateRecurringTransactionRequest(
                        RecurringTransactionType.PAYMENT,
                        10L,
                        null,
                        new BigDecimal("5000000.00"),
                        RecurringFrequency.MONTHLY,
                        LocalDate.of(2026, 10, 15),
                        LocalDate.of(2027, 10, 15),
                        "Hà Nội",
                        "Thanh toán hóa đơn điện nước định kỳ");

        when(recurringTransactionService.createRecurringTransaction(any()))
                .thenReturn(sampleResponse(42L, RecurringStatus.ACTIVE));

        mockMvc.perform(
                        post("/api/v1/recurring-transactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(
                        header().string(
                                        "Location",
                                        containsString("/api/v1/recurring-transactions/42")))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.type").value("PAYMENT"))
                .andExpect(jsonPath("$.frequency").value("MONTHLY"));
    }

    @Test
    @DisplayName(
            "POST /api/v1/recurring-transactions with non-positive amount should return 400"
                    + " VALIDATION_FAILED")
    void createRecurringTransaction_whenAmountNonPositive_shouldReturn400() throws Exception {
        CreateRecurringTransactionRequest badRequest =
                new CreateRecurringTransactionRequest(
                        RecurringTransactionType.PAYMENT,
                        10L,
                        null,
                        BigDecimal.ZERO,
                        RecurringFrequency.MONTHLY,
                        LocalDate.of(2026, 10, 15),
                        null,
                        null,
                        null);

        mockMvc.perform(
                        post("/api/v1/recurring-transactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /api/v1/recurring-transactions/{id} should return 200 or 404 when not found")
    void getRecurringTransactionById_shouldReturn200Or404() throws Exception {
        when(recurringTransactionService.getRecurringTransactionById(42L))
                .thenReturn(sampleResponse(42L, RecurringStatus.ACTIVE));
        when(recurringTransactionService.getRecurringTransactionById(999L))
                .thenThrow(new ResourceNotFoundException("RecurringTransaction", 999L));

        mockMvc.perform(get("/api/v1/recurring-transactions/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42));

        mockMvc.perform(get("/api/v1/recurring-transactions/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/recurring-transactions should return paginated list")
    void searchRecurringTransactions_shouldReturnPagedResponse() throws Exception {
        PageResponse<RecurringTransactionResponse> page =
                new PageResponse<>(
                        List.of(sampleResponse(42L, RecurringStatus.ACTIVE)), 0, 20, 1, 1);

        when(recurringTransactionService.searchRecurringTransactions(
                        eq(RecurringStatus.ACTIVE), eq(10L), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(
                        get("/api/v1/recurring-transactions")
                                .param("status", "ACTIVE")
                                .param("accountId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(42));
    }

    @Test
    @DisplayName(
            "POST /{id}/pause, POST /{id}/resume, and DELETE /{id} should handle lifecycle"
                    + " transitions and 409 conflicts")
    void lifecycleEndpoints_shouldHandlePauseResumeCancelAndConflict() throws Exception {
        when(recurringTransactionService.pauseRecurringTransaction(42L))
                .thenReturn(sampleResponse(42L, RecurringStatus.PAUSED));
        when(recurringTransactionService.resumeRecurringTransaction(42L))
                .thenReturn(sampleResponse(42L, RecurringStatus.ACTIVE));
        doNothing().when(recurringTransactionService).cancelRecurringTransaction(42L);

        mockMvc.perform(post("/api/v1/recurring-transactions/42/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        mockMvc.perform(post("/api/v1/recurring-transactions/42/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(delete("/api/v1/recurring-transactions/42"))
                .andExpect(status().isNoContent());

        // Attempting to resume a CANCELLED instruction -> 409 INVALID_STATUS_TRANSITION
        doThrow(
                        new BusinessRuleException(
                                ErrorCode.INVALID_STATUS_TRANSITION,
                                "Cannot resume recurring transaction in status CANCELLED"))
                .when(recurringTransactionService)
                .resumeRecurringTransaction(42L);

        mockMvc.perform(post("/api/v1/recurring-transactions/42/resume"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName(
            "ROLE_CUSTOMER should be forbidden (403 ACCESS_DENIED) on"
                    + " /api/v1/recurring-transactions")
    @WithMockUser(roles = "CUSTOMER")
    void customerRole_shouldReceive403OnRecurringEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/recurring-transactions"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}

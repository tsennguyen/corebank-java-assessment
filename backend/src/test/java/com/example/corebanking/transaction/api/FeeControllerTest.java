package com.example.corebanking.transaction.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.auth.infrastructure.SecurityConfig;
import com.example.corebanking.common.api.GlobalExceptionHandler;
import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.config.ClockConfig;
import com.example.corebanking.common.domain.Money;
import com.example.corebanking.transaction.domain.FeePolicy;
import com.example.corebanking.transaction.domain.TransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FeeController.class)
@Import({
    ClockConfig.class,
    GlobalExceptionHandler.class,
    RequestIdFilter.class,
    SecurityConfig.class
})
@WithMockUser(roles = "ADMIN")
class FeeControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private FeePolicy feePolicy;

    @Test
    @DisplayName("GET /api/v1/fees/preview returns 200 with calculated fee for valid withdrawal")
    void previewFee_withdrawal_whenValid_shouldReturn200() throws Exception {
        when(feePolicy.calculateFee(eq(TransactionType.WITHDRAWAL), eq(Money.of("12000000.00"))))
                .thenReturn(Money.of("2400.00"));

        mockMvc.perform(
                        get("/api/v1/fees/preview")
                                .param("type", "WITHDRAWAL")
                                .param("amount", "12000000.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.amount").value(12000000.00))
                .andExpect(jsonPath("$.fee").value(2400.00));
    }

    @Test
    @DisplayName("GET /api/v1/fees/preview returns 200 with calculated fee for valid transfer")
    void previewFee_transfer_whenValid_shouldReturn200() throws Exception {
        when(feePolicy.calculateFee(eq(TransactionType.TRANSFER), eq(Money.of("500000.00"))))
                .thenReturn(Money.of("2000.00"));

        mockMvc.perform(
                        get("/api/v1/fees/preview")
                                .param("type", "TRANSFER")
                                .param("amount", "500000.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.amount").value(500000.00))
                .andExpect(jsonPath("$.fee").value(2000.00));
    }

    @Test
    @DisplayName("GET /api/v1/fees/preview returns 200 with 0.00 fee for deposit")
    void previewFee_deposit_whenValid_shouldReturn200() throws Exception {
        when(feePolicy.calculateFee(eq(TransactionType.DEPOSIT), eq(Money.of("1000000.00"))))
                .thenReturn(Money.of("0.00"));

        mockMvc.perform(
                        get("/api/v1/fees/preview")
                                .param("type", "DEPOSIT")
                                .param("amount", "1000000.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.amount").value(1000000.00))
                .andExpect(jsonPath("$.fee").value(0.00));
    }

    @Test
    @DisplayName("GET /api/v1/fees/preview returns 400 when amount is negative or zero")
    void previewFee_whenAmountNegativeOrZero_shouldReturn400() throws Exception {
        mockMvc.perform(
                        get("/api/v1/fees/preview")
                                .param("type", "WITHDRAWAL")
                                .param("amount", "-1000.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.type").value("urn:problem-type:validation-failed"))
                .andExpect(jsonPath("$.errors").isArray());

        mockMvc.perform(
                        get("/api/v1/fees/preview")
                                .param("type", "WITHDRAWAL")
                                .param("amount", "0.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /api/v1/fees/preview returns 400 when amount has scale > 2")
    void previewFee_whenAmountHasExcessFractionalDigits_shouldReturn400() throws Exception {
        mockMvc.perform(
                        get("/api/v1/fees/preview")
                                .param("type", "WITHDRAWAL")
                                .param("amount", "10000.123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /api/v1/fees/preview returns 400 when type is invalid enum")
    void previewFee_whenTypeIsInvalid_shouldReturn400() throws Exception {
        mockMvc.perform(
                        get("/api/v1/fees/preview")
                                .param("type", "UNKNOWN_TYPE")
                                .param("amount", "500000.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /api/v1/fees/preview returns 400 when required parameters are missing")
    void previewFee_whenParametersMissing_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/fees/preview").param("type", "TRANSFER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/fees/preview").param("amount", "500000.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}

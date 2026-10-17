package com.example.corebanking.customer.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.auth.infrastructure.SecurityConfig;
import com.example.corebanking.common.api.GlobalExceptionHandler;
import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.config.ClockConfig;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.customer.api.dto.CustomerTypeResponse;
import com.example.corebanking.customer.api.dto.UpdateCustomerTypeRequest;
import com.example.corebanking.customer.application.CustomerTypeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerTypeController.class)
@Import({
    ClockConfig.class,
    GlobalExceptionHandler.class,
    RequestIdFilter.class,
    SecurityConfig.class
})
@WithMockUser(roles = "ADMIN")
@DisplayName("CustomerTypeController WebMvc Tests")
class CustomerTypeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private CustomerTypeService customerTypeService;

    @Test
    @DisplayName("GET /api/v1/customer-types returns 200 with INDIVIDUAL and BUSINESS types")
    void getAllCustomerTypes_shouldReturn200() throws Exception {
        List<CustomerTypeResponse> responses =
                List.of(
                        new CustomerTypeResponse(
                                1L,
                                "INDIVIDUAL",
                                "Cá nhân",
                                new BigDecimal("100000000.00"),
                                new BigDecimal("500000000.00"),
                                Instant.parse("2026-10-09T01:00:00Z"),
                                0L),
                        new CustomerTypeResponse(
                                2L,
                                "BUSINESS",
                                "Doanh nghiệp",
                                new BigDecimal("2000000000.00"),
                                new BigDecimal("10000000000.00"),
                                Instant.parse("2026-10-09T01:00:00Z"),
                                0L));

        when(customerTypeService.getAllCustomerTypes()).thenReturn(responses);

        mockMvc.perform(get("/api/v1/customer-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].code").value("INDIVIDUAL"))
                .andExpect(jsonPath("$[0].maxTransactionLimit").value(100000000.00))
                .andExpect(jsonPath("$[1].code").value("BUSINESS"))
                .andExpect(jsonPath("$[1].maxTransactionLimit").value(2000000000.00));
    }

    @Test
    @DisplayName("PUT /api/v1/customer-types/{code} updates limits and returns 200")
    void updateCustomerType_shouldReturn200() throws Exception {
        UpdateCustomerTypeRequest request =
                new UpdateCustomerTypeRequest(
                        new BigDecimal("150000000.00"), new BigDecimal("600000000.00"));
        CustomerTypeResponse updated =
                new CustomerTypeResponse(
                        1L,
                        "INDIVIDUAL",
                        "Cá nhân",
                        new BigDecimal("150000000.00"),
                        new BigDecimal("600000000.00"),
                        Instant.parse("2026-10-09T02:00:00Z"),
                        1L);

        when(customerTypeService.updateCustomerType(
                        eq("INDIVIDUAL"), any(UpdateCustomerTypeRequest.class)))
                .thenReturn(updated);

        mockMvc.perform(
                        put("/api/v1/customer-types/INDIVIDUAL")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("INDIVIDUAL"))
                .andExpect(jsonPath("$.maxTransactionLimit").value(150000000.00))
                .andExpect(jsonPath("$.maxDailyLimit").value(600000000.00))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @DisplayName(
            "PUT /api/v1/customer-types/{code} returns 400 when maxDailyLimit <"
                    + " maxTransactionLimit")
    void updateCustomerType_whenDailyLessThanTxLimit_shouldReturn400() throws Exception {
        UpdateCustomerTypeRequest request =
                new UpdateCustomerTypeRequest(
                        new BigDecimal("200000000.00"), new BigDecimal("100000000.00"));

        when(customerTypeService.updateCustomerType(
                        eq("INDIVIDUAL"), any(UpdateCustomerTypeRequest.class)))
                .thenThrow(
                        new BusinessRuleException(
                                ErrorCode.VALIDATION_FAILED,
                                "Max daily limit must be greater than or equal to max transaction"
                                        + " limit"));

        mockMvc.perform(
                        put("/api/v1/customer-types/INDIVIDUAL")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("PUT /api/v1/customer-types/{code} returns 404 when code does not exist")
    void updateCustomerType_whenUnknownCode_shouldReturn404() throws Exception {
        UpdateCustomerTypeRequest request =
                new UpdateCustomerTypeRequest(
                        new BigDecimal("100000000.00"), new BigDecimal("500000000.00"));

        when(customerTypeService.updateCustomerType(
                        eq("UNKNOWN"), any(UpdateCustomerTypeRequest.class)))
                .thenThrow(new ResourceNotFoundException("CustomerType", "UNKNOWN"));

        mockMvc.perform(
                        put("/api/v1/customer-types/UNKNOWN")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("GET /api/v1/customer-types returns 403 ACCESS_DENIED for ROLE_CUSTOMER")
    void getAllCustomerTypes_whenCustomerRole_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/customer-types"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}

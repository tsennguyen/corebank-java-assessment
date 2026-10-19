package com.example.corebanking.report.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.account.domain.BalanceTier;
import com.example.corebanking.auth.infrastructure.SecurityConfig;
import com.example.corebanking.common.api.GlobalExceptionHandler;
import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.config.ClockConfig;
import com.example.corebanking.report.api.dto.BalanceTierStatisticResponse;
import com.example.corebanking.report.api.dto.LocationStatisticResponse;
import com.example.corebanking.report.application.StatisticsService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StatisticsController.class)
@Import({
    ClockConfig.class,
    GlobalExceptionHandler.class,
    RequestIdFilter.class,
    SecurityConfig.class
})
@WithMockUser(roles = "ADMIN")
class StatisticsControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private StatisticsService statisticsService;

    @Test
    @DisplayName(
            "GET /api/v1/statistics/balance-tiers returns 200 OK with LOW, MEDIUM, HIGH statistics")
    void getBalanceTierStatistics_returns200AndJsonArray() throws Exception {
        when(statisticsService.getBalanceTierStatistics())
                .thenReturn(
                        List.of(
                                new BalanceTierStatisticResponse(BalanceTier.LOW, 4L, 6L),
                                new BalanceTierStatisticResponse(BalanceTier.MEDIUM, 4L, 5L),
                                new BalanceTierStatisticResponse(BalanceTier.HIGH, 4L, 7L)));

        mockMvc.perform(get("/api/v1/statistics/balance-tiers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].tier").value("LOW"))
                .andExpect(jsonPath("$[0].accountCount").value(4))
                .andExpect(jsonPath("$[0].transactionCount").value(6))
                .andExpect(jsonPath("$[1].tier").value("MEDIUM"))
                .andExpect(jsonPath("$[1].accountCount").value(4))
                .andExpect(jsonPath("$[1].transactionCount").value(5))
                .andExpect(jsonPath("$[2].tier").value("HIGH"))
                .andExpect(jsonPath("$[2].accountCount").value(4))
                .andExpect(jsonPath("$[2].transactionCount").value(7));
    }

    @Test
    @DisplayName(
            "GET /api/v1/statistics/customers-by-location returns 200 OK with descending customer"
                    + " counts")
    void getCustomersByLocation_returns200AndJsonArray() throws Exception {
        when(statisticsService.getCustomersByLocation())
                .thenReturn(
                        List.of(
                                new LocationStatisticResponse("Hà Nội", 7L),
                                new LocationStatisticResponse("Hồ Chí Minh", 6L),
                                new LocationStatisticResponse("Đà Nẵng", 3L)));

        mockMvc.perform(get("/api/v1/statistics/customers-by-location"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].location").value("Hà Nội"))
                .andExpect(jsonPath("$[0].customerCount").value(7))
                .andExpect(jsonPath("$[1].location").value("Hồ Chí Minh"))
                .andExpect(jsonPath("$[1].customerCount").value(6))
                .andExpect(jsonPath("$[2].location").value("Đà Nẵng"))
                .andExpect(jsonPath("$[2].customerCount").value(3));
    }
}

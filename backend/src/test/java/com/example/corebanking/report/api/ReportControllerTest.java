package com.example.corebanking.report.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.auth.infrastructure.SecurityConfig;
import com.example.corebanking.common.api.GlobalExceptionHandler;
import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.config.ClockConfig;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.report.api.dto.ReportExportCriteria;
import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.api.dto.TrendPointResponse;
import com.example.corebanking.report.application.ReportExportService;
import com.example.corebanking.report.application.ReportService;
import com.example.corebanking.report.domain.ExportFormat;
import com.example.corebanking.report.domain.ReportPeriod;
import com.example.corebanking.report.domain.TrendGranularity;
import com.example.corebanking.transaction.domain.TransactionType;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(ReportController.class)
@Import({
    ClockConfig.class,
    GlobalExceptionHandler.class,
    RequestIdFilter.class,
    SecurityConfig.class
})
@WithMockUser(roles = "ADMIN")
class ReportControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ReportService reportService;

    @MockBean private ReportExportService reportExportService;

    @Test
    @DisplayName(
            "GET /api/v1/reports/transactions returns 200 with periodic buckets for ADMIN"
                    + " (L3-01, L3-02)")
    void getPeriodicTransactionReport_whenValidParams_shouldReturn200WithBuckets()
            throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);

        List<TransactionReportBucketResponse> buckets =
                List.of(
                        new TransactionReportBucketResponse(
                                LocalDate.of(2026, 1, 1),
                                2L,
                                new BigDecimal("3000000.00"),
                                new BigDecimal("1500000.00"),
                                new BigDecimal("2000000.00"),
                                new BigDecimal("1000000.00"),
                                new BigDecimal("4000.00")),
                        new TransactionReportBucketResponse(
                                LocalDate.of(2026, 4, 1),
                                1L,
                                new BigDecimal("5000000.00"),
                                new BigDecimal("5000000.00"),
                                new BigDecimal("5000000.00"),
                                new BigDecimal("5000000.00"),
                                new BigDecimal("6000.00")));

        when(reportService.getPeriodicTransactionReport(
                        ReportPeriod.QUARTER, from, to, TransactionType.TRANSFER))
                .thenReturn(buckets);

        mockMvc.perform(
                        get("/api/v1/reports/transactions")
                                .param("period", "QUARTER")
                                .param("from", "2026-01-01")
                                .param("to", "2026-06-30")
                                .param("type", "TRANSFER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].periodStart").value("2026-01-01"))
                .andExpect(jsonPath("$[0].txCount").value(2))
                .andExpect(jsonPath("$[0].totalAmount").value(3000000.00))
                .andExpect(jsonPath("$[0].averageAmount").value(1500000.00))
                .andExpect(jsonPath("$[0].maxAmount").value(2000000.00))
                .andExpect(jsonPath("$[0].minAmount").value(1000000.00))
                .andExpect(jsonPath("$[0].totalFee").value(4000.00))
                .andExpect(jsonPath("$[1].periodStart").value("2026-04-01"))
                .andExpect(jsonPath("$[1].txCount").value(1));
    }

    @Test
    @DisplayName(
            "GET /api/v1/reports/transactions returns 400 VALIDATION_FAILED when period is outside"
                    + " enum")
    void getPeriodicTransactionReport_whenInvalidPeriodEnum_shouldReturn400() throws Exception {
        mockMvc.perform(
                        get("/api/v1/reports/transactions")
                                .param("period", "DECADE")
                                .param("from", "2026-01-01")
                                .param("to", "2026-06-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("period"));

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName(
            "GET /api/v1/reports/transactions returns 400 VALIDATION_FAILED when required query"
                    + " params are missing")
    void getPeriodicTransactionReport_whenRequiredParamsMissing_shouldReturn400() throws Exception {
        mockMvc.perform(
                        get("/api/v1/reports/transactions")
                                .param("from", "2026-01-01")
                                .param("to", "2026-06-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("period"));

        mockMvc.perform(
                        get("/api/v1/reports/transactions")
                                .param("period", "WEEK")
                                .param("to", "2026-06-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("from"));

        mockMvc.perform(
                        get("/api/v1/reports/transactions")
                                .param("period", "WEEK")
                                .param("from", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("to"));

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName(
            "GET /api/v1/reports/transactions returns 400 VALIDATION_FAILED when service rejects"
                    + " date range")
    void getPeriodicTransactionReport_whenServiceThrowsValidationFailed_shouldReturn400()
            throws Exception {
        LocalDate from = LocalDate.of(2026, 6, 30);
        LocalDate to = LocalDate.of(2026, 1, 1);

        when(reportService.getPeriodicTransactionReport(ReportPeriod.QUARTER, from, to, null))
                .thenThrow(
                        new BusinessRuleException(
                                ErrorCode.VALIDATION_FAILED,
                                "End date 'to' must not be before start date 'from'"));

        mockMvc.perform(
                        get("/api/v1/reports/transactions")
                                .param("period", "QUARTER")
                                .param("from", "2026-06-30")
                                .param("to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName(
            "GET /api/v1/reports/transactions returns 403 ACCESS_DENIED for CUSTOMER role (L2-01)")
    void getPeriodicTransactionReport_whenCustomerRole_shouldReturn403() throws Exception {
        mockMvc.perform(
                        get("/api/v1/reports/transactions")
                                .param("period", "QUARTER")
                                .param("from", "2026-01-01")
                                .param("to", "2026-06-30"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(reportService);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/v1/reports/transactions returns 401 UNAUTHORIZED when unauthenticated")
    void getPeriodicTransactionReport_whenUnauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(
                        get("/api/v1/reports/transactions")
                                .param("period", "QUARTER")
                                .param("from", "2026-01-01")
                                .param("to", "2026-06-30"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName(
            "GET /api/v1/reports/trends returns 200 with system-wide or account trend points for"
                    + " ADMIN (L3-08, FR-20)")
    void getTrendSeries_whenValidParams_shouldReturn200WithTrendPoints() throws Exception {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 2);

        List<TrendPointResponse> points =
                List.of(
                        new TrendPointResponse(
                                LocalDate.of(2026, 10, 1),
                                3L,
                                new BigDecimal("15000000.00"),
                                new BigDecimal("6000.00"),
                                new BigDecimal("120000000.00")),
                        new TrendPointResponse(
                                LocalDate.of(2026, 10, 2),
                                0L,
                                new BigDecimal("0.00"),
                                new BigDecimal("0.00"),
                                new BigDecimal("120000000.00")));

        when(reportService.getTrendSeries(TrendGranularity.DAY, from, to, 15L)).thenReturn(points);

        mockMvc.perform(
                        get("/api/v1/reports/trends")
                                .param("granularity", "DAY")
                                .param("from", "2026-10-01")
                                .param("to", "2026-10-02")
                                .param("accountId", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].bucket").value("2026-10-01"))
                .andExpect(jsonPath("$[0].txCount").value(3))
                .andExpect(jsonPath("$[0].totalAmount").value(15000000.00))
                .andExpect(jsonPath("$[0].totalFee").value(6000.00))
                .andExpect(jsonPath("$[0].closingBalance").value(120000000.00))
                .andExpect(jsonPath("$[1].bucket").value("2026-10-02"))
                .andExpect(jsonPath("$[1].txCount").value(0))
                .andExpect(jsonPath("$[1].closingBalance").value(120000000.00));
    }

    @Test
    @DisplayName(
            "GET /api/v1/reports/trends returns 404 RESOURCE_NOT_FOUND when accountId does not"
                    + " exist")
    void getTrendSeries_whenAccountNotFound_shouldReturn404() throws Exception {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 5);

        when(reportService.getTrendSeries(TrendGranularity.DAY, from, to, 999L))
                .thenThrow(new ResourceNotFoundException("Account", 999L));

        mockMvc.perform(
                        get("/api/v1/reports/trends")
                                .param("granularity", "DAY")
                                .param("from", "2026-10-01")
                                .param("to", "2026-10-05")
                                .param("accountId", "999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName(
            "GET /api/v1/reports/trends returns 400 VALIDATION_FAILED when granularity is invalid"
                    + " or missing")
    void getTrendSeries_whenInvalidGranularity_shouldReturn400() throws Exception {
        mockMvc.perform(
                        get("/api/v1/reports/trends")
                                .param("granularity", "HOUR")
                                .param("from", "2026-10-01")
                                .param("to", "2026-10-05"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("granularity"));

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName(
            "GET /api/v1/reports/export returns 200 with XLSX attachment headers and streamed body"
                    + " for ADMIN (AC 1, L3-07, FR-21)")
    void exportReport_whenValidXlsxParams_shouldReturn200WithAttachmentHeadersAndStreamedBody()
            throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        ReportExportCriteria criteria =
                new ReportExportCriteria(
                        ExportFormat.XLSX,
                        ReportPeriod.QUARTER,
                        from,
                        to,
                        TransactionType.TRANSFER);

        when(reportExportService.validateAndBuildCriteria(
                        ExportFormat.XLSX,
                        ReportPeriod.QUARTER,
                        from,
                        to,
                        TransactionType.TRANSFER))
                .thenReturn(criteria);

        byte[] dummyXlsx = "PK-dummy-xlsx".getBytes(StandardCharsets.UTF_8);
        doAnswer(
                        invocation -> {
                            OutputStream out = invocation.getArgument(1);
                            out.write(dummyXlsx);
                            return null;
                        })
                .when(reportExportService)
                .export(eq(criteria), any(OutputStream.class));

        MvcResult mvcResult =
                mockMvc.perform(
                                get("/api/v1/reports/export")
                                        .param("format", "XLSX")
                                        .param("period", "QUARTER")
                                        .param("from", "2026-01-01")
                                        .param("to", "2026-06-30")
                                        .param("type", "TRANSFER"))
                        .andExpect(status().isOk())
                        .andExpect(
                                header().string(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment;"
                                                    + " filename=\"core-banking-report-quarter-2026-01-01-2026-06-30.xlsx\""))
                        .andExpect(
                                header().string(
                                                HttpHeaders.CONTENT_TYPE,
                                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .andReturn();

        byte[] body =
                mvcResult.getRequest().isAsyncStarted()
                        ? mockMvc.perform(asyncDispatch(mvcResult))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray()
                        : mvcResult.getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(dummyXlsx);
    }

    @Test
    @DisplayName(
            "GET /api/v1/reports/export returns 400 VALIDATION_FAILED before streaming when format"
                    + " is invalid or range exceeds 366 days (AC 3, NC-08)")
    void exportReport_whenInvalidParams_shouldReturn400BeforeStreaming() throws Exception {
        mockMvc.perform(
                        get("/api/v1/reports/export")
                                .param("format", "CSV")
                                .param("period", "QUARTER")
                                .param("from", "2026-01-01")
                                .param("to", "2026-06-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("format"));

        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        when(reportExportService.validateAndBuildCriteria(
                        ExportFormat.XLSX, ReportPeriod.QUARTER, from, to, null))
                .thenThrow(
                        new BusinessRuleException(
                                ErrorCode.VALIDATION_FAILED,
                                "Report date range cannot exceed 366 days"));

        mockMvc.perform(
                        get("/api/v1/reports/export")
                                .param("format", "XLSX")
                                .param("period", "QUARTER")
                                .param("from", "2024-01-01")
                                .param("to", "2026-06-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}

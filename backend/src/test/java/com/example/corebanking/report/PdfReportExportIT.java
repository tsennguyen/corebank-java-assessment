package com.example.corebanking.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.support.IntegrationTestBase;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * PostgreSQL 17 Testcontainers integration test for Story 4.7 (PDF Report Export: L3-07, L3-08,
 * FR-21, NFR-2, ADR-014).
 */
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class PdfReportExportIT extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName(
            "GET /api/v1/reports/export?format=PDF streams valid PDF report with Vietnamese"
                    + " diacritics, summary buckets, streamed details, 3 embedded 800x400 PNG"
                    + " charts, 0 JPA queries, and Index Scan (AC 1, AC 2, L3-07, L3-08, Rule 6)")
    void exportPdf_viaHttpEndpoint_shouldReturnValidPdfWithVietnameseTextAndCharts()
            throws Exception {
        Customer customer =
                customerRepository.save(
                        new Customer(
                                "CUS9990471",
                                "Nguyễn Xuất Báo Cáo PDF",
                                "000999888471",
                                "export47@example.com",
                                "0900000471",
                                "Số 47 Quận 1",
                                "TP. Hồ Chí Minh",
                                LocalDate.of(1991, 7, 20)));
        Account accountA =
                accountRepository.save(
                        new Account(
                                "100999999471",
                                customer.getId(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00"),
                                LocalDate.of(2025, 1, 1)));
        Account accountB =
                accountRepository.save(
                        new Account(
                                "100999999472",
                                customer.getId(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00"),
                                LocalDate.of(2025, 1, 1)));

        try {
            jdbcClient
                    .sql("UPDATE core.account SET balance = 24998000.00 WHERE id = :id")
                    .param("id", accountA.getId())
                    .update();
            jdbcClient
                    .sql("UPDATE core.account SET balance = 5000000.00 WHERE id = :id")
                    .param("id", accountB.getId())
                    .update();

            jdbcClient
                    .sql(
                            """
INSERT INTO core.transaction (
    reference_no, type, source_account_id, target_account_id,
    amount, fee, source_balance_after, target_balance_after,
    system_balance_delta, location, description, status, created_at
) VALUES
('TXN-PDF-IT-1', 'DEPOSIT',  NULL,  :accA, 30000000.00, 0.00,    NULL,        30000000.00, 30000000.00, 'TP. Hồ Chí Minh', 'Nộp tiền mặt tại quầy',              'COMPLETED', '2028-02-10T09:00:00+07:00'),
('TXN-PDF-IT-2', 'TRANSFER', :accA, :accB,  5000000.00, 2000.00, 24998000.00,  5000000.00,    -2000.00, 'Hà Nội',          'Chuyển khoản thanh toán tiền điện',  'COMPLETED', '2028-05-20T14:30:00+07:00'),
('TXN-PDF-IT-3', 'REVERSAL', :accB, :accA,  5000000.00, 0.00,           0.00, 29998000.00,        0.00, 'Hà Nội',          'Giao dịch hoàn trả bị loại trừ',     'COMPLETED', '2028-05-21T10:00:00+07:00')
""")
                    .param("accA", accountA.getId())
                    .param("accB", accountB.getId())
                    .update();

            SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
            Statistics stats = sessionFactory.getStatistics();
            stats.setStatisticsEnabled(true);
            stats.clear();

            MvcResult initialResult =
                    mockMvc.perform(
                                    get("/api/v1/reports/export")
                                            .param("format", "PDF")
                                            .param("period", "QUARTER")
                                            .param("from", "2028-01-01")
                                            .param("to", "2028-06-30"))
                            .andExpect(status().isOk())
                            .andExpect(
                                    header().string(
                                                    HttpHeaders.CONTENT_DISPOSITION,
                                                    "attachment;"
                                                        + " filename=\"core-banking-report-quarter-2028-01-01-2028-06-30.pdf\""))
                            .andExpect(
                                    header().string(
                                                    HttpHeaders.CONTENT_TYPE,
                                                    MediaType.APPLICATION_PDF_VALUE))
                            .andReturn();

            byte[] pdfBytes =
                    initialResult.getRequest().isAsyncStarted()
                            ? mockMvc.perform(asyncDispatch(initialResult))
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsByteArray()
                            : initialResult.getResponse().getContentAsByteArray();

            assertThat(pdfBytes).isNotEmpty();
            assertThat(new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
            assertThat(stats.getPrepareStatementCount())
                    .as("PDF export executes purely via JdbcClient/JdbcTemplate with 0 JPA queries")
                    .isZero();

            PdfReader reader = new PdfReader(pdfBytes);
            try {
                assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(1);

                PdfTextExtractor extractor = new PdfTextExtractor(reader);
                StringBuilder allText = new StringBuilder();
                for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                    allText.append(extractor.getTextFromPage(page)).append('\n');
                }
                String extracted = allText.toString().replaceAll("\\s+", " ");

                assertThat(extracted)
                        .contains("Core Banking System — Báo cáo tài chính")
                        .contains("2028-01-01")
                        .contains("2028-04-01")
                        .contains("30,000,000.00")
                        .contains("5,000,000.00")
                        .contains("TXN-PDF-IT-1")
                        .contains("Nộp tiền mặt tại quầy")
                        .contains("TXN-PDF-IT-2")
                        .contains("Chuyển khoản thanh toán tiền điện")
                        .doesNotContain("TXN-PDF-IT-3");

                int imageCount = 0;
                for (int i = 1; i < reader.getXrefSize(); i++) {
                    PdfObject obj = reader.getPdfObject(i);
                    if (obj != null && obj.isStream()) {
                        PdfDictionary dict = (PdfDictionary) obj;
                        if (PdfName.IMAGE.equals(dict.getAsName(PdfName.SUBTYPE))) {
                            assertThat(dict.getAsNumber(PdfName.WIDTH).intValue()).isEqualTo(800);
                            assertThat(dict.getAsNumber(PdfName.HEIGHT).intValue()).isEqualTo(400);
                            imageCount++;
                        }
                    }
                }
                assertThat(imageCount).isEqualTo(3);
            } finally {
                reader.close();
            }

            // Verify EXPLAIN (ANALYZE, BUFFERS) on PDF capped query uses idx_tx_status_created
            jdbcClient.sql("SET enable_seqscan = off").update();
            try {
                List<String> explainLines =
                        jdbcClient
                                .sql(
                                        """
EXPLAIN (ANALYZE, BUFFERS)
SELECT t.reference_no, t.type, t.status, t.source_account_id, t.target_account_id,
       t.amount, t.fee, t.location, t.description, t.created_at
FROM core.transaction t
WHERE t.status = 'COMPLETED'
  AND t.type <> 'REVERSAL'
  AND t.created_at >= '2028-01-01T00:00:00+07:00'
  AND t.created_at < '2028-07-01T00:00:00+07:00'
ORDER BY t.created_at DESC, t.id DESC
LIMIT 1000
""")
                                .query(String.class)
                                .list();
                String explainPlan = String.join("\n", explainLines);
                assertThat(explainPlan)
                        .contains("Index Scan")
                        .containsAnyOf("idx_tx_status_created", "idx_tx_created_id_keyset");
            } finally {
                jdbcClient.sql("RESET enable_seqscan").update();
            }
        } finally {
            jdbcClient
                    .sql(
                            "DELETE FROM core.transaction WHERE reference_no IN ('TXN-PDF-IT-1',"
                                    + " 'TXN-PDF-IT-2', 'TXN-PDF-IT-3')")
                    .update();
            jdbcClient
                    .sql("DELETE FROM core.account WHERE id IN (:idA, :idB)")
                    .param("idA", accountA.getId())
                    .param("idB", accountB.getId())
                    .update();
            jdbcClient
                    .sql("DELETE FROM core.customer WHERE id = :id")
                    .param("id", customer.getId())
                    .update();
        }
    }

    @Test
    @DisplayName(
            "GET /api/v1/reports/export?format=PDF returns 400 ProblemDetail before committing"
                    + " streaming headers when date range exceeds 366 days or type=REVERSAL (AC 3,"
                    + " NC-08)")
    void exportPdf_whenInvalidDateRangeOrReversalFilter_shouldReturn400ProblemDetail()
            throws Exception {
        mockMvc.perform(
                        get("/api/v1/reports/export")
                                .param("format", "PDF")
                                .param("period", "QUARTER")
                                .param("from", "2025-01-01")
                                .param("to", "2026-06-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(
                        get("/api/v1/reports/export")
                                .param("format", "PDF")
                                .param("period", "QUARTER")
                                .param("from", "2026-01-01")
                                .param("to", "2026-06-30")
                                .param("type", "REVERSAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName(
            "GET /api/v1/reports/export?format=PDF returns 403 ACCESS_DENIED when invoked by"
                    + " CUSTOMER role (AC 3, L2-02)")
    void exportPdf_whenCustomerRole_shouldReturn403ProblemDetail() throws Exception {
        mockMvc.perform(
                        get("/api/v1/reports/export")
                                .param("format", "PDF")
                                .param("period", "QUARTER")
                                .param("from", "2026-01-01")
                                .param("to", "2026-06-30"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}

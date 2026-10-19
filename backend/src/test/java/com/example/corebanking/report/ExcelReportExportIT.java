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
import jakarta.persistence.EntityManagerFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFPictureData;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * PostgreSQL 17 Testcontainers integration test for Story 4.6 (Excel Report Export: L3-07, L3-08,
 * G-05, FR-21, NFR-2, ADR-014).
 */
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class ExcelReportExportIT extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName(
            "GET /api/v1/reports/export?format=XLSX streams valid 3-sheet Excel workbook with"
                    + " summary, streamed details, 3 embedded 800x400 PNG charts, 0 JPA queries,"
                    + " and Index Scan (AC 1, AC 2, L3-07, L3-08, G-05, Rule 6)")
    void exportXlsx_viaHttpEndpoint_shouldReturnValidExcelWithCorrectHeadersAndData()
            throws Exception {
        Customer customer =
                customerRepository.save(
                        new Customer(
                                "CUS9990461",
                                "Nguyễn Xuất Báo Cáo",
                                "000999888461",
                                "export46@example.com",
                                "0900000461",
                                "Số 46 Quận 1",
                                "TP. Hồ Chí Minh",
                                LocalDate.of(1990, 6, 15)));
        Account accountA =
                accountRepository.save(
                        new Account(
                                "100999999461",
                                customer.getId(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00"),
                                LocalDate.of(2025, 1, 1)));
        Account accountB =
                accountRepository.save(
                        new Account(
                                "100999999462",
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
('TXN-XLS-1', 'DEPOSIT',  NULL,  :accA, 30000000.00, 0.00,    NULL,        30000000.00, 30000000.00, 'TP. HCM', 'Q1 Deposit',       'COMPLETED', '2028-02-10T09:00:00+07:00'),
('TXN-XLS-2', 'TRANSFER', :accA, :accB,  5000000.00, 2000.00, 24998000.00,  5000000.00,    -2000.00, 'Ha Noi',  '=HYPERLINK("x")',  'COMPLETED', '2028-05-20T14:30:00+07:00'),
('TXN-XLS-3', 'REVERSAL', :accB, :accA,  5000000.00, 0.00,           0.00, 29998000.00,        0.00, 'Ha Noi',  'Excluded Reversal','COMPLETED', '2028-05-21T10:00:00+07:00')
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
                                            .param("format", "XLSX")
                                            .param("period", "QUARTER")
                                            .param("from", "2028-01-01")
                                            .param("to", "2028-06-30"))
                            .andExpect(status().isOk())
                            .andExpect(
                                    header().string(
                                                    HttpHeaders.CONTENT_DISPOSITION,
                                                    "attachment;"
                                                        + " filename=\"core-banking-report-quarter-2028-01-01-2028-06-30.xlsx\""))
                            .andExpect(
                                    header().string(
                                                    HttpHeaders.CONTENT_TYPE,
                                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                            .andReturn();

            byte[] xlsxBytes =
                    initialResult.getRequest().isAsyncStarted()
                            ? mockMvc.perform(asyncDispatch(initialResult))
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsByteArray()
                            : initialResult.getResponse().getContentAsByteArray();

            assertThat(xlsxBytes).isNotEmpty();
            assertThat(stats.getPrepareStatementCount())
                    .as(
                            "Excel export executes purely via JdbcClient/JdbcTemplate with 0 JPA"
                                    + " queries")
                    .isZero();

            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
                assertThat(wb.getNumberOfSheets()).isEqualTo(3);

                Sheet summary = wb.getSheet("Tong hop");
                assertThat(summary).isNotNull();
                assertThat(summary.getRow(0).getCell(0).getStringCellValue())
                        .isEqualTo("Core Banking System — Báo cáo tài chính");
                // Q1 2028 (2028-01-01) and Q2 2028 (2028-04-01)
                assertThat(summary.getRow(4).getCell(0).getStringCellValue())
                        .isEqualTo("2028-01-01");
                assertThat(summary.getRow(4).getCell(1).getNumericCellValue()).isEqualTo(1.0d);
                assertThat(summary.getRow(4).getCell(2).getNumericCellValue())
                        .isEqualTo(30000000.00d);
                assertThat(summary.getRow(5).getCell(0).getStringCellValue())
                        .isEqualTo("2028-04-01");
                assertThat(summary.getRow(5).getCell(1).getNumericCellValue()).isEqualTo(1.0d);
                assertThat(summary.getRow(5).getCell(2).getNumericCellValue())
                        .isEqualTo(5000000.00d);

                Sheet details = wb.getSheet("Chi tiet");
                assertThat(details).isNotNull();
                // 2 non-REVERSAL rows ordered by created_at DESC (TXN-XLS-2 first, TXN-XLS-1
                // second)
                assertThat(details.getLastRowNum()).isEqualTo(2);
                assertThat(details.getRow(1).getCell(0).getStringCellValue())
                        .isEqualTo("TXN-XLS-2");
                assertThat(details.getRow(1).getCell(8).getStringCellValue())
                        .isEqualTo("'=HYPERLINK(\"x\")");
                assertThat(details.getRow(2).getCell(0).getStringCellValue())
                        .isEqualTo("TXN-XLS-1");

                List<XSSFPictureData> pictures = wb.getAllPictures();
                assertThat(pictures).hasSize(3);
                for (XSSFPictureData pic : pictures) {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(pic.getData()));
                    assertThat(img.getWidth()).isEqualTo(800);
                    assertThat(img.getHeight()).isEqualTo(400);
                }
            }

            // Verify EXPLAIN (ANALYZE, BUFFERS) on streaming detail query uses
            // idx_tx_status_created
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
""")
                                .query(String.class)
                                .list();
                String explainPlan = String.join("\n", explainLines);
                assertThat(explainPlan).contains("Index Scan").contains("idx_tx_status_created");
            } finally {
                jdbcClient.sql("RESET enable_seqscan").update();
            }
        } finally {
            jdbcClient
                    .sql(
                            "DELETE FROM core.transaction WHERE reference_no IN ('TXN-XLS-1',"
                                    + " 'TXN-XLS-2', 'TXN-XLS-3')")
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
            "GET /api/v1/reports/export returns 400 ProblemDetail before committing streaming"
                    + " headers when date range exceeds 366 days or format is invalid (AC 3,"
                    + " NC-08)")
    void exportXlsx_whenDateRangeExceeds366Days_shouldReturn400ProblemDetail() throws Exception {
        mockMvc.perform(
                        get("/api/v1/reports/export")
                                .param("format", "XLSX")
                                .param("period", "QUARTER")
                                .param("from", "2025-01-01")
                                .param("to", "2026-06-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(
                        get("/api/v1/reports/export")
                                .param("format", "CSV")
                                .param("period", "QUARTER")
                                .param("from", "2026-01-01")
                                .param("to", "2026-06-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("format"));
    }
}

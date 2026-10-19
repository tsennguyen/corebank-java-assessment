package com.example.corebanking.report.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.report.api.dto.ReportBundle;
import com.example.corebanking.report.api.dto.ReportExportCriteria;
import com.example.corebanking.report.api.dto.TransactionExportRow;
import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.api.dto.TrendPointResponse;
import com.example.corebanking.report.application.ReportDataService;
import com.example.corebanking.report.application.ReportExportService;
import com.example.corebanking.report.application.ReportService;
import com.example.corebanking.report.domain.ExportFormat;
import com.example.corebanking.report.domain.ReportPeriod;
import com.example.corebanking.transaction.domain.TransactionType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFPictureData;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExcelReportExporterTest {

    @Mock private ReportDataService reportDataService;
    @Mock private ReportService reportService;

    private ChartRenderer chartRenderer;
    private ExcelReportExporter excelReportExporter;

    @BeforeEach
    void setUp() {
        chartRenderer = new ChartRenderer();
        excelReportExporter = new ExcelReportExporter(reportDataService, chartRenderer);
    }

    @Test
    @DisplayName(
            "ExcelReportExporter writes 3 sheets (Tong hop, Chi tiet, Bieu do), embeds 3 800x400"
                    + " PNG charts, and sanitizes formula injection in transaction details (AC 1,"
                    + " L3-07, L3-08)")
    void export_shouldCreateWorkbookWithThreeSheetsAndThreeEmbeddedCharts() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        ReportExportCriteria criteria =
                new ReportExportCriteria(
                        ExportFormat.XLSX,
                        ReportPeriod.QUARTER,
                        from,
                        to,
                        TransactionType.TRANSFER);

        List<TransactionReportBucketResponse> buckets =
                List.of(
                        new TransactionReportBucketResponse(
                                LocalDate.of(2026, 1, 1),
                                2L,
                                new BigDecimal("15000000.00"),
                                new BigDecimal("7500000.00"),
                                new BigDecimal("10000000.00"),
                                new BigDecimal("5000000.00"),
                                new BigDecimal("4000.00")),
                        new TransactionReportBucketResponse(
                                LocalDate.of(2026, 4, 1),
                                1L,
                                new BigDecimal("8000000.00"),
                                new BigDecimal("8000000.00"),
                                new BigDecimal("8000000.00"),
                                new BigDecimal("8000000.00"),
                                new BigDecimal("2000.00")));

        List<TrendPointResponse> trendPoints =
                List.of(
                        new TrendPointResponse(
                                LocalDate.of(2026, 1, 1),
                                2L,
                                new BigDecimal("15000000.00"),
                                new BigDecimal("4000.00"),
                                new BigDecimal("120000000.00")),
                        new TrendPointResponse(
                                LocalDate.of(2026, 4, 1),
                                1L,
                                new BigDecimal("8000000.00"),
                                new BigDecimal("2000.00"),
                                new BigDecimal("128000000.00")));

        when(reportDataService.loadReportBundle(
                        ReportPeriod.QUARTER, from, to, TransactionType.TRANSFER))
                .thenReturn(new ReportBundle(buckets, trendPoints));

        doAnswer(
                        invocation -> {
                            Consumer<TransactionExportRow> consumer = invocation.getArgument(3);
                            consumer.accept(
                                    new TransactionExportRow(
                                            "TXN-EXP-001",
                                            "TRANSFER",
                                            "COMPLETED",
                                            10L,
                                            20L,
                                            new BigDecimal("10000000.00"),
                                            new BigDecimal("2000.00"),
                                            "TP. Hồ Chí Minh",
                                            "=SUM(A1:A10)",
                                            Instant.parse("2026-02-15T03:30:00Z")));
                            consumer.accept(
                                    new TransactionExportRow(
                                            "TXN-EXP-002",
                                            "DEPOSIT",
                                            "COMPLETED",
                                            null,
                                            20L,
                                            new BigDecimal("5000000.00"),
                                            new BigDecimal("0.00"),
                                            "+cmd|'/c calc'!A0",
                                            "Normal description",
                                            Instant.parse("2026-05-10T08:00:00Z")));
                            return null;
                        })
                .when(reportDataService)
                .streamTransactionsInPeriod(eq(from), eq(to), eq(TransactionType.TRANSFER), any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        excelReportExporter.export(criteria, out);

        byte[] xlsxBytes = out.toByteArray();
        assertThat(xlsxBytes).isNotEmpty();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            assertThat(wb.getSheetName(0)).isEqualTo("Tong hop");
            assertThat(wb.getSheetName(1)).isEqualTo("Chi tiet");
            assertThat(wb.getSheetName(2)).isEqualTo("Bieu do");

            // 1. Verify Tong hop sheet
            Sheet summary = wb.getSheet("Tong hop");
            assertThat(summary.getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("Core Banking System — Báo cáo tài chính");
            assertThat(summary.getRow(1).getCell(0).getStringCellValue())
                    .contains("QUARTER")
                    .contains("2026-01-01")
                    .contains("2026-06-30")
                    .contains("TRANSFER");
            assertThat(summary.getRow(3).getCell(0).getStringCellValue()).isEqualTo("periodStart");
            assertThat(summary.getRow(4).getCell(0).getStringCellValue()).isEqualTo("2026-01-01");
            assertThat(summary.getRow(4).getCell(1).getNumericCellValue()).isEqualTo(2.0d);
            assertThat(summary.getRow(4).getCell(2).getNumericCellValue()).isEqualTo(15000000.00d);
            assertThat(summary.getRow(5).getCell(0).getStringCellValue()).isEqualTo("2026-04-01");

            // 2. Verify Chi tiet sheet + formula injection protection
            Sheet details = wb.getSheet("Chi tiet");
            assertThat(details.getLastRowNum()).isEqualTo(2);
            Row row1 = details.getRow(1);
            assertThat(row1.getCell(0).getStringCellValue()).isEqualTo("TXN-EXP-001");
            assertThat(row1.getCell(3).getNumericCellValue()).isEqualTo(10.0d);
            assertThat(row1.getCell(4).getNumericCellValue()).isEqualTo(20.0d);
            assertThat(row1.getCell(5).getNumericCellValue()).isEqualTo(10000000.00d);
            assertThat(row1.getCell(6).getNumericCellValue()).isEqualTo(2000.00d);
            assertThat(row1.getCell(8).getStringCellValue()).isEqualTo("'=SUM(A1:A10)");

            Row row2 = details.getRow(2);
            assertThat(row2.getCell(3).getStringCellValue()).isEmpty();
            assertThat(row2.getCell(7).getStringCellValue()).isEqualTo("'+cmd|'/c calc'!A0");
            assertThat(row2.getCell(8).getStringCellValue()).isEqualTo("Normal description");

            // 3. Verify Bieu do sheet + 3 embedded 800x400 PNG images
            List<XSSFPictureData> pictures = wb.getAllPictures();
            assertThat(pictures).hasSize(3);
            for (XSSFPictureData pic : pictures) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(pic.getData()));
                assertThat(img).isNotNull();
                assertThat(img.getWidth()).isEqualTo(800);
                assertThat(img.getHeight()).isEqualTo(400);
            }
        }
    }

    @Test
    @DisplayName(
            "ExcelReportExporter streams 100,000 transaction rows via SXSSFWorkbook(100) without"
                + " OOM and preserves all 100,000 detail rows when reopened (AC 2, G-05, NFR-2)")
    void export_with100kStreamedRows_shouldCompleteWithoutOomAndContainAllRows() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);
        ReportExportCriteria criteria =
                new ReportExportCriteria(ExportFormat.XLSX, ReportPeriod.YEAR, from, to, null);

        when(reportDataService.loadReportBundle(ReportPeriod.YEAR, from, to, null))
                .thenReturn(
                        new ReportBundle(
                                List.of(
                                        new TransactionReportBucketResponse(
                                                from,
                                                100_000L,
                                                new BigDecimal("10000000000.00"),
                                                new BigDecimal("100000.00"),
                                                new BigDecimal("100000.00"),
                                                new BigDecimal("100000.00"),
                                                new BigDecimal("0.00"))),
                                List.of(
                                        new TrendPointResponse(
                                                from,
                                                100_000L,
                                                new BigDecimal("10000000000.00"),
                                                new BigDecimal("0.00"),
                                                new BigDecimal("10000000000.00")))));

        Instant fixedTime = Instant.parse("2026-06-15T10:00:00Z");
        BigDecimal amt = new BigDecimal("100000.00");
        BigDecimal zero = new BigDecimal("0.00");

        doAnswer(
                        invocation -> {
                            Consumer<TransactionExportRow> consumer = invocation.getArgument(3);
                            for (int i = 1; i <= 100_000; i++) {
                                consumer.accept(
                                        new TransactionExportRow(
                                                "TXN-100K-" + i,
                                                "DEPOSIT",
                                                "COMPLETED",
                                                null,
                                                1L,
                                                amt,
                                                zero,
                                                "TP. HCM",
                                                "Bulk row " + i,
                                                fixedTime));
                            }
                            return null;
                        })
                .when(reportDataService)
                .streamTransactionsInPeriod(eq(from), eq(to), eq(null), any());

        ByteArrayOutputStream out = new ByteArrayOutputStream(4 * 1024 * 1024);
        excelReportExporter.export(criteria, out);

        byte[] xlsxBytes = out.toByteArray();
        assertThat(xlsxBytes).isNotEmpty();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            Sheet details = wb.getSheet("Chi tiet");
            assertThat(details.getLastRowNum()).isEqualTo(100_000);
            assertThat(details.getRow(1).getCell(0).getStringCellValue()).isEqualTo("TXN-100K-1");
            assertThat(details.getRow(100_000).getCell(0).getStringCellValue())
                    .isEqualTo("TXN-100K-100000");
        }
    }

    @Test
    @DisplayName(
            "sanitizeFormulaCell prefixes single quote on =, +, -, @, tab, and CR to prevent"
                    + " CSV/Excel formula injection")
    void sanitizeFormulaCell_shouldNeutralizeFormulaPrefixes() {
        assertThat(ExcelReportExporter.sanitizeFormulaCell(null)).isEmpty();
        assertThat(ExcelReportExporter.sanitizeFormulaCell("")).isEmpty();
        assertThat(ExcelReportExporter.sanitizeFormulaCell("Normal text")).isEqualTo("Normal text");
        assertThat(ExcelReportExporter.sanitizeFormulaCell("=1+1")).isEqualTo("'=1+1");
        assertThat(ExcelReportExporter.sanitizeFormulaCell("+1+1")).isEqualTo("'+1+1");
        assertThat(ExcelReportExporter.sanitizeFormulaCell("-1+1")).isEqualTo("'-1+1");
        assertThat(ExcelReportExporter.sanitizeFormulaCell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(ExcelReportExporter.sanitizeFormulaCell("\t=cmd")).isEqualTo("'\t=cmd");
        assertThat(ExcelReportExporter.sanitizeFormulaCell("\r=cmd")).isEqualTo("'\r=cmd");
    }

    @Test
    @DisplayName(
            "ReportExportService validates format, period, date range, and REVERSAL filter before"
                    + " streaming begins (AC 3, NC-08)")
    void reportExportService_validateAndBuildCriteria_shouldEnforceRules() {
        ReportExportService exportService =
                new ReportExportService(reportService, List.of(excelReportExporter));
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);

        ReportExportCriteria criteria =
                exportService.validateAndBuildCriteria(
                        ExportFormat.XLSX, ReportPeriod.QUARTER, from, to, null);
        assertThat(criteria.buildFilename())
                .isEqualTo("core-banking-report-quarter-2026-01-01-2026-06-30.xlsx");
        verify(reportService).validateDateRange(from, to);

        assertThatThrownBy(
                        () ->
                                exportService.validateAndBuildCriteria(
                                        null, ReportPeriod.QUARTER, from, to, null))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(
                        () ->
                                exportService.validateAndBuildCriteria(
                                        ExportFormat.XLSX, null, from, to, null))
                .isInstanceOf(BusinessRuleException.class);

        assertThatThrownBy(
                        () ->
                                exportService.validateAndBuildCriteria(
                                        ExportFormat.XLSX,
                                        ReportPeriod.QUARTER,
                                        from,
                                        to,
                                        TransactionType.REVERSAL))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("REVERSAL");

        assertThatThrownBy(
                        () ->
                                exportService.validateAndBuildCriteria(
                                        ExportFormat.PDF, ReportPeriod.QUARTER, from, to, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Unsupported export format");
    }
}

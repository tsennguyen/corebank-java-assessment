package com.example.corebanking.report.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PdfReportExporterTest {

    @Mock private ReportDataService reportDataService;
    @Mock private ReportService reportService;

    private ChartRenderer chartRenderer;
    private PdfReportExporter pdfReportExporter;

    @BeforeEach
    void setUp() {
        chartRenderer = new ChartRenderer();
        pdfReportExporter = new PdfReportExporter(reportDataService, chartRenderer);
    }

    @Test
    @DisplayName(
            "PdfReportExporter generates valid PDF with embedded DejaVuSans Vietnamese diacritics,"
                    + " summary table, 3 embedded 800x400 PNG charts, and transaction details (AC"
                    + " 1, AC 2, L3-07, L3-08)")
    void export_shouldGeneratePdfWithVietnameseTextSummaryThreeChartsAndDetails() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        ReportExportCriteria criteria =
                new ReportExportCriteria(
                        ExportFormat.PDF, ReportPeriod.QUARTER, from, to, TransactionType.TRANSFER);

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
                            Consumer<TransactionExportRow> consumer = invocation.getArgument(4);
                            consumer.accept(
                                    new TransactionExportRow(
                                            "TXN-PDF-001",
                                            "TRANSFER",
                                            "COMPLETED",
                                            10L,
                                            20L,
                                            new BigDecimal("10000000.00"),
                                            new BigDecimal("2000.00"),
                                            "TP. Hồ Chí Minh",
                                            "Chuyển khoản thanh toán hóa đơn điện nước",
                                            Instant.parse("2026-02-15T03:30:00Z")));
                            consumer.accept(
                                    new TransactionExportRow(
                                            "TXN-PDF-002",
                                            "DEPOSIT",
                                            "COMPLETED",
                                            null,
                                            20L,
                                            new BigDecimal("5000000.00"),
                                            new BigDecimal("0.00"),
                                            "Đà Nẵng",
                                            "Nộp tiền tiết kiệm kỳ hạn",
                                            Instant.parse("2026-05-10T08:00:00Z")));
                            return null;
                        })
                .when(reportDataService)
                .streamTransactionsInPeriod(
                        eq(from),
                        eq(to),
                        eq(TransactionType.TRANSFER),
                        eq(PdfReportExporter.MAX_PDF_DETAIL_ROWS),
                        any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pdfReportExporter.export(criteria, out);

        byte[] pdfBytes = out.toByteArray();
        assertThat(pdfBytes).isNotEmpty();
        assertThat(new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        PdfReader reader = new PdfReader(pdfBytes);
        try {
            assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(1);

            String extractedText = extractAllText(reader);
            assertThat(extractedText)
                    .contains("Core Banking System — Báo cáo tài chính")
                    .contains("QUARTER")
                    .contains("2026-01-01")
                    .contains("2026-06-30")
                    .contains("15,000,000.00")
                    .contains("TXN-PDF-001")
                    .contains("TP. Hồ Chí Minh")
                    .contains("Chuyển khoản thanh toán hóa đơn điện nước")
                    .contains("TXN-PDF-002")
                    .contains("Đà Nẵng")
                    .contains("Nộp tiền tiết kiệm kỳ hạn");

            int imageCount = countEmbedded800x400Images(reader);
            assertThat(imageCount).isEqualTo(3);
        } finally {
            reader.close();
        }
    }

    @Test
    @DisplayName(
            "PdfReportExporter caps transaction details at 1,000 rows and includes truncation note"
                    + " when dataset exceeds 1,000 rows (AC 2, FR-21, ADR-014)")
    void export_whenStreamExceeds1000Rows_shouldCapAt1000RowsAndIncludeTruncationNote()
            throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);
        ReportExportCriteria criteria =
                new ReportExportCriteria(ExportFormat.PDF, ReportPeriod.YEAR, from, to, null);

        when(reportDataService.loadReportBundle(ReportPeriod.YEAR, from, to, null))
                .thenReturn(new ReportBundle(List.of(), List.of()));

        Instant fixedTime = Instant.parse("2026-06-15T10:00:00Z");
        BigDecimal amount = new BigDecimal("250000.00");
        BigDecimal zero = new BigDecimal("0.00");

        doAnswer(
                        invocation -> {
                            Consumer<TransactionExportRow> consumer = invocation.getArgument(4);
                            for (int i = 1; i <= 1200; i++) {
                                consumer.accept(
                                        new TransactionExportRow(
                                                String.format("TXN-CAP-%04d", i),
                                                "TRANSFER",
                                                "COMPLETED",
                                                1L,
                                                2L,
                                                amount,
                                                zero,
                                                "Hà Nội",
                                                "Giao dịch thử nghiệm " + i,
                                                fixedTime));
                            }
                            return null;
                        })
                .when(reportDataService)
                .streamTransactionsInPeriod(
                        eq(from),
                        eq(to),
                        eq(null),
                        eq(PdfReportExporter.MAX_PDF_DETAIL_ROWS),
                        any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pdfReportExporter.export(criteria, out);

        PdfReader reader = new PdfReader(out.toByteArray());
        try {
            String extractedText = extractAllText(reader);
            assertThat(extractedText)
                    .contains("TXN-CAP-0001")
                    .contains("TXN-CAP-1000")
                    .doesNotContain("TXN-CAP-1001")
                    .doesNotContain("TXN-CAP-1200")
                    .contains("Hiển thị tối đa 1.000 giao dịch mới nhất");
        } finally {
            reader.close();
        }
    }

    @Test
    @DisplayName(
            "ReportExportService registers both XLSX and PDF exporters and builds .pdf filename"
                    + " (AC 1, AC 3)")
    void reportExportService_withExcelAndPdfExporters_shouldSupportPdfFormat() {
        ExcelReportExporter excelExporter =
                new ExcelReportExporter(reportDataService, chartRenderer);
        ReportExportService exportService =
                new ReportExportService(reportService, List.of(excelExporter, pdfReportExporter));

        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);
        ReportExportCriteria criteria =
                exportService.validateAndBuildCriteria(
                        ExportFormat.PDF, ReportPeriod.QUARTER, from, to, null);

        assertThat(pdfReportExporter.supportedFormat()).isEqualTo(ExportFormat.PDF);
        assertThat(criteria.format()).isEqualTo(ExportFormat.PDF);
        assertThat(criteria.buildFilename())
                .isEqualTo("core-banking-report-quarter-2026-01-01-2026-03-31.pdf");
        verify(reportService).validateDateRange(from, to);
    }

    @Test
    @DisplayName(
            "PdfReportExporter renders Vietnamese empty-state placeholders gracefully when period"
                    + " has 0 summary buckets and 0 transactions")
    void export_whenNoTransactionsInPeriod_shouldRenderEmptyStateGracefully() throws Exception {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        ReportExportCriteria criteria =
                new ReportExportCriteria(ExportFormat.PDF, ReportPeriod.QUARTER, from, to, null);

        when(reportDataService.loadReportBundle(ReportPeriod.QUARTER, from, to, null))
                .thenReturn(new ReportBundle(List.of(), List.of()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pdfReportExporter.export(criteria, out);

        PdfReader reader = new PdfReader(out.toByteArray());
        try {
            String extractedText = extractAllText(reader);
            assertThat(extractedText)
                    .contains("Core Banking System — Báo cáo tài chính")
                    .contains("Không có dữ liệu tổng hợp trong kỳ đã chọn.")
                    .contains("Không có giao dịch chi tiết trong kỳ báo cáo.");
            assertThat(countEmbedded800x400Images(reader)).isEqualTo(3);
        } finally {
            reader.close();
        }
    }

    private static String extractAllText(PdfReader reader) throws Exception {
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder sb = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            sb.append(extractor.getTextFromPage(page)).append('\n');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    private static int countEmbedded800x400Images(PdfReader reader) {
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
        return imageCount;
    }
}

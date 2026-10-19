package com.example.corebanking.report.export;

import com.example.corebanking.report.api.dto.ReportExportCriteria;
import com.example.corebanking.report.api.dto.TransactionExportRow;
import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.application.ReportDataService;
import com.example.corebanking.report.domain.ExportFormat;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * PDF ({@code .pdf}) financial report exporter built on OpenPDF with embedded {@code DejaVuSans}
 * Unicode TrueType fonts for full Vietnamese diacritics support, 3 embedded {@code 800x400} PNG
 * trend charts, and a 1,000-row detail table cap (Story 4.7: L3-07, L3-08, FR-21, ADR-014).
 */
@Component
public class PdfReportExporter extends ReportExporter<PdfReportExporter.PdfExportContext> {

    /** Maximum number of transaction detail rows rendered into a PDF report (FR-21, ADR-014). */
    public static final int MAX_PDF_DETAIL_ROWS = 1000;

    /** Note appended when transaction details reach the 1,000-row PDF cap. */
    public static final String TRUNCATION_NOTE =
            "Hiển thị tối đa 1.000 giao dịch mới nhất. Vui lòng xuất định dạng Excel (.xlsx) để"
                    + " xem toàn bộ dữ liệu chi tiết.";

    private static final String FONT_REGULAR_RESOURCE = "fonts/DejaVuSans.ttf";
    private static final String FONT_BOLD_RESOURCE = "fonts/DejaVuSans-Bold.ttf";

    private static final DateTimeFormatter CREATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private static final Color HEADER_BG_COLOR = new Color(232, 240, 254);
    private static final Color BORDER_COLOR = new Color(203, 213, 225);

    private static final String[] SUMMARY_HEADERS = {
        "Kỳ bắt đầu (periodStart)",
        "Số GD (txCount)",
        "Tổng tiền (totalAmount)",
        "Trung bình (averageAmount)",
        "Cao nhất (maxAmount)",
        "Thấp nhất (minAmount)",
        "Tổng phí (totalFee)"
    };

    private static final String[] DETAIL_HEADERS = {
        "Mã GD (referenceNo)",
        "Loại (type)",
        "Trạng thái",
        "TK nguồn",
        "TK đích",
        "Số tiền (VND)",
        "Phí (VND)",
        "Địa điểm",
        "Diễn giải",
        "Thời gian"
    };

    private final byte[] regularFontBytes;
    private final byte[] boldFontBytes;

    public PdfReportExporter(ReportDataService reportDataService, ChartRenderer chartRenderer) {
        super(reportDataService, chartRenderer);
        this.regularFontBytes = loadFontResource(FONT_REGULAR_RESOURCE);
        this.boldFontBytes = loadFontResource(FONT_BOLD_RESOURCE);
    }

    @Override
    public ExportFormat supportedFormat() {
        return ExportFormat.PDF;
    }

    @Override
    protected PdfExportContext createContext(
            ReportExportCriteria criteria, OutputStream outputStream) throws IOException {
        try {
            Document document = new Document(PageSize.A4.rotate(), 28f, 28f, 28f, 28f);
            PdfWriter writer = PdfWriter.getInstance(document, outputStream);
            document.open();

            BaseFont regularBase =
                    BaseFont.createFont(
                            "DejaVuSans.ttf",
                            BaseFont.IDENTITY_H,
                            BaseFont.EMBEDDED,
                            true,
                            regularFontBytes,
                            null);
            BaseFont boldBase =
                    BaseFont.createFont(
                            "DejaVuSans-Bold.ttf",
                            BaseFont.IDENTITY_H,
                            BaseFont.EMBEDDED,
                            true,
                            boldFontBytes,
                            null);

            Font titleFont = new Font(boldBase, 15f, Font.BOLD);
            Font sectionFont = new Font(boldBase, 11f, Font.BOLD);
            Font tableHeaderFont = new Font(boldBase, 8.5f, Font.BOLD);
            Font bodyFont = new Font(regularBase, 8.5f, Font.NORMAL);
            Font smallFont = new Font(regularBase, 7.5f, Font.NORMAL);

            return new PdfExportContext(
                    document, writer, titleFont, sectionFont, tableHeaderFont, bodyFont, smallFont);
        } catch (DocumentException ex) {
            throw new IOException("Failed to initialize PDF document context", ex);
        }
    }

    @Override
    protected void writeHeader(PdfExportContext context, ReportExportCriteria criteria)
            throws IOException {
        try {
            Paragraph title = new Paragraph(REPORT_TITLE, context.titleFont());
            title.setSpacingAfter(4f);
            context.document().add(title);

            String typeLabel = criteria.type() != null ? criteria.type().name() : "ALL";
            Paragraph meta =
                    new Paragraph(
                            "Kỳ báo cáo: "
                                    + criteria.period().name()
                                    + " | Từ ngày: "
                                    + criteria.from()
                                    + " | Đến ngày: "
                                    + criteria.to()
                                    + " | Loại giao dịch: "
                                    + typeLabel,
                            context.bodyFont());
            meta.setSpacingAfter(10f);
            context.document().add(meta);
        } catch (DocumentException ex) {
            throw new IOException("Failed to write PDF report header", ex);
        }
    }

    @Override
    protected void writeSummary(
            PdfExportContext context,
            ReportExportCriteria criteria,
            List<TransactionReportBucketResponse> periodicBuckets)
            throws IOException {
        try {
            Paragraph heading =
                    new Paragraph("1. Tổng hợp theo kỳ (Periodic Summary)", context.sectionFont());
            heading.setSpacingAfter(6f);
            context.document().add(heading);

            PdfPTable table = new PdfPTable(new float[] {1.5f, 1.0f, 1.8f, 1.8f, 1.8f, 1.8f, 1.5f});
            table.setWidthPercentage(100f);
            table.setHeaderRows(1);
            table.setSpacingAfter(12f);

            for (String header : SUMMARY_HEADERS) {
                table.addCell(createHeaderCell(header, context.tableHeaderFont()));
            }

            if (periodicBuckets != null && !periodicBuckets.isEmpty()) {
                for (TransactionReportBucketResponse bucket : periodicBuckets) {
                    table.addCell(
                            createBodyCell(
                                    bucket.periodStart() != null
                                            ? bucket.periodStart().toString()
                                            : "",
                                    context.bodyFont(),
                                    Element.ALIGN_LEFT));
                    table.addCell(
                            createBodyCell(
                                    String.valueOf(bucket.txCount()),
                                    context.bodyFont(),
                                    Element.ALIGN_RIGHT));
                    table.addCell(
                            createBodyCell(
                                    formatMoney(bucket.totalAmount()),
                                    context.bodyFont(),
                                    Element.ALIGN_RIGHT));
                    table.addCell(
                            createBodyCell(
                                    formatMoney(bucket.averageAmount()),
                                    context.bodyFont(),
                                    Element.ALIGN_RIGHT));
                    table.addCell(
                            createBodyCell(
                                    formatMoney(bucket.maxAmount()),
                                    context.bodyFont(),
                                    Element.ALIGN_RIGHT));
                    table.addCell(
                            createBodyCell(
                                    formatMoney(bucket.minAmount()),
                                    context.bodyFont(),
                                    Element.ALIGN_RIGHT));
                    table.addCell(
                            createBodyCell(
                                    formatMoney(bucket.totalFee()),
                                    context.bodyFont(),
                                    Element.ALIGN_RIGHT));
                }
            } else {
                PdfPCell emptyCell =
                        createBodyCell(
                                "Không có dữ liệu tổng hợp trong kỳ đã chọn.",
                                context.bodyFont(),
                                Element.ALIGN_CENTER);
                emptyCell.setColspan(SUMMARY_HEADERS.length);
                table.addCell(emptyCell);
            }

            context.document().add(table);
        } catch (DocumentException ex) {
            throw new IOException("Failed to write PDF summary table", ex);
        }
    }

    @Override
    protected void writeCharts(PdfExportContext context, ChartRenderer.RenderedCharts charts)
            throws IOException {
        try {
            Paragraph heading =
                    new Paragraph(
                            "2. Biểu đồ xu hướng (Transaction Volume, Balance, Fee)",
                            context.sectionFont());
            heading.setSpacingAfter(6f);
            context.document().add(heading);

            embedChartImage(
                    context, ChartRenderer.TRANSACTION_VOLUME_TITLE, charts.transactionVolumePng());
            embedChartImage(context, ChartRenderer.BALANCE_TREND_TITLE, charts.balanceTrendPng());
            embedChartImage(context, ChartRenderer.FEE_TREND_TITLE, charts.feeTrendPng());
        } catch (DocumentException ex) {
            throw new IOException("Failed to write PDF chart images", ex);
        }
    }

    @Override
    protected void writeDetails(
            PdfExportContext context, ReportExportCriteria criteria, ReportDataService dataService)
            throws IOException {
        try {
            Paragraph heading =
                    new Paragraph(
                            "3. Chi tiết giao dịch (Transaction Details — tối đa 1.000 dòng)",
                            context.sectionFont());
            heading.setSpacingBefore(6f);
            heading.setSpacingAfter(6f);
            context.document().add(heading);

            PdfPTable detailTable =
                    new PdfPTable(
                            new float[] {
                                1.9f, 1.1f, 1.2f, 1.0f, 1.0f, 1.6f, 1.2f, 1.5f, 2.3f, 1.7f
                            });
            detailTable.setWidthPercentage(100f);
            detailTable.setHeaderRows(1);
            detailTable.setSpacingAfter(8f);

            for (String header : DETAIL_HEADERS) {
                detailTable.addCell(createHeaderCell(header, context.tableHeaderFont()));
            }

            AtomicInteger rowCount = new AtomicInteger(0);
            dataService.streamTransactionsInPeriod(
                    criteria.from(),
                    criteria.to(),
                    criteria.type(),
                    MAX_PDF_DETAIL_ROWS,
                    tx -> {
                        if (rowCount.incrementAndGet() <= MAX_PDF_DETAIL_ROWS) {
                            appendDetailRow(detailTable, tx, context.smallFont());
                        }
                    });

            if (rowCount.get() == 0) {
                PdfPCell emptyCell =
                        createBodyCell(
                                "Không có giao dịch chi tiết trong kỳ báo cáo.",
                                context.smallFont(),
                                Element.ALIGN_CENTER);
                emptyCell.setColspan(DETAIL_HEADERS.length);
                detailTable.addCell(emptyCell);
            }

            context.document().add(detailTable);

            Paragraph note = new Paragraph(TRUNCATION_NOTE, context.smallFont());
            note.setSpacingBefore(4f);
            context.document().add(note);
        } catch (DocumentException ex) {
            throw new IOException("Failed to write PDF transaction details table", ex);
        }
    }

    @Override
    protected void finish(PdfExportContext context, OutputStream outputStream) throws IOException {
        if (context.document().isOpen()) {
            context.document().close();
        }
        outputStream.flush();
    }

    @Override
    protected void cleanup(PdfExportContext context) {
        if (context == null) {
            return;
        }
        if (context.document() != null && context.document().isOpen()) {
            context.document().close();
        }
        if (context.writer() != null) {
            context.writer().close();
        }
    }

    private static void embedChartImage(PdfExportContext context, String caption, byte[] pngBytes)
            throws DocumentException, IOException {
        Paragraph captionParagraph = new Paragraph(caption, context.bodyFont());
        captionParagraph.setSpacingAfter(3f);
        context.document().add(captionParagraph);

        if (pngBytes == null || pngBytes.length == 0) {
            return;
        }
        Image image = Image.getInstance(pngBytes);
        image.scaleToFit(500f, 220f);
        image.setAlignment(Element.ALIGN_CENTER);
        image.setSpacingAfter(8f);
        context.document().add(image);
    }

    private static void appendDetailRow(PdfPTable table, TransactionExportRow tx, Font smallFont) {
        table.addCell(createBodyCell(nullSafe(tx.referenceNo()), smallFont, Element.ALIGN_LEFT));
        table.addCell(createBodyCell(nullSafe(tx.type()), smallFont, Element.ALIGN_LEFT));
        table.addCell(createBodyCell(nullSafe(tx.status()), smallFont, Element.ALIGN_LEFT));
        table.addCell(
                createBodyCell(
                        tx.sourceAccountId() != null ? String.valueOf(tx.sourceAccountId()) : "-",
                        smallFont,
                        Element.ALIGN_RIGHT));
        table.addCell(
                createBodyCell(
                        tx.targetAccountId() != null ? String.valueOf(tx.targetAccountId()) : "-",
                        smallFont,
                        Element.ALIGN_RIGHT));
        table.addCell(createBodyCell(formatMoney(tx.amount()), smallFont, Element.ALIGN_RIGHT));
        table.addCell(createBodyCell(formatMoney(tx.fee()), smallFont, Element.ALIGN_RIGHT));
        table.addCell(createBodyCell(nullSafe(tx.location()), smallFont, Element.ALIGN_LEFT));
        table.addCell(createBodyCell(nullSafe(tx.description()), smallFont, Element.ALIGN_LEFT));
        table.addCell(
                createBodyCell(
                        tx.createdAt() != null ? CREATED_AT_FORMATTER.format(tx.createdAt()) : "",
                        smallFont,
                        Element.ALIGN_LEFT));
    }

    private static PdfPCell createHeaderCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(HEADER_BG_COLOR);
        cell.setBorderColor(BORDER_COLOR);
        cell.setPadding(4f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private static PdfPCell createBodyCell(String text, Font font, int horizontalAlignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorderColor(BORDER_COLOR);
        cell.setPadding(3.5f);
        cell.setHorizontalAlignment(horizontalAlignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private static String formatMoney(BigDecimal value) {
        BigDecimal safeValue = value != null ? value : BigDecimal.ZERO;
        DecimalFormat df =
                new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return df.format(safeValue);
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    private static byte[] loadFontResource(String resourcePath) {
        try (InputStream in =
                PdfReportExporter.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Missing embedded PDF font resource on classpath: " + resourcePath);
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Failed to read embedded PDF font resource: " + resourcePath, ex);
        }
    }

    /** Per-export OpenPDF document and font state holder for {@link PdfReportExporter}. */
    protected record PdfExportContext(
            Document document,
            PdfWriter writer,
            Font titleFont,
            Font sectionFont,
            Font tableHeaderFont,
            Font bodyFont,
            Font smallFont) {}
}

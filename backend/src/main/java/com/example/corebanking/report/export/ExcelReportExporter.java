package com.example.corebanking.report.export;

import com.example.corebanking.report.api.dto.ReportExportCriteria;
import com.example.corebanking.report.api.dto.TransactionExportRow;
import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.application.ReportDataService;
import com.example.corebanking.report.domain.ExportFormat;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Streaming Excel ({@code .xlsx}) report exporter built on Apache POI {@link SXSSFWorkbook} with a
 * sliding 100-row window to bound JVM heap consumption when exporting up to 100,000 transaction
 * rows (L3-07, L3-08, G-05, FR-21, NFR-2, ADR-014).
 */
@Component
public class ExcelReportExporter extends ReportExporter<ExcelReportExporter.ExcelExportContext> {

    /** Sliding window size keeping at most 100 rows in heap memory before flushing to temp XML. */
    public static final int SXSSF_WINDOW_SIZE = 100;

    public static final String SHEET_SUMMARY = "Tong hop";
    public static final String SHEET_DETAILS = "Chi tiet";
    public static final String SHEET_CHARTS = "Bieu do";

    private static final DateTimeFormatter CREATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private static final String[] SUMMARY_HEADERS = {
        "periodStart",
        "txCount",
        "totalAmount",
        "averageAmount",
        "maxAmount",
        "minAmount",
        "totalFee"
    };

    private static final String[] DETAIL_HEADERS = {
        "referenceNo",
        "type",
        "status",
        "sourceAccountId",
        "targetAccountId",
        "amount",
        "fee",
        "location",
        "description",
        "createdAt"
    };

    public ExcelReportExporter(ReportDataService reportDataService, ChartRenderer chartRenderer) {
        super(reportDataService, chartRenderer);
    }

    @Override
    public ExportFormat supportedFormat() {
        return ExportFormat.XLSX;
    }

    @Override
    protected ExcelExportContext createContext(
            ReportExportCriteria criteria, OutputStream outputStream) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(SXSSF_WINDOW_SIZE);
        workbook.setCompressTempFiles(false);

        SXSSFSheet summarySheet = workbook.createSheet(SHEET_SUMMARY);
        SXSSFSheet detailsSheet = workbook.createSheet(SHEET_DETAILS);
        SXSSFSheet chartsSheet = workbook.createSheet(SHEET_CHARTS);

        configureFixedColumnWidths(summarySheet, detailsSheet, chartsSheet);

        CellStyle titleStyle = createBoldStyle(workbook, (short) 14);
        CellStyle headerStyle = createBoldStyle(workbook, (short) 11);
        CellStyle moneyStyle = createMoneyStyle(workbook);

        return new ExcelExportContext(
                workbook,
                summarySheet,
                detailsSheet,
                chartsSheet,
                titleStyle,
                headerStyle,
                moneyStyle);
    }

    @Override
    protected void writeHeader(ExcelExportContext context, ReportExportCriteria criteria) {
        Sheet summarySheet = context.summarySheet();

        Row titleRow = summarySheet.createRow(0);
        Cell titleCell = titleRow.createCell(0, CellType.STRING);
        titleCell.setCellValue(REPORT_TITLE);
        titleCell.setCellStyle(context.titleStyle());

        Row metaRow = summarySheet.createRow(1);
        Cell metaCell = metaRow.createCell(0, CellType.STRING);
        String typeLabel = criteria.type() != null ? criteria.type().name() : "ALL";
        metaCell.setCellValue(
                "Kỳ báo cáo: "
                        + criteria.period().name()
                        + " | Từ ngày: "
                        + criteria.from()
                        + " | Đến ngày: "
                        + criteria.to()
                        + " | Loại giao dịch: "
                        + typeLabel);
    }

    @Override
    protected void writeSummary(
            ExcelExportContext context,
            ReportExportCriteria criteria,
            List<TransactionReportBucketResponse> periodicBuckets) {
        Sheet summarySheet = context.summarySheet();

        Row headerRow = summarySheet.createRow(3);
        for (int i = 0; i < SUMMARY_HEADERS.length; i++) {
            Cell cell = headerRow.createCell(i, CellType.STRING);
            cell.setCellValue(SUMMARY_HEADERS[i]);
            cell.setCellStyle(context.headerStyle());
        }

        int rowIdx = 4;
        if (periodicBuckets != null) {
            for (TransactionReportBucketResponse bucket : periodicBuckets) {
                Row row = summarySheet.createRow(rowIdx++);
                row.createCell(0, CellType.STRING)
                        .setCellValue(
                                bucket.periodStart() != null
                                        ? bucket.periodStart().toString()
                                        : "");
                row.createCell(1, CellType.NUMERIC).setCellValue(bucket.txCount());
                setMoneyCell(row, 2, bucket.totalAmount(), context.moneyStyle());
                setMoneyCell(row, 3, bucket.averageAmount(), context.moneyStyle());
                setMoneyCell(row, 4, bucket.maxAmount(), context.moneyStyle());
                setMoneyCell(row, 5, bucket.minAmount(), context.moneyStyle());
                setMoneyCell(row, 6, bucket.totalFee(), context.moneyStyle());
            }
        }
    }

    @Override
    protected void writeCharts(ExcelExportContext context, ChartRenderer.RenderedCharts charts) {
        Sheet chartsSheet = context.chartsSheet();

        Row titleRow = chartsSheet.createRow(0);
        Cell titleCell = titleRow.createCell(0, CellType.STRING);
        titleCell.setCellValue(
                "Core Banking System — Biểu đồ xu hướng (Transaction Volume, Balance, Fee)");
        titleCell.setCellStyle(context.titleStyle());

        Drawing<?> drawing = chartsSheet.createDrawingPatriarch();
        CreationHelper helper = context.workbook().getCreationHelper();

        embedChartPng(
                context,
                chartsSheet,
                drawing,
                helper,
                ChartRenderer.TRANSACTION_VOLUME_TITLE,
                charts.transactionVolumePng(),
                1,
                2);
        embedChartPng(
                context,
                chartsSheet,
                drawing,
                helper,
                ChartRenderer.BALANCE_TREND_TITLE,
                charts.balanceTrendPng(),
                23,
                24);
        embedChartPng(
                context,
                chartsSheet,
                drawing,
                helper,
                ChartRenderer.FEE_TREND_TITLE,
                charts.feeTrendPng(),
                45,
                46);
    }

    @Override
    protected void writeDetails(
            ExcelExportContext context,
            ReportExportCriteria criteria,
            ReportDataService dataService) {
        Sheet detailsSheet = context.detailsSheet();

        Row headerRow = detailsSheet.createRow(0);
        for (int i = 0; i < DETAIL_HEADERS.length; i++) {
            Cell cell = headerRow.createCell(i, CellType.STRING);
            cell.setCellValue(DETAIL_HEADERS[i]);
            cell.setCellStyle(context.headerStyle());
        }

        AtomicInteger rowIdx = new AtomicInteger(1);
        dataService.streamTransactionsInPeriod(
                criteria.from(),
                criteria.to(),
                criteria.type(),
                row -> writeDetailRow(detailsSheet, rowIdx.getAndIncrement(), row, context));
    }

    @Override
    protected void finish(ExcelExportContext context, OutputStream outputStream)
            throws IOException {
        context.workbook().write(outputStream);
        outputStream.flush();
    }

    @Override
    protected void cleanup(ExcelExportContext context) {
        if (context == null || context.workbook() == null) {
            return;
        }
        try {
            context.workbook().dispose();
        } finally {
            try {
                context.workbook().close();
            } catch (IOException ignored) {
                // ignore close failure after temp file disposal
            }
        }
    }

    /**
     * Neutralizes spreadsheet formula injection (CSV/Excel injection) by prefixing a single quote
     * {@code '} whenever a text value starts with {@code =}, {@code +}, {@code -}, {@code @},
     * {@code \t}, or {@code \r}.
     *
     * @param value raw text value
     * @return safe text value for Excel string cells
     */
    public static String sanitizeFormulaCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '='
                || first == '+'
                || first == '-'
                || first == '@'
                || first == '\t'
                || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    private static void writeDetailRow(
            Sheet detailsSheet,
            int rowNumber,
            TransactionExportRow tx,
            ExcelExportContext context) {
        Row row = detailsSheet.createRow(rowNumber);
        row.createCell(0, CellType.STRING).setCellValue(sanitizeFormulaCell(tx.referenceNo()));
        row.createCell(1, CellType.STRING).setCellValue(sanitizeFormulaCell(tx.type()));
        row.createCell(2, CellType.STRING).setCellValue(sanitizeFormulaCell(tx.status()));

        Cell sourceCell = row.createCell(3);
        if (tx.sourceAccountId() != null) {
            sourceCell.setCellValue(tx.sourceAccountId());
        } else {
            sourceCell.setCellValue("");
        }

        Cell targetCell = row.createCell(4);
        if (tx.targetAccountId() != null) {
            targetCell.setCellValue(tx.targetAccountId());
        } else {
            targetCell.setCellValue("");
        }

        setMoneyCell(row, 5, tx.amount(), context.moneyStyle());
        setMoneyCell(row, 6, tx.fee(), context.moneyStyle());

        row.createCell(7, CellType.STRING).setCellValue(sanitizeFormulaCell(tx.location()));
        row.createCell(8, CellType.STRING).setCellValue(sanitizeFormulaCell(tx.description()));
        row.createCell(9, CellType.STRING)
                .setCellValue(
                        tx.createdAt() != null ? CREATED_AT_FORMATTER.format(tx.createdAt()) : "");
    }

    private static void embedChartPng(
            ExcelExportContext context,
            Sheet chartsSheet,
            Drawing<?> drawing,
            CreationHelper helper,
            String caption,
            byte[] pngBytes,
            int labelRowIdx,
            int anchorStartRow) {
        Row labelRow = chartsSheet.createRow(labelRowIdx);
        Cell labelCell = labelRow.createCell(0, CellType.STRING);
        labelCell.setCellValue(caption);
        labelCell.setCellStyle(context.headerStyle());

        if (pngBytes == null || pngBytes.length == 0) {
            return;
        }
        int pictureIdx = context.workbook().addPicture(pngBytes, Workbook.PICTURE_TYPE_PNG);
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setCol1(0);
        anchor.setRow1(anchorStartRow);
        anchor.setCol2(10);
        anchor.setRow2(anchorStartRow + 20);
        drawing.createPicture(anchor, pictureIdx);
    }

    private static void setMoneyCell(Row row, int colIdx, BigDecimal value, CellStyle moneyStyle) {
        Cell cell = row.createCell(colIdx, CellType.NUMERIC);
        cell.setCellValue(value != null ? value.doubleValue() : 0.0d);
        cell.setCellStyle(moneyStyle);
    }

    private static void configureFixedColumnWidths(
            Sheet summarySheet, Sheet detailsSheet, Sheet chartsSheet) {
        for (int i = 0; i < SUMMARY_HEADERS.length; i++) {
            summarySheet.setColumnWidth(i, 20 * 256);
        }
        int[] detailWidths = {22, 14, 14, 16, 16, 18, 14, 20, 32, 22};
        for (int i = 0; i < detailWidths.length; i++) {
            detailsSheet.setColumnWidth(i, detailWidths[i] * 256);
        }
        chartsSheet.setColumnWidth(0, 50 * 256);
    }

    private static CellStyle createBoldStyle(Workbook workbook, short fontPoints) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints(fontPoints);
        style.setFont(font);
        return style;
    }

    private static CellStyle createMoneyStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        DataFormat format = workbook.getCreationHelper().createDataFormat();
        style.setDataFormat(format.getFormat("#,##0.00"));
        return style;
    }

    /** Per-export state holder for {@link ExcelReportExporter}. */
    protected record ExcelExportContext(
            SXSSFWorkbook workbook,
            SXSSFSheet summarySheet,
            SXSSFSheet detailsSheet,
            SXSSFSheet chartsSheet,
            CellStyle titleStyle,
            CellStyle headerStyle,
            CellStyle moneyStyle) {}
}

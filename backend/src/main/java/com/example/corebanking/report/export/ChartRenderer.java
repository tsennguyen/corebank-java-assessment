package com.example.corebanking.report.export;

import com.example.corebanking.report.api.dto.TrendPointResponse;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.StandardChartTheme;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.time.Day;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.springframework.stereotype.Component;

/**
 * Headless JFreeChart renderer producing 800x400 PNG trend charts (Transaction Volume, Closing
 * Balance, and Fee Revenue) for embedding into Excel and PDF financial reports (L3-08, FR-20,
 * FR-21, ADR-014).
 */
@Component
public class ChartRenderer {

    /** Standard chart image width in pixels (Architecture §3.12). */
    public static final int DEFAULT_WIDTH = 800;

    /** Standard chart image height in pixels (Architecture §3.12). */
    public static final int DEFAULT_HEIGHT = 400;

    /** Generic system title prefix strictly compliant with E-02 (no real bank names). */
    public static final String TRANSACTION_VOLUME_TITLE =
            "Core Banking System - Transaction Volume Trend (VND)";

    public static final String BALANCE_TREND_TITLE = "Core Banking System - Balance Trend (VND)";

    public static final String FEE_TREND_TITLE = "Core Banking System - Fee Revenue Trend (VND)";

    private static final Color VOLUME_LINE_COLOR = new Color(30, 102, 245);
    private static final Color BALANCE_LINE_COLOR = new Color(16, 152, 104);
    private static final Color FEE_LINE_COLOR = new Color(217, 119, 6);
    private static final Color GRID_LINE_COLOR = new Color(226, 232, 240);
    private static final Color PLOT_BG_COLOR = new Color(248, 250, 252);

    static {
        System.setProperty("java.awt.headless", "true");
        ChartFactory.setChartTheme(StandardChartTheme.createLegacyTheme());
    }

    /**
     * Bundle of the 3 rendered PNG chart byte arrays ready for Excel/PDF embedding.
     *
     * @param transactionVolumePng 800x400 PNG bytes for transaction volume trend
     * @param balanceTrendPng 800x400 PNG bytes for closing balance trend
     * @param feeTrendPng 800x400 PNG bytes for fee revenue trend
     */
    public record RenderedCharts(
            byte[] transactionVolumePng, byte[] balanceTrendPng, byte[] feeTrendPng) {}

    /**
     * Renders all 3 trend charts (Transaction Volume, Balance, and Fee) from the given trend
     * points.
     *
     * @param points chronological list of trend points
     * @return {@link RenderedCharts} containing three 800x400 PNG byte arrays
     */
    public RenderedCharts renderAllCharts(List<TrendPointResponse> points) {
        return new RenderedCharts(
                renderTransactionVolumeChart(points),
                renderBalanceTrendChart(points),
                renderFeeTrendChart(points));
    }

    /**
     * Renders the transaction volume trend chart (total transaction amount per bucket in VND) as an
     * 800x400 PNG byte array.
     *
     * @param points chronological list of trend points
     * @return PNG image bytes (800x400)
     */
    public byte[] renderTransactionVolumeChart(List<TrendPointResponse> points) {
        return renderTimeSeriesPng(
                TRANSACTION_VOLUME_TITLE,
                "Transaction Volume (VND)",
                "Volume (VND)",
                points,
                TrendPointResponse::totalAmount,
                VOLUME_LINE_COLOR);
    }

    /**
     * Renders the closing balance trend chart (reconstructed balance per bucket in VND) as an
     * 800x400 PNG byte array.
     *
     * @param points chronological list of trend points
     * @return PNG image bytes (800x400)
     */
    public byte[] renderBalanceTrendChart(List<TrendPointResponse> points) {
        return renderTimeSeriesPng(
                BALANCE_TREND_TITLE,
                "Closing Balance (VND)",
                "Closing Balance (VND)",
                points,
                TrendPointResponse::closingBalance,
                BALANCE_LINE_COLOR);
    }

    /**
     * Renders the fee revenue trend chart (total fee collected per bucket in VND) as an 800x400 PNG
     * byte array.
     *
     * @param points chronological list of trend points
     * @return PNG image bytes (800x400)
     */
    public byte[] renderFeeTrendChart(List<TrendPointResponse> points) {
        return renderTimeSeriesPng(
                FEE_TREND_TITLE,
                "Fee Revenue (VND)",
                "Fee (VND)",
                points,
                TrendPointResponse::totalFee,
                FEE_LINE_COLOR);
    }

    private byte[] renderTimeSeriesPng(
            String chartTitle,
            String seriesName,
            String yAxisLabel,
            List<TrendPointResponse> points,
            Function<TrendPointResponse, BigDecimal> valueExtractor,
            Color lineColor) {
        TimeSeries series = new TimeSeries(seriesName);
        if (points != null) {
            for (TrendPointResponse point : points) {
                if (point != null && point.bucket() != null) {
                    LocalDate d = point.bucket();
                    BigDecimal val = valueExtractor.apply(point);
                    double numericValue = val != null ? val.doubleValue() : 0.0d;
                    series.addOrUpdate(
                            new Day(d.getDayOfMonth(), d.getMonthValue(), d.getYear()),
                            numericValue);
                }
            }
        }

        TimeSeriesCollection dataset = new TimeSeriesCollection();
        dataset.addSeries(series);

        JFreeChart chart =
                ChartFactory.createTimeSeriesChart(
                        chartTitle, "Date", yAxisLabel, dataset, true, false, false);

        styleChart(chart, lineColor);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream(16_384)) {
            ChartUtils.writeChartAsPNG(out, chart, DEFAULT_WIDTH, DEFAULT_HEIGHT);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to render JFreeChart PNG: " + chartTitle, ex);
        }
    }

    private void styleChart(JFreeChart chart, Color lineColor) {
        chart.setAntiAlias(true);
        chart.setBackgroundPaint(Color.WHITE);
        if (chart.getTitle() != null) {
            chart.getTitle().setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        }

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(PLOT_BG_COLOR);
        plot.setDomainGridlinePaint(GRID_LINE_COLOR);
        plot.setRangeGridlinePaint(GRID_LINE_COLOR);
        plot.setNoDataMessage("No data in selected period");
        plot.setNoDataMessageFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));

        if (plot.getDomainAxis() instanceof DateAxis dateAxis) {
            dateAxis.setDateFormatOverride(new SimpleDateFormat("yyyy-MM-dd"));
            dateAxis.setLabelFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            dateAxis.setTickLabelFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        }

        if (plot.getRangeAxis() instanceof NumberAxis numberAxis) {
            numberAxis.setNumberFormatOverride(new DecimalFormat("#,##0"));
            numberAxis.setAutoRangeIncludesZero(true);
            numberAxis.setLabelFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            numberAxis.setTickLabelFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        }

        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, true);
        renderer.setSeriesPaint(0, lineColor);
        renderer.setSeriesStroke(0, new BasicStroke(2.2f));
        plot.setRenderer(renderer);
    }
}

package com.example.corebanking.report.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.corebanking.report.api.dto.TrendPointResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChartRendererTest {

    private final ChartRenderer chartRenderer = new ChartRenderer();

    @Test
    @DisplayName(
            "renderAllCharts produces three valid 800x400 PNG images in headless mode (AC 3,"
                    + " L3-08)")
    void renderAllThreeCharts_shouldProduceValid800x400PngImagesInHeadlessMode()
            throws IOException {
        List<TrendPointResponse> points =
                List.of(
                        new TrendPointResponse(
                                LocalDate.of(2026, 10, 1),
                                5L,
                                new BigDecimal("25000000.00"),
                                new BigDecimal("10000.00"),
                                new BigDecimal("500000000.00")),
                        new TrendPointResponse(
                                LocalDate.of(2026, 10, 2),
                                0L,
                                new BigDecimal("0.00"),
                                new BigDecimal("0.00"),
                                new BigDecimal("500000000.00")),
                        new TrendPointResponse(
                                LocalDate.of(2026, 10, 3),
                                8L,
                                new BigDecimal("64000000.00"),
                                new BigDecimal("22000.00"),
                                new BigDecimal("542000000.00")));

        ChartRenderer.RenderedCharts rendered = chartRenderer.renderAllCharts(points);

        assertValid800x400Png(rendered.transactionVolumePng());
        assertValid800x400Png(rendered.balanceTrendPng());
        assertValid800x400Png(rendered.feeTrendPng());
        assertThat(System.getProperty("java.awt.headless")).isEqualTo("true");
        assertThat(ChartRenderer.TRANSACTION_VOLUME_TITLE).startsWith("Core Banking System");
        assertThat(ChartRenderer.BALANCE_TREND_TITLE).startsWith("Core Banking System");
        assertThat(ChartRenderer.FEE_TREND_TITLE).startsWith("Core Banking System");
    }

    @Test
    @DisplayName(
            "renderCharts handles empty and null trend point lists gracefully and still outputs"
                    + " 800x400 PNGs")
    void renderCharts_whenEmptyPoints_shouldStillProduceValid800x400PngImage() throws IOException {
        assertValid800x400Png(chartRenderer.renderTransactionVolumeChart(List.of()));
        assertValid800x400Png(chartRenderer.renderBalanceTrendChart(List.of()));
        assertValid800x400Png(chartRenderer.renderFeeTrendChart(null));
    }

    private static void assertValid800x400Png(byte[] pngBytes) throws IOException {
        assertThat(pngBytes).isNotNull().hasSizeGreaterThan(100);
        // Verify PNG magic signature: 89 50 4E 47 0D 0A 1A 0A
        assertThat(pngBytes[0]).isEqualTo((byte) 0x89);
        assertThat(pngBytes[1]).isEqualTo((byte) 0x50);
        assertThat(pngBytes[2]).isEqualTo((byte) 0x4E);
        assertThat(pngBytes[3]).isEqualTo((byte) 0x47);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(ChartRenderer.DEFAULT_WIDTH);
        assertThat(image.getHeight()).isEqualTo(ChartRenderer.DEFAULT_HEIGHT);
    }
}

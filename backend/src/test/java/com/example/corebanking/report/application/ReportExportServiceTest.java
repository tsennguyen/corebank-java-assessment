package com.example.corebanking.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.report.api.dto.ReportExportCriteria;
import com.example.corebanking.report.domain.ExportFormat;
import com.example.corebanking.report.domain.ReportPeriod;
import com.example.corebanking.report.export.ReportExporter;
import com.example.corebanking.transaction.domain.TransactionType;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportExportServiceTest {

    @Mock private ReportService reportService;
    @Mock private ReportExporter<Object> xlsxExporter;

    private ReportExportService reportExportService;

    @BeforeEach
    void setUp() {
        when(xlsxExporter.supportedFormat()).thenReturn(ExportFormat.XLSX);
        reportExportService = new ReportExportService(reportService, List.of(xlsxExporter));
    }

    @Test
    @DisplayName("validateAndBuildCriteria builds valid criteria and formats filename")
    void validateAndBuildCriteria_whenValid_shouldReturnCriteria() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);

        ReportExportCriteria criteria =
                reportExportService.validateAndBuildCriteria(
                        ExportFormat.XLSX, ReportPeriod.QUARTER, from, to, TransactionType.DEPOSIT);

        verify(reportService).validateDateRange(from, to);
        assertThat(criteria.format()).isEqualTo(ExportFormat.XLSX);
        assertThat(criteria.period()).isEqualTo(ReportPeriod.QUARTER);
        assertThat(criteria.from()).isEqualTo(from);
        assertThat(criteria.to()).isEqualTo(to);
        assertThat(criteria.type()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(criteria.buildFilename())
                .isEqualTo("core-banking-report-quarter-2026-01-01-2026-06-30.xlsx");
    }

    @Test
    @DisplayName(
            "validateAndBuildCriteria rejects null format, null period, REVERSAL type, or"
                    + " unregistered format")
    void validateAndBuildCriteria_whenInvalidInputs_shouldThrowValidationFailed() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);

        assertThatThrownBy(
                        () ->
                                reportExportService.validateAndBuildCriteria(
                                        null, ReportPeriod.QUARTER, from, to, null))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(
                        () ->
                                reportExportService.validateAndBuildCriteria(
                                        ExportFormat.XLSX, null, from, to, null))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(
                        () ->
                                reportExportService.validateAndBuildCriteria(
                                        ExportFormat.XLSX,
                                        ReportPeriod.QUARTER,
                                        from,
                                        to,
                                        TransactionType.REVERSAL))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(
                        () ->
                                reportExportService.validateAndBuildCriteria(
                                        ExportFormat.PDF, ReportPeriod.QUARTER, from, to, null))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("export dispatches to registered exporter or throws when missing")
    void export_shouldDispatchToRegisteredExporterOrThrow() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        ReportExportCriteria xlsxCriteria =
                new ReportExportCriteria(ExportFormat.XLSX, ReportPeriod.QUARTER, from, to, null);
        OutputStream out = new ByteArrayOutputStream();

        reportExportService.export(xlsxCriteria, out);
        verify(xlsxExporter).export(xlsxCriteria, out);

        ReportExportCriteria pdfCriteria =
                new ReportExportCriteria(ExportFormat.PDF, ReportPeriod.QUARTER, from, to, null);
        assertThatThrownBy(() -> reportExportService.export(pdfCriteria, out))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}

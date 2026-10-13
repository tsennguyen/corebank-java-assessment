package com.example.corebanking.report.api;

import com.example.corebanking.report.api.dto.BalanceTierStatisticResponse;
import com.example.corebanking.report.api.dto.LocationStatisticResponse;
import com.example.corebanking.report.application.StatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller exposing aggregated banking statistics (L1-09, L1-10, FR-11, FR-12). */
@RestController
@RequestMapping("/api/v1/statistics")
@RequiredArgsConstructor
@Tag(
        name = "Statistics",
        description = "Aggregated statistics for balance tiers and customer locations")
public class StatisticsController {

    private final StatisticsService statisticsService;

    /**
     * Retrieves account and transaction counts grouped by balance tier (LOW, MEDIUM, HIGH).
     *
     * @return ordered list of 3 balance tier statistics
     */
    @GetMapping("/balance-tiers")
    @Operation(
            summary = "Get account and transaction statistics by balance tier",
            description =
                    "Aggregates non-closed account counts and total associated transactions across"
                            + " LOW, MEDIUM, and HIGH balance tiers using configured thresholds.")
    @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully")
    public ResponseEntity<List<BalanceTierStatisticResponse>> getBalanceTierStatistics() {
        return ResponseEntity.ok(statisticsService.getBalanceTierStatistics());
    }

    /**
     * Retrieves active customer counts grouped by geographical location in descending order.
     *
     * @return list of location statistics
     */
    @GetMapping("/customers-by-location")
    @Operation(
            summary = "Get active customer count grouped by location",
            description =
                    "Counts active (non-soft-deleted) customers per location, sorted descending by"
                            + " customer count.")
    @ApiResponse(responseCode = "200", description = "Location statistics retrieved successfully")
    public ResponseEntity<List<LocationStatisticResponse>> getCustomersByLocation() {
        return ResponseEntity.ok(statisticsService.getCustomersByLocation());
    }
}

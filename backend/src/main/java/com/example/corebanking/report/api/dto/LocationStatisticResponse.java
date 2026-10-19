package com.example.corebanking.report.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response DTO representing active customer count grouped by geographical location. */
@Schema(description = "Active customer count aggregated by geographical location")
public record LocationStatisticResponse(
        @Schema(description = "Geographical location (city/province)", example = "Hà Nội")
                String location,
        @Schema(description = "Number of active (non-deleted) customers", example = "8")
                long customerCount) {}

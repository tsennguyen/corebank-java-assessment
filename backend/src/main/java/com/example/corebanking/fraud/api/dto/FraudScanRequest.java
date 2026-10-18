package com.example.corebanking.fraud.api.dto;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO triggering a historical fraud scan across a time window (L3-03, FR-22).
 *
 * @param from inclusive start boundary (ISO-8601 Instant or LocalDate YYYY-MM-DD)
 * @param to inclusive end boundary (ISO-8601 Instant or LocalDate YYYY-MM-DD)
 */
@Schema(
        description =
                "Yêu cầu quét lịch sử giao dịch bất thường theo khoảng thời gian (L3-03, FR-22)")
public record FraudScanRequest(
        @NotBlank(message = "from must not be blank")
                @JsonAlias("fromDate")
                @Schema(
                        description =
                                "Mốc thời gian bắt đầu quét (ISO-8601 Instant hoặc LocalDate"
                                        + " YYYY-MM-DD)",
                        example = "2026-10-01T00:00:00Z")
                String from,
        @NotBlank(message = "to must not be blank")
                @JsonAlias("toDate")
                @Schema(
                        description =
                                "Mốc thời gian kết thúc quét (ISO-8601 Instant hoặc LocalDate"
                                        + " YYYY-MM-DD)",
                        example = "2026-10-18T23:59:59Z")
                String to) {

    @JsonAnySetter
    public void rejectUnknownProperty(String key, Object value) {
        throw new BusinessRuleException(
                ErrorCode.VALIDATION_FAILED, "Unknown request field: " + key);
    }
}

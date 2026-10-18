package com.example.corebanking.fraud.api.dto;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.fraud.domain.AlertStatus;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for transitioning a fraud alert's lifecycle status (L3-04, FR-23).
 *
 * @param targetStatus target alert status ({@code INVESTIGATING}, {@code RESOLVED}, {@code
 *     FALSE_POSITIVE})
 * @param note optional investigation or resolution note (up to 500 characters)
 */
@Schema(description = "Yêu cầu chuyển trạng thái xử lý cảnh báo (L3-04, FR-23)")
public record UpdateAlertStatusRequest(
        @NotNull(message = "targetStatus must not be null")
                @Schema(
                        description = "Trạng thái đích (INVESTIGATING, RESOLVED, FALSE_POSITIVE)",
                        example = "INVESTIGATING")
                AlertStatus targetStatus,
        @JsonAlias("reason")
                @Size(max = 500, message = "note must not exceed 500 characters")
                @Schema(
                        description = "Ghi chú điều tra hoặc kết luận xử lý",
                        example = "Checking customer call log")
                String note) {

    @JsonAnySetter
    public void rejectUnknownProperty(String key, Object value) {
        throw new BusinessRuleException(
                ErrorCode.VALIDATION_FAILED, "Unknown request field: " + key);
    }
}

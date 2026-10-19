package com.example.corebanking.account.api.dto;

import com.example.corebanking.account.domain.AccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request payload for transitioning an account's lifecycle status (L2-05, FR-3). */
@Schema(description = "Yêu cầu chuyển trạng thái tài khoản (ACTIVE, LOCKED, CLOSED)")
public record ChangeAccountStatusRequest(
        @Schema(
                        description = "Trạng thái đích muốn chuyển đến",
                        example = "LOCKED",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull(message = "targetStatus must not be null")
                AccountStatus targetStatus,
        @Schema(
                        description = "Lý do chuyển trạng thái (tối đa 500 ký tự)",
                        example = "Phong tỏa tạm thời để xác minh giao dịch bất thường",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "reason must not be blank")
                @Size(max = 500, message = "reason must not exceed 500 characters")
                String reason) {}

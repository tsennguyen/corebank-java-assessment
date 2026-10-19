package com.example.corebanking.transaction.api.dto;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.transaction.domain.TransactionType;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request payload for patching transaction metadata (description and location). Financial and
 * relational attributes are strictly immutable.
 */
@Schema(description = "Yêu cầu cập nhật thông tin mô tả và địa điểm của giao dịch")
public record UpdateTransactionRequest(
        @Schema(
                        description = "Nội dung mô tả mới của giao dịch",
                        example = "Nộp tiền mặt tại quầy cập nhật")
                @Size(max = 500, message = "Description must not exceed 500 characters")
                String description,
        @Schema(description = "Địa điểm thực hiện mới", example = "Chi nhánh Đà Nẵng")
                @Size(max = 255, message = "Location must not exceed 255 characters")
                String location,
        @Schema(description = "Trường bất biến (cấm sửa đổi)", hidden = true) BigDecimal amount,
        @Schema(description = "Trường bất biến (cấm sửa đổi)", hidden = true) BigDecimal fee,
        @Schema(description = "Trường bất biến (cấm sửa đổi)", hidden = true) Long accountId,
        @Schema(description = "Trường bất biến (cấm sửa đổi)", hidden = true) Long sourceAccountId,
        @Schema(description = "Trường bất biến (cấm sửa đổi)", hidden = true) Long targetAccountId,
        @Schema(description = "Trường bất biến (cấm sửa đổi)", hidden = true) TransactionType type,
        @Schema(description = "Trường bất biến (cấm sửa đổi)", hidden = true) String referenceNo,
        @JsonIgnore Map<String, Object> additionalImmutableFields) {

    public UpdateTransactionRequest {
        if (additionalImmutableFields == null) {
            additionalImmutableFields = new LinkedHashMap<>();
        }
    }

    public UpdateTransactionRequest(String description, String location) {
        this(
                description,
                location,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new LinkedHashMap<>());
    }

    public UpdateTransactionRequest(
            String description,
            String location,
            BigDecimal amount,
            BigDecimal fee,
            Long accountId,
            Long sourceAccountId,
            Long targetAccountId,
            TransactionType type,
            String referenceNo) {
        this(
                description,
                location,
                amount,
                fee,
                accountId,
                sourceAccountId,
                targetAccountId,
                type,
                referenceNo,
                new LinkedHashMap<>());
    }

    @JsonAnySetter
    public void captureImmutableOrUnknownField(String name, Object value) {
        additionalImmutableFields.put(name, value);
    }

    /** Validates that no immutable fields were provided in the update payload. */
    public void validateImmutability() {
        if (amount != null
                || fee != null
                || accountId != null
                || sourceAccountId != null
                || targetAccountId != null
                || type != null
                || referenceNo != null
                || !additionalImmutableFields.isEmpty()) {
            throw new BusinessRuleException(
                    ErrorCode.IMMUTABLE_FIELD,
                    "Transaction financial, status, and identity attributes are immutable");
        }
    }
}

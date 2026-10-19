package com.example.corebanking.transaction.api.dto;

import com.example.corebanking.transaction.domain.TransactionStatus;
import com.example.corebanking.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Criteria object for filtering transactions across multiple dimensions (L2-03, FR-8). */
@Schema(description = "Tiêu chí tìm kiếm và lọc giao dịch nâng cao")
public record TransactionSearchCriteria(
        @Schema(description = "Lọc theo ID tài khoản (nguồn hoặc đích)", example = "1")
                Long accountId,
        @Schema(description = "Lọc theo loại giao dịch", example = "TRANSFER") TransactionType type,
        @Schema(description = "Lọc theo trạng thái giao dịch", example = "COMPLETED")
                TransactionStatus status,
        @Schema(description = "Số tiền giao dịch tối thiểu (VND)", example = "1000000")
                BigDecimal minAmount,
        @Schema(description = "Số tiền giao dịch tối đa (VND)", example = "5000000")
                BigDecimal maxAmount,
        @Schema(
                        description = "Từ ngày (YYYY-MM-DD, tính theo múi giờ Asia/Ho_Chi_Minh)",
                        example = "2026-09-01")
                LocalDate fromDate,
        @Schema(
                        description =
                                "Đến hết ngày (YYYY-MM-DD, bao gồm trọn ngày theo múi giờ"
                                        + " Asia/Ho_Chi_Minh)",
                        example = "2026-09-30")
                LocalDate toDate) {

    /** Convenience constructor for basic account and type filtering. */
    public TransactionSearchCriteria(Long accountId, TransactionType type) {
        this(accountId, type, null, null, null, null, null);
    }
}

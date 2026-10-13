package com.example.corebanking.transaction.api.dto;

import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.domain.TransactionStatus;
import com.example.corebanking.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/** Response representation of a financial transaction. */
@Schema(description = "Thông tin chi tiết giao dịch ngân hàng")
public record TransactionResponse(
        @Schema(description = "ID nội bộ của giao dịch", example = "1") Long id,
        @Schema(
                        description = "Mã tham chiếu duy nhất của giao dịch",
                        example = "TXN-20261012-ABC123DEF456")
                String referenceNo,
        @Schema(description = "Loại giao dịch", example = "DEPOSIT") TransactionType type,
        @Schema(description = "Trạng thái giao dịch", example = "COMPLETED")
                TransactionStatus status,
        @Schema(description = "Số tiền giao dịch", example = "1500000.00") BigDecimal amount,
        @Schema(description = "Phí giao dịch", example = "0.00") BigDecimal fee,
        @Schema(description = "ID tài khoản nguồn (null nếu là DEPOSIT)", example = "null")
                Long sourceAccountId,
        @Schema(description = "ID tài khoản đích", example = "1") Long targetAccountId,
        @Schema(description = "Số dư tài khoản nguồn sau giao dịch", example = "null")
                BigDecimal sourceBalanceAfter,
        @Schema(description = "Số dư tài khoản đích sau giao dịch", example = "1500000.00")
                BigDecimal targetBalanceAfter,
        @Schema(description = "Biến động số dư toàn hệ thống (có dấu)", example = "1500000.00")
                BigDecimal systemBalanceDelta,
        @Schema(description = "Địa điểm thực hiện giao dịch", example = "Hà Nội") String location,
        @Schema(description = "Nội dung mô tả giao dịch", example = "Nạp tiền ban đầu")
                String description,
        @Schema(
                        description = "Thời điểm khởi tạo giao dịch (UTC)",
                        example = "2026-10-12T14:30:00Z")
                Instant createdAt) {

    /** Maps a Transaction JPA domain entity to its corresponding API response DTO. */
    public static TransactionResponse from(Transaction entity) {
        return new TransactionResponse(
                entity.getId(),
                entity.getReferenceNo(),
                entity.getType(),
                entity.getStatus(),
                entity.getAmount(),
                entity.getFee(),
                entity.getSourceAccountId(),
                entity.getTargetAccountId(),
                entity.getSourceBalanceAfter(),
                entity.getTargetBalanceAfter(),
                entity.getSystemBalanceDelta(),
                entity.getLocation(),
                entity.getDescription(),
                entity.getCreatedAt());
    }
}

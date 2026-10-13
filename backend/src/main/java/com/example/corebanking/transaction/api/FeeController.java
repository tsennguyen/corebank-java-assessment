package com.example.corebanking.transaction.api;

import com.example.corebanking.common.domain.Money;
import com.example.corebanking.transaction.api.dto.FeePreviewRequest;
import com.example.corebanking.transaction.api.dto.FeePreviewResponse;
import com.example.corebanking.transaction.domain.FeePolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST Controller providing fee preview and calculation services. */
@RestController
@RequestMapping("/api/v1/fees")
@Tag(name = "Fee Policy", description = "Chính sách và tra cứu biểu phí giao dịch")
public class FeeController {

    private final FeePolicy feePolicy;

    public FeeController(FeePolicy feePolicy) {
        this.feePolicy = feePolicy;
    }

    @GetMapping("/preview")
    @Operation(
            summary = "Xem trước phí giao dịch",
            description =
                    "Tính toán số tiền phí áp dụng cho loại giao dịch và số tiền tương ứng mà không"
                            + " tạo giao dịch thực tế.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Tính toán phí thành công",
                content = @Content(schema = @Schema(implementation = FeePreviewResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Tham số không hợp lệ (số tiền âm, sai định dạng, loại giao dịch không"
                                + " đúng)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<FeePreviewResponse> previewFee(
            @Valid @ParameterObject @ModelAttribute FeePreviewRequest request) {
        Money amountMoney = Money.of(request.amount());
        Money feeMoney = feePolicy.calculateFee(request.type(), amountMoney);
        return ResponseEntity.ok(
                new FeePreviewResponse(
                        request.type(), amountMoney.getAmount(), feeMoney.getAmount()));
    }
}

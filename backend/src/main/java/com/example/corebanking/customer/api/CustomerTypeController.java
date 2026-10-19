package com.example.corebanking.customer.api;

import com.example.corebanking.customer.api.dto.CustomerTypeResponse;
import com.example.corebanking.customer.api.dto.UpdateCustomerTypeRequest;
import com.example.corebanking.customer.application.CustomerTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller exposing CustomerType classification listing and limit configuration endpoints
 * for administrators (L3-05, L3-06, FR-18).
 */
@RestController
@RequestMapping("/api/v1/customer-types")
@Tag(
        name = "CustomerType",
        description = "Quản lý phân loại khách hàng (Cá nhân / Doanh nghiệp) và hạn mức tối đa")
public class CustomerTypeController {

    private final CustomerTypeService customerTypeService;

    public CustomerTypeController(CustomerTypeService customerTypeService) {
        this.customerTypeService = customerTypeService;
    }

    @GetMapping
    @Operation(
            summary = "Danh sách loại khách hàng và hạn mức tối đa",
            description =
                    "Trả về danh sách các phân loại khách hàng (INDIVIDUAL, BUSINESS) cùng hạn mức"
                            + " giao dịch tối đa. Kết quả được lưu cache Redis 1 giờ.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Lấy danh sách loại khách hàng thành công",
                content =
                        @Content(
                                array =
                                        @ArraySchema(
                                                schema =
                                                        @Schema(
                                                                implementation =
                                                                        CustomerTypeResponse
                                                                                .class))))
    })
    public ResponseEntity<List<CustomerTypeResponse>> getAllCustomerTypes() {
        return ResponseEntity.ok(customerTypeService.getAllCustomerTypes());
    }

    @GetMapping("/{code}")
    @Operation(
            summary = "Chi tiết loại khách hàng theo mã",
            description = "Truy vấn thông tin và hạn mức tối đa của một loại khách hàng theo mã.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Lấy thông tin loại khách hàng thành công",
                content = @Content(schema = @Schema(implementation = CustomerTypeResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy loại khách hàng",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<CustomerTypeResponse> getCustomerTypeByCode(
            @Parameter(description = "Mã loại khách hàng", example = "INDIVIDUAL") @PathVariable
                    String code) {
        return ResponseEntity.ok(customerTypeService.getCustomerTypeByCode(code));
    }

    @PutMapping("/{code}")
    @Operation(
            summary = "Cập nhật hạn mức tối đa theo loại khách hàng",
            description =
                    "Cập nhật hạn mức mỗi giao dịch và hạn mức ngày tối đa cho loại khách hàng, xóa"
                            + " cache customerTypes ngay lập tức.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Cập nhật hạn mức loại khách hàng thành công",
                content = @Content(schema = @Schema(implementation = CustomerTypeResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Dữ liệu không hợp lệ (hạn mức <= 0 hoặc hạn mức ngày < hạn mức giao dịch)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy loại khách hàng",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Xung đột phiên bản dữ liệu (Concurrent Modification)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<CustomerTypeResponse> updateCustomerType(
            @Parameter(description = "Mã loại khách hàng", example = "INDIVIDUAL") @PathVariable
                    String code,
            @Valid @RequestBody UpdateCustomerTypeRequest request) {
        return ResponseEntity.ok(customerTypeService.updateCustomerType(code, request));
    }
}

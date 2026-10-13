package com.example.corebanking.customer.api;

import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.customer.api.dto.CreateCustomerRequest;
import com.example.corebanking.customer.api.dto.CustomerResponse;
import com.example.corebanking.customer.api.dto.UpdateCustomerRequest;
import com.example.corebanking.customer.application.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** REST Controller exposing Customer management operations. */
@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customer", description = "Quản lý thông tin hồ sơ và tìm kiếm khách hàng")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @Operation(
            summary = "Tạo mới khách hàng",
            description =
                    "Tạo hồ sơ khách hàng mới, sinh mã CUSxxxxxxx và che mờ CCCD trong dữ liệu trả"
                            + " về.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Khách hàng được tạo thành công",
                headers =
                        @Header(
                                name = "Location",
                                description = "URI tài nguyên khách hàng vừa tạo",
                                schema = @Schema(type = "string")),
                content = @Content(schema = @Schema(implementation = CustomerResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Dữ liệu yêu cầu không hợp lệ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "CCCD hoặc tài nguyên đã tồn tại",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<CustomerResponse> createCustomer(
            @Valid @RequestBody CreateCustomerRequest request) {
        CustomerResponse response = customerService.createCustomer(request);
        URI location =
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.id())
                        .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Lấy chi tiết khách hàng",
            description = "Truy vấn thông tin chi tiết một khách hàng theo ID nếu chưa bị xóa mềm.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Lấy thông tin khách hàng thành công",
                content = @Content(schema = @Schema(implementation = CustomerResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy khách hàng",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<CustomerResponse> getCustomerById(
            @Parameter(description = "ID nội bộ của khách hàng", example = "1") @PathVariable
                    Long id) {
        return ResponseEntity.ok(customerService.getCustomerById(id));
    }

    @GetMapping
    @Operation(
            summary = "Tìm kiếm và phân trang khách hàng",
            description =
                    "Tìm kiếm khách hàng theo tên và địa điểm, hỗ trợ phân trang và sắp xếp có"
                            + " whitelist.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Danh sách khách hàng phân trang thành công",
                content = @Content(schema = @Schema(implementation = PageResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Trường sắp xếp không hợp lệ hoặc kích thước trang vượt ngưỡng 100",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<PageResponse<CustomerResponse>> searchCustomers(
            @Parameter(description = "Lọc theo tên (không phân biệt hoa thường)", example = "van")
                    @RequestParam(required = false)
                    String name,
            @Parameter(description = "Lọc theo địa điểm tỉnh/thành", example = "Ha Noi")
                    @RequestParam(required = false)
                    String location,
            @ParameterObject
                    @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return ResponseEntity.ok(customerService.searchCustomers(name, location, pageable));
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Cập nhật thông tin khách hàng",
            description =
                    "Cập nhật thông tin khách hàng với cơ chế khóa lạc quan (optimistic locking)"
                            + " qua trường version.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Cập nhật thành công",
                content = @Content(schema = @Schema(implementation = CustomerResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Dữ liệu cập nhật không hợp lệ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy khách hàng",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Xung đột phiên bản dữ liệu (Concurrent Modification)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<CustomerResponse> updateCustomer(
            @Parameter(description = "ID nội bộ của khách hàng", example = "1") @PathVariable
                    Long id,
            @Valid @RequestBody UpdateCustomerRequest request) {
        return ResponseEntity.ok(customerService.updateCustomer(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Xóa mềm khách hàng",
            description = "Đánh dấu xóa mềm khách hàng, bảo toàn toàn vẹn lịch sử giao dịch.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Xóa mềm thành công"),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy khách hàng",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Void> deleteCustomer(
            @Parameter(description = "ID nội bộ của khách hàng", example = "1") @PathVariable
                    Long id) {
        customerService.deleteCustomer(id);
        return ResponseEntity.noContent().build();
    }
}

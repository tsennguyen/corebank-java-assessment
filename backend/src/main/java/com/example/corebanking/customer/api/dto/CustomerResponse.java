package com.example.corebanking.customer.api.dto;

import com.example.corebanking.common.util.MaskingUtils;
import com.example.corebanking.customer.domain.Customer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

/** Customer response representation with sensitive national identity card masked. */
@Schema(description = "Customer details response")
public record CustomerResponse(
        @Schema(description = "Internal database ID", example = "1") Long id,
        @Schema(description = "Unique business customer code", example = "CUS0000001")
                String customerCode,
        @Schema(description = "Full name", example = "Nguyen Van An") String fullName,
        @Schema(
                        description =
                                "Masked national identity number (only last 3 digits visible)",
                        example = "*********789")
                String nationalId,
        @Schema(description = "Email address", example = "customer@example.com") String email,
        @Schema(description = "Phone number", example = "0900000001") String phone,
        @Schema(description = "Residential address", example = "123 Pho Hue, Hai Ba Trung")
                String address,
        @Schema(description = "Province or city location", example = "Ha Noi") String location,
        @Schema(description = "Date of birth", example = "1990-01-15") LocalDate dateOfBirth,
        @Schema(description = "Creation timestamp") Instant createdAt,
        @Schema(description = "Last update timestamp") Instant updatedAt,
        @Schema(description = "Current entity version for optimistic locking", example = "0")
                Long version) {

    /** Factory method mapping domain entity to response DTO, applying national ID masking. */
    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getCustomerCode(),
                customer.getFullName(),
                MaskingUtils.nationalId(customer.getNationalId()),
                customer.getEmail(),
                customer.getPhone(),
                customer.getAddress(),
                customer.getLocation(),
                customer.getDateOfBirth(),
                customer.getCreatedAt(),
                customer.getUpdatedAt(),
                customer.getVersion());
    }
}

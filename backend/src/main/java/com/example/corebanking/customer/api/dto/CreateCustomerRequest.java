package com.example.corebanking.customer.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Request payload for registering a new customer. */
@Schema(description = "Request payload for creating a new customer")
public record CreateCustomerRequest(
        @Schema(description = "Full name of the customer", example = "Nguyen Van An")
                @NotBlank(message = "Full name is required")
                @Size(max = 255, message = "Full name must not exceed 255 characters")
                String fullName,
        @Schema(
                        description =
                                "Synthetic national identity card number (must start with 000)",
                        example = "000123456789")
                @NotBlank(message = "National ID is required")
                @Pattern(
                        regexp = "^000\\d{9}$",
                        message = "National ID must be synthetic 12 digits starting with 000")
                String nationalId,
        @Schema(description = "Email address", example = "customer@example.com")
                @Email(message = "Email must be valid")
                @Size(max = 255, message = "Email must not exceed 255 characters")
                String email,
        @Schema(description = "Contact phone number", example = "0900000001")
                @Pattern(
                        regexp = "^0\\d{9,10}$",
                        message = "Phone number must be 10-11 digits starting with 0")
                String phone,
        @Schema(description = "Residential address", example = "123 Pho Hue, Hai Ba Trung")
                @Size(max = 255, message = "Address must not exceed 255 characters")
                String address,
        @Schema(description = "Province or city location", example = "Ha Noi")
                @NotBlank(message = "Location is required")
                @Size(max = 100, message = "Location must not exceed 100 characters")
                String location,
        @Schema(description = "Date of birth", example = "1990-01-15")
                @Past(message = "Date of birth must be in the past")
                LocalDate dateOfBirth) {}

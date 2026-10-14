package com.example.corebanking.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request payload for username/password login (`POST /api/v1/auth/login`). */
@Schema(description = "Yêu cầu đăng nhập bằng tên đăng nhập và mật khẩu")
public record LoginRequest(
        @Schema(description = "Tên đăng nhập", example = "admin")
                @NotBlank(message = "Username is required")
                @Size(max = 100, message = "Username must not exceed 100 characters")
                String username,
        @Schema(description = "Mật khẩu đăng nhập", example = "Admin@123")
                @NotBlank(message = "Password is required")
                @Size(max = 72, message = "Password must not exceed 72 characters")
                String password) {

    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=***]";
    }
}

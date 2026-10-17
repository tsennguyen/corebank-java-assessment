package com.example.corebanking.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Optional JSON request payload carrying an opaque refresh token for {@code POST
 * /api/v1/auth/refresh} or {@code POST /api/v1/auth/logout} when the client does not rely on the
 * {@code HttpOnly} cookie (NC-07, FR-15).
 */
@Schema(description = "Yêu cầu làm mới phiên hoặc đăng xuất bằng Refresh Token")
public record RefreshTokenRequest(
        @Schema(
                        description =
                                "Chuỗi Opaque Refresh Token 256-bit (tùy chọn nếu đã gửi qua cookie"
                                        + " HttpOnly refresh_token)",
                        example = "dGhpcyBpcyBhIDMyLWJ5dGUgcmFuZG9tIHRva2Vu",
                        nullable = true)
                String refreshToken) {

    @Override
    public String toString() {
        return "RefreshTokenRequest[refreshToken=***]";
    }
}

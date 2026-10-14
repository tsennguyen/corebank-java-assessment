package com.example.corebanking.auth.api.dto;

import com.example.corebanking.auth.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

/** Response payload containing the issued JWT access token and principal metadata (L2-02). */
@Schema(description = "Phản hồi đăng nhập thành công chứa JWT Access Token và thông tin vai trò")
public record TokenResponse(
        @Schema(
                        description = "Chuỗi JWT Access Token ký bằng HS256",
                        example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
                String accessToken,
        @Schema(description = "Loại token xác thực", example = "Bearer") String tokenType,
        @Schema(
                        description = "Thời gian hiệu lực của Access Token (tính bằng giây)",
                        example = "900")
                long expiresIn,
        @Schema(description = "Vai trò của người dùng", example = "ADMIN") Role role,
        @Schema(
                        description =
                                "ID khách hàng liên kết (chỉ có khi role = CUSTOMER, null khi role"
                                        + " = ADMIN)",
                        example = "1",
                        nullable = true)
                Long customerId) {

    public static TokenResponse bearer(
            String accessToken, long expiresIn, Role role, Long customerId) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, role, customerId);
    }
}

package com.example.corebanking.auth.api;

import com.example.corebanking.auth.api.dto.LoginRequest;
import com.example.corebanking.auth.api.dto.TokenResponse;
import com.example.corebanking.auth.application.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for authentication and JWT token issuance (`L2-01`, `L2-02`, `FR-13`). */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(
        name = "Authentication",
        description = "Xác thực người dùng và phát hành JWT Access Token (HS256)")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
            summary = "Đăng nhập nhận JWT Access Token",
            description =
                    "Xác thực bằng username/password (BCrypt) và phát hành JWT Access Token ký"
                            + " bằng HS256 có hiệu lực 15 phút.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Đăng nhập thành công"),
        @ApiResponse(
                responseCode = "400",
                description = "Dữ liệu đầu vào không hợp lệ",
                content = @Content(schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
        @ApiResponse(
                responseCode = "401",
                description = "Sai tên đăng nhập hoặc mật khẩu",
                content = @Content(schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    })
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}

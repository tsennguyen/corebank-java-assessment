package com.example.corebanking.auth.api;

import com.example.corebanking.auth.api.dto.LoginRequest;
import com.example.corebanking.auth.api.dto.RefreshTokenRequest;
import com.example.corebanking.auth.api.dto.TokenResponse;
import com.example.corebanking.auth.application.AuthService;
import com.example.corebanking.auth.infrastructure.SecurityProperties;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for authentication, JWT access token issuance, one-time refresh token rotation,
 * and session logout (`L2-01`, `L2-02`, `NC-07`, `FR-13`, `FR-15`, `NFR-5`).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(
        name = "Authentication",
        description =
                "Xác thực người dùng, phát hành JWT Access Token (HS256) và xoay vòng Opaque"
                        + " Refresh Token")
public class AuthController {

    public static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    public static final String REFRESH_TOKEN_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final long refreshTokenTtlSeconds;

    public AuthController(AuthService authService) {
        this(authService, new SecurityProperties());
    }

    @Autowired
    public AuthController(AuthService authService, SecurityProperties securityProperties) {
        this.authService = authService;
        this.refreshTokenTtlSeconds =
                Math.max(1L, securityProperties.getJwt().getRefreshTokenTtlSeconds());
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
            summary = "Đăng nhập nhận JWT Access Token và Refresh Token",
            description =
                    "Xác thực bằng username/password (BCrypt), kiểm tra giới hạn tần suất chống"
                        + " brute-force (tối đa 5 lần sai / 5 phút) và phát hành JWT Access Token"
                        + " (15 phút) kèm Opaque Refresh Token 256-bit (7 ngày) trong body và"
                        + " cookie HttpOnly.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Đăng nhập thành công",
                headers =
                        @Header(
                                name = HttpHeaders.SET_COOKIE,
                                description =
                                        "Cookie refresh_token (HttpOnly; Secure; SameSite=Strict;"
                                                + " Path=/api/v1/auth)")),
        @ApiResponse(
                responseCode = "400",
                description = "Dữ liệu đầu vào không hợp lệ",
                content = @Content(schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
        @ApiResponse(
                responseCode = "401",
                description = "Sai tên đăng nhập hoặc mật khẩu",
                content = @Content(schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
        @ApiResponse(
                responseCode = "429",
                description = "Vượt quá số lần đăng nhập sai cho phép (kèm header Retry-After)",
                content = @Content(schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    })
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse response = authService.login(request);
        return withRefreshTokenCookie(response);
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(
            summary = "Làm mới JWT Access Token bằng Refresh Token (One-Time Rotation)",
            description =
                    "Nhận Refresh Token từ body JSON hoặc cookie HttpOnly refresh_token, xoay vòng"
                        + " sang Refresh Token mới cùng familyId. Nếu phát hiện Refresh Token cũ bị"
                        + " gửi lại lần thứ 2, thu hồi toàn bộ họ token và trả về 401"
                        + " TOKEN_REUSED.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Làm mới token thành công"),
        @ApiResponse(
                responseCode = "401",
                description =
                        "Refresh Token không hợp lệ, đã hết hạn, hoặc bị tái sử dụng"
                                + " (TOKEN_REUSED)",
                content = @Content(schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    })
    public ResponseEntity<TokenResponse> refresh(
            @RequestBody(required = false) @Nullable RefreshTokenRequest request,
            @CookieValue(name = REFRESH_TOKEN_COOKIE_NAME, required = false) @Nullable
                    String cookieRefreshToken,
            HttpServletResponse servletResponse) {
        String rawToken = resolveRefreshToken(request, cookieRefreshToken);
        try {
            TokenResponse response = authService.refresh(rawToken);
            return withRefreshTokenCookie(response);
        } catch (BusinessRuleException ex) {
            if (ex.getErrorCode() == ErrorCode.TOKEN_REUSED && servletResponse != null) {
                servletResponse.addHeader(HttpHeaders.SET_COOKIE, buildClearCookie().toString());
            }
            throw ex;
        }
    }

    @PostMapping("/logout")
    @SecurityRequirements
    @Operation(
            summary = "Đăng xuất và thu hồi họ Refresh Token",
            description =
                    "Thu hồi toàn bộ họ Refresh Token hiện tại trong RefreshTokenStore và xóa"
                            + " cookie refresh_token (Max-Age=0).")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "Đăng xuất thành công")})
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) @Nullable RefreshTokenRequest request,
            @CookieValue(name = REFRESH_TOKEN_COOKIE_NAME, required = false) @Nullable
                    String cookieRefreshToken) {
        String rawToken = resolveRefreshToken(request, cookieRefreshToken);
        authService.logout(rawToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, buildClearCookie().toString())
                .build();
    }

    private ResponseEntity<TokenResponse> withRefreshTokenCookie(TokenResponse response) {
        if (response.refreshToken() == null || response.refreshToken().isBlank()) {
            return ResponseEntity.ok(response);
        }
        ResponseCookie cookie =
                ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, response.refreshToken())
                        .httpOnly(true)
                        .secure(true)
                        .sameSite("Strict")
                        .path(REFRESH_TOKEN_COOKIE_PATH)
                        .maxAge(Duration.ofSeconds(refreshTokenTtlSeconds))
                        .build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(response);
    }

    private ResponseCookie buildClearCookie() {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_TOKEN_COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build();
    }

    @Nullable
    private static String resolveRefreshToken(
            @Nullable RefreshTokenRequest request, @Nullable String cookieRefreshToken) {
        if (request != null
                && request.refreshToken() != null
                && !request.refreshToken().isBlank()) {
            return request.refreshToken().trim();
        }
        if (cookieRefreshToken != null && !cookieRefreshToken.isBlank()) {
            return cookieRefreshToken.trim();
        }
        return null;
    }
}

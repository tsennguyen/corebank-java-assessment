package com.example.corebanking.auth.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalized security configuration properties for JWT token issuance/validation and admin
 * account bootstrap (`app.security.*`).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private JwtProperties jwt = new JwtProperties();
    private AdminBootstrapProperties adminBootstrap = new AdminBootstrapProperties();

    @Getter
    @Setter
    public static class JwtProperties {
        private String secret = "corebank-demo-jwt-secret-key-at-least-256-bits-long-2026";
        private long accessTokenTtlSeconds = 900L;
        private String issuer = "corebank-java-assessment";
    }

    @Getter
    @Setter
    public static class AdminBootstrapProperties {
        private boolean enabled = true;
        private String username = "admin";
        private String password = "Admin@123";
    }
}

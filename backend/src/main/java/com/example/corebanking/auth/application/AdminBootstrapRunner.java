package com.example.corebanking.auth.application;

import com.example.corebanking.auth.domain.AppUser;
import com.example.corebanking.auth.infrastructure.AppUserRepository;
import com.example.corebanking.auth.infrastructure.SecurityProperties;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotently bootstraps or synchronizes the initial {@code ADMIN} account at application startup
 * from {@code app.security.admin-bootstrap.*} properties (L2-01, FR-13).
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties securityProperties;

    public AdminBootstrapRunner(
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            SecurityProperties securityProperties) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityProperties = securityProperties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        SecurityProperties.AdminBootstrapProperties bootstrap =
                securityProperties.getAdminBootstrap();
        if (!bootstrap.isEnabled()) {
            log.info("Admin bootstrap is disabled via configuration");
            return;
        }

        String username = bootstrap.getUsername().trim();
        String rawPassword = bootstrap.getPassword();

        Optional<AppUser> existingOpt = appUserRepository.findByUsername(username);
        if (existingOpt.isEmpty()) {
            AppUser admin = AppUser.createAdmin(username, passwordEncoder.encode(rawPassword));
            appUserRepository.save(admin);
            log.info("Bootstrapped initial ADMIN user '{}'", username);
            return;
        }

        AppUser existing = existingOpt.get();
        boolean updated = false;
        if (!passwordEncoder.matches(rawPassword, existing.getPasswordHash())) {
            existing.updatePasswordHash(passwordEncoder.encode(rawPassword));
            updated = true;
        }
        if (!existing.isEnabled()) {
            existing.enable();
            updated = true;
        }
        if (updated) {
            appUserRepository.save(existing);
            log.info("Synchronized bootstrap ADMIN user '{}'", username);
        }
    }
}

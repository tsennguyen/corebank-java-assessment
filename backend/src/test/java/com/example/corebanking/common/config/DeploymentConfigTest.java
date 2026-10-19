package com.example.corebanking.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Verifies containerization and cloud deployment configuration invariants (Story 2.10: NC-06,
 * NC-10, NFR-10, FR-24).
 */
class DeploymentConfigTest {

    @Test
    @DisplayName(
            "backend/Dockerfile uses multi-stage Temurin 21 JRE, layered JAR extraction, non-root"
                    + " user, fontconfig, and memory-tuned JVM flags (NC-06)")
    void dockerfile_shouldFollowMultiStageLayeredHardenedConventions() throws Exception {
        Path dockerfilePath = resolveBackendFile("Dockerfile");
        String dockerfile = Files.readString(dockerfilePath, StandardCharsets.UTF_8);

        assertThat(dockerfile)
                .contains("FROM eclipse-temurin:21-jdk-jammy AS build")
                .contains("FROM eclipse-temurin:21-jre-jammy AS runtime")
                .contains("extract --layers --launcher")
                .contains("fontconfig")
                .contains("fonts-dejavu-core")
                .contains("useradd --system")
                .contains("USER appuser:appgroup")
                .contains("-XX:MaxRAMPercentage=75.0")
                .contains("-XX:+UseSerialGC")
                .contains("-XX:+ExitOnOutOfMemoryError")
                .contains("-Djava.awt.headless=true")
                .contains("HEALTHCHECK")
                .contains("/actuator/health")
                .contains("org.springframework.boot.loader.launch.JarLauncher");
    }

    @Test
    @DisplayName(
            "docker-compose.yml and render.yaml define PostgreSQL 17, health checks, and secretless"
                    + " cloud staging configuration (NC-06, NC-10)")
    void composeAndRenderBlueprint_shouldConfigureHealthChecksAndNoHardcodedProdSecrets()
            throws Exception {
        Path composePath = resolveRepoRootFile("docker-compose.yml");
        String compose = Files.readString(composePath, StandardCharsets.UTF_8);

        assertThat(compose)
                .contains("postgres:17-alpine")
                .contains("pg_isready")
                .contains("condition: service_healthy")
                .contains("http://localhost:8080/actuator/health");

        Path renderPath = resolveRepoRootFile("render.yaml");
        String render = Files.readString(renderPath, StandardCharsets.UTF_8);

        assertThat(render)
                .contains("runtime: docker")
                .contains("region: singapore")
                .contains("branch: prod")
                .contains("rootDir: backend")
                .contains("healthCheckPath: /actuator/health")
                .contains("sync: false");

        ClassPathResource prodConfig = new ClassPathResource("application-prod.yml");
        String prodYaml = prodConfig.getContentAsString(StandardCharsets.UTF_8);

        assertThat(prodYaml)
                .contains("${DATABASE_URL:${SPRING_DATASOURCE_URL}}")
                .contains("maximum-pool-size: ${HIKARI_MAX_POOL_SIZE:5}")
                .contains("connection-timeout: 5000")
                .doesNotContain("options: \"-c lock_timeout=3000\"")
                .contains("${FLYWAY_LOCATIONS:classpath:db/migration}");

        ClassPathResource baseConfig = new ClassPathResource("application.yml");
        String baseYaml = baseConfig.getContentAsString(StandardCharsets.UTF_8);
        assertThat(baseYaml).contains("SET lock_timeout = '3s'");
    }

    @Test
    @DisplayName(
            "coverage-pages.yml, security.yml, and dependabot.yml enforce >=80% JaCoCo GitHub Pages"
                + " report, CodeQL SAST, Dependency Review, and weekly Dependabot updates (Story"
                + " 5.1 & 6.4: G-04, NC-04, NC-05, NC-13)")
    void coveragePagesAndSecurityWorkflows_shouldEnforceJaCoCoGateCodeQlAndDependabot()
            throws Exception {
        Path coverageWorkflowPath = resolveRepoRootFile(".github/workflows/coverage-pages.yml");
        String coverageWorkflow = Files.readString(coverageWorkflowPath, StandardCharsets.UTF_8);

        assertThat(coverageWorkflow)
                .contains("Publish JaCoCo Coverage Report to GitHub Pages")
                .contains("pages: write")
                .contains("id-token: write")
                .contains("cache-dependency-path: 'backend/pom.xml'")
                .contains("./mvnw -B verify -Pci")
                .contains("actions/upload-pages-artifact@v3")
                .contains("backend/target/site/jacoco")
                .contains("actions/deploy-pages@v4");

        Path securityWorkflowPath = resolveRepoRootFile(".github/workflows/security.yml");
        String securityWorkflow = Files.readString(securityWorkflowPath, StandardCharsets.UTF_8);

        assertThat(securityWorkflow)
                .contains("Security Scanning (CodeQL & Dependency Review)")
                .contains("security-events: write")
                .contains("github/codeql-action/init@v3")
                .contains("languages: java-kotlin")
                .contains("github/codeql-action/analyze@v3")
                .contains("actions/dependency-review-action@v4")
                .contains("fail-on-severity: high");

        Path dependabotPath = resolveRepoRootFile(".github/dependabot.yml");
        String dependabot = Files.readString(dependabotPath, StandardCharsets.UTF_8);

        assertThat(dependabot)
                .contains("package-ecosystem: \"maven\"")
                .contains("directory: \"/backend\"")
                .contains("package-ecosystem: \"github-actions\"")
                .contains("target-branch: \"dev\"")
                .contains("interval: \"weekly\"")
                .contains("dependencies");
    }

    @Test
    @DisplayName(
            "Operations Console static assets should be synchronized between frontend/ and"
                    + " classpath:/static/ and wire all Level 1-3 API endpoints")
    void shouldPackageSynchronizedOperationsConsoleAssets() throws Exception {
        String frontendHtml =
                Files.readString(
                        resolveRepoRootFile("frontend/index.html"), StandardCharsets.UTF_8);
        String staticHtml =
                Files.readString(
                        resolveBackendFile("src/main/resources/static/index.html"),
                        StandardCharsets.UTF_8);
        String frontendJs =
                Files.readString(resolveRepoRootFile("frontend/app.js"), StandardCharsets.UTF_8);
        String staticJs =
                Files.readString(
                        resolveBackendFile("src/main/resources/static/app.js"),
                        StandardCharsets.UTF_8);

        assertThat(staticHtml).isEqualTo(frontendHtml);
        assertThat(staticJs).isEqualTo(frontendJs);
        assertThat(frontendHtml)
                .contains("Core Banking System — Operations Console")
                .contains("quickLogin('admin'")
                .contains("quickLogin('cust.an'")
                .contains("quickLogin('cust.binh'")
                .contains("previewFee()")
                .contains("submitTransaction()")
                .contains("runHistoricalFraudScan()")
                .contains("exportFile('XLSX')")
                .contains("exportFile('PDF')");
        assertThat(frontendJs)
                .contains("function esc(")
                .contains("/api/v1/auth/login")
                .contains("/api/v1/auth/refresh")
                .contains("/api/v1/fees/preview")
                .contains("/api/v1/statistics/balance-tiers")
                .contains("/api/v1/statistics/customers-by-location")
                .contains("/api/v1/reports/transactions")
                .contains("/api/v1/reports/trends")
                .contains("/api/v1/reports/export")
                .contains("/api/v1/alerts")
                .contains("/api/v1/fraud/scans")
                .contains("Idempotent-Replayed");
    }

    private static Path resolveBackendFile(String relativePath) {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.exists(cwd.resolve(relativePath))) {
            return cwd.resolve(relativePath);
        }
        return cwd.resolve("backend").resolve(relativePath);
    }

    private static Path resolveRepoRootFile(String relativePath) {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.exists(cwd.resolve(relativePath))) {
            return cwd.resolve(relativePath);
        }
        return cwd.getParent().resolve(relativePath);
    }
}

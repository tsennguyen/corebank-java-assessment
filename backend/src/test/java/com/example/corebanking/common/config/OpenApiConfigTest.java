package com.example.corebanking.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiConfigTest {

    @Test
    @DisplayName(
            "OpenAPI bean should be configured with Core Banking title, synthetic contact, and"
                    + " BearerAuth scheme")
    void openApiBeanShouldBeConfiguredCorrectly() {
        OpenApiConfig config = new OpenApiConfig("1.0.0-DEMO");
        OpenAPI openAPI = config.coreBankingOpenAPI();

        assertThat(openAPI.getInfo()).isNotNull();
        assertThat(openAPI.getInfo().getTitle()).isEqualTo("Core Banking System API");
        assertThat(openAPI.getInfo().getVersion()).isEqualTo("1.0.0-DEMO");
        assertThat(openAPI.getInfo().getDescription()).contains("Core Banking");
        assertThat(openAPI.getInfo().getContact()).isNotNull();
        assertThat(openAPI.getInfo().getContact().getEmail()).isEqualTo("developer@example.com");

        // Verify BearerAuth security scheme
        assertThat(openAPI.getComponents()).isNotNull();
        SecurityScheme securityScheme =
                openAPI.getComponents()
                        .getSecuritySchemes()
                        .get(OpenApiConfig.SECURITY_SCHEME_NAME);
        assertThat(securityScheme).isNotNull();
        assertThat(securityScheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(securityScheme.getScheme()).isEqualTo("bearer");
        assertThat(securityScheme.getBearerFormat()).isEqualTo("JWT");

        // Verify ProblemDetail schema in components
        Schema<?> problemDetailSchema = openAPI.getComponents().getSchemas().get("ProblemDetail");
        assertThat(problemDetailSchema).isNotNull();
        assertThat(problemDetailSchema.getProperties())
                .containsKeys(
                        "type",
                        "title",
                        "status",
                        "detail",
                        "instance",
                        "code",
                        "requestId",
                        "timestamp",
                        "errors");

        // Verify ValidationError schema in components
        Schema<?> validationErrorSchema =
                openAPI.getComponents().getSchemas().get("ValidationError");
        assertThat(validationErrorSchema).isNotNull();
        assertThat(validationErrorSchema.getProperties()).containsKeys("field", "message");

        // Verify tags
        assertThat(openAPI.getTags()).isNotEmpty();
        assertThat(openAPI.getTags())
                .extracting("name")
                .contains("Customer", "Account", "Transaction", "Report", "System");
    }

    @Test
    @DisplayName(
            "OpenAPI bean should fallback to 0.0.1-SNAPSHOT when app.version is null, blank or"
                    + " placeholder")
    void openApiBeanShouldFallbackToDefaultVersion() {
        OpenApiConfig configWithNull = new OpenApiConfig(null);
        assertThat(configWithNull.coreBankingOpenAPI().getInfo().getVersion())
                .isEqualTo("0.0.1-SNAPSHOT");

        OpenApiConfig configWithBlank = new OpenApiConfig("   ");
        assertThat(configWithBlank.coreBankingOpenAPI().getInfo().getVersion())
                .isEqualTo("0.0.1-SNAPSHOT");

        OpenApiConfig configWithPlaceholder = new OpenApiConfig("@project.version@");
        assertThat(configWithPlaceholder.coreBankingOpenAPI().getInfo().getVersion())
                .isEqualTo("0.0.1-SNAPSHOT");
    }

    @Test
    @DisplayName(
            "OpenApiCustomizer should register ProblemDetail, ValidationError and BearerAuth"
                    + " schemas")
    void openApiCustomizerShouldEnrichOpenApi() {
        OpenApiConfig config = new OpenApiConfig("1.0.0-DEMO");
        OpenAPI openAPI = new OpenAPI();
        config.coreBankingOpenApiCustomizer().customise(openAPI);

        assertThat(openAPI.getComponents()).isNotNull();
        assertThat(openAPI.getComponents().getSecuritySchemes())
                .containsKey(OpenApiConfig.SECURITY_SCHEME_NAME);
        assertThat(openAPI.getComponents().getSchemas())
                .containsKeys("ProblemDetail", "ValidationError");
    }
}

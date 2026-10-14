package com.example.corebanking.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 / Swagger documentation configuration for Core Banking System. Adheres to RFC 9457
 * ProblemDetail error structure, Bearer JWT authentication, and synthetic data conventions.
 */
@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME_NAME = "BearerAuth";

    private final String appVersion;

    public OpenApiConfig(@Value("${app.version:0.0.1-SNAPSHOT}") String appVersion) {
        if (appVersion == null || appVersion.isBlank() || appVersion.startsWith("@")) {
            this.appVersion = "0.0.1-SNAPSHOT";
        } else {
            this.appVersion = appVersion;
        }
    }

    @Bean
    public OpenAPI coreBankingOpenAPI() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Core Banking System API")
                                .description(
                                        "Tài liệu đặc tả REST API cho Hệ thống Core Banking (demo)"
                                            + " quản lý khách hàng, tài khoản, giao dịch tài chính,"
                                            + " hạn mức, phí và phát hiện bất thường.")
                                .version(appVersion)
                                .contact(
                                        new Contact()
                                                .name("Core Banking Development Team")
                                                .email("developer@example.com"))
                                .license(
                                        new License()
                                                .name("Apache 2.0")
                                                .url(
                                                        "https://www.apache.org/licenses/LICENSE-2.0")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        SECURITY_SCHEME_NAME,
                                        new SecurityScheme()
                                                .name(SECURITY_SCHEME_NAME)
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description(
                                                        "Nhập JSON Web Token (JWT) theo định dạng:"
                                                                + " Bearer <token>"))
                                .addSchemas("ValidationError", createValidationErrorSchema())
                                .addSchemas("ProblemDetail", createProblemDetailSchema()))
                .tags(
                        List.of(
                                new Tag()
                                        .name("Customer")
                                        .description("Quản lý thông tin và hồ sơ khách hàng"),
                                new Tag()
                                        .name("Account")
                                        .description("Quản lý tài khoản thanh toán và số dư"),
                                new Tag()
                                        .name("Transaction")
                                        .description(
                                                "Nạp tiền, rút tiền, chuyển khoản và hoàn tiền"),
                                new Tag()
                                        .name("Report")
                                        .description("Báo cáo thống kê giao dịch và xuất dữ liệu"),
                                new Tag()
                                        .name("System")
                                        .description(
                                                "Giám sát trạng thái sức khỏe và chỉ số hệ"
                                                        + " thống")));
    }

    @Bean
    public OpenApiCustomizer coreBankingOpenApiCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents()
                    .addSecuritySchemes(
                            SECURITY_SCHEME_NAME,
                            new SecurityScheme()
                                    .name(SECURITY_SCHEME_NAME)
                                    .type(SecurityScheme.Type.HTTP)
                                    .scheme("bearer")
                                    .bearerFormat("JWT")
                                    .description(
                                            "Nhập JSON Web Token (JWT) theo định dạng: Bearer"
                                                    + " <token>"))
                    .addSchemas("ValidationError", createValidationErrorSchema())
                    .addSchemas("ProblemDetail", createProblemDetailSchema());
        };
    }

    private Schema<?> createValidationErrorSchema() {
        Schema<Object> schema =
                new ObjectSchema().description("Chi tiết lỗi xác thực của từng trường dữ liệu");
        schema.addProperty(
                "field",
                new StringSchema().example("nationalId").description("Tên trường dữ liệu vi phạm"));
        schema.addProperty(
                "message",
                new StringSchema()
                        .example("National ID must be exactly 12 digits")
                        .description("Thông báo lỗi chi tiết"));
        return schema;
    }

    private Schema<?> createProblemDetailSchema() {
        Schema<Object> schema =
                new ObjectSchema()
                        .description(
                                "Cấu trúc phản hồi lỗi chuẩn hóa theo khuyến nghị RFC 9457 Problem"
                                        + " Details");
        schema.addProperty(
                "type",
                new StringSchema()
                        .example("urn:problem-type:validation-failed")
                        .description("Định danh loại lỗi dạng URN"));
        schema.addProperty(
                "title",
                new StringSchema()
                        .example("Validation Failed")
                        .description("Tiêu đề ngắn gọn mô tả loại lỗi"));
        schema.addProperty(
                "status", new IntegerSchema().example(400).description("Mã trạng thái HTTP"));
        schema.addProperty(
                "detail",
                new StringSchema()
                        .example("Input validation failed for 1 field(s)")
                        .description("Chi tiết cụ thể về lỗi đã xảy ra"));
        schema.addProperty(
                "instance",
                new StringSchema()
                        .example("/api/v1/customers")
                        .description("URI endpoint xảy ra lỗi"));
        schema.addProperty(
                "code",
                new StringSchema()
                        .example("VALIDATION_FAILED")
                        .description("Mã lỗi nghiệp vụ máy đọc ổn định"));
        schema.addProperty(
                "requestId",
                new StringSchema()
                        .example("550e8400-e29b-41d4-a716-446655440000")
                        .description("Mã định danh tương quan request dùng để đối soát"));
        schema.addProperty(
                "timestamp",
                new StringSchema()
                        .example("2026-10-09T08:00:00Z")
                        .description("Thời điểm xảy ra lỗi theo chuẩn ISO 8601"));
        schema.addProperty(
                "errors",
                new ArraySchema()
                        .items(new Schema<>().$ref("#/components/schemas/ValidationError"))
                        .description("Danh sách lỗi kiểm thực chi tiết (nếu có)"));
        return schema;
    }
}

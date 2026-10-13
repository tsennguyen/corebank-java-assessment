package com.example.corebanking.common.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class OpenApiDocumentationIT extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName(
            "GET /v3/api-docs should return OpenAPI 3 JSON specification with title, security, and"
                    + " schemas")
    void apiDocsEndpointShouldReturnOpenApiSpecification() throws Exception {
        mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi", notNullValue()))
                .andExpect(jsonPath("$.info.title").value("Core Banking System API"))
                .andExpect(jsonPath("$.info.description", containsString("Core Banking")))
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth.type").value("http"))
                .andExpect(
                        jsonPath("$.components.securitySchemes.BearerAuth.scheme").value("bearer"))
                .andExpect(
                        jsonPath("$.components.securitySchemes.BearerAuth.bearerFormat")
                                .value("JWT"))
                .andExpect(jsonPath("$.components.schemas.ProblemDetail", notNullValue()))
                .andExpect(jsonPath("$.components.schemas.ValidationError", notNullValue()));
    }

    @Test
    @DisplayName("GET /swagger-ui.html should redirect to Swagger UI index page")
    void swaggerUiEndpointShouldBeAccessible() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("swagger-ui/index.html")));
    }
}

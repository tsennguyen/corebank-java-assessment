package com.example.corebanking.auth.infrastructure;

import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Spring Security {@link AuthenticationEntryPoint} that renders RFC 9457 {@link ProblemDetail} JSON
 * payloads (`code = "UNAUTHORIZED"`) whenever a request lacks a valid Bearer JWT (NC-08, L2-02).
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log =
            LoggerFactory.getLogger(ProblemDetailAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        ErrorCode errorCode = ErrorCode.UNAUTHORIZED;
        String requestId = RequestIdFilter.getCurrentRequestId();

        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.UNAUTHORIZED,
                        "Authentication is required to access this resource");
        problemDetail.setTitle(errorCode.getDefaultTitle());
        problemDetail.setType(URI.create(errorCode.getProblemTypeUri()));
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("code", errorCode.name());
        problemDetail.setProperty("requestId", requestId);
        problemDetail.setProperty("timestamp", clock.instant());

        log.warn(
                "Unauthorized request to {} [requestId={}]: {}",
                request.getRequestURI(),
                requestId,
                authException.getMessage());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        if (response.getHeader(RequestIdFilter.REQUEST_ID_HEADER) == null) {
            response.setHeader(RequestIdFilter.REQUEST_ID_HEADER, requestId);
        }
        objectMapper.writeValue(response.getWriter(), problemDetail);
    }
}

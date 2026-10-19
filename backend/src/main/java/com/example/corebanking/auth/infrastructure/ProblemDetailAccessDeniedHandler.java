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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Spring Security {@link AccessDeniedHandler} that renders RFC 9457 {@link ProblemDetail} JSON
 * payloads (`code = "ACCESS_DENIED"`) whenever an authenticated principal lacks required role or
 * ownership permissions (NC-08, L2-01).
 */
@Component
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log =
            LoggerFactory.getLogger(ProblemDetailAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProblemDetailAccessDeniedHandler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        ErrorCode errorCode = ErrorCode.ACCESS_DENIED;
        String requestId = RequestIdFilter.getCurrentRequestId();

        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.FORBIDDEN, "You do not have permission to access this resource");
        problemDetail.setTitle(errorCode.getDefaultTitle());
        problemDetail.setType(URI.create(errorCode.getProblemTypeUri()));
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("code", errorCode.name());
        problemDetail.setProperty("requestId", requestId);
        problemDetail.setProperty("timestamp", clock.instant());

        log.warn(
                "Access denied to {} [requestId={}]: {}",
                request.getRequestURI(),
                requestId,
                accessDeniedException.getMessage());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (response.getHeader(RequestIdFilter.REQUEST_ID_HEADER) == null) {
            response.setHeader(RequestIdFilter.REQUEST_ID_HEADER, requestId);
        }
        objectMapper.writeValue(response.getWriter(), problemDetail);
    }
}

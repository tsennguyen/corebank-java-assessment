package com.example.corebanking.common.api;

import com.example.corebanking.common.domain.DomainException;
import com.example.corebanking.common.domain.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Centralized exception handler producing RFC 9457 ProblemDetail payloads with stable error codes,
 * correlation request IDs, and security-safe error sanitization.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROPERTY_CODE = "code";
    private static final String PROPERTY_REQUEST_ID = "requestId";
    private static final String PROPERTY_TIMESTAMP = "timestamp";
    private static final String PROPERTY_ERRORS = "errors";

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /** Handles all banking domain business rule and state exceptions. */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomainException(
            DomainException ex, WebRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        HttpStatus status = HttpStatus.valueOf(errorCode.getHttpStatus());
        String requestId = RequestIdFilter.getCurrentRequestId();

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problemDetail.setTitle(errorCode.getDefaultTitle());
        problemDetail.setType(URI.create(errorCode.getProblemTypeUri()));
        problemDetail.setInstance(resolveInstanceUri(request));
        problemDetail.setProperty(PROPERTY_CODE, errorCode.name());
        problemDetail.setProperty(PROPERTY_REQUEST_ID, requestId);
        problemDetail.setProperty(PROPERTY_TIMESTAMP, clock.instant());

        log.warn("Domain exception [{}] handled: {}", errorCode.name(), ex.getMessage());
        return ResponseEntity.status(status).body(problemDetail);
    }

    /** Handles request body validation failures triggered by @Valid annotations. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        List<ValidationError> validationErrors = new ArrayList<>();

        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            validationErrors.add(
                    new ValidationError(fieldError.getField(), fieldError.getDefaultMessage()));
        }

        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST, "Validation failed for request payload");
        problemDetail.setTitle(ErrorCode.VALIDATION_FAILED.getDefaultTitle());
        problemDetail.setType(URI.create(ErrorCode.VALIDATION_FAILED.getProblemTypeUri()));
        problemDetail.setInstance(resolveInstanceUri(request));
        problemDetail.setProperty(PROPERTY_CODE, ErrorCode.VALIDATION_FAILED.name());
        problemDetail.setProperty(PROPERTY_REQUEST_ID, requestId);
        problemDetail.setProperty(PROPERTY_TIMESTAMP, clock.instant());
        problemDetail.setProperty(PROPERTY_ERRORS, validationErrors);

        log.warn(
                "Validation failed [requestId={}]: {} error(s)",
                requestId,
                validationErrors.size());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(problemDetail);
    }

    /** Handles unreadable or malformed JSON payloads. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST, "Malformed JSON request body");
        problemDetail.setTitle(ErrorCode.VALIDATION_FAILED.getDefaultTitle());
        problemDetail.setType(URI.create(ErrorCode.VALIDATION_FAILED.getProblemTypeUri()));
        problemDetail.setInstance(resolveInstanceUri(request));
        problemDetail.setProperty(PROPERTY_CODE, ErrorCode.VALIDATION_FAILED.name());
        problemDetail.setProperty(PROPERTY_REQUEST_ID, requestId);
        problemDetail.setProperty(PROPERTY_TIMESTAMP, clock.instant());

        log.warn("Malformed JSON request [requestId={}]: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(problemDetail);
    }

    /** Handles missing required request headers like Idempotency-Key. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingRequestHeader(
            MissingRequestHeaderException ex, WebRequest request) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        if ("Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())) {
            ErrorCode errorCode = ErrorCode.IDEMPOTENCY_KEY_MISSING;
            ProblemDetail problemDetail =
                    ProblemDetail.forStatusAndDetail(
                            HttpStatus.BAD_REQUEST,
                            "Required header 'Idempotency-Key' is missing or blank");
            problemDetail.setTitle(errorCode.getDefaultTitle());
            problemDetail.setType(URI.create(errorCode.getProblemTypeUri()));
            problemDetail.setInstance(resolveInstanceUri(request));
            problemDetail.setProperty(PROPERTY_CODE, errorCode.name());
            problemDetail.setProperty(PROPERTY_REQUEST_ID, requestId);
            problemDetail.setProperty(PROPERTY_TIMESTAMP, clock.instant());

            log.warn("Missing Idempotency-Key header [requestId={}]", requestId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
        }
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        "Required header '" + ex.getHeaderName() + "' is missing");
        problemDetail.setTitle(ErrorCode.VALIDATION_FAILED.getDefaultTitle());
        problemDetail.setType(URI.create(ErrorCode.VALIDATION_FAILED.getProblemTypeUri()));
        problemDetail.setInstance(resolveInstanceUri(request));
        problemDetail.setProperty(PROPERTY_CODE, ErrorCode.VALIDATION_FAILED.name());
        problemDetail.setProperty(PROPERTY_REQUEST_ID, requestId);
        problemDetail.setProperty(PROPERTY_TIMESTAMP, clock.instant());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    /**
     * Handles method argument type mismatch errors (e.g. invalid enum or number in query params).
     */
    @ExceptionHandler(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex,
            WebRequest request) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        List<ValidationError> validationErrors =
                List.of(
                        new ValidationError(
                                ex.getName(), "Invalid value: '" + ex.getValue() + "'"));

        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        "Parameter '" + ex.getName() + "' has invalid format");
        problemDetail.setTitle(ErrorCode.VALIDATION_FAILED.getDefaultTitle());
        problemDetail.setType(URI.create(ErrorCode.VALIDATION_FAILED.getProblemTypeUri()));
        problemDetail.setInstance(resolveInstanceUri(request));
        problemDetail.setProperty(PROPERTY_CODE, ErrorCode.VALIDATION_FAILED.name());
        problemDetail.setProperty(PROPERTY_REQUEST_ID, requestId);
        problemDetail.setProperty(PROPERTY_TIMESTAMP, clock.instant());
        problemDetail.setProperty(PROPERTY_ERRORS, validationErrors);

        log.warn(
                "Parameter type mismatch [requestId={}]: {} = {}",
                requestId,
                ex.getName(),
                ex.getValue());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    /** Handles validation failures on method arguments and query parameters. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            org.springframework.web.method.annotation.HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        List<ValidationError> validationErrors = new ArrayList<>();
        ex.getAllValidationResults()
                .forEach(
                        result -> {
                            String paramName = result.getMethodParameter().getParameterName();
                            result.getResolvableErrors()
                                    .forEach(
                                            error ->
                                                    validationErrors.add(
                                                            new ValidationError(
                                                                    paramName,
                                                                    error.getDefaultMessage())));
                        });

        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST, "Validation failed for request parameters");
        problemDetail.setTitle(ErrorCode.VALIDATION_FAILED.getDefaultTitle());
        problemDetail.setType(URI.create(ErrorCode.VALIDATION_FAILED.getProblemTypeUri()));
        problemDetail.setInstance(resolveInstanceUri(request));
        problemDetail.setProperty(PROPERTY_CODE, ErrorCode.VALIDATION_FAILED.name());
        problemDetail.setProperty(PROPERTY_REQUEST_ID, requestId);
        problemDetail.setProperty(PROPERTY_TIMESTAMP, clock.instant());
        problemDetail.setProperty(PROPERTY_ERRORS, validationErrors);

        log.warn(
                "Handler method validation failed [requestId={}]: {} error(s)",
                requestId,
                validationErrors.size());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(problemDetail);
    }

    /** Handles JPA optimistic locking concurrency failures. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(
            OptimisticLockingFailureException ex, WebRequest request) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        ErrorCode errorCode = ErrorCode.CONCURRENT_MODIFICATION;
        HttpStatus status = HttpStatus.valueOf(errorCode.getHttpStatus());

        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        status,
                        "Resource has been modified by another concurrent transaction. Please"
                                + " refresh and retry.");
        problemDetail.setTitle(errorCode.getDefaultTitle());
        problemDetail.setType(URI.create(errorCode.getProblemTypeUri()));
        problemDetail.setInstance(resolveInstanceUri(request));
        problemDetail.setProperty(PROPERTY_CODE, errorCode.name());
        problemDetail.setProperty(PROPERTY_REQUEST_ID, requestId);
        problemDetail.setProperty(PROPERTY_TIMESTAMP, clock.instant());

        log.warn("Optimistic locking conflict [requestId={}]: {}", requestId, ex.getMessage());
        return ResponseEntity.status(status).body(problemDetail);
    }

    /** Handles pessimistic locking and database lock acquisition timeouts. */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handlePessimisticLockingFailure(
            PessimisticLockingFailureException ex, WebRequest request) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        ErrorCode errorCode = ErrorCode.LOCK_TIMEOUT;
        HttpStatus status = HttpStatus.valueOf(errorCode.getHttpStatus());

        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        status,
                        "Resource lock acquisition timed out. Please retry the transaction.");
        problemDetail.setTitle(errorCode.getDefaultTitle());
        problemDetail.setType(URI.create(errorCode.getProblemTypeUri()));
        problemDetail.setInstance(resolveInstanceUri(request));
        problemDetail.setProperty(PROPERTY_CODE, errorCode.name());
        problemDetail.setProperty(PROPERTY_REQUEST_ID, requestId);
        problemDetail.setProperty(PROPERTY_TIMESTAMP, clock.instant());

        log.warn("Pessimistic lock timeout [requestId={}]: {}", requestId, ex.getMessage());
        return ResponseEntity.status(status).body(problemDetail);
    }

    /** Handles unique constraint and database integrity violations safely. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, WebRequest request) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        ErrorCode errorCode = ErrorCode.DUPLICATE_RESOURCE;
        HttpStatus status = HttpStatus.valueOf(errorCode.getHttpStatus());

        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        status,
                        "Data integrity constraint violated: resource or unique field already"
                                + " exists.");
        problemDetail.setTitle(errorCode.getDefaultTitle());
        problemDetail.setType(URI.create(errorCode.getProblemTypeUri()));
        problemDetail.setInstance(resolveInstanceUri(request));
        problemDetail.setProperty(PROPERTY_CODE, errorCode.name());
        problemDetail.setProperty(PROPERTY_REQUEST_ID, requestId);
        problemDetail.setProperty(PROPERTY_TIMESTAMP, clock.instant());

        log.warn("Data integrity conflict [requestId={}]: {}", requestId, ex.getMessage());
        return ResponseEntity.status(status).body(problemDetail);
    }

    /** Handles Spring Security method-level access denied exceptions (403 ACCESS_DENIED). */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDeniedException(
            org.springframework.security.access.AccessDeniedException ex, WebRequest request) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        ErrorCode errorCode = ErrorCode.ACCESS_DENIED;
        HttpStatus status = HttpStatus.valueOf(errorCode.getHttpStatus());

        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        status, "You do not have permission to access this resource");
        problemDetail.setTitle(errorCode.getDefaultTitle());
        problemDetail.setType(URI.create(errorCode.getProblemTypeUri()));
        problemDetail.setInstance(resolveInstanceUri(request));
        problemDetail.setProperty(PROPERTY_CODE, errorCode.name());
        problemDetail.setProperty(PROPERTY_REQUEST_ID, requestId);
        problemDetail.setProperty(PROPERTY_TIMESTAMP, clock.instant());

        log.warn("Access denied [requestId={}]: {}", requestId, ex.getMessage());
        return ResponseEntity.status(status).body(problemDetail);
    }

    /** Handles Spring Security authentication exceptions (401 UNAUTHORIZED). */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationException(
            org.springframework.security.core.AuthenticationException ex, WebRequest request) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        ErrorCode errorCode = ErrorCode.UNAUTHORIZED;
        HttpStatus status = HttpStatus.valueOf(errorCode.getHttpStatus());

        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        status, "Authentication is required to access this resource");
        problemDetail.setTitle(errorCode.getDefaultTitle());
        problemDetail.setType(URI.create(errorCode.getProblemTypeUri()));
        problemDetail.setInstance(resolveInstanceUri(request));
        problemDetail.setProperty(PROPERTY_CODE, errorCode.name());
        problemDetail.setProperty(PROPERTY_REQUEST_ID, requestId);
        problemDetail.setProperty(PROPERTY_TIMESTAMP, clock.instant());

        log.warn("Authentication failed [requestId={}]: {}", requestId, ex.getMessage());
        return ResponseEntity.status(status).body(problemDetail);
    }

    /** Catches and secures all unexpected exceptions, preventing stack trace leaks. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleAllUncaughtExceptions(
            Exception ex, WebRequest request) {
        String requestId = RequestIdFilter.getCurrentRequestId();

        log.error(
                "Unhandled internal server error [requestId={}]: {}",
                requestId,
                ex.getMessage(),
                ex);

        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "An unexpected error occurred. Please contact support with request ID: "
                                + requestId);
        problemDetail.setTitle(ErrorCode.INTERNAL_ERROR.getDefaultTitle());
        problemDetail.setType(URI.create(ErrorCode.INTERNAL_ERROR.getProblemTypeUri()));
        problemDetail.setInstance(resolveInstanceUri(request));
        problemDetail.setProperty(PROPERTY_CODE, ErrorCode.INTERNAL_ERROR.name());
        problemDetail.setProperty(PROPERTY_REQUEST_ID, requestId);
        problemDetail.setProperty(PROPERTY_TIMESTAMP, clock.instant());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
    }

    private URI resolveInstanceUri(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return URI.create(servletWebRequest.getRequest().getRequestURI());
        }
        return URI.create("about:blank");
    }
}

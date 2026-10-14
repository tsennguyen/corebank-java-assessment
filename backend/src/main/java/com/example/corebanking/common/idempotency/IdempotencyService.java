package com.example.corebanking.common.idempotency;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service orchestrating idempotency lifecycle: key validation, request hashing, database-level
 * locking, replay of previous executions, and atomic persistence with financial transactions.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final long ttlHours;

    public IdempotencyService(
            IdempotencyRepository idempotencyRepository,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${app.idempotency.ttl-hours:24}") long ttlHours) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.ttlHours = ttlHours;
    }

    /** Validates that the idempotency key header is present and non-blank. */
    public void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessRuleException(
                    ErrorCode.IDEMPOTENCY_KEY_MISSING,
                    "Required header 'Idempotency-Key' is missing or blank");
        }
    }

    /** Computes a deterministic SHA-256 hash of the canonical request method, path, and payload. */
    public String computeRequestHash(String method, String path, Object payload) {
        try {
            String canonicalJson = payload != null ? objectMapper.writeValueAsString(payload) : "";
            String input = method.toUpperCase() + ":" + path + ":" + canonicalJson;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("Failed to compute request hash", e);
        }
    }

    /**
     * Executes the business action idempotently. If the key has already been executed with an
     * identical payload, returns the previously cached response. If the key is reused with a
     * different payload, throws BusinessRuleException(IDEMPOTENCY_KEY_REUSED). If the key is new,
     * executes the action and persists the idempotency record in the same active transaction.
     */
    @Transactional
    public <T> IdempotencyResult<T> execute(
            String idempotencyKey,
            String scope,
            String method,
            String path,
            Object requestPayload,
            Class<T> responseType,
            Supplier<T> action) {
        validateKey(idempotencyKey);
        String trimmedKey = idempotencyKey.trim();
        String currentScope = (scope == null || scope.isBlank()) ? "DEFAULT" : scope.trim();
        String requestHash = computeRequestHash(method, path, requestPayload);

        try {
            idempotencyRepository.acquireAdvisoryLock(trimmedKey);
        } catch (Exception e) {
            log.debug("Advisory lock acquisition skipped or unsupported: {}", e.getMessage());
        }

        IdempotencyKeyId keyId = new IdempotencyKeyId(trimmedKey, currentScope);
        Optional<IdempotencyKey> existing = idempotencyRepository.findById(keyId);

        if (existing.isPresent()) {
            IdempotencyKey record = existing.get();
            if (!record.getRequestHash().equals(requestHash)) {
                throw new BusinessRuleException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency key '"
                                + trimmedKey
                                + "' has already been used with a different request payload");
            }
            try {
                T cachedResponse = objectMapper.readValue(record.getResponseBody(), responseType);
                log.info(
                        "Idempotent replay served [key='{}', scope='{}', status={}]",
                        trimmedKey,
                        currentScope,
                        record.getResponseStatus());
                return IdempotencyResult.replayed(cachedResponse);
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize cached response body for key='{}'", trimmedKey, e);
                throw new IllegalStateException(
                        "Failed to deserialize idempotency cached response", e);
            }
        }

        T response = action.get();

        try {
            String responseBodyJson = objectMapper.writeValueAsString(response);
            Instant now = clock.instant();
            Instant expiresAt = now.plus(ttlHours, ChronoUnit.HOURS);
            IdempotencyKey newRecord =
                    IdempotencyKey.of(
                            trimmedKey,
                            currentScope,
                            requestHash,
                            201,
                            responseBodyJson,
                            now,
                            expiresAt);
            idempotencyRepository.saveAndFlush(newRecord);
            log.info(
                    "Idempotency key recorded [key='{}', scope='{}', expiresAt={}]",
                    trimmedKey,
                    currentScope,
                    expiresAt);
            return IdempotencyResult.executed(response);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response body for idempotency key='{}'", trimmedKey, e);
            throw new IllegalStateException("Failed to serialize idempotency response", e);
        }
    }
}

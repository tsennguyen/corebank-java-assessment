package com.example.corebanking.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.domain.TransactionStatus;
import com.example.corebanking.transaction.domain.TransactionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock private IdempotencyRepository idempotencyRepository;

    private ObjectMapper objectMapper;
    private Clock clock;
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        clock = Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        idempotencyService = new IdempotencyService(idempotencyRepository, objectMapper, clock, 24);
    }

    private TransactionResponse sampleResponse() {
        return new TransactionResponse(
                101L,
                "TXN-20261013-ABC123DEF456",
                TransactionType.DEPOSIT,
                TransactionStatus.COMPLETED,
                new BigDecimal("500000.00"),
                BigDecimal.ZERO,
                null,
                1L,
                null,
                new BigDecimal("1500000.00"),
                new BigDecimal("500000.00"),
                "Hà Nội",
                "Nạp tiền thử nghiệm",
                Instant.parse("2026-10-13T10:00:00Z"));
    }

    @Test
    @DisplayName("validateKey throws IDEMPOTENCY_KEY_MISSING when key is null or blank")
    void validateKey_whenNullOrBlank_shouldThrowIdempotencyKeyMissing() {
        assertThatThrownBy(() -> idempotencyService.validateKey(null))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_MISSING);

        assertThatThrownBy(() -> idempotencyService.validateKey("   "))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_MISSING);
    }

    @Test
    @DisplayName("computeRequestHash produces deterministic SHA-256 hex string")
    void computeRequestHash_shouldProduceDeterministicHex() {
        DepositRequest req1 =
                new DepositRequest(
                        1L, new BigDecimal("500000.00"), "Hà Nội", "Nạp tiền thử nghiệm");
        DepositRequest req2 =
                new DepositRequest(
                        1L, new BigDecimal("500000.00"), "Hà Nội", "Nạp tiền thử nghiệm");

        String hash1 =
                idempotencyService.computeRequestHash(
                        "POST", "/api/v1/transactions/deposits", req1);
        String hash2 =
                idempotencyService.computeRequestHash(
                        "POST", "/api/v1/transactions/deposits", req2);

        assertThat(hash1).isNotNull().isEqualTo(hash2).hasSize(64);
    }

    @Test
    @DisplayName("computeRequestHash produces different hash when payload differs")
    void computeRequestHash_whenPayloadDiffers_shouldProduceDifferentHash() {
        DepositRequest req1 =
                new DepositRequest(
                        1L, new BigDecimal("500000.00"), "Hà Nội", "Nạp tiền thử nghiệm");
        DepositRequest req2 =
                new DepositRequest(
                        1L, new BigDecimal("700000.00"), "Hà Nội", "Nạp tiền thử nghiệm");

        String hash1 =
                idempotencyService.computeRequestHash(
                        "POST", "/api/v1/transactions/deposits", req1);
        String hash2 =
                idempotencyService.computeRequestHash(
                        "POST", "/api/v1/transactions/deposits", req2);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("execute executes business action and persists key when key is new")
    void execute_shouldExecuteActionAndSaveKey_whenKeyIsNew() {
        DepositRequest request =
                new DepositRequest(
                        1L, new BigDecimal("500000.00"), "Hà Nội", "Nạp tiền thử nghiệm");
        TransactionResponse response = sampleResponse();

        when(idempotencyRepository.findById(any())).thenReturn(Optional.empty());

        AtomicBoolean executed = new AtomicBoolean(false);
        IdempotencyResult<TransactionResponse> result =
                idempotencyService.execute(
                        "uuid-key-1",
                        "DEFAULT",
                        "POST",
                        "/api/v1/transactions/deposits",
                        request,
                        TransactionResponse.class,
                        () -> {
                            executed.set(true);
                            return response;
                        });

        assertThat(executed.get()).isTrue();
        assertThat(result.replayed()).isFalse();
        assertThat(result.data().id()).isEqualTo(101L);

        ArgumentCaptor<IdempotencyKey> captor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(idempotencyRepository).saveAndFlush(captor.capture());
        IdempotencyKey saved = captor.getValue();
        assertThat(saved.getId().getIdemKey()).isEqualTo("uuid-key-1");
        assertThat(saved.getId().getScope()).isEqualTo("DEFAULT");
        assertThat(saved.getResponseStatus()).isEqualTo(201);
        assertThat(saved.getExpiresAt()).isEqualTo(Instant.parse("2026-10-14T10:00:00Z"));
    }

    @Test
    @DisplayName("execute returns cached response with replayed=true when key and payload match")
    void execute_shouldReturnCachedResponse_whenKeyAndPayloadMatch() throws Exception {
        DepositRequest request =
                new DepositRequest(
                        1L, new BigDecimal("500000.00"), "Hà Nội", "Nạp tiền thử nghiệm");
        TransactionResponse response = sampleResponse();
        String json = objectMapper.writeValueAsString(response);
        String hash =
                idempotencyService.computeRequestHash(
                        "POST", "/api/v1/transactions/deposits", request);

        IdempotencyKey existingRecord =
                IdempotencyKey.of(
                        "uuid-key-1",
                        "DEFAULT",
                        hash,
                        201,
                        json,
                        Instant.parse("2026-10-13T09:00:00Z"),
                        Instant.parse("2026-10-14T09:00:00Z"));

        when(idempotencyRepository.findById(any())).thenReturn(Optional.of(existingRecord));

        AtomicBoolean executed = new AtomicBoolean(false);
        IdempotencyResult<TransactionResponse> result =
                idempotencyService.execute(
                        "uuid-key-1",
                        "DEFAULT",
                        "POST",
                        "/api/v1/transactions/deposits",
                        request,
                        TransactionResponse.class,
                        () -> {
                            executed.set(true);
                            return response;
                        });

        assertThat(executed.get()).isFalse();
        assertThat(result.replayed()).isTrue();
        assertThat(result.data().id()).isEqualTo(101L);
        assertThat(result.data().referenceNo()).isEqualTo("TXN-20261013-ABC123DEF456");

        verify(idempotencyRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("execute throws IDEMPOTENCY_KEY_REUSED when key matches but payload differs")
    void execute_shouldThrowIdempotencyKeyReused_whenPayloadDiffers() throws Exception {
        DepositRequest req1 =
                new DepositRequest(
                        1L, new BigDecimal("500000.00"), "Hà Nội", "Nạp tiền thử nghiệm");
        DepositRequest req2 =
                new DepositRequest(1L, new BigDecimal("999999.00"), "Hà Nội", "Nạp số khác");

        String hash1 =
                idempotencyService.computeRequestHash(
                        "POST", "/api/v1/transactions/deposits", req1);

        IdempotencyKey existingRecord =
                IdempotencyKey.of(
                        "uuid-key-1",
                        "DEFAULT",
                        hash1,
                        201,
                        "{}",
                        Instant.parse("2026-10-13T09:00:00Z"),
                        Instant.parse("2026-10-14T09:00:00Z"));

        when(idempotencyRepository.findById(any())).thenReturn(Optional.of(existingRecord));

        assertThatThrownBy(
                        () ->
                                idempotencyService.execute(
                                        "uuid-key-1",
                                        "DEFAULT",
                                        "POST",
                                        "/api/v1/transactions/deposits",
                                        req2,
                                        TransactionResponse.class,
                                        this::sampleResponse))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex -> {
                            BusinessRuleException bre = (BusinessRuleException) ex;
                            assertThat(bre.getErrorCode())
                                    .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                            assertThat(bre.getMessage())
                                    .contains(
                                            "Idempotency key 'uuid-key-1' has already been used"
                                                    + " with a different request payload");
                        });

        verify(idempotencyRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("execute does not persist idempotency key when business action throws exception")
    void execute_shouldNotSaveKey_whenBusinessActionThrowsException() {
        DepositRequest request =
                new DepositRequest(
                        1L, new BigDecimal("500000.00"), "Hà Nội", "Nạp tiền thử nghiệm");

        when(idempotencyRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                idempotencyService.execute(
                                        "uuid-key-1",
                                        "DEFAULT",
                                        "POST",
                                        "/api/v1/transactions/deposits",
                                        request,
                                        TransactionResponse.class,
                                        () -> {
                                            throw new BusinessRuleException(
                                                    ErrorCode.LIMIT_EXCEEDED,
                                                    "Transaction limit exceeded");
                                        }))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.LIMIT_EXCEEDED);

        verify(idempotencyRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("validateKey throws VALIDATION_FAILED when key exceeds 128 characters")
    void validateKey_whenExceeds128Chars_shouldThrowValidationFailed() {
        assertThatThrownBy(() -> idempotencyService.validateKey("k".repeat(129)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("execute deletes expired record and executes new action with replayed=false")
    void execute_whenExistingRecordExpired_shouldDeleteAndExecuteFreshAction() throws Exception {
        DepositRequest request =
                new DepositRequest(
                        1L, new BigDecimal("500000.00"), "Hà Nội", "Nạp tiền thử nghiệm");
        TransactionResponse response = sampleResponse();
        String json = objectMapper.writeValueAsString(response);
        String hash =
                idempotencyService.computeRequestHash(
                        "POST", "/api/v1/transactions/deposits", request);

        IdempotencyKey expiredRecord =
                IdempotencyKey.of(
                        "uuid-key-expired",
                        "DEFAULT",
                        hash,
                        201,
                        json,
                        Instant.parse("2026-10-11T09:00:00Z"),
                        Instant.parse("2026-10-12T09:00:00Z")); // expired before 2026-10-13T10:00Z

        when(idempotencyRepository.findById(any())).thenReturn(Optional.of(expiredRecord));

        AtomicBoolean executed = new AtomicBoolean(false);
        IdempotencyResult<TransactionResponse> result =
                idempotencyService.execute(
                        "uuid-key-expired",
                        "DEFAULT",
                        "POST",
                        "/api/v1/transactions/deposits",
                        request,
                        TransactionResponse.class,
                        () -> {
                            executed.set(true);
                            return response;
                        });

        assertThat(executed.get()).isTrue();
        assertThat(result.replayed()).isFalse();
        verify(idempotencyRepository).delete(expiredRecord);
        verify(idempotencyRepository).flush();
        verify(idempotencyRepository).saveAndFlush(any(IdempotencyKey.class));
    }

    @Test
    @DisplayName("execute rethrows PessimisticLockingFailureException from acquireAdvisoryLock")
    void execute_whenAdvisoryLockTimesOut_shouldRethrowLockException() {
        DepositRequest request =
                new DepositRequest(
                        1L, new BigDecimal("500000.00"), "Hà Nội", "Nạp tiền thử nghiệm");

        org.mockito.Mockito.doThrow(
                        new org.springframework.dao.CannotAcquireLockException(
                                "lock timeout on advisory lock"))
                .when(idempotencyRepository)
                .acquireAdvisoryLock(any(), any());

        assertThatThrownBy(
                        () ->
                                idempotencyService.execute(
                                        "uuid-key-lock",
                                        "DEFAULT",
                                        "POST",
                                        "/api/v1/transactions/deposits",
                                        request,
                                        TransactionResponse.class,
                                        this::sampleResponse))
                .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
    }

    @Test
    @DisplayName("cleanupScheduler calls deleteExpiredKeys with current clock instant")
    void cleanupScheduler_shouldDeleteExpiredKeys() {
        IdempotencyCleanupScheduler scheduler =
                new IdempotencyCleanupScheduler(idempotencyRepository, clock);
        when(idempotencyRepository.deleteExpiredKeys(any())).thenReturn(5);

        scheduler.cleanupExpiredKeys();

        verify(idempotencyRepository).deleteExpiredKeys(Instant.parse("2026-10-13T10:00:00Z"));
    }
}

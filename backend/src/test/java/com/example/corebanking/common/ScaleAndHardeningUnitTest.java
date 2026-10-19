package com.example.corebanking.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.account.domain.event.AccountLimitsUpdatedEvent;
import com.example.corebanking.account.domain.event.AccountStatusChangedEvent;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.auth.infrastructure.LoginRateLimiter;
import com.example.corebanking.auth.infrastructure.RefreshTokenStore;
import com.example.corebanking.auth.infrastructure.SecurityProperties;
import com.example.corebanking.common.api.CursorPageResponse;
import com.example.corebanking.common.audit.AuditLog;
import com.example.corebanking.common.audit.AuditLogController;
import com.example.corebanking.common.audit.AuditLogRepository;
import com.example.corebanking.common.audit.AuditLogResponse;
import com.example.corebanking.common.audit.AuditLogService;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.common.scheduler.SchedulerLockService;
import com.example.corebanking.recurring.application.RecurringExecutor;
import com.example.corebanking.recurring.application.RecurringTransactionJob;
import com.example.corebanking.recurring.domain.RecurringProperties;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.application.TransactionService;
import com.example.corebanking.transaction.domain.FeePolicy;
import com.example.corebanking.transaction.domain.LimitPolicy;
import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.domain.TransactionType;
import com.example.corebanking.transaction.domain.event.TransactionCompletedEvent;
import com.example.corebanking.transaction.infrastructure.TransactionReferenceGenerator;
import com.example.corebanking.transaction.infrastructure.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Unit tests for Epic 6 Scale & Hardening components: {@link CursorPageResponse} (Story 6.1),
 * {@link SchedulerLockService} (Story 6.2), {@link AuditLogService} & {@link AuditLogController}
 * (Story 6.3), and atomic Redis {@code GETDEL} / Lua {@code INCR+EXPIRE} hardening (NC-07).
 */
class ScaleAndHardeningUnitTest {

    private final Clock fixedClock =
            Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    @Test
    @DisplayName(
            "CursorPageResponse encodes and decodes valid cursor and throws 400 INVALID_CURSOR on"
                    + " tampered input (Story 6.1)")
    void cursorPageResponse_encodeAndDecode_shouldValidateStrictly() {
        Instant now = Instant.parse("2026-10-19T02:30:45.123456Z");
        String encoded = CursorPageResponse.encodeCursor(now, 98765L);
        assertThat(encoded).isNotBlank().doesNotContain("=");

        CursorPageResponse.DecodedCursor decoded = CursorPageResponse.decodeCursor(encoded);
        assertThat(decoded.createdAt()).isEqualTo(now);
        assertThat(decoded.id()).isEqualTo(98765L);

        for (String invalid :
                List.of(
                        "",
                        "   ",
                        "%%%not-base64%%%",
                        CursorPageResponse.encodeCursor(now, 0L),
                        java.util.Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString("no-pipe-delimiter".getBytes()),
                        java.util.Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString("not-a-timestamp|123".getBytes()),
                        java.util.Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString("2026-10-19T02:30:45Z|not-a-number".getBytes()))) {
            assertThatThrownBy(() -> CursorPageResponse.decodeCursor(invalid))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_CURSOR);
        }
    }

    @Test
    @DisplayName(
            "TransactionService.getAccountTransactionsByCursor handles first page, cursor page,"
                    + " and validation errors (Story 6.1)")
    void transactionService_getAccountTransactionsByCursor_shouldPaginateAndValidate() {
        AccountRepository accountRepo = mock(AccountRepository.class);
        TransactionRepository txRepo = mock(TransactionRepository.class);
        TransactionReferenceGenerator refGen = mock(TransactionReferenceGenerator.class);
        FeePolicy feePolicy = mock(FeePolicy.class);
        LimitPolicy limitPolicy = mock(LimitPolicy.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        TransactionService txService =
                new TransactionService(
                        accountRepo, txRepo, refGen, feePolicy, limitPolicy, fixedClock, publisher);

        when(accountRepo.existsById(999L)).thenReturn(false);
        assertThatThrownBy(() -> txService.getAccountTransactionsByCursor(999L, null, 20))
                .isInstanceOf(ResourceNotFoundException.class);

        when(accountRepo.existsById(10L)).thenReturn(true);
        assertThatThrownBy(() -> txService.getAccountTransactionsByCursor(10L, null, 0))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> txService.getAccountTransactionsByCursor(10L, null, 101))
                .isInstanceOf(BusinessRuleException.class);

        Transaction tx1 =
                Transaction.createDeposit(
                        "TXN-1",
                        10L,
                        new BigDecimal("100000.00"),
                        new BigDecimal("100000.00"),
                        "Hà Nội",
                        "Dep 1");
        ReflectionTestUtils.setField(tx1, "id", 102L);
        ReflectionTestUtils.setField(tx1, "createdAt", Instant.parse("2026-10-19T02:00:00Z"));

        Transaction tx2 =
                Transaction.createDeposit(
                        "TXN-2",
                        10L,
                        new BigDecimal("200000.00"),
                        new BigDecimal("300000.00"),
                        "Hà Nội",
                        "Dep 2");
        ReflectionTestUtils.setField(tx2, "id", 101L);
        ReflectionTestUtils.setField(tx2, "createdAt", Instant.parse("2026-10-19T01:00:00Z"));

        // Request size=1 when 2 rows exist -> hasNext=true, nextCursor!=null
        when(txRepo.findKeysetFirstPage(eq(10L), eq(PageRequest.of(0, 2))))
                .thenReturn(List.of(tx1, tx2));
        CursorPageResponse<TransactionResponse> page1 =
                txService.getAccountTransactionsByCursor(10L, null, 1);
        assertThat(page1.content()).hasSize(1);
        assertThat(page1.hasNext()).isTrue();
        assertThat(page1.nextCursor()).isNotBlank();

        // Request next page using nextCursor -> 1 row returned -> hasNext=false, nextCursor=null
        when(txRepo.findKeysetAfterCursor(
                        eq(10L),
                        eq(Instant.parse("2026-10-19T02:00:00Z")),
                        eq(102L),
                        eq(PageRequest.of(0, 2))))
                .thenReturn(List.of(tx2));
        CursorPageResponse<TransactionResponse> page2 =
                txService.getAccountTransactionsByCursor(10L, page1.nextCursor(), 1);
        assertThat(page2.content()).hasSize(1);
        assertThat(page2.hasNext()).isFalse();
        assertThat(page2.nextCursor()).isNull();
    }

    @Test
    @DisplayName(
            "SchedulerLockService and RecurringTransactionJob execute task only when cluster lock"
                    + " is acquired (Story 6.2)")
    void schedulerLockService_shouldExecuteOnlyWhenLockAcquired() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionStatus txStatus = new SimpleTransactionStatus();
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.update()).thenReturn(1, 1, 0);

        SchedulerLockService lockService =
                new SchedulerLockService(jdbcClient, fixedClock, txManager);
        RecurringExecutor executor = mock(RecurringExecutor.class);
        when(executor.executeNextDue()).thenReturn(true, false);

        RecurringProperties props = new RecurringProperties();
        props.setSchedulerEnabled(true);
        props.setBatchSize(10);

        RecurringTransactionJob node1 =
                new RecurringTransactionJob(executor, props, lockService, "node-1");
        RecurringTransactionJob node2 =
                new RecurringTransactionJob(executor, props, lockService, "node-2");

        assertThat(node1.runClusterLockedDueTransactions()).isEqualTo(1);
        assertThat(node2.runClusterLockedDueTransactions()).isZero();
    }

    @Test
    @DisplayName(
            "AuditLogService and AuditLogController record events, mask sensitive fields, and"
                    + " query audit logs (Story 6.3)")
    @SuppressWarnings("unchecked")
    void auditLogService_eventsAndController_shouldWork() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        AuditLogService service =
                new AuditLogService(repository, null, new ObjectMapper(), fixedClock);

        when(repository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        // Trigger all event listeners and alert status hook
        service.onAccountLimitsUpdated(
                new AccountLimitsUpdatedEvent(10L, 1L, fixedClock.instant()));
        service.onAccountStatusChanged(
                new AccountStatusChangedEvent(
                        10L,
                        1L,
                        AccountStatus.ACTIVE,
                        AccountStatus.LOCKED,
                        "Security lock",
                        "admin",
                        fixedClock.instant()));
        service.onTransactionCompleted(
                new TransactionCompletedEvent(
                        500L,
                        "TXN-REV-1",
                        TransactionType.REVERSAL,
                        new BigDecimal("1000000.00"),
                        BigDecimal.ZERO,
                        10L,
                        20L,
                        fixedClock.instant()));
        service.recordAlertStatusChange(
                77L,
                10L,
                500L,
                "NEW",
                "INVESTIGATING",
                "Checking customer",
                "admin",
                fixedClock.instant());

        AuditLog saved =
                service.recordAudit(
                        "admin",
                        AuditLogService.ACTION_ACCOUNT_STATUS_CHANGED,
                        "ACCOUNT",
                        "42",
                        "{\"status\":\"ACTIVE\"}",
                        "{\"status\":\"LOCKED\"}",
                        fixedClock.instant());
        assertThat(saved.getActor()).isEqualTo("admin");
        assertThat(saved.getAction()).isEqualTo("ACCOUNT_STATUS_CHANGED");
        assertThat(saved.getEntityType()).isEqualTo("ACCOUNT");
        assertThat(saved.getEntityId()).isEqualTo("42");
        assertThat(saved.getBeforeJson()).contains("ACTIVE");
        assertThat(saved.getAfterJson()).contains("LOCKED");
        assertThat(saved.getRequestId()).isNotBlank();
        assertThat(saved.getIp()).isNotBlank();
        assertThat(saved.getCreatedAt()).isEqualTo(fixedClock.instant());

        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(saved)));

        AuditLogController controller = new AuditLogController(service);
        ResponseEntity<com.example.corebanking.common.api.PageResponse<AuditLogResponse>> resp =
                controller.searchAuditLogs(
                        "admin",
                        "ACCOUNT_STATUS_CHANGED",
                        "ACCOUNT",
                        LocalDate.of(2026, 10, 1),
                        LocalDate.of(2026, 10, 31),
                        null,
                        null,
                        PageRequest.of(0, 20));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().content()).hasSize(1);

        // Invalid sort field -> 400 INVALID_SORT_FIELD
        assertThatThrownBy(
                        () ->
                                service.searchAuditLogs(
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        PageRequest.of(0, 20, Sort.by("hackedField"))))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_SORT_FIELD);
    }

    @Test
    @DisplayName(
            "RefreshTokenStore uses atomic getAndDelete (GETDEL) and LoginRateLimiter uses atomic"
                    + " Lua script in Redis mode (NC-07 hardening)")
    @SuppressWarnings("unchecked")
    void redisAtomicOperations_shouldUseGetAndDeleteAndLuaScript() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        SecurityProperties props = new SecurityProperties();
        RefreshTokenStore tokenStore =
                new RefreshTokenStore(props, fixedClock, redisTemplate, true);

        String r1 = tokenStore.issueNewFamilyToken(99L);
        String hash = RefreshTokenStore.sha256Hex(r1);
        long expMs = fixedClock.instant().plusSeconds(604800L).toEpochMilli();
        when(valueOps.getAndDelete("rt:" + hash)).thenReturn("99|fam-1|" + expMs);

        RefreshTokenStore.ConsumedRefreshToken consumed = tokenStore.consumeForRotation(r1);
        assertThat(consumed.userId()).isEqualTo(99L);
        assertThat(consumed.familyId()).isEqualTo("fam-1");
        verify(valueOps).getAndDelete("rt:" + hash);

        LoginRateLimiter limiter = new LoginRateLimiter(props, fixedClock, redisTemplate, true);
        when(redisTemplate.execute(
                        any(DefaultRedisScript.class),
                        eq(List.of("rl:login:admin|10.0.0.1")),
                        eq("300")))
                .thenReturn(1L);

        limiter.recordFailure("admin", "10.0.0.1");
        verify(redisTemplate)
                .execute(
                        any(DefaultRedisScript.class),
                        eq(List.of("rl:login:admin|10.0.0.1")),
                        eq("300"));
    }
}

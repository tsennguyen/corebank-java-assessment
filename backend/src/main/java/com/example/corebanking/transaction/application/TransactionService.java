package com.example.corebanking.transaction.application;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.config.CacheConfig;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.Money;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.common.idempotency.IdempotencyResult;
import com.example.corebanking.common.idempotency.IdempotencyService;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import com.example.corebanking.transaction.api.dto.PaymentRequest;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.api.dto.TransactionSearchCriteria;
import com.example.corebanking.transaction.api.dto.TransferRequest;
import com.example.corebanking.transaction.api.dto.UpdateTransactionRequest;
import com.example.corebanking.transaction.api.dto.WithdrawalRequest;
import com.example.corebanking.transaction.domain.FeePolicy;
import com.example.corebanking.transaction.domain.LimitPolicy;
import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.domain.TransactionStatus;
import com.example.corebanking.transaction.domain.TransactionType;
import com.example.corebanking.transaction.domain.event.TransactionCompletedEvent;
import com.example.corebanking.transaction.domain.event.TransactionMetadataUpdatedEvent;
import com.example.corebanking.transaction.infrastructure.TransactionReferenceGenerator;
import com.example.corebanking.transaction.infrastructure.TransactionRepository;
import com.example.corebanking.transaction.infrastructure.TransactionSpecifications;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service managing money movement transactions, balance updates with pessimistic
 * locking, and paginated transaction queries.
 */
@Service
@Transactional(readOnly = true)
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
    public static final String TRANSACTIONS_METRIC = "banking.transactions";
    private static final String OUTCOME_SUCCESS = "SUCCESS";
    private static final String OUTCOME_FAILURE = "FAILURE";
    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("id", "createdAt", "amount", "fee");

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionReferenceGenerator referenceGenerator;
    private final FeePolicy feePolicy;
    private final LimitPolicy limitPolicy;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    @Nullable private final IdempotencyService idempotencyService;
    @Nullable private final MeterRegistry meterRegistry;

    public TransactionService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            TransactionReferenceGenerator referenceGenerator,
            FeePolicy feePolicy,
            LimitPolicy limitPolicy,
            Clock clock,
            ApplicationEventPublisher eventPublisher) {
        this(
                accountRepository,
                transactionRepository,
                referenceGenerator,
                feePolicy,
                limitPolicy,
                clock,
                eventPublisher,
                null,
                null);
    }

    public TransactionService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            TransactionReferenceGenerator referenceGenerator,
            FeePolicy feePolicy,
            LimitPolicy limitPolicy,
            Clock clock,
            ApplicationEventPublisher eventPublisher,
            @Nullable IdempotencyService idempotencyService) {
        this(
                accountRepository,
                transactionRepository,
                referenceGenerator,
                feePolicy,
                limitPolicy,
                clock,
                eventPublisher,
                idempotencyService,
                null);
    }

    @Autowired
    public TransactionService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            TransactionReferenceGenerator referenceGenerator,
            FeePolicy feePolicy,
            LimitPolicy limitPolicy,
            Clock clock,
            ApplicationEventPublisher eventPublisher,
            @Nullable IdempotencyService idempotencyService,
            @Nullable MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.referenceGenerator = referenceGenerator;
        this.feePolicy = feePolicy;
        this.limitPolicy = limitPolicy;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.idempotencyService = idempotencyService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Executes an idempotent atomic cash deposit into an active account.
     *
     * @param idempotencyKey unique key supplied by client
     * @param request deposit details
     * @return idempotency result wrapping transaction response and replay flag
     */
    @Transactional
    public IdempotencyResult<TransactionResponse> deposit(
            String idempotencyKey, DepositRequest request) {
        if (idempotencyService != null) {
            return idempotencyService.execute(
                    idempotencyKey,
                    resolveCurrentScope(),
                    "POST",
                    "/api/v1/transactions/deposits",
                    request,
                    TransactionResponse.class,
                    () -> executeDeposit(request));
        }
        return IdempotencyResult.executed(executeDeposit(request));
    }

    /**
     * Executes an atomic cash deposit into an active account under pessimistic write lock.
     *
     * @param request deposit details
     * @return persisted transaction response
     */
    @Transactional
    public TransactionResponse deposit(DepositRequest request) {
        return executeDeposit(request);
    }

    private TransactionResponse executeDeposit(DepositRequest request) {
        return recordTransactionTimer(
                TransactionType.DEPOSIT,
                () -> {
                    Account account =
                            accountRepository
                                    .findByIdForUpdate(request.accountId())
                                    .orElseThrow(
                                            () ->
                                                    new ResourceNotFoundException(
                                                            "Account", request.accountId()));

                    Money depositAmount = Money.of(request.amount());
                    account.credit(depositAmount);

                    String referenceNo = referenceGenerator.generate();
                    Transaction transaction =
                            Transaction.createDeposit(
                                    referenceNo,
                                    account.getId(),
                                    request.amount(),
                                    account.getBalance(),
                                    request.location(),
                                    request.description());

                    Transaction saved = transactionRepository.save(transaction);
                    eventPublisher.publishEvent(
                            new TransactionCompletedEvent(
                                    saved.getId(),
                                    saved.getReferenceNo(),
                                    saved.getType(),
                                    saved.getAmount(),
                                    saved.getFee(),
                                    saved.getSourceAccountId(),
                                    saved.getTargetAccountId(),
                                    clock.instant()));

                    log.info(
                            "Deposit completed [txnId={}, ref={}, accountId={}, amount={},"
                                    + " balanceAfter={}]",
                            saved.getId(),
                            saved.getReferenceNo(),
                            account.getId(),
                            saved.getAmount(),
                            saved.getTargetBalanceAfter());

                    return TransactionResponse.from(saved);
                });
    }

    /**
     * Executes an idempotent atomic cash withdrawal with limit validation and fee deduction.
     *
     * @param idempotencyKey unique key supplied by client
     * @param request withdrawal details
     * @return idempotency result wrapping transaction response and replay flag
     */
    @Transactional
    public IdempotencyResult<TransactionResponse> withdraw(
            String idempotencyKey, WithdrawalRequest request) {
        if (idempotencyService != null) {
            return idempotencyService.execute(
                    idempotencyKey,
                    resolveCurrentScope(),
                    "POST",
                    "/api/v1/transactions/withdrawals",
                    request,
                    TransactionResponse.class,
                    () -> executeWithdraw(request));
        }
        return IdempotencyResult.executed(executeWithdraw(request));
    }

    /**
     * Executes an atomic cash withdrawal with limit validation and fee deduction under pessimistic
     * write lock.
     *
     * @param request withdrawal details
     * @return persisted transaction response
     */
    @Transactional
    public TransactionResponse withdraw(WithdrawalRequest request) {
        return executeWithdraw(request);
    }

    private TransactionResponse executeWithdraw(WithdrawalRequest request) {
        return recordTransactionTimer(
                TransactionType.WITHDRAWAL,
                () -> {
                    Account account =
                            accountRepository
                                    .findByIdForUpdate(request.accountId())
                                    .orElseThrow(
                                            () ->
                                                    new ResourceNotFoundException(
                                                            "Account", request.accountId()));

                    Money amount = Money.of(request.amount());
                    BigDecimal dailySpent =
                            transactionRepository.sumOutgoingAmountSince(
                                    account.getId(), TransactionStatus.COMPLETED, getStartOfDay());
                    limitPolicy.validateLimits(account, amount, Money.of(dailySpent));

                    Money fee = feePolicy.calculateFee(TransactionType.WITHDRAWAL, amount);
                    Money totalDebit = amount.plus(fee);
                    account.debit(totalDebit);

                    String referenceNo = referenceGenerator.generate();
                    Transaction transaction =
                            Transaction.createWithdrawal(
                                    referenceNo,
                                    account.getId(),
                                    amount.getAmount(),
                                    fee.getAmount(),
                                    account.getBalance(),
                                    request.location(),
                                    request.description());

                    Transaction saved = transactionRepository.save(transaction);
                    eventPublisher.publishEvent(
                            new TransactionCompletedEvent(
                                    saved.getId(),
                                    saved.getReferenceNo(),
                                    saved.getType(),
                                    saved.getAmount(),
                                    saved.getFee(),
                                    saved.getSourceAccountId(),
                                    saved.getTargetAccountId(),
                                    clock.instant()));

                    log.info(
                            "Withdrawal completed [txnId={}, ref={}, accountId={}, amount={},"
                                    + " fee={}, balanceAfter={}]",
                            saved.getId(),
                            saved.getReferenceNo(),
                            account.getId(),
                            saved.getAmount(),
                            saved.getFee(),
                            saved.getSourceBalanceAfter());

                    return TransactionResponse.from(saved);
                });
    }

    /**
     * Executes an idempotent atomic fund transfer between two distinct accounts.
     *
     * @param idempotencyKey unique key supplied by client
     * @param request transfer details
     * @return idempotency result wrapping transaction response and replay flag
     */
    @Transactional
    public IdempotencyResult<TransactionResponse> transfer(
            String idempotencyKey, TransferRequest request) {
        if (idempotencyService != null) {
            return idempotencyService.execute(
                    idempotencyKey,
                    resolveCurrentScope(),
                    "POST",
                    "/api/v1/transactions/transfers",
                    request,
                    TransactionResponse.class,
                    () -> executeTransfer(request));
        }
        return IdempotencyResult.executed(executeTransfer(request));
    }

    /**
     * Executes an atomic fund transfer between two distinct accounts under ascending ID pessimistic
     * write locks to prevent deadlocks.
     *
     * @param request transfer details
     * @return persisted transaction response
     */
    @Transactional
    public TransactionResponse transfer(TransferRequest request) {
        return executeTransfer(request);
    }

    private TransactionResponse executeTransfer(TransferRequest request) {
        return recordTransactionTimer(
                TransactionType.TRANSFER,
                () -> {
                    if (request.sourceAccountId().equals(request.targetAccountId())) {
                        throw new BusinessRuleException(
                                ErrorCode.SAME_ACCOUNT_TRANSFER,
                                "Source and target accounts cannot be identical");
                    }

                    // Lock accounts in strictly ascending ID order to prevent circular wait
                    // deadlock (NC-01)
                    Long firstId = Math.min(request.sourceAccountId(), request.targetAccountId());
                    Long secondId = Math.max(request.sourceAccountId(), request.targetAccountId());

                    Account firstAccount =
                            accountRepository
                                    .findByIdForUpdate(firstId)
                                    .orElseThrow(
                                            () ->
                                                    new ResourceNotFoundException(
                                                            "Account", firstId));
                    Account secondAccount =
                            accountRepository
                                    .findByIdForUpdate(secondId)
                                    .orElseThrow(
                                            () ->
                                                    new ResourceNotFoundException(
                                                            "Account", secondId));

                    Account source =
                            request.sourceAccountId().equals(firstId)
                                    ? firstAccount
                                    : secondAccount;
                    Account target =
                            request.targetAccountId().equals(firstId)
                                    ? firstAccount
                                    : secondAccount;

                    Money amount = Money.of(request.amount());
                    BigDecimal dailySpent =
                            transactionRepository.sumOutgoingAmountSince(
                                    source.getId(), TransactionStatus.COMPLETED, getStartOfDay());
                    limitPolicy.validateLimits(source, amount, Money.of(dailySpent));

                    Money fee = feePolicy.calculateFee(TransactionType.TRANSFER, amount);
                    Money totalDebit = amount.plus(fee);

                    source.debit(totalDebit);
                    target.credit(amount);

                    String referenceNo = referenceGenerator.generate();
                    Transaction transaction =
                            Transaction.createTransfer(
                                    referenceNo,
                                    source.getId(),
                                    target.getId(),
                                    amount.getAmount(),
                                    fee.getAmount(),
                                    source.getBalance(),
                                    target.getBalance(),
                                    request.location(),
                                    request.description());

                    Transaction saved = transactionRepository.save(transaction);
                    eventPublisher.publishEvent(
                            new TransactionCompletedEvent(
                                    saved.getId(),
                                    saved.getReferenceNo(),
                                    saved.getType(),
                                    saved.getAmount(),
                                    saved.getFee(),
                                    saved.getSourceAccountId(),
                                    saved.getTargetAccountId(),
                                    clock.instant()));

                    log.info(
                            "Transfer completed [txnId={}, ref={}, from={}, to={}, amount={},"
                                    + " fee={}]",
                            saved.getId(),
                            saved.getReferenceNo(),
                            source.getId(),
                            target.getId(),
                            saved.getAmount(),
                            saved.getFee());

                    return TransactionResponse.from(saved);
                });
    }

    /**
     * Executes an idempotent outbound payment with limit validation and fee deduction.
     *
     * @param idempotencyKey unique key supplied by client
     * @param request payment details
     * @return idempotency result wrapping transaction response and replay flag
     */
    @Transactional
    public IdempotencyResult<TransactionResponse> payment(
            String idempotencyKey, PaymentRequest request) {
        if (idempotencyService != null) {
            return idempotencyService.execute(
                    idempotencyKey,
                    resolveCurrentScope(),
                    "POST",
                    "/api/v1/transactions/payments",
                    request,
                    TransactionResponse.class,
                    () -> executePayment(request));
        }
        return IdempotencyResult.executed(executePayment(request));
    }

    /**
     * Executes an outbound payment with limit validation and fee deduction.
     *
     * @param request payment details
     * @return persisted transaction response
     */
    @Transactional
    public TransactionResponse payment(PaymentRequest request) {
        return executePayment(request);
    }

    private TransactionResponse executePayment(PaymentRequest request) {
        return recordTransactionTimer(
                TransactionType.PAYMENT,
                () -> {
                    Account account =
                            accountRepository
                                    .findByIdForUpdate(request.sourceAccountId())
                                    .orElseThrow(
                                            () ->
                                                    new ResourceNotFoundException(
                                                            "Account", request.sourceAccountId()));

                    Money amount = Money.of(request.amount());
                    BigDecimal dailySpent =
                            transactionRepository.sumOutgoingAmountSince(
                                    account.getId(), TransactionStatus.COMPLETED, getStartOfDay());
                    limitPolicy.validateLimits(account, amount, Money.of(dailySpent));

                    Money fee = feePolicy.calculateFee(TransactionType.PAYMENT, amount);
                    Money totalDebit = amount.plus(fee);
                    account.debit(totalDebit);

                    String referenceNo = referenceGenerator.generate();
                    String description = request.description();
                    if (request.recipientInfo() != null && !request.recipientInfo().isBlank()) {
                        description =
                                (description != null ? description + " | " : "")
                                        + request.recipientInfo().trim();
                    }
                    if (description != null && description.length() > 500) {
                        throw new BusinessRuleException(
                                ErrorCode.VALIDATION_FAILED,
                                "Combined payment description and recipientInfo must not exceed 500"
                                        + " characters");
                    }

                    Transaction transaction =
                            Transaction.createPayment(
                                    referenceNo,
                                    account.getId(),
                                    amount.getAmount(),
                                    fee.getAmount(),
                                    account.getBalance(),
                                    request.location(),
                                    description);

                    Transaction saved = transactionRepository.save(transaction);
                    eventPublisher.publishEvent(
                            new TransactionCompletedEvent(
                                    saved.getId(),
                                    saved.getReferenceNo(),
                                    saved.getType(),
                                    saved.getAmount(),
                                    saved.getFee(),
                                    saved.getSourceAccountId(),
                                    saved.getTargetAccountId(),
                                    clock.instant()));

                    log.info(
                            "Payment completed [txnId={}, ref={}, accountId={}, amount={}, fee={},"
                                    + " balanceAfter={}]",
                            saved.getId(),
                            saved.getReferenceNo(),
                            account.getId(),
                            saved.getAmount(),
                            saved.getFee(),
                            saved.getSourceBalanceAfter());

                    return TransactionResponse.from(saved);
                });
    }

    /**
     * Updates mutable metadata (description and location) for an existing completed transaction.
     *
     * @param id target transaction ID
     * @param request update payload
     * @return updated transaction response
     */
    @Transactional
    public TransactionResponse updateTransactionMetadata(
            Long id, UpdateTransactionRequest request) {
        request.validateImmutability();

        Transaction transaction =
                transactionRepository
                        .findByIdForUpdate(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));

        transaction.updateMetadata(request.description(), request.location());
        Transaction saved = transactionRepository.saveAndFlush(transaction);

        eventPublisher.publishEvent(
                new TransactionMetadataUpdatedEvent(
                        saved.getId(),
                        saved.getSourceAccountId(),
                        saved.getTargetAccountId(),
                        clock.instant()));

        log.info(
                "Transaction metadata updated [txnId={}, ref={}]",
                saved.getId(),
                saved.getReferenceNo());
        return TransactionResponse.from(saved);
    }

    /**
     * Reverses an existing financial transaction, updating account balance(s) atomically under
     * pessimistic write lock(s) and marking the original transaction as REVERSED.
     *
     * @param id transaction ID to reverse
     * @return created reversal transaction response
     */
    @Transactional
    public TransactionResponse reverseTransaction(Long id) {
        return recordTransactionTimer(
                TransactionType.REVERSAL,
                () -> {
                    Transaction original =
                            transactionRepository
                                    .findByIdForUpdate(id)
                                    .orElseThrow(
                                            () -> new ResourceNotFoundException("Transaction", id));

                    if (original.getStatus() == TransactionStatus.REVERSED
                            || transactionRepository.existsByReversalOfId(id)) {
                        throw new BusinessRuleException(
                                ErrorCode.ALREADY_REVERSED,
                                "Transaction " + id + " has already been reversed");
                    }

                    if (original.getType() == TransactionType.REVERSAL) {
                        throw new BusinessRuleException(
                                ErrorCode.INVALID_STATUS_TRANSITION,
                                "Cannot reverse a reversal transaction");
                    }

                    Transaction reversal;
                    String referenceNo = referenceGenerator.generate();

                    switch (original.getType()) {
                        case DEPOSIT -> {
                            Account target =
                                    accountRepository
                                            .findByIdForUpdate(original.getTargetAccountId())
                                            .orElseThrow(
                                                    () ->
                                                            new ResourceNotFoundException(
                                                                    "Account",
                                                                    original.getTargetAccountId()));

                            target.debit(Money.of(original.getAmount()));
                            original.markReversed();

                            reversal =
                                    Transaction.createDepositReversal(
                                            referenceNo, original, target.getBalance(), null, null);
                        }
                        case WITHDRAWAL -> {
                            Account source =
                                    accountRepository
                                            .findByIdForUpdate(original.getSourceAccountId())
                                            .orElseThrow(
                                                    () ->
                                                            new ResourceNotFoundException(
                                                                    "Account",
                                                                    original.getSourceAccountId()));

                            Money refundTotal =
                                    Money.of(original.getAmount())
                                            .plus(Money.of(original.getFee()));
                            source.credit(refundTotal);
                            original.markReversed();

                            reversal =
                                    Transaction.createWithdrawalReversal(
                                            referenceNo, original, source.getBalance(), null, null);
                        }
                        case PAYMENT -> {
                            Account source =
                                    accountRepository
                                            .findByIdForUpdate(original.getSourceAccountId())
                                            .orElseThrow(
                                                    () ->
                                                            new ResourceNotFoundException(
                                                                    "Account",
                                                                    original.getSourceAccountId()));

                            Money refundTotal =
                                    Money.of(original.getAmount())
                                            .plus(Money.of(original.getFee()));
                            source.credit(refundTotal);
                            original.markReversed();

                            reversal =
                                    Transaction.createPaymentReversal(
                                            referenceNo, original, source.getBalance(), null, null);
                        }
                        case TRANSFER -> {
                            Long firstId =
                                    Math.min(
                                            original.getSourceAccountId(),
                                            original.getTargetAccountId());
                            Long secondId =
                                    Math.max(
                                            original.getSourceAccountId(),
                                            original.getTargetAccountId());

                            Account firstAccount =
                                    accountRepository
                                            .findByIdForUpdate(firstId)
                                            .orElseThrow(
                                                    () ->
                                                            new ResourceNotFoundException(
                                                                    "Account", firstId));
                            Account secondAccount =
                                    accountRepository
                                            .findByIdForUpdate(secondId)
                                            .orElseThrow(
                                                    () ->
                                                            new ResourceNotFoundException(
                                                                    "Account", secondId));

                            Account sourceA =
                                    original.getSourceAccountId().equals(firstId)
                                            ? firstAccount
                                            : secondAccount;
                            Account targetB =
                                    original.getTargetAccountId().equals(firstId)
                                            ? firstAccount
                                            : secondAccount;

                            targetB.debit(Money.of(original.getAmount()));
                            Money refundA =
                                    Money.of(original.getAmount())
                                            .plus(Money.of(original.getFee()));
                            sourceA.credit(refundA);

                            original.markReversed();

                            reversal =
                                    Transaction.createTransferReversal(
                                            referenceNo,
                                            original,
                                            targetB.getBalance(),
                                            sourceA.getBalance(),
                                            null,
                                            null);
                        }
                        default ->
                                throw new BusinessRuleException(
                                        ErrorCode.INVALID_STATUS_TRANSITION,
                                        "Unsupported transaction type for reversal: "
                                                + original.getType());
                    }

                    transactionRepository.saveAndFlush(original);
                    Transaction savedReversal = transactionRepository.save(reversal);

                    eventPublisher.publishEvent(
                            new TransactionCompletedEvent(
                                    savedReversal.getId(),
                                    savedReversal.getReferenceNo(),
                                    savedReversal.getType(),
                                    savedReversal.getAmount(),
                                    savedReversal.getFee(),
                                    savedReversal.getSourceAccountId(),
                                    savedReversal.getTargetAccountId(),
                                    clock.instant()));

                    log.info(
                            "Transaction reversed successfully [originalId={}, reversalId={},"
                                    + " ref={}]",
                            original.getId(),
                            savedReversal.getId(),
                            savedReversal.getReferenceNo());

                    return TransactionResponse.from(savedReversal);
                });
    }

    private Instant getStartOfDay() {
        return LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
    }

    /**
     * Retrieves transaction details by ID.
     *
     * @param id transaction database identifier
     * @return transaction response
     */
    public TransactionResponse getTransactionById(Long id) {
        Transaction transaction =
                transactionRepository
                        .findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
        return TransactionResponse.from(transaction);
    }

    /**
     * Retrieves paginated transaction history for a specific account. The default first page
     * ({@code page=0, size=20}, ordered by {@code createdAt DESC}) is cached in {@code
     * recentTransactions} and evicted after commit whenever a transaction on the account completes
     * (L2-08, G-05).
     *
     * @param accountId target account database ID
     * @param pageable pagination and sorting parameters
     * @return paginated transaction list
     */
    @Cacheable(
            cacheNames = CacheConfig.RECENT_TRANSACTIONS_CACHE,
            key = "#accountId",
            condition =
                    "T(com.example.corebanking.transaction.application.TransactionService).isDefaultRecentTransactionsPage(#pageable)")
    public PageResponse<TransactionResponse> getAccountTransactions(
            Long accountId, Pageable pageable) {
        if (!accountRepository.existsById(accountId)) {
            throw new ResourceNotFoundException("Account", accountId);
        }

        Pageable effectivePageable = resolvePageable(pageable);
        validatePaginationAndSort(effectivePageable);

        Page<Transaction> page =
                transactionRepository.findByAccountId(accountId, effectivePageable);
        return PageResponse.from(page, TransactionResponse::from);
    }

    /**
     * Determines whether a pagination request matches the default first page ({@code page=0,
     * size=20}, unsorted or sorted by {@code createdAt DESC [, id DESC]}), qualifying for {@code
     * recentTransactions} cache storage.
     */
    public static boolean isDefaultRecentTransactionsPage(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return false;
        }
        if (pageable.getPageNumber() != 0 || pageable.getPageSize() != 20) {
            return false;
        }
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            return true;
        }
        List<Sort.Order> orders = sort.toList();
        if (orders.size() == 1) {
            Sort.Order first = orders.get(0);
            return "createdAt".equals(first.getProperty()) && first.isDescending();
        }
        if (orders.size() == 2) {
            Sort.Order first = orders.get(0);
            Sort.Order second = orders.get(1);
            return "createdAt".equals(first.getProperty())
                    && first.isDescending()
                    && "id".equals(second.getProperty())
                    && second.isDescending();
        }
        return false;
    }

    /**
     * Searches transactions with dynamic filtering criteria and pagination. When invoked by a
     * {@code CUSTOMER}, automatically restricts results to accounts owned by that customer (L2-01,
     * FR-14).
     *
     * @param criteria search filters
     * @param pageable pagination and sorting parameters
     * @return paginated search results
     */
    public PageResponse<TransactionResponse> searchTransactions(
            TransactionSearchCriteria criteria, Pageable pageable) {
        Pageable effectivePageable = resolvePageable(pageable);
        validatePaginationAndSort(effectivePageable);
        validateSearchCriteria(criteria);

        Set<Long> customerAccountIds = resolveRestrictedCustomerAccountIds(criteria.accountId());
        if (customerAccountIds != null && customerAccountIds.isEmpty()) {
            return emptyPageResponse(effectivePageable);
        }

        Instant fromInstant =
                criteria.fromDate() != null
                        ? criteria.fromDate().atStartOfDay(clock.getZone()).toInstant()
                        : null;
        Instant toExclusiveInstant =
                criteria.toDate() != null
                        ? criteria.toDate().plusDays(1).atStartOfDay(clock.getZone()).toInstant()
                        : null;

        Specification<Transaction> spec =
                TransactionSpecifications.withAccountId(criteria.accountId())
                        .and(TransactionSpecifications.withType(criteria.type()))
                        .and(TransactionSpecifications.withStatus(criteria.status()))
                        .and(TransactionSpecifications.withMinAmount(criteria.minAmount()))
                        .and(TransactionSpecifications.withMaxAmount(criteria.maxAmount()))
                        .and(TransactionSpecifications.withCreatedAtFrom(fromInstant))
                        .and(TransactionSpecifications.withCreatedAtBefore(toExclusiveInstant));
        if (criteria.accountId() == null && customerAccountIds != null) {
            spec = spec.and(TransactionSpecifications.withAccountIdsIn(customerAccountIds));
        }

        Page<Transaction> page = transactionRepository.findAll(spec, effectivePageable);
        return PageResponse.from(page, TransactionResponse::from);
    }

    private void validateSearchCriteria(TransactionSearchCriteria criteria) {
        if (criteria.minAmount() != null && criteria.minAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Minimum amount must not be negative");
        }
        if (criteria.maxAmount() != null && criteria.maxAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Maximum amount must not be negative");
        }
        if (criteria.minAmount() != null
                && criteria.maxAmount() != null
                && criteria.minAmount().compareTo(criteria.maxAmount()) > 0) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "Minimum amount must not be greater than maximum amount");
        }
        if (criteria.fromDate() != null
                && criteria.toDate() != null
                && criteria.fromDate().isAfter(criteria.toDate())) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "fromDate must not be after toDate");
        }
    }

    private Set<Long> resolveRestrictedCustomerAccountIds(Long requestedAccountId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        boolean isAdmin =
                auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (isAdmin) {
            return null;
        }
        boolean isCustomer =
                auth.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()));
        if (!isCustomer) {
            return null;
        }

        Long customerId = extractCustomerIdFromAuth(auth);
        if (customerId == null) {
            throw new AccessDeniedException("Customer token is missing customerId claim");
        }
        if (requestedAccountId != null) {
            if (!accountRepository.existsByIdAndCustomerId(requestedAccountId, customerId)) {
                throw new AccessDeniedException(
                        "You do not have permission to access this resource");
            }
            return Set.of(requestedAccountId);
        }
        Set<Long> ownedIds = accountRepository.findIdsByCustomerId(customerId);
        return ownedIds != null ? ownedIds : Set.of();
    }

    private Long extractCustomerIdFromAuth(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof Jwt jwt) {
            Object claim = jwt.getClaim("customerId");
            if (claim instanceof Number number) {
                return number.longValue();
            }
            if (claim instanceof String str && !str.isBlank()) {
                try {
                    return Long.parseLong(str.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private PageResponse<TransactionResponse> emptyPageResponse(Pageable pageable) {
        int page = pageable.isUnpaged() ? 0 : pageable.getPageNumber();
        int size = pageable.isUnpaged() ? 0 : pageable.getPageSize();
        return new PageResponse<>(List.of(), page, size, 0L, 0);
    }

    private Pageable resolvePageable(Pageable pageable) {
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        } else {
            boolean hasIdSort = sort.stream().anyMatch(order -> "id".equals(order.getProperty()));
            if (!hasIdSort) {
                sort = sort.and(Sort.by(Sort.Order.desc("id")));
            }
        }
        if (pageable.isUnpaged()) {
            return Pageable.unpaged(sort);
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    private void validatePaginationAndSort(Pageable pageable) {
        if (!pageable.isUnpaged()) {
            if (pageable.getPageSize() > 100 || pageable.getPageSize() < 1) {
                throw new BusinessRuleException(
                        ErrorCode.VALIDATION_FAILED, "Page size must be between 1 and 100");
            }
            if (pageable.getPageNumber() < 0) {
                throw new BusinessRuleException(
                        ErrorCode.VALIDATION_FAILED, "Page number must not be negative");
            }
        }

        for (Sort.Order order : pageable.getSort()) {
            if (!ALLOWED_SORT_FIELDS.contains(order.getProperty())) {
                throw new BusinessRuleException(
                        ErrorCode.INVALID_SORT_FIELD,
                        "Invalid sort field '"
                                + order.getProperty()
                                + "'. Allowed fields: "
                                + ALLOWED_SORT_FIELDS);
            }
        }
    }

    static String resolveCurrentScope() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.isAuthenticated()
                && auth.getName() != null
                && !auth.getName().isBlank()
                && !"anonymousUser".equals(auth.getName())) {
            return auth.getName().trim();
        }
        return "DEFAULT";
    }

    /**
     * Executes a scheduled recurring transaction instruction ({@code DEPOSIT}, {@code PAYMENT}, or
     * {@code TRANSFER}) with pessimistic account locking in ascending ID order, limit validation,
     * tiered fee calculation, deterministic {@code referenceNo} ({@code REC-{id}-{yyyyMMdd}}), and
     * {@link TransactionCompletedEvent} publication (L2-07, FR-17, ADR-011).
     *
     * <p>All business rule validations are performed before mutating any {@link Account} entity in
     * the persistence context, and {@code noRollbackFor} ensures that if a business rule or account
     * lookup fails, the caller's transaction remains active so {@code failureCount} and {@code
     * lastError} can be persisted cleanly on the recurring instruction.
     *
     * @param type transaction type ({@code DEPOSIT}, {@code PAYMENT}, or {@code TRANSFER})
     * @param referenceNo deterministic period reference number ({@code REC-{id}-{yyyyMMdd}})
     * @param recurringId originating recurring transaction ID
     * @param sourceAccountId source account ID (required for {@code PAYMENT} and {@code TRANSFER})
     * @param targetAccountId target account ID (required for {@code DEPOSIT} and {@code TRANSFER})
     * @param amount positive transaction amount
     * @param location optional location metadata
     * @param description optional description metadata
     * @return persisted transaction response
     */
    @Transactional(noRollbackFor = {BusinessRuleException.class, ResourceNotFoundException.class})
    public TransactionResponse executeScheduledTransaction(
            TransactionType type,
            String referenceNo,
            Long recurringId,
            Long sourceAccountId,
            Long targetAccountId,
            BigDecimal amount,
            String location,
            String description) {
        return recordTransactionTimer(
                type,
                () -> {
                    if (transactionRepository.findByReferenceNo(referenceNo).isPresent()) {
                        throw new BusinessRuleException(
                                ErrorCode.DUPLICATE_RESOURCE,
                                "Scheduled transaction already executed for period reference: "
                                        + referenceNo);
                    }
                    Money amountMoney = Money.of(amount);
                    Transaction transaction;

                    switch (type) {
                        case DEPOSIT -> {
                            Account target =
                                    accountRepository
                                            .findByIdForUpdate(targetAccountId)
                                            .orElseThrow(
                                                    () ->
                                                            new ResourceNotFoundException(
                                                                    "Account", targetAccountId));
                            target.credit(amountMoney);
                            transaction =
                                    Transaction.createDeposit(
                                            referenceNo,
                                            target.getId(),
                                            amountMoney.getAmount(),
                                            target.getBalance(),
                                            location,
                                            description,
                                            recurringId,
                                            "SYSTEM");
                        }
                        case PAYMENT -> {
                            Account source =
                                    accountRepository
                                            .findByIdForUpdate(sourceAccountId)
                                            .orElseThrow(
                                                    () ->
                                                            new ResourceNotFoundException(
                                                                    "Account", sourceAccountId));
                            BigDecimal dailySpent =
                                    transactionRepository.sumOutgoingAmountSince(
                                            source.getId(),
                                            TransactionStatus.COMPLETED,
                                            getStartOfDay());
                            limitPolicy.validateLimits(source, amountMoney, Money.of(dailySpent));

                            Money fee =
                                    feePolicy.calculateFee(TransactionType.PAYMENT, amountMoney);
                            Money totalDebit = amountMoney.plus(fee);
                            source.debit(totalDebit);

                            transaction =
                                    Transaction.createPayment(
                                            referenceNo,
                                            source.getId(),
                                            amountMoney.getAmount(),
                                            fee.getAmount(),
                                            source.getBalance(),
                                            location,
                                            description,
                                            recurringId,
                                            "SYSTEM");
                        }
                        case TRANSFER -> {
                            if (sourceAccountId.equals(targetAccountId)) {
                                throw new BusinessRuleException(
                                        ErrorCode.SAME_ACCOUNT_TRANSFER,
                                        "Source and target accounts cannot be identical");
                            }
                            Long firstId = Math.min(sourceAccountId, targetAccountId);
                            Long secondId = Math.max(sourceAccountId, targetAccountId);

                            Account firstAccount =
                                    accountRepository
                                            .findByIdForUpdate(firstId)
                                            .orElseThrow(
                                                    () ->
                                                            new ResourceNotFoundException(
                                                                    "Account", firstId));
                            Account secondAccount =
                                    accountRepository
                                            .findByIdForUpdate(secondId)
                                            .orElseThrow(
                                                    () ->
                                                            new ResourceNotFoundException(
                                                                    "Account", secondId));

                            Account source =
                                    sourceAccountId.equals(firstId) ? firstAccount : secondAccount;
                            Account target =
                                    targetAccountId.equals(firstId) ? firstAccount : secondAccount;

                            if (!source.isActive()) {
                                throw new BusinessRuleException(
                                        ErrorCode.ACCOUNT_NOT_ACTIVE,
                                        "Account "
                                                + source.getAccountNumber()
                                                + " is not active (current status: "
                                                + source.getStatus()
                                                + ")");
                            }
                            if (!target.isActive()) {
                                throw new BusinessRuleException(
                                        ErrorCode.ACCOUNT_NOT_ACTIVE,
                                        "Account "
                                                + target.getAccountNumber()
                                                + " is not active (current status: "
                                                + target.getStatus()
                                                + ")");
                            }

                            BigDecimal dailySpent =
                                    transactionRepository.sumOutgoingAmountSince(
                                            source.getId(),
                                            TransactionStatus.COMPLETED,
                                            getStartOfDay());
                            limitPolicy.validateLimits(source, amountMoney, Money.of(dailySpent));

                            Money fee =
                                    feePolicy.calculateFee(TransactionType.TRANSFER, amountMoney);
                            Money totalDebit = amountMoney.plus(fee);

                            source.debit(totalDebit);
                            target.credit(amountMoney);

                            transaction =
                                    Transaction.createTransfer(
                                            referenceNo,
                                            source.getId(),
                                            target.getId(),
                                            amountMoney.getAmount(),
                                            fee.getAmount(),
                                            source.getBalance(),
                                            target.getBalance(),
                                            location,
                                            description,
                                            recurringId,
                                            "SYSTEM");
                        }
                        default ->
                                throw new BusinessRuleException(
                                        ErrorCode.VALIDATION_FAILED,
                                        "Unsupported scheduled transaction type: " + type);
                    }

                    Transaction saved = transactionRepository.saveAndFlush(transaction);
                    eventPublisher.publishEvent(
                            new TransactionCompletedEvent(
                                    saved.getId(),
                                    saved.getReferenceNo(),
                                    saved.getType(),
                                    saved.getAmount(),
                                    saved.getFee(),
                                    saved.getSourceAccountId(),
                                    saved.getTargetAccountId(),
                                    clock.instant()));

                    log.info(
                            "Scheduled transaction completed [recurringId={}, txnId={}, ref={},"
                                    + " type={}, amount={}, fee={}]",
                            recurringId,
                            saved.getId(),
                            saved.getReferenceNo(),
                            saved.getType(),
                            saved.getAmount(),
                            saved.getFee());

                    return TransactionResponse.from(saved);
                });
    }

    private <T> T recordTransactionTimer(TransactionType type, Supplier<T> action) {
        if (meterRegistry == null) {
            return action.get();
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = OUTCOME_SUCCESS;
        try {
            return action.get();
        } catch (RuntimeException ex) {
            outcome = OUTCOME_FAILURE;
            throw ex;
        } finally {
            String typeTag = type != null ? type.name() : "UNKNOWN";
            sample.stop(
                    Timer.builder(TRANSACTIONS_METRIC)
                            .description("Execution duration and outcome of banking transactions")
                            .tag("type", typeTag)
                            .tag("outcome", outcome)
                            .register(meterRegistry));
        }
    }
}

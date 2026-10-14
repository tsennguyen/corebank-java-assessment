package com.example.corebanking.transaction.application;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.common.api.PageResponse;
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
import com.example.corebanking.transaction.infrastructure.TransactionReferenceGenerator;
import com.example.corebanking.transaction.infrastructure.TransactionRepository;
import com.example.corebanking.transaction.infrastructure.TransactionSpecifications;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "createdAt", "amount");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionReferenceGenerator referenceGenerator;
    private final FeePolicy feePolicy;
    private final LimitPolicy limitPolicy;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final IdempotencyService idempotencyService;

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
            IdempotencyService idempotencyService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.referenceGenerator = referenceGenerator;
        this.feePolicy = feePolicy;
        this.limitPolicy = limitPolicy;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.idempotencyService = idempotencyService;
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
                "Deposit completed [txnId={}, ref={}, accountId={}, amount={}, balanceAfter={}]",
                saved.getId(),
                saved.getReferenceNo(),
                account.getId(),
                saved.getAmount(),
                saved.getTargetBalanceAfter());

        return TransactionResponse.from(saved);
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
                "Withdrawal completed [txnId={}, ref={}, accountId={}, amount={}, fee={},"
                        + " balanceAfter={}]",
                saved.getId(),
                saved.getReferenceNo(),
                account.getId(),
                saved.getAmount(),
                saved.getFee(),
                saved.getSourceBalanceAfter());

        return TransactionResponse.from(saved);
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
        if (request.sourceAccountId().equals(request.targetAccountId())) {
            throw new BusinessRuleException(
                    ErrorCode.SAME_ACCOUNT_TRANSFER,
                    "Source and target accounts cannot be identical");
        }

        // Lock accounts in strictly ascending ID order to prevent circular wait deadlock (NC-01)
        Long firstId = Math.min(request.sourceAccountId(), request.targetAccountId());
        Long secondId = Math.max(request.sourceAccountId(), request.targetAccountId());

        Account firstAccount =
                accountRepository
                        .findByIdForUpdate(firstId)
                        .orElseThrow(() -> new ResourceNotFoundException("Account", firstId));
        Account secondAccount =
                accountRepository
                        .findByIdForUpdate(secondId)
                        .orElseThrow(() -> new ResourceNotFoundException("Account", secondId));

        Account source = request.sourceAccountId().equals(firstId) ? firstAccount : secondAccount;
        Account target = request.targetAccountId().equals(firstId) ? firstAccount : secondAccount;

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
                "Transfer completed [txnId={}, ref={}, from={}, to={}, amount={}, fee={}]",
                saved.getId(),
                saved.getReferenceNo(),
                source.getId(),
                target.getId(),
                saved.getAmount(),
                saved.getFee());

        return TransactionResponse.from(saved);
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
                        .findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));

        transaction.updateMetadata(request.description(), request.location());
        Transaction saved = transactionRepository.saveAndFlush(transaction);

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
        Transaction original =
                transactionRepository
                        .findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));

        if (original.getStatus() == TransactionStatus.REVERSED
                || transactionRepository.existsByReversalOfId(id)) {
            throw new BusinessRuleException(
                    ErrorCode.ALREADY_REVERSED, "Transaction " + id + " has already been reversed");
        }

        if (original.getType() == TransactionType.REVERSAL) {
            throw new BusinessRuleException(
                    ErrorCode.INVALID_STATUS_TRANSITION, "Cannot reverse a reversal transaction");
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
                                                        "Account", original.getTargetAccountId()));

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
                                                        "Account", original.getSourceAccountId()));

                Money refundTotal =
                        Money.of(original.getAmount()).plus(Money.of(original.getFee()));
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
                                                        "Account", original.getSourceAccountId()));

                Money refundTotal =
                        Money.of(original.getAmount()).plus(Money.of(original.getFee()));
                source.credit(refundTotal);
                original.markReversed();

                reversal =
                        Transaction.createPaymentReversal(
                                referenceNo, original, source.getBalance(), null, null);
            }
            case TRANSFER -> {
                Long firstId =
                        Math.min(original.getSourceAccountId(), original.getTargetAccountId());
                Long secondId =
                        Math.max(original.getSourceAccountId(), original.getTargetAccountId());

                Account firstAccount =
                        accountRepository
                                .findByIdForUpdate(firstId)
                                .orElseThrow(
                                        () -> new ResourceNotFoundException("Account", firstId));
                Account secondAccount =
                        accountRepository
                                .findByIdForUpdate(secondId)
                                .orElseThrow(
                                        () -> new ResourceNotFoundException("Account", secondId));

                Account sourceA =
                        original.getSourceAccountId().equals(firstId)
                                ? firstAccount
                                : secondAccount;
                Account targetB =
                        original.getTargetAccountId().equals(firstId)
                                ? firstAccount
                                : secondAccount;

                targetB.debit(Money.of(original.getAmount()));
                Money refundA = Money.of(original.getAmount()).plus(Money.of(original.getFee()));
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
                            "Unsupported transaction type for reversal: " + original.getType());
        }

        transactionRepository.save(original);
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
                "Transaction reversed successfully [originalId={}, reversalId={}, ref={}]",
                original.getId(),
                savedReversal.getId(),
                savedReversal.getReferenceNo());

        return TransactionResponse.from(savedReversal);
    }

    private Instant getStartOfDay() {
        return LocalDate.now(clock).atStartOfDay(BUSINESS_ZONE).toInstant();
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
     * Retrieves paginated transaction history for a specific account.
     *
     * @param accountId target account database ID
     * @param pageable pagination and sorting parameters
     * @return paginated transaction list
     */
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
     * Searches transactions with dynamic filtering criteria and pagination.
     *
     * @param criteria search filters
     * @param pageable pagination and sorting parameters
     * @return paginated search results
     */
    public PageResponse<TransactionResponse> searchTransactions(
            TransactionSearchCriteria criteria, Pageable pageable) {
        Pageable effectivePageable = resolvePageable(pageable);
        validatePaginationAndSort(effectivePageable);

        Specification<Transaction> spec =
                TransactionSpecifications.withAccountId(criteria.accountId())
                        .and(TransactionSpecifications.withType(criteria.type()));

        Page<Transaction> page = transactionRepository.findAll(spec, effectivePageable);
        return PageResponse.from(page, TransactionResponse::from);
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
        if (pageable.getPageSize() > 100 || pageable.getPageSize() < 1) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Page size must be between 1 and 100");
        }
        if (pageable.getPageNumber() < 0) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Page number must not be negative");
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
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        if (auth != null
                && auth.isAuthenticated()
                && auth.getName() != null
                && !auth.getName().isBlank()
                && !"anonymousUser".equals(auth.getName())) {
            return auth.getName().trim();
        }
        return "DEFAULT";
    }
}

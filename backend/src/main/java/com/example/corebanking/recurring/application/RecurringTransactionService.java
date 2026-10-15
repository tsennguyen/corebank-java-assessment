package com.example.corebanking.recurring.application;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.recurring.api.dto.CreateRecurringTransactionRequest;
import com.example.corebanking.recurring.api.dto.RecurringTransactionResponse;
import com.example.corebanking.recurring.domain.RecurringFrequency;
import com.example.corebanking.recurring.domain.RecurringStatus;
import com.example.corebanking.recurring.domain.RecurringTransaction;
import com.example.corebanking.recurring.domain.RecurringTransactionType;
import com.example.corebanking.recurring.infrastructure.RecurringTransactionRepository;
import com.example.corebanking.recurring.infrastructure.RecurringTransactionSpecifications;
import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service managing setup, lifecycle transitions ({@code pause}, {@code resume}, {@code
 * cancel}), and filtered paginated search of recurring transaction instructions (L2-06, FR-16).
 */
@Service
@Transactional(readOnly = true)
public class RecurringTransactionService {

    private static final Logger log = LoggerFactory.getLogger(RecurringTransactionService.class);
    private static final String DEFAULT_ACTOR = "SYSTEM";
    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("id", "nextRunAt", "amount", "startDate", "status", "createdAt");

    private final RecurringTransactionRepository recurringRepository;
    private final AccountRepository accountRepository;
    private final Clock clock;

    public RecurringTransactionService(
            RecurringTransactionRepository recurringRepository,
            AccountRepository accountRepository,
            Clock clock) {
        this.recurringRepository = recurringRepository;
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    /**
     * Creates a new {@code ACTIVE} recurring transaction instruction after validating domain
     * invariants and verifying that all referenced accounts exist and are {@code ACTIVE} under
     * pessimistic lock in ascending ID order.
     *
     * @param request creation payload
     * @return created recurring transaction representation
     */
    @Transactional
    public RecurringTransactionResponse createRecurringTransaction(
            CreateRecurringTransactionRequest request) {
        String actor = resolveCurrentActor();
        RecurringTransaction recurring =
                RecurringTransaction.create(
                        request.type(),
                        request.sourceAccountId(),
                        request.targetAccountId(),
                        request.amount(),
                        request.frequency(),
                        request.startDate(),
                        request.endDate(),
                        request.location(),
                        request.description(),
                        actor,
                        clock);

        lockAndValidateActiveAccounts(
                recurring.getType(),
                recurring.getSourceAccountId(),
                recurring.getTargetAccountId());

        RecurringTransaction saved = recurringRepository.save(recurring);
        log.info(
                "Created recurring transaction [id={}, type={}, frequency={}, nextRunAt={}]",
                saved.getId(),
                saved.getType(),
                saved.getFrequency(),
                saved.getNextRunAt());
        return RecurringTransactionResponse.from(saved);
    }

    /** Retrieves a recurring transaction instruction by its ID. */
    public RecurringTransactionResponse getRecurringTransactionById(Long id) {
        RecurringTransaction recurring =
                recurringRepository
                        .findById(id)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("RecurringTransaction", id));
        return RecurringTransactionResponse.from(recurring);
    }

    /**
     * Searches recurring transaction instructions with optional status, accountId (matching source
     * or target), type, and frequency filters.
     */
    public PageResponse<RecurringTransactionResponse> searchRecurringTransactions(
            RecurringStatus status,
            Long accountId,
            RecurringTransactionType type,
            RecurringFrequency frequency,
            Pageable pageable) {
        Pageable effectivePageable = resolvePageable(pageable);
        validatePaginationAndSort(effectivePageable);

        Specification<RecurringTransaction> spec =
                RecurringTransactionSpecifications.withStatus(status)
                        .and(RecurringTransactionSpecifications.withAccountId(accountId))
                        .and(RecurringTransactionSpecifications.withType(type))
                        .and(RecurringTransactionSpecifications.withFrequency(frequency));

        Page<RecurringTransaction> page = recurringRepository.findAll(spec, effectivePageable);
        return PageResponse.from(page, RecurringTransactionResponse::from);
    }

    /**
     * Pauses an {@code ACTIVE} recurring transaction instruction ({@code ACTIVE -> PAUSED}).
     *
     * @param id recurring transaction ID
     * @return updated recurring transaction
     */
    @Transactional
    public RecurringTransactionResponse pauseRecurringTransaction(Long id) {
        RecurringTransaction recurring =
                recurringRepository
                        .findByIdForUpdate(id)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("RecurringTransaction", id));

        recurring.pause();
        RecurringTransaction saved = recurringRepository.save(recurring);
        log.info("Paused recurring transaction [id={}]", saved.getId());
        return RecurringTransactionResponse.from(saved);
    }

    /**
     * Resumes a {@code PAUSED} recurring transaction instruction ({@code PAUSED -> ACTIVE}) after
     * verifying that referenced accounts are still {@code ACTIVE}.
     *
     * @param id recurring transaction ID
     * @return updated recurring transaction
     */
    @Transactional
    public RecurringTransactionResponse resumeRecurringTransaction(Long id) {
        RecurringTransaction recurring =
                recurringRepository
                        .findByIdForUpdate(id)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("RecurringTransaction", id));

        if (recurring.getStatus().canTransitionTo(RecurringStatus.ACTIVE)) {
            lockAndValidateActiveAccounts(
                    recurring.getType(),
                    recurring.getSourceAccountId(),
                    recurring.getTargetAccountId());
        }
        recurring.resume(clock);

        RecurringTransaction saved = recurringRepository.save(recurring);
        log.info(
                "Resumed recurring transaction [id={}, nextRunAt={}]",
                saved.getId(),
                saved.getNextRunAt());
        return RecurringTransactionResponse.from(saved);
    }

    /**
     * Cancels an {@code ACTIVE} or {@code PAUSED} recurring transaction instruction ({@code ACTIVE
     * | PAUSED -> CANCELLED}).
     *
     * @param id recurring transaction ID
     */
    @Transactional
    public void cancelRecurringTransaction(Long id) {
        RecurringTransaction recurring =
                recurringRepository
                        .findByIdForUpdate(id)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("RecurringTransaction", id));

        recurring.cancel();
        recurringRepository.save(recurring);
        log.info("Cancelled recurring transaction [id={}]", id);
    }

    private void lockAndValidateActiveAccounts(
            RecurringTransactionType type, Long sourceAccountId, Long targetAccountId) {
        if (type == RecurringTransactionType.DEPOSIT) {
            validateAccountActive(targetAccountId);
        } else if (type == RecurringTransactionType.PAYMENT) {
            validateAccountActive(sourceAccountId);
        } else {
            Long firstId = Math.min(sourceAccountId, targetAccountId);
            Long secondId = Math.max(sourceAccountId, targetAccountId);
            Account first = lockAccountOrThrow(firstId);
            Account second = lockAccountOrThrow(secondId);
            Account source = sourceAccountId.equals(firstId) ? first : second;
            Account target = targetAccountId.equals(firstId) ? first : second;
            assertActive(source);
            assertActive(target);
        }
    }

    private void validateAccountActive(Long accountId) {
        Account account = lockAccountOrThrow(accountId);
        assertActive(account);
    }

    private Account lockAccountOrThrow(Long accountId) {
        return accountRepository
                .findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    private void assertActive(Account account) {
        if (!account.isActive()) {
            throw new BusinessRuleException(
                    ErrorCode.ACCOUNT_NOT_ACTIVE,
                    "Account "
                            + account.getAccountNumber()
                            + " is not active (status: "
                            + account.getStatus()
                            + ")");
        }
    }

    private String resolveCurrentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getName() != null
                && !authentication.getName().isBlank()
                && !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }
        return DEFAULT_ACTOR;
    }

    private Pageable resolvePageable(Pageable pageable) {
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            sort = Sort.by(Sort.Order.desc("id"));
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
}

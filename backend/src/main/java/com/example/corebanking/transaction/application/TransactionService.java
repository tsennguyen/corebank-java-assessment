package com.example.corebanking.transaction.application;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.Money;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.api.dto.TransactionSearchCriteria;
import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.infrastructure.TransactionReferenceGenerator;
import com.example.corebanking.transaction.infrastructure.TransactionRepository;
import com.example.corebanking.transaction.infrastructure.TransactionSpecifications;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionReferenceGenerator referenceGenerator;

    public TransactionService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            TransactionReferenceGenerator referenceGenerator) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.referenceGenerator = referenceGenerator;
    }

    /**
     * Executes an atomic cash deposit into an active account under pessimistic write lock.
     *
     * @param request deposit details
     * @return persisted transaction response
     */
    @Transactional
    public TransactionResponse deposit(DepositRequest request) {
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
}

package com.example.corebanking.account.application;

import com.example.corebanking.account.api.dto.AccountResponse;
import com.example.corebanking.account.api.dto.CreateAccountRequest;
import com.example.corebanking.account.api.dto.UpdateAccountLimitsRequest;
import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.account.domain.BalanceTierPolicy;
import com.example.corebanking.account.infrastructure.AccountNumberGenerator;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.account.infrastructure.AccountSpecifications;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import java.time.Clock;
import java.time.LocalDate;
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

/** Application service managing Account opening, limits adjustment, search, and closure. */
@Service
@Transactional(readOnly = true)
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("id", "accountNumber", "balance", "openedDate", "status", "createdAt");

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final BalanceTierPolicy balanceTierPolicy;
    private final Clock clock;

    public AccountService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            AccountNumberGenerator accountNumberGenerator,
            BalanceTierPolicy balanceTierPolicy,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.balanceTierPolicy = balanceTierPolicy;
        this.clock = clock;
    }

    /**
     * Opens a new account for an existing active customer.
     *
     * @param request opening payload with customer ID and limits
     * @return created account details
     */
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        if (customerRepository.findByIdAndDeletedAtIsNull(request.customerId()).isEmpty()) {
            throw new ResourceNotFoundException("Customer", request.customerId());
        }

        if (request.dailyLimit().compareTo(request.transactionLimit()) < 0) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "Daily limit ("
                            + request.dailyLimit()
                            + ") must be greater than or equal to transaction limit ("
                            + request.transactionLimit()
                            + ")");
        }

        String accountNumber = accountNumberGenerator.nextAccountNumber();
        LocalDate openedDate = LocalDate.now(clock);

        Account account =
                new Account(
                        accountNumber,
                        request.customerId(),
                        request.transactionLimit(),
                        request.dailyLimit(),
                        openedDate);

        Account saved = accountRepository.save(account);
        log.info(
                "Opened account [id={}, number={}] for customer {}",
                saved.getId(),
                saved.getAccountNumber(),
                request.customerId());
        return AccountResponse.from(saved, balanceTierPolicy.classify(saved.getBalance()));
    }

    /** Retrieves account details by internal ID. */
    public AccountResponse getAccountById(Long id) {
        Account account =
                accountRepository
                        .findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Account", id));
        return AccountResponse.from(account, balanceTierPolicy.classify(account.getBalance()));
    }

    /** Searches accounts matching filters with pagination and sorting. */
    public PageResponse<AccountResponse> searchAccounts(
            Long customerId, AccountStatus status, String accountNumber, Pageable pageable) {
        Pageable effectivePageable = resolvePageable(pageable);
        validatePaginationAndSort(effectivePageable);

        Specification<Account> spec =
                AccountSpecifications.withCustomerId(customerId)
                        .and(AccountSpecifications.withStatus(status))
                        .and(AccountSpecifications.withAccountNumber(accountNumber));

        Page<Account> page = accountRepository.findAll(spec, effectivePageable);
        return PageResponse.from(
                page,
                account ->
                        AccountResponse.from(
                                account, balanceTierPolicy.classify(account.getBalance())));
    }

    /** Modifies per-transaction and daily limits with optimistic concurrency check. */
    @Transactional
    public AccountResponse updateLimits(Long id, UpdateAccountLimitsRequest request) {
        Account account =
                accountRepository
                        .findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Account", id));

        if (account.isClosed()) {
            throw new BusinessRuleException(
                    ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot update limits on a closed account");
        }

        if (!account.getVersion().equals(request.version())) {
            log.warn(
                    "Optimistic concurrency conflict on account [id={}]: expected version={},"
                            + " actual={}",
                    id,
                    request.version(),
                    account.getVersion());
            throw new BusinessRuleException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "Account has been modified by another concurrent transaction. Expected version "
                            + request.version()
                            + " but found "
                            + account.getVersion());
        }

        account.updateLimits(request.transactionLimit(), request.dailyLimit());
        Account updated = accountRepository.saveAndFlush(account);
        log.info(
                "Updated limits on account [id={}, number={}]",
                updated.getId(),
                updated.getAccountNumber());
        return AccountResponse.from(updated, balanceTierPolicy.classify(updated.getBalance()));
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

    /** Closes an account if balance is zero. Throws ACCOUNT_HAS_BALANCE (409) if balance > 0. */
    @Transactional
    public void closeAccount(Long id) {
        Account account =
                accountRepository
                        .findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Account", id));

        account.close(clock.instant());
        accountRepository.save(account);
        log.info("Closed account [id={}, number={}]", account.getId(), account.getAccountNumber());
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

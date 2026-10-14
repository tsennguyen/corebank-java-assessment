package com.example.corebanking.customer.application;

import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.DuplicateResourceException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.common.util.MaskingUtils;
import com.example.corebanking.customer.api.dto.CreateCustomerRequest;
import com.example.corebanking.customer.api.dto.CustomerResponse;
import com.example.corebanking.customer.api.dto.UpdateCustomerRequest;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerCodeGenerator;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.customer.infrastructure.CustomerSpecifications;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service managing Customer lifecycle, search filtering, optimistic concurrency, and
 * soft-deletion.
 */
@Service
@Transactional(readOnly = true)
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);
    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("id", "customerCode", "fullName", "location", "createdAt");

    private final CustomerRepository customerRepository;
    private final CustomerCodeGenerator customerCodeGenerator;
    private final Clock clock;
    private final CustomerDeletionGuard customerDeletionGuard;

    @Autowired
    public CustomerService(
            CustomerRepository customerRepository,
            CustomerCodeGenerator customerCodeGenerator,
            Clock clock,
            CustomerDeletionGuard customerDeletionGuard) {
        this.customerRepository = customerRepository;
        this.customerCodeGenerator = customerCodeGenerator;
        this.clock = clock;
        this.customerDeletionGuard =
                Objects.requireNonNull(
                        customerDeletionGuard, "customerDeletionGuard must not be null");
    }

    /**
     * Registers a new customer after verifying national identity uniqueness.
     *
     * @param request creation parameters
     * @return persisted customer representation with masked national ID
     */
    @Transactional
    public CustomerResponse createCustomer(CreateCustomerRequest request) {
        if (customerRepository.existsByNationalId(request.nationalId())) {
            log.warn(
                    "Attempted to register customer with duplicate nationalId: {}",
                    MaskingUtils.nationalId(request.nationalId()));
            throw new DuplicateResourceException("Customer with national ID already exists");
        }

        String customerCode = customerCodeGenerator.nextCustomerCode();
        Customer customer =
                new Customer(
                        customerCode,
                        request.fullName(),
                        request.nationalId(),
                        request.email(),
                        request.phone(),
                        request.address(),
                        request.location(),
                        request.dateOfBirth());

        Customer saved = customerRepository.save(customer);
        log.info("Created customer [id={}, code={}]", saved.getId(), saved.getCustomerCode());
        return CustomerResponse.from(saved);
    }

    /**
     * Retrieves an active customer profile by its primary key ID.
     *
     * @param id customer database ID
     * @return customer details
     * @throws ResourceNotFoundException if customer does not exist or has been deleted
     */
    public CustomerResponse getCustomerById(Long id) {
        Customer customer =
                customerRepository
                        .findByIdAndDeletedAtIsNull(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
        return CustomerResponse.from(customer);
    }

    /**
     * Searches active customers by optional name and location filters with pagination and sorting.
     *
     * @param name optional full name query (case-insensitive substring)
     * @param location optional province/city query
     * @param pageable page index, page size, and sorting
     * @return paginated response of matching customer records
     */
    public PageResponse<CustomerResponse> searchCustomers(
            String name, String location, Pageable pageable) {
        Pageable effectivePageable = resolvePageable(pageable);
        validatePaginationAndSort(effectivePageable);

        Specification<Customer> spec =
                CustomerSpecifications.activeOnly()
                        .and(CustomerSpecifications.withName(name))
                        .and(CustomerSpecifications.withLocation(location));

        Page<Customer> page = customerRepository.findAll(spec, effectivePageable);
        return PageResponse.from(page, CustomerResponse::from);
    }

    /**
     * Updates an existing customer's mutable details with optimistic concurrency check.
     *
     * @param id customer ID
     * @param request update payload including target version
     * @return updated customer details
     */
    @Transactional
    public CustomerResponse updateCustomer(Long id, UpdateCustomerRequest request) {
        Customer customer =
                customerRepository
                        .findByIdAndDeletedAtIsNull(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Customer", id));

        if (!customer.getVersion().equals(request.version())) {
            log.warn(
                    "Optimistic concurrency conflict on customer [id={}]: expected version={},"
                            + " actual={}",
                    id,
                    request.version(),
                    customer.getVersion());
            throw new BusinessRuleException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "Customer has been modified by another concurrent transaction. Expected version"
                            + " "
                            + request.version()
                            + " but found "
                            + customer.getVersion());
        }

        customer.update(
                request.fullName(),
                request.email(),
                request.phone(),
                request.address(),
                request.location(),
                request.dateOfBirth());

        Customer updated = customerRepository.saveAndFlush(customer);
        log.info(
                "Updated customer [id={}, code={}, version={}]",
                updated.getId(),
                updated.getCustomerCode(),
                updated.getVersion());
        return CustomerResponse.from(updated);
    }

    /**
     * Soft-deletes a customer, marking their deleted_at timestamp.
     *
     * @param id customer ID
     */
    @Transactional
    public void deleteCustomer(Long id) {
        Customer customer =
                customerRepository
                        .findByIdAndDeletedAtIsNullForUpdate(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Customer", id));

        customerDeletionGuard.validateCanDeleteCustomer(id);

        customer.softDelete(clock.instant());
        customerRepository.save(customer);
        log.info(
                "Soft-deleted customer [id={}, code={}]",
                customer.getId(),
                customer.getCustomerCode());
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

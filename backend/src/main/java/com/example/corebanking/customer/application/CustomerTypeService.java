package com.example.corebanking.customer.application;

import com.example.corebanking.common.config.CacheConfig;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.customer.api.dto.CustomerTypeResponse;
import com.example.corebanking.customer.api.dto.UpdateCustomerTypeRequest;
import com.example.corebanking.customer.domain.CustomerType;
import com.example.corebanking.customer.infrastructure.CustomerTypeRepository;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service managing customer classifications ({@code INDIVIDUAL}, {@code BUSINESS}) and
 * their maximum outgoing transaction and daily limits (L3-05, L3-06, FR-18).
 */
@Service
@Transactional(readOnly = true)
public class CustomerTypeService {

    private static final Logger log = LoggerFactory.getLogger(CustomerTypeService.class);

    private final CustomerTypeRepository customerTypeRepository;

    public CustomerTypeService(CustomerTypeRepository customerTypeRepository) {
        this.customerTypeRepository = customerTypeRepository;
    }

    /**
     * Lists all customer types ordered by ID ascending. Cached in Redis {@code customerTypes} (key
     * {@code 'all'}, TTL 1h).
     */
    @Cacheable(value = CacheConfig.CUSTOMER_TYPES_CACHE, key = "'all'")
    public List<CustomerTypeResponse> getAllCustomerTypes() {
        return customerTypeRepository.findAllByOrderByIdAsc().stream()
                .map(CustomerTypeResponse::from)
                .toList();
    }

    /** Retrieves a single customer type by its case-insensitive code. */
    public CustomerTypeResponse getCustomerTypeByCode(String code) {
        CustomerType customerType = findEntityByCodeOrThrow(code);
        return CustomerTypeResponse.from(customerType);
    }

    /**
     * Updates the maximum transaction and daily limits (and optional display name) of a customer
     * type and evicts the {@code customerTypes} cache.
     */
    @Transactional
    @CacheEvict(value = CacheConfig.CUSTOMER_TYPES_CACHE, allEntries = true)
    public CustomerTypeResponse updateCustomerType(String code, UpdateCustomerTypeRequest request) {
        CustomerType customerType = findEntityByCodeOrThrow(code);
        customerType.updateNameAndLimits(
                request.name(), request.maxTransactionLimit(), request.maxDailyLimit());
        CustomerType saved = customerTypeRepository.saveAndFlush(customerType);
        log.info(
                "Updated CustomerType [code={}, maxTxLimit={}, maxDailyLimit={}]",
                saved.getCode(),
                saved.getMaxTransactionLimit(),
                saved.getMaxDailyLimit());
        return CustomerTypeResponse.from(saved);
    }

    private CustomerType findEntityByCodeOrThrow(String code) {
        if (code == null || code.isBlank()) {
            throw new ResourceNotFoundException("CustomerType", String.valueOf(code));
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return customerTypeRepository
                .findByCodeIgnoreCase(normalized)
                .orElseThrow(() -> new ResourceNotFoundException("CustomerType", normalized));
    }
}

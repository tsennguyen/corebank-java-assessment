package com.example.corebanking.transaction.domain;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.customer.api.dto.CustomerTypeResponse;
import com.example.corebanking.customer.application.CustomerTypeService;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Primary {@link LimitProvider} implementation that resolves effective outgoing transaction and
 * daily limits as {@code min(account.limit, customerType.maxLimit)} without modifying {@link
 * LimitPolicy} (Open/Closed Principle, L3-05, L3-06, FR-18, FR-5).
 */
@Component
@Primary
public class CustomerTypeLimitProvider implements LimitProvider {

    private final CustomerRepository customerRepository;
    private final CustomerTypeService customerTypeService;

    public CustomerTypeLimitProvider(
            CustomerRepository customerRepository, CustomerTypeService customerTypeService) {
        this.customerRepository =
                Objects.requireNonNull(customerRepository, "customerRepository must not be null");
        this.customerTypeService =
                Objects.requireNonNull(customerTypeService, "customerTypeService must not be null");
    }

    @Override
    public AccountLimits getLimits(Account account) {
        Objects.requireNonNull(account, "account must not be null");

        BigDecimal effectiveTxLimit = account.getTransactionLimit();
        BigDecimal effectiveDailyLimit = account.getDailyLimit();

        Long customerId = account.getCustomerId();
        if (customerId != null) {
            Optional<Customer> customerOpt = customerRepository.findById(customerId);
            if (customerOpt.isPresent()) {
                String rawTypeCode = customerOpt.get().getCustomerTypeCode();
                String typeCode =
                        (rawTypeCode == null || rawTypeCode.isBlank())
                                ? Customer.DEFAULT_CUSTOMER_TYPE_CODE
                                : rawTypeCode.trim();

                Optional<CustomerTypeResponse> customerTypeOpt = findCustomerTypeByCode(typeCode);
                if (customerTypeOpt.isPresent()) {
                    CustomerTypeResponse customerType = customerTypeOpt.get();
                    effectiveTxLimit =
                            minNonNull(effectiveTxLimit, customerType.maxTransactionLimit());
                    effectiveDailyLimit =
                            minNonNull(effectiveDailyLimit, customerType.maxDailyLimit());
                }
            }
        }

        return new AccountLimits(effectiveTxLimit, effectiveDailyLimit);
    }

    private Optional<CustomerTypeResponse> findCustomerTypeByCode(String code) {
        List<CustomerTypeResponse> allTypes = customerTypeService.getAllCustomerTypes();
        if (allTypes == null || allTypes.isEmpty()) {
            return Optional.empty();
        }
        return allTypes.stream().filter(t -> t.code().equalsIgnoreCase(code)).findFirst();
    }

    private static BigDecimal minNonNull(BigDecimal first, BigDecimal second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.min(second);
    }
}

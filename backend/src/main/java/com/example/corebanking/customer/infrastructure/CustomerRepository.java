package com.example.corebanking.customer.infrastructure;

import com.example.corebanking.customer.domain.Customer;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for Customer entity with Specification querying support. */
@Repository
public interface CustomerRepository
        extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {

    /** Checks whether a customer exists with the given national ID (CCCD). */
    boolean existsByNationalId(String nationalId);

    /** Finds an active, non-deleted customer by internal ID. */
    Optional<Customer> findByIdAndDeletedAtIsNull(Long id);

    /** Finds an active, non-deleted customer by customer code. */
    Optional<Customer> findByCustomerCodeAndDeletedAtIsNull(String customerCode);
}

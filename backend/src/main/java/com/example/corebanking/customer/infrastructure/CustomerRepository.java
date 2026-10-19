package com.example.corebanking.customer.infrastructure;

import com.example.corebanking.customer.domain.Customer;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for Customer entity with Specification querying support. */
@Repository
public interface CustomerRepository
        extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {

    /** Checks whether a customer exists with the given national ID (CCCD). */
    boolean existsByNationalId(String nationalId);

    /** Finds an active, non-deleted customer by internal ID. */
    Optional<Customer> findByIdAndDeletedAtIsNull(Long id);

    /** Finds and locks an active, non-deleted customer by internal ID for atomic state changes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Customer c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<Customer> findByIdAndDeletedAtIsNullForUpdate(@Param("id") Long id);

    /** Finds an active, non-deleted customer by customer code. */
    Optional<Customer> findByCustomerCodeAndDeletedAtIsNull(String customerCode);
}

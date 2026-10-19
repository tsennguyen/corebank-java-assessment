package com.example.corebanking.account.infrastructure;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.domain.AccountStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for Account entity with Specification support. */
@Repository
public interface AccountRepository
        extends JpaRepository<Account, Long>, JpaSpecificationExecutor<Account> {

    boolean existsByAccountNumber(String accountNumber);

    boolean existsByIdAndCustomerId(Long id, Long customerId);

    boolean existsByCustomerIdAndStatusNot(Long customerId, AccountStatus status);

    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findByCustomerIdOrderByIdDesc(Long customerId);

    List<Account> findByCustomerIdAndStatusOrderByIdDesc(Long customerId, AccountStatus status);

    @Query("SELECT a.id FROM Account a WHERE a.customerId = :customerId")
    Set<Long> findIdsByCustomerId(@Param("customerId") Long customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}

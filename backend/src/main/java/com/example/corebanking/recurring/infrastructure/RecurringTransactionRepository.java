package com.example.corebanking.recurring.infrastructure;

import com.example.corebanking.recurring.domain.RecurringStatus;
import com.example.corebanking.recurring.domain.RecurringTransaction;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link RecurringTransaction} instructions (L2-06, L2-07, FR-16).
 */
public interface RecurringTransactionRepository
        extends JpaRepository<RecurringTransaction, Long>,
                JpaSpecificationExecutor<RecurringTransaction> {

    /**
     * Acquires a pessimistic write lock ({@code SELECT ... FOR UPDATE}) on a recurring transaction
     * record with a 5-second lock timeout.
     *
     * @param id recurring transaction ID
     * @return locked recurring transaction if found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT r FROM RecurringTransaction r WHERE r.id = :id")
    Optional<RecurringTransaction> findByIdForUpdate(@Param("id") Long id);

    /**
     * Checks whether the specified account is referenced as source or target by any recurring
     * transaction in the given status.
     *
     * @param accountId account ID
     * @param status recurring status (e.g. {@link RecurringStatus#ACTIVE})
     * @return {@code true} if at least one matching recurring transaction exists
     */
    @Query(
            "SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM RecurringTransaction r"
                    + " WHERE r.status = :status AND (r.sourceAccountId = :accountId OR"
                    + " r.targetAccountId = :accountId)")
    boolean existsByAccountIdAndStatus(
            @Param("accountId") Long accountId, @Param("status") RecurringStatus status);
}

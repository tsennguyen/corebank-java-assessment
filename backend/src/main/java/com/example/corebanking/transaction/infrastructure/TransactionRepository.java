package com.example.corebanking.transaction.infrastructure;

import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.domain.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for Transaction entity with Specification support. */
@Repository
public interface TransactionRepository
        extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByReferenceNo(String referenceNo);

    @Query(
            "SELECT t FROM Transaction t WHERE t.sourceAccountId = :accountId OR t.targetAccountId"
                    + " = :accountId")
    Page<Transaction> findByAccountId(@Param("accountId") Long accountId, Pageable pageable);

    @Query(
            "SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t "
                    + "WHERE t.sourceAccountId = :accountId "
                    + "AND t.status = :status "
                    + "AND t.createdAt >= :since")
    BigDecimal sumOutgoingAmountSince(
            @Param("accountId") Long accountId,
            @Param("status") TransactionStatus status,
            @Param("since") Instant since);
}

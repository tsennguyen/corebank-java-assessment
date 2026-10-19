package com.example.corebanking.transaction.infrastructure;

import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.domain.TransactionStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for Transaction entity with Specification support. */
@Repository
public interface TransactionRepository
        extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") Long id);

    Optional<Transaction> findByReferenceNo(String referenceNo);

    boolean existsByReversalOfId(Long reversalOfId);

    Optional<Transaction> findByReversalOfId(Long reversalOfId);

    @Query(
            "SELECT t FROM Transaction t WHERE t.sourceAccountId = :accountId OR t.targetAccountId"
                    + " = :accountId")
    Page<Transaction> findByAccountId(@Param("accountId") Long accountId, Pageable pageable);

    @Query(
            "SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.sourceAccountId ="
                    + " :accountId AND t.status = :status AND t.type IN"
                    + " (com.example.corebanking.transaction.domain.TransactionType.WITHDRAWAL,"
                    + " com.example.corebanking.transaction.domain.TransactionType.TRANSFER,"
                    + " com.example.corebanking.transaction.domain.TransactionType.PAYMENT) AND"
                    + " t.createdAt >= :since")
    BigDecimal sumOutgoingAmountSince(
            @Param("accountId") Long accountId,
            @Param("status") TransactionStatus status,
            @Param("since") Instant since);

    @Query(
            "SELECT t FROM Transaction t WHERE t.sourceAccountId = :accountId OR"
                    + " t.targetAccountId = :accountId ORDER BY t.createdAt DESC, t.id DESC")
    java.util.List<Transaction> findKeysetFirstPage(
            @Param("accountId") Long accountId, Pageable limit);

    @Query(
            "SELECT t FROM Transaction t WHERE (t.sourceAccountId = :accountId OR"
                    + " t.targetAccountId = :accountId) AND (t.createdAt < :cursorCreatedAt OR"
                    + " (t.createdAt = :cursorCreatedAt AND t.id < :cursorId)) ORDER BY"
                    + " t.createdAt DESC, t.id DESC")
    java.util.List<Transaction> findKeysetAfterCursor(
            @Param("accountId") Long accountId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable limit);
}

package com.example.corebanking.common.idempotency;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for IdempotencyKey management and cleanup. */
@Repository
public interface IdempotencyRepository extends JpaRepository<IdempotencyKey, IdempotencyKeyId> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM IdempotencyKey k WHERE k.id = :id")
    Optional<IdempotencyKey> findByIdForUpdate(@Param("id") IdempotencyKeyId id);

    @Query(
            value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:lockKey))) t",
            nativeQuery = true)
    Integer acquireAdvisoryLock(@Param("lockKey") String lockKey);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :now")
    int deleteExpiredKeys(@Param("now") Instant now);
}

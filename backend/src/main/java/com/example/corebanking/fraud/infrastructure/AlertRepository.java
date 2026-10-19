package com.example.corebanking.fraud.infrastructure;

import com.example.corebanking.fraud.domain.Alert;
import com.example.corebanking.fraud.domain.AlertRuleCode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link Alert} persistence and dynamic specification queries
 * (L3-03, L3-04, FR-22, FR-23).
 */
@Repository
public interface AlertRepository
        extends JpaRepository<Alert, Long>, JpaSpecificationExecutor<Alert> {

    List<Alert> findByTransactionIdOrderByIdAsc(Long transactionId);

    Optional<Alert> findByTransactionIdAndRuleCode(Long transactionId, AlertRuleCode ruleCode);
}

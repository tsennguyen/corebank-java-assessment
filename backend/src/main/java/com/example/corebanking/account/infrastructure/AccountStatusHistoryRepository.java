package com.example.corebanking.account.infrastructure;

import com.example.corebanking.account.domain.AccountStatusHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for immutable {@link AccountStatusHistory} audit records. */
@Repository
public interface AccountStatusHistoryRepository extends JpaRepository<AccountStatusHistory, Long> {

    List<AccountStatusHistory> findByAccountIdOrderByChangedAtDescIdDesc(Long accountId);
}

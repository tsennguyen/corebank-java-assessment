package com.example.corebanking.customer.infrastructure;

import com.example.corebanking.customer.domain.CustomerType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for {@link CustomerType} entities. */
@Repository
public interface CustomerTypeRepository extends JpaRepository<CustomerType, Long> {

    /**
     * Finds a customer type by its case-insensitive code (e.g. {@code INDIVIDUAL}, {@code
     * BUSINESS}).
     */
    Optional<CustomerType> findByCodeIgnoreCase(String code);

    /** Returns all customer types ordered by ID ascending. */
    List<CustomerType> findAllByOrderByIdAsc();
}

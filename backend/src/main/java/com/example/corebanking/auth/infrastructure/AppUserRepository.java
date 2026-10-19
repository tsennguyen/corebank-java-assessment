package com.example.corebanking.auth.infrastructure;

import com.example.corebanking.auth.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link AppUser} persistence in {@code core.app_user}. */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByCustomerId(Long customerId);

    boolean existsByUsername(String username);
}

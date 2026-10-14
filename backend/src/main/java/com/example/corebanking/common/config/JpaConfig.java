package com.example.corebanking.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** JPA configuration enabling entity auditing for created_at and updated_at timestamps. */
@Configuration
@EnableJpaAuditing
public class JpaConfig {}

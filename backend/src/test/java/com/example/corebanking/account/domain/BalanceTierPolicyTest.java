package com.example.corebanking.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BalanceTierPolicyTest {

    private BalanceTierPolicy policy;

    @BeforeEach
    void setUp() {
        BalanceTierProperties properties = new BalanceTierProperties();
        properties.setMediumThreshold(new BigDecimal("10000000.00"));
        properties.setHighThreshold(new BigDecimal("100000000.00"));
        policy = new BalanceTierPolicy(properties);
    }

    @Test
    @DisplayName("classify correctly assigns LOW tier for balance < 10 million")
    void classify_shouldReturnLow() {
        assertThat(policy.classify(null)).isEqualTo(BalanceTier.LOW);
        assertThat(policy.classify(BigDecimal.ZERO)).isEqualTo(BalanceTier.LOW);
        assertThat(policy.classify(new BigDecimal("5000000.00"))).isEqualTo(BalanceTier.LOW);
        assertThat(policy.classify(new BigDecimal("9999999.99"))).isEqualTo(BalanceTier.LOW);
    }

    @Test
    @DisplayName("classify correctly assigns MEDIUM tier for 10M <= balance < 100M")
    void classify_shouldReturnMedium() {
        assertThat(policy.classify(new BigDecimal("10000000.00"))).isEqualTo(BalanceTier.MEDIUM);
        assertThat(policy.classify(new BigDecimal("50000000.00"))).isEqualTo(BalanceTier.MEDIUM);
        assertThat(policy.classify(new BigDecimal("99999999.99"))).isEqualTo(BalanceTier.MEDIUM);
    }

    @Test
    @DisplayName("classify correctly assigns HIGH tier for balance >= 100M")
    void classify_shouldReturnHigh() {
        assertThat(policy.classify(new BigDecimal("100000000.00"))).isEqualTo(BalanceTier.HIGH);
        assertThat(policy.classify(new BigDecimal("500000000.00"))).isEqualTo(BalanceTier.HIGH);
    }
}

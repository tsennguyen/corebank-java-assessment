package com.example.corebanking.account.domain;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration properties for balance tier thresholds. */
@Component
@ConfigurationProperties(prefix = "app.statistics.balance-tiers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BalanceTierProperties {

    /** Minimum balance for MEDIUM tier (default 10,000,000 VND). */
    private BigDecimal mediumThreshold = new BigDecimal("10000000.00");

    /** Minimum balance for HIGH tier (default 100,000,000 VND). */
    private BigDecimal highThreshold = new BigDecimal("100000000.00");
}

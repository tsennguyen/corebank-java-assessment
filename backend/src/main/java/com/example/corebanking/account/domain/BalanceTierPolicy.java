package com.example.corebanking.account.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Policy classifying account balances into tiers (LOW, MEDIUM, HIGH) using a TreeMap floorEntry
 * lookup for O(log n) performance.
 */
@Component
public class BalanceTierPolicy {

    private final TreeMap<BigDecimal, BalanceTier> tierMap;

    public BalanceTierPolicy(BalanceTierProperties properties) {
        this.tierMap = new TreeMap<>();
        this.tierMap.put(BigDecimal.ZERO, BalanceTier.LOW);
        this.tierMap.put(properties.getMediumThreshold(), BalanceTier.MEDIUM);
        this.tierMap.put(properties.getHighThreshold(), BalanceTier.HIGH);
    }

    /**
     * Classifies a given balance into its corresponding BalanceTier.
     *
     * @param balance account balance
     * @return matching BalanceTier (LOW, MEDIUM, or HIGH)
     */
    public BalanceTier classify(BigDecimal balance) {
        if (balance == null || balance.compareTo(BigDecimal.ZERO) <= 0) {
            return BalanceTier.LOW;
        }
        Map.Entry<BigDecimal, BalanceTier> entry = tierMap.floorEntry(balance);
        return entry != null ? entry.getValue() : BalanceTier.LOW;
    }
}

package com.example.corebanking.transaction.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for tiered fee policies mapped to prefix "app.fees". Supplies default
 * tiers according to PRD FR-6 if properties are omitted in YAML.
 */
@Component
@ConfigurationProperties(prefix = "app.fees")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FeeProperties {

    private List<FeeTierConfig> withdrawal = new ArrayList<>();
    private List<FeeTierConfig> transfer = new ArrayList<>();
    private List<FeeTierConfig> deposit = new ArrayList<>();
    private List<FeeTierConfig> payment = new ArrayList<>();

    public record FeeTierConfig(
            BigDecimal minAmount, BigDecimal flatFee, BigDecimal ratePercent, BigDecimal maxFee) {}

    public List<FeeTier> getWithdrawalTiers() {
        if (withdrawal == null || withdrawal.isEmpty()) {
            return List.of(
                    new FeeTier(
                            new BigDecimal("0.00"),
                            new BigDecimal("2000.00"),
                            BigDecimal.ZERO,
                            null),
                    new FeeTier(
                            new BigDecimal("10000000.00"),
                            BigDecimal.ZERO,
                            new BigDecimal("0.02"),
                            new BigDecimal("50000.00")));
        }
        return toTiers(withdrawal);
    }

    public List<FeeTier> getTransferTiers() {
        if (transfer == null || transfer.isEmpty()) {
            return List.of(
                    new FeeTier(new BigDecimal("0.00"), BigDecimal.ZERO, BigDecimal.ZERO, null),
                    new FeeTier(
                            new BigDecimal("500000.00"),
                            new BigDecimal("2000.00"),
                            BigDecimal.ZERO,
                            null),
                    new FeeTier(
                            new BigDecimal("10000000.00"),
                            BigDecimal.ZERO,
                            new BigDecimal("0.02"),
                            new BigDecimal("50000.00")));
        }
        return toTiers(transfer);
    }

    public List<FeeTier> getDepositTiers() {
        if (deposit == null || deposit.isEmpty()) {
            return List.of(new FeeTier(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null));
        }
        return toTiers(deposit);
    }

    public List<FeeTier> getPaymentTiers() {
        if (payment == null || payment.isEmpty()) {
            return List.of(new FeeTier(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null));
        }
        return toTiers(payment);
    }

    private List<FeeTier> toTiers(List<FeeTierConfig> configs) {
        return configs.stream()
                .map(c -> new FeeTier(c.minAmount(), c.flatFee(), c.ratePercent(), c.maxFee()))
                .toList();
    }
}

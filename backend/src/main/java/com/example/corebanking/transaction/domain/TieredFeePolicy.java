package com.example.corebanking.transaction.domain;

import com.example.corebanking.common.domain.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Domain implementation of {@link FeePolicy} evaluating tiered transaction fees using a {@link
 * TreeMap#floorEntry(Object)} lookup with O(log n) performance.
 */
@Component
public class TieredFeePolicy implements FeePolicy {

    private final Map<TransactionType, TreeMap<BigDecimal, FeeTier>> policyMap;

    public TieredFeePolicy(FeeProperties properties) {
        Map<TransactionType, TreeMap<BigDecimal, FeeTier>> map =
                new EnumMap<>(TransactionType.class);

        map.put(TransactionType.WITHDRAWAL, buildTreeMap(properties.getWithdrawalTiers()));
        map.put(TransactionType.TRANSFER, buildTreeMap(properties.getTransferTiers()));
        map.put(TransactionType.DEPOSIT, buildTreeMap(properties.getDepositTiers()));
        map.put(TransactionType.PAYMENT, buildTreeMap(properties.getPaymentTiers()));

        this.policyMap = Collections.unmodifiableMap(map);
    }

    private TreeMap<BigDecimal, FeeTier> buildTreeMap(List<FeeTier> tiers) {
        TreeMap<BigDecimal, FeeTier> treeMap = new TreeMap<>();
        if (tiers != null) {
            for (FeeTier tier : tiers) {
                treeMap.put(tier.minAmount(), tier);
            }
        }
        if (treeMap.isEmpty() || !treeMap.containsKey(BigDecimal.ZERO)) {
            treeMap.put(
                    BigDecimal.ZERO,
                    new FeeTier(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null));
        }
        return treeMap;
    }

    @Override
    public Money calculateFee(TransactionType type, Money amount) {
        Objects.requireNonNull(type, "transaction type must not be null");
        Objects.requireNonNull(amount, "transaction amount must not be null");

        if (amount.isNegative() || amount.isZero()) {
            throw new IllegalArgumentException("transaction amount must be strictly positive");
        }

        TreeMap<BigDecimal, FeeTier> tiers = policyMap.get(type);
        if (tiers == null) {
            return Money.ZERO;
        }

        Map.Entry<BigDecimal, FeeTier> entry = tiers.floorEntry(amount.getAmount());
        if (entry == null) {
            return Money.ZERO;
        }

        FeeTier tier = entry.getValue();
        BigDecimal flatFee = tier.flatFee() != null ? tier.flatFee() : BigDecimal.ZERO;
        BigDecimal ratePercent = tier.ratePercent() != null ? tier.ratePercent() : BigDecimal.ZERO;

        BigDecimal percentageFee = BigDecimal.ZERO;
        if (ratePercent.compareTo(BigDecimal.ZERO) > 0) {
            percentageFee =
                    amount.getAmount()
                            .multiply(ratePercent)
                            .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        }

        BigDecimal calculatedTotal = flatFee.add(percentageFee);
        if (tier.maxFee() != null && calculatedTotal.compareTo(tier.maxFee()) > 0) {
            calculatedTotal = tier.maxFee();
        }

        if (calculatedTotal.compareTo(BigDecimal.ZERO) < 0) {
            calculatedTotal = BigDecimal.ZERO;
        }

        return Money.of(calculatedTotal);
    }
}

package com.example.corebanking.report.application;

import com.example.corebanking.account.domain.BalanceTier;
import com.example.corebanking.account.domain.BalanceTierProperties;
import com.example.corebanking.report.api.dto.BalanceTierStatisticResponse;
import com.example.corebanking.report.api.dto.LocationStatisticResponse;
import com.example.corebanking.report.infrastructure.StatisticsRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service providing aggregated statistics for account balance tiers and customer
 * geographical distribution (L1-09, L1-10, FR-11, FR-12).
 */
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final StatisticsRepository statisticsRepository;
    private final BalanceTierProperties balanceTierProperties;

    /**
     * Returns account count and transaction count for each balance tier (LOW, MEDIUM, HIGH) using
     * configured thresholds from {@link BalanceTierProperties}.
     *
     * @return ordered list of 3 balance tier statistics
     */
    @Transactional(readOnly = true)
    public List<BalanceTierStatisticResponse> getBalanceTierStatistics() {
        List<BalanceTierStatisticResponse> raw =
                statisticsRepository.findBalanceTierStatistics(
                        balanceTierProperties.getMediumThreshold(),
                        balanceTierProperties.getHighThreshold());

        Map<BalanceTier, BalanceTierStatisticResponse> byTier = new EnumMap<>(BalanceTier.class);
        for (BalanceTierStatisticResponse item : raw) {
            byTier.put(item.tier(), item);
        }

        return List.of(
                byTier.getOrDefault(
                        BalanceTier.LOW, new BalanceTierStatisticResponse(BalanceTier.LOW, 0L, 0L)),
                byTier.getOrDefault(
                        BalanceTier.MEDIUM,
                        new BalanceTierStatisticResponse(BalanceTier.MEDIUM, 0L, 0L)),
                byTier.getOrDefault(
                        BalanceTier.HIGH,
                        new BalanceTierStatisticResponse(BalanceTier.HIGH, 0L, 0L)));
    }

    /**
     * Returns active customer counts grouped by location in descending order.
     *
     * @return list of location statistics
     */
    @Transactional(readOnly = true)
    public List<LocationStatisticResponse> getCustomersByLocation() {
        return statisticsRepository.findCustomerCountByLocation();
    }
}

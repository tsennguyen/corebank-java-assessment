package com.example.corebanking.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.account.domain.BalanceTier;
import com.example.corebanking.account.domain.BalanceTierProperties;
import com.example.corebanking.report.api.dto.BalanceTierStatisticResponse;
import com.example.corebanking.report.api.dto.LocationStatisticResponse;
import com.example.corebanking.report.infrastructure.StatisticsRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

@ExtendWith(MockitoExtension.class)
class StatisticsServiceTest {

    @Mock private StatisticsRepository statisticsRepository;

    private BalanceTierProperties balanceTierProperties;
    private StatisticsService statisticsService;

    @BeforeEach
    void setUp() {
        balanceTierProperties =
                new BalanceTierProperties(
                        new BigDecimal("10000000.00"), new BigDecimal("100000000.00"));
        statisticsService = new StatisticsService(statisticsRepository, balanceTierProperties);
    }

    @Test
    @DisplayName(
            "getBalanceTierStatistics returns all 3 tiers in LOW, MEDIUM, HIGH order and fills"
                    + " missing tiers with zeros")
    void getBalanceTierStatistics_whenPartialRowsReturned_fillsMissingTiersAndPreservesOrder() {
        when(statisticsRepository.findBalanceTierStatistics(
                        new BigDecimal("10000000.00"), new BigDecimal("100000000.00")))
                .thenReturn(
                        List.of(
                                new BalanceTierStatisticResponse(BalanceTier.HIGH, 2L, 9L),
                                new BalanceTierStatisticResponse(BalanceTier.LOW, 4L, 11L)));

        List<BalanceTierStatisticResponse> result = statisticsService.getBalanceTierStatistics();

        assertThat(result)
                .containsExactly(
                        new BalanceTierStatisticResponse(BalanceTier.LOW, 4L, 11L),
                        new BalanceTierStatisticResponse(BalanceTier.MEDIUM, 0L, 0L),
                        new BalanceTierStatisticResponse(BalanceTier.HIGH, 2L, 9L));
        verify(statisticsRepository)
                .findBalanceTierStatistics(
                        new BigDecimal("10000000.00"), new BigDecimal("100000000.00"));
    }

    @Test
    @DisplayName("getCustomersByLocation delegates to repository and returns sorted statistics")
    void getCustomersByLocation_delegatesToRepository() {
        List<LocationStatisticResponse> expected =
                List.of(
                        new LocationStatisticResponse("Hà Nội", 7L),
                        new LocationStatisticResponse("Hồ Chí Minh", 6L),
                        new LocationStatisticResponse("Đà Nẵng", 3L));
        when(statisticsRepository.findCustomerCountByLocation()).thenReturn(expected);

        List<LocationStatisticResponse> actual = statisticsService.getCustomersByLocation();

        assertThat(actual).isEqualTo(expected);
        verify(statisticsRepository).findCustomerCountByLocation();
    }

    @Test
    @DisplayName("StatisticsRepository maps balance tier and location rows via JdbcClient")
    @SuppressWarnings("unchecked")
    void statisticsRepository_mapsRowsCorrectly() throws Exception {
        JdbcClient jdbcClient = Mockito.mock(JdbcClient.class);
        JdbcClient.StatementSpec statementSpec = Mockito.mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Object> mappedQuerySpec =
                Mockito.mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(mappedQuerySpec);
        when(mappedQuerySpec.list()).thenReturn(List.of());

        StatisticsRepository repo = new StatisticsRepository(jdbcClient);
        repo.findBalanceTierStatistics(
                new BigDecimal("10000000.00"), new BigDecimal("100000000.00"));

        ArgumentCaptor<RowMapper<BalanceTierStatisticResponse>> tierMapperCaptor =
                ArgumentCaptor.forClass(RowMapper.class);
        verify(statementSpec).query(tierMapperCaptor.capture());

        ResultSet tierRs = Mockito.mock(ResultSet.class);
        when(tierRs.getString("tier")).thenReturn("MEDIUM");
        when(tierRs.getLong("account_count")).thenReturn(5L);
        when(tierRs.getLong("transaction_count")).thenReturn(18L);

        BalanceTierStatisticResponse mappedTier = tierMapperCaptor.getValue().mapRow(tierRs, 0);
        assertThat(mappedTier)
                .isEqualTo(new BalanceTierStatisticResponse(BalanceTier.MEDIUM, 5L, 18L));

        Mockito.reset(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(mappedQuerySpec);
        repo.findCustomerCountByLocation();

        ArgumentCaptor<RowMapper<LocationStatisticResponse>> locMapperCaptor =
                ArgumentCaptor.forClass(RowMapper.class);
        verify(statementSpec).query(locMapperCaptor.capture());

        ResultSet locRs = Mockito.mock(ResultSet.class);
        when(locRs.getString("location")).thenReturn("Đà Nẵng");
        when(locRs.getLong("customer_count")).thenReturn(3L);

        LocationStatisticResponse mappedLoc = locMapperCaptor.getValue().mapRow(locRs, 0);
        assertThat(mappedLoc).isEqualTo(new LocationStatisticResponse("Đà Nẵng", 3L));
    }
}

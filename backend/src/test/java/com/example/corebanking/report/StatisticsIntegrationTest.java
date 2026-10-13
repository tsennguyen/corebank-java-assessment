package com.example.corebanking.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.domain.BalanceTier;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.report.api.dto.BalanceTierStatisticResponse;
import com.example.corebanking.report.api.dto.LocationStatisticResponse;
import com.example.corebanking.report.application.StatisticsService;
import com.example.corebanking.support.IntegrationTestBase;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import com.example.corebanking.transaction.api.dto.TransferRequest;
import com.example.corebanking.transaction.application.TransactionService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
class StatisticsIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private StatisticsService statisticsService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionService transactionService;
    @Autowired private JdbcClient jdbcClient;

    @Test
    @Transactional
    @DisplayName(
            "Balance tier and location statistics match hand-calculated deltas and exclude CLOSED"
                    + " accounts and soft-deleted customers (L1-09, L1-10)")
    void statisticsEndpoints_shouldMatchHandCalculatedDeltasAndExcludeClosedOrDeleted()
            throws Exception {
        // Record baseline counts before inserting test fixtures
        Map<BalanceTier, BalanceTierStatisticResponse> baselineTiers =
                statisticsService.getBalanceTierStatistics().stream()
                        .collect(
                                Collectors.toMap(BalanceTierStatisticResponse::tier, item -> item));
        Map<String, Long> baselineLocations =
                statisticsService.getCustomersByLocation().stream()
                        .collect(
                                Collectors.toMap(
                                        LocationStatisticResponse::location,
                                        LocationStatisticResponse::customerCount));

        // 1. Insert 3 active customers in "Nha Trang" and 1 soft-deleted customer in "Nha Trang"
        Customer c1 =
                customerRepository.save(
                        new Customer(
                                "CUS9990201",
                                "Nguyễn Thống Kê Một",
                                "000999888801",
                                "tk1@example.com",
                                "0900000201",
                                "Số 1 Nha Trang",
                                "Nha Trang",
                                LocalDate.of(1990, 1, 1)));
        Customer c2 =
                customerRepository.save(
                        new Customer(
                                "CUS9990202",
                                "Nguyễn Thống Kê Hai",
                                "000999888802",
                                "tk2@example.com",
                                "0900000202",
                                "Số 2 Nha Trang",
                                "Nha Trang",
                                LocalDate.of(1991, 2, 2)));
        Customer c3 =
                customerRepository.save(
                        new Customer(
                                "CUS9990203",
                                "Nguyễn Thống Kê Ba",
                                "000999888803",
                                "tk3@example.com",
                                "0900000203",
                                "Số 3 Nha Trang",
                                "Nha Trang",
                                LocalDate.of(1992, 3, 3)));
        Customer cDeleted =
                new Customer(
                        "CUS9990204",
                        "Nguyễn Đã Xóa",
                        "000999888804",
                        "tk4@example.com",
                        "0900000204",
                        "Số 4 Nha Trang",
                        "Nha Trang",
                        LocalDate.of(1993, 4, 4));
        cDeleted.softDelete(Instant.now());
        customerRepository.save(cDeleted);

        // 2. Create 1 LOW account (5M), 1 MEDIUM account (25M), 1 HIGH account (150M), and 1
        // CLOSED account (0M)
        Account lowAcc =
                accountRepository.save(
                        new Account(
                                "100999999201",
                                c1.getId(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00"),
                                LocalDate.now()));
        Account medAcc =
                accountRepository.save(
                        new Account(
                                "100999999202",
                                c2.getId(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00"),
                                LocalDate.now()));
        Account highAcc =
                accountRepository.save(
                        new Account(
                                "100999999203",
                                c3.getId(),
                                new BigDecimal("200000000.00"),
                                new BigDecimal("500000000.00"),
                                LocalDate.now()));
        Account closedAcc =
                new Account(
                        "100999999204",
                        c1.getId(),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("200000000.00"),
                        LocalDate.now());
        closedAcc.close(Instant.now());
        accountRepository.save(closedAcc);

        // Execute transactions:
        // - Deposit 5M into lowAcc (1 tx for LOW)
        // - Deposit 25M into medAcc (1 tx for MEDIUM)
        // - Deposit 155M into highAcc (1 tx for HIGH)
        // - Transfer 5M from highAcc to medAcc (1 tx for HIGH, 1 tx for MEDIUM)
        // Final balances: lowAcc = 5M (LOW), medAcc = 30M (MEDIUM), highAcc = 149.998M (HIGH)
        transactionService.deposit(
                new DepositRequest(
                        lowAcc.getId(), new BigDecimal("5000000.00"), "Nha Trang", "Deposit LOW"));
        transactionService.deposit(
                new DepositRequest(
                        medAcc.getId(),
                        new BigDecimal("25000000.00"),
                        "Nha Trang",
                        "Deposit MEDIUM"));
        transactionService.deposit(
                new DepositRequest(
                        highAcc.getId(),
                        new BigDecimal("155000000.00"),
                        "Nha Trang",
                        "Deposit HIGH"));
        transactionService.transfer(
                new TransferRequest(
                        highAcc.getId(),
                        medAcc.getId(),
                        new BigDecimal("5000000.00"),
                        "Nha Trang",
                        "Transfer HIGH to MEDIUM"));

        // Verify balance tier deltas:
        // LOW: +1 account, +1 transaction
        // MEDIUM: +1 account, +2 transactions
        // HIGH: +1 account, +2 transactions
        // CLOSED account is excluded!
        Map<BalanceTier, BalanceTierStatisticResponse> afterTiers =
                statisticsService.getBalanceTierStatistics().stream()
                        .collect(
                                Collectors.toMap(BalanceTierStatisticResponse::tier, item -> item));

        assertThat(
                        afterTiers.get(BalanceTier.LOW).accountCount()
                                - baselineTiers.get(BalanceTier.LOW).accountCount())
                .isEqualTo(1L);
        assertThat(
                        afterTiers.get(BalanceTier.LOW).transactionCount()
                                - baselineTiers.get(BalanceTier.LOW).transactionCount())
                .isEqualTo(1L);

        assertThat(
                        afterTiers.get(BalanceTier.MEDIUM).accountCount()
                                - baselineTiers.get(BalanceTier.MEDIUM).accountCount())
                .isEqualTo(1L);
        assertThat(
                        afterTiers.get(BalanceTier.MEDIUM).transactionCount()
                                - baselineTiers.get(BalanceTier.MEDIUM).transactionCount())
                .isEqualTo(2L);

        assertThat(
                        afterTiers.get(BalanceTier.HIGH).accountCount()
                                - baselineTiers.get(BalanceTier.HIGH).accountCount())
                .isEqualTo(1L);
        assertThat(
                        afterTiers.get(BalanceTier.HIGH).transactionCount()
                                - baselineTiers.get(BalanceTier.HIGH).transactionCount())
                .isEqualTo(2L);

        // Verify location delta for "Nha Trang": +3 active customers (soft-deleted customer
        // excluded)
        Map<String, Long> afterLocations =
                statisticsService.getCustomersByLocation().stream()
                        .collect(
                                Collectors.toMap(
                                        LocationStatisticResponse::location,
                                        LocationStatisticResponse::customerCount));
        long nhaTrangDelta =
                afterLocations.getOrDefault("Nha Trang", 0L)
                        - baselineLocations.getOrDefault("Nha Trang", 0L);
        assertThat(nhaTrangDelta).isEqualTo(3L);

        // Verify HTTP endpoints return 200 OK with expected structure
        mockMvc.perform(get("/api/v1/statistics/balance-tiers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].tier").value("LOW"))
                .andExpect(jsonPath("$[1].tier").value("MEDIUM"))
                .andExpect(jsonPath("$[2].tier").value("HIGH"));

        mockMvc.perform(get("/api/v1/statistics/customers-by-location"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customerCount").isNumber());
    }

    @Test
    @Transactional
    @DisplayName(
            "R__demo_seed.sql executes idempotently twice on PostgreSQL 17 and populates synthetic"
                    + " data across all 3 tiers (E-02, NFR-11)")
    void demoSeedScript_shouldExecuteIdempotentlyTwiceAndPopulateAllTiers() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/seed/R__demo_seed.sql");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        // Execute R__demo_seed.sql twice to verify ON CONFLICT DO NOTHING idempotency
        jdbcClient.sql(sql).update();
        jdbcClient.sql(sql).update();

        List<BalanceTierStatisticResponse> tiers = statisticsService.getBalanceTierStatistics();
        assertThat(tiers).hasSize(3);
        assertThat(tiers.get(0).accountCount()).isGreaterThanOrEqualTo(4L);
        assertThat(tiers.get(1).accountCount()).isGreaterThanOrEqualTo(4L);
        assertThat(tiers.get(2).accountCount()).isGreaterThanOrEqualTo(4L);

        List<LocationStatisticResponse> locations = statisticsService.getCustomersByLocation();
        assertThat(locations).isNotEmpty();
        // Verify descending order of customer counts
        for (int i = 0; i < locations.size() - 1; i++) {
            assertThat(locations.get(i).customerCount())
                    .isGreaterThanOrEqualTo(locations.get(i + 1).customerCount());
        }
    }
}

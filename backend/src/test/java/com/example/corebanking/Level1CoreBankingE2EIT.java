package com.example.corebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.account.domain.BalanceTier;
import com.example.corebanking.account.infrastructure.AccountNumberGenerator;
import com.example.corebanking.report.api.dto.BalanceTierStatisticResponse;
import com.example.corebanking.report.api.dto.LocationStatisticResponse;
import com.example.corebanking.report.application.StatisticsService;
import com.example.corebanking.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-End API Integration Test covering the full Epic 2 (Level 1 Core Banking) lifecycle:
 * Customer Onboarding -> Account Opening (Luhn) & Limit Adjustment -> Fee Preview -> Idempotent
 * Deposit -> Tiered-Fee Transfer -> Metadata Patch & Immutability Guard -> SQL Statistics ->
 * Transaction Reversal -> Zero-Balance Account Closure & Customer Soft-Deletion (L1-01..L1-10,
 * NC-01, NC-02, NC-08).
 */
@AutoConfigureMockMvc
class Level1CoreBankingE2EIT extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StatisticsService statisticsService;

    @Test
    @DisplayName(
            "Full Level 1 Core Banking E2E journey across Customer, Account, Fee, Transaction,"
                    + " Idempotency, Reversal, and Statistics APIs (L1-01..L1-10)")
    void fullLevel1CoreBankingLifecycle_shouldMaintainLedgerAndStatisticsInvariants()
            throws Exception {
        // Record baseline statistics before starting the E2E journey
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

        // =========================================================================
        // 1. Customer Onboarding & Optimistic Locking Profile Update (L1-01, L1-04)
        // =========================================================================
        MvcResult createCustAResult =
                mockMvc.perform(
                                post("/api/v1/customers")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "fullName": "Nguyễn Văn An E2E",
                                                  "nationalId": "000777888901",
                                                  "email": "an.e2e@example.com",
                                                  "phone": "0907778901",
                                                  "address": "Số 10 Hùng Vương",
                                                  "location": "Pleiku",
                                                  "dateOfBirth": "1990-05-15"
                                                }
                                                """))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.customerCode").isString())
                        .andExpect(jsonPath("$.nationalId").value("000*****8901"))
                        .andExpect(jsonPath("$.version").value(0))
                        .andReturn();
        JsonNode customerA =
                objectMapper.readTree(createCustAResult.getResponse().getContentAsString());
        long customerAId = customerA.get("id").asLong();

        MvcResult createCustBResult =
                mockMvc.perform(
                                post("/api/v1/customers")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "fullName": "Trần Thị Bình E2E",
                                                  "nationalId": "000777888902",
                                                  "email": "binh.e2e@example.com",
                                                  "phone": "0907778902",
                                                  "address": "Số 20 Lê Lợi",
                                                  "location": "Pleiku",
                                                  "dateOfBirth": "1992-08-20"
                                                }
                                                """))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.nationalId").value("000*****8902"))
                        .andReturn();
        JsonNode customerB =
                objectMapper.readTree(createCustBResult.getResponse().getContentAsString());
        long customerBId = customerB.get("id").asLong();

        // Update Customer A profile with version 0 -> increments version to 1
        mockMvc.perform(
                        put("/api/v1/customers/{id}", customerAId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "fullName": "Nguyễn Văn An E2E Updated",
                                          "email": "an.e2e.updated@example.com",
                                          "phone": "0907778901",
                                          "address": "Số 12 Hùng Vương",
                                          "location": "Pleiku",
                                          "dateOfBirth": "1990-05-15",
                                          "version": 0
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // Stale update with version 0 must fail with 409 Conflict (NC-08)
        mockMvc.perform(
                        put("/api/v1/customers/{id}", customerAId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "fullName": "Nguyễn Văn An Stale",
                                          "email": "an.stale@example.com",
                                          "phone": "0907778901",
                                          "address": "Số 12 Hùng Vương",
                                          "location": "Pleiku",
                                          "dateOfBirth": "1990-05-15",
                                          "version": 0
                                        }
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));

        // =========================================================================
        // 2. Account Opening, Luhn Check & Limit Update (L1-02, L1-05)
        // =========================================================================
        MvcResult createAccAResult =
                mockMvc.perform(
                                post("/api/v1/accounts")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                String.format(
                                                        """
                                                        {
                                                          "customerId": %d,
                                                          "transactionLimit": 100000000.00,
                                                          "dailyLimit": 200000000.00
                                                        }
                                                        """,
                                                        customerAId)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("ACTIVE"))
                        .andExpect(jsonPath("$.balanceTier").value("LOW"))
                        .andExpect(jsonPath("$.version").value(0))
                        .andReturn();
        JsonNode accountA =
                objectMapper.readTree(createAccAResult.getResponse().getContentAsString());
        long accountAId = accountA.get("id").asLong();
        String accountANumber = accountA.get("accountNumber").asText();
        assertValidLuhnAccountNumber(accountANumber);

        MvcResult createAccBResult =
                mockMvc.perform(
                                post("/api/v1/accounts")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                String.format(
                                                        """
                                                        {
                                                          "customerId": %d,
                                                          "transactionLimit": 50000000.00,
                                                          "dailyLimit": 100000000.00
                                                        }
                                                        """,
                                                        customerBId)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("ACTIVE"))
                        .andReturn();
        JsonNode accountB =
                objectMapper.readTree(createAccBResult.getResponse().getContentAsString());
        long accountBId = accountB.get("id").asLong();
        String accountBNumber = accountB.get("accountNumber").asText();
        assertValidLuhnAccountNumber(accountBNumber);

        // Update Account A limits with optimistic locking version 0 -> version 1
        mockMvc.perform(
                        patch("/api/v1/accounts/{id}", accountAId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "transactionLimit": 120000000.00,
                                          "dailyLimit": 250000000.00,
                                          "version": 0
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionLimit").value(120000000.00))
                .andExpect(jsonPath("$.dailyLimit").value(250000000.00))
                .andExpect(jsonPath("$.version").value(1));

        // =========================================================================
        // 3. Fee Preview, Deposit & Idempotency Replay (L1-06, L1-08, NC-02)
        // =========================================================================
        mockMvc.perform(
                        get("/api/v1/fees/preview")
                                .param("type", "TRANSFER")
                                .param("amount", "30000000.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").value(2000.00));

        String depositIdempKey = "e2e-dep-" + UUID.randomUUID();
        String depositPayload =
                String.format(
                        """
                        {
                          "accountId": %d,
                          "amount": 150000000.00,
                          "location": "Pleiku",
                          "description": "Nạp tiền khởi tạo E2E"
                        }
                        """,
                        accountAId);

        MvcResult depositResult =
                mockMvc.perform(
                                post("/api/v1/transactions/deposits")
                                        .header("Idempotency-Key", depositIdempKey)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(depositPayload))
                        .andExpect(status().isCreated())
                        .andExpect(header().doesNotExist("Idempotent-Replayed"))
                        .andExpect(jsonPath("$.type").value("DEPOSIT"))
                        .andExpect(jsonPath("$.amount").value(150000000.00))
                        .andExpect(jsonPath("$.fee").value(0.00))
                        .andExpect(jsonPath("$.targetBalanceAfter").value(150000000.00))
                        .andReturn();
        long depositTxId =
                objectMapper
                        .readTree(depositResult.getResponse().getContentAsString())
                        .get("id")
                        .asLong();

        // Replay with identical Idempotency-Key and payload -> 201 Created + Idempotent-Replayed:
        // true
        mockMvc.perform(
                        post("/api/v1/transactions/deposits")
                                .header("Idempotency-Key", depositIdempKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(depositPayload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(depositTxId));

        // Reusing same Idempotency-Key with different payload -> 422 IDEMPOTENCY_KEY_REUSED
        mockMvc.perform(
                        post("/api/v1/transactions/deposits")
                                .header("Idempotency-Key", depositIdempKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {
                                                  "accountId": %d,
                                                  "amount": 999999.00,
                                                  "location": "Pleiku",
                                                  "description": "Payload bị thay đổi"
                                                }
                                                """,
                                                accountAId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));

        // =========================================================================
        // 4. Transfer with Tiered Fee, Limit Guard & Metadata Update (L1-03, L1-06, L1-07, L1-08)
        // =========================================================================
        // Exceeding transactionLimit (130M > 120M) must fail with 422 TRANSACTION_LIMIT_EXCEEDED
        mockMvc.perform(
                        post("/api/v1/transactions/transfers")
                                .header("Idempotency-Key", "e2e-trf-limit-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {
                                                  "sourceAccountId": %d,
                                                  "targetAccountId": %d,
                                                  "amount": 130000000.00,
                                                  "location": "Pleiku",
                                                  "description": "Chuyển vượt hạn mức"
                                                }
                                                """,
                                                accountAId, accountBId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_LIMIT_EXCEEDED"));

        // Valid transfer of 30,000,000 VND (fee = 2,000 VND)
        MvcResult transferResult =
                mockMvc.perform(
                                post("/api/v1/transactions/transfers")
                                        .header("Idempotency-Key", "e2e-trf-" + UUID.randomUUID())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                String.format(
                                                        """
                                                        {
                                                          "sourceAccountId": %d,
                                                          "targetAccountId": %d,
                                                          "amount": 30000000.00,
                                                          "location": "Pleiku",
                                                          "description": "Chuyển khoản E2E"
                                                        }
                                                        """,
                                                        accountAId, accountBId)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.type").value("TRANSFER"))
                        .andExpect(jsonPath("$.amount").value(30000000.00))
                        .andExpect(jsonPath("$.fee").value(2000.00))
                        .andExpect(jsonPath("$.sourceBalanceAfter").value(119998000.00))
                        .andExpect(jsonPath("$.targetBalanceAfter").value(30000000.00))
                        .andReturn();
        long transferTxId =
                objectMapper
                        .readTree(transferResult.getResponse().getContentAsString())
                        .get("id")
                        .asLong();

        // Patch mutable transaction metadata -> 200 OK
        mockMvc.perform(
                        patch("/api/v1/transactions/{id}", transferTxId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "description": "Chuyển khoản E2E đã cập nhật nội dung",
                                          "location": "Chi nhánh Pleiku"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Chuyển khoản E2E đã cập nhật nội dung"))
                .andExpect(jsonPath("$.location").value("Chi nhánh Pleiku"));

        // Attempting to patch immutable financial field amount -> 400 IMMUTABLE_FIELD
        mockMvc.perform(
                        patch("/api/v1/transactions/{id}", transferTxId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "description": "Sửa số tiền trái phép",
                                          "amount": 1000.00
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IMMUTABLE_FIELD"));

        // =========================================================================
        // 5. Verify SQL Statistics Endpoints (L1-09, L1-10)
        // =========================================================================
        // Account A is HIGH (119,998,000 VND) with 2 tx (deposit + transfer)
        // Account B is MEDIUM (30,000,000 VND) with 1 tx (incoming transfer)
        Map<BalanceTier, BalanceTierStatisticResponse> afterTiers =
                statisticsService.getBalanceTierStatistics().stream()
                        .collect(
                                Collectors.toMap(BalanceTierStatisticResponse::tier, item -> item));
        assertThat(afterTiers.get(BalanceTier.HIGH).accountCount())
                .isEqualTo(baselineTiers.get(BalanceTier.HIGH).accountCount() + 1L);
        assertThat(afterTiers.get(BalanceTier.HIGH).transactionCount())
                .isEqualTo(baselineTiers.get(BalanceTier.HIGH).transactionCount() + 2L);
        assertThat(afterTiers.get(BalanceTier.MEDIUM).accountCount())
                .isEqualTo(baselineTiers.get(BalanceTier.MEDIUM).accountCount() + 1L);
        assertThat(afterTiers.get(BalanceTier.MEDIUM).transactionCount())
                .isEqualTo(baselineTiers.get(BalanceTier.MEDIUM).transactionCount() + 1L);

        Map<String, Long> afterLocations =
                statisticsService.getCustomersByLocation().stream()
                        .collect(
                                Collectors.toMap(
                                        LocationStatisticResponse::location,
                                        LocationStatisticResponse::customerCount));
        long baselinePleiku = baselineLocations.getOrDefault("Pleiku", 0L);
        assertThat(afterLocations.get("Pleiku")).isEqualTo(baselinePleiku + 2L);

        // =========================================================================
        // 6. Transaction Reversal, Account Closure & Customer Soft-Delete (L1-04..L1-06)
        // =========================================================================
        // Closing Account B while balance is 30M must fail with 409 ACCOUNT_HAS_BALANCE
        mockMvc.perform(delete("/api/v1/accounts/{id}", accountBId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_HAS_BALANCE"));

        // Reverse the transfer -> refunds 30M + 2,000 fee to Account A and deducts 30M from Account
        // B
        mockMvc.perform(delete("/api/v1/transactions/{id}", transferTxId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.reversalOfId").value(transferTxId));

        // Verify Account A balance is restored to 150,000,000.00 and Account B is 0.00
        mockMvc.perform(get("/api/v1/accounts/{id}", accountAId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(150000000.00));

        mockMvc.perform(get("/api/v1/accounts/{id}", accountBId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0.00));

        // Close Account B now that balance is 0.00 -> 204 No Content
        mockMvc.perform(delete("/api/v1/accounts/{id}", accountBId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/accounts/{id}", accountBId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // Soft-delete Customer B now that all their accounts are CLOSED -> 204 No Content
        mockMvc.perform(delete("/api/v1/customers/{id}", customerBId))
                .andExpect(status().isNoContent());

        // Location statistics for "Pleiku" should now reflect only 1 active customer (Customer A)
        Map<String, Long> finalLocations =
                statisticsService.getCustomersByLocation().stream()
                        .collect(
                                Collectors.toMap(
                                        LocationStatisticResponse::location,
                                        LocationStatisticResponse::customerCount));
        assertThat(finalLocations.get("Pleiku")).isEqualTo(baselinePleiku + 1L);
    }

    private static void assertValidLuhnAccountNumber(String accountNumber) {
        assertThat(accountNumber).hasSize(12).startsWith("100");
        int expectedCheckDigit =
                AccountNumberGenerator.calculateLuhnCheckDigit(accountNumber.substring(0, 11));
        int actualCheckDigit = accountNumber.charAt(11) - '0';
        assertThat(actualCheckDigit).isEqualTo(expectedCheckDigit);
    }
}

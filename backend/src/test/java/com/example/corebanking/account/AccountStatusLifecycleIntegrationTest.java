package com.example.corebanking.account;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.auth.domain.AppUser;
import com.example.corebanking.auth.infrastructure.AppUserRepository;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@WithMockUser(username = "admin.auditor", roles = "ADMIN")
class AccountStatusLifecycleIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private long createAccountForCustomer(long customerId) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/accounts")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                String.format(
                                                        """
                                                        {
                                                          "customerId": %d,
                                                          "transactionLimit": 50000000.00,
                                                          "dailyLimit": 200000000.00
                                                        }
                                                        """,
                                                        customerId)))
                        .andExpect(status().isCreated())
                        .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asLong();
    }

    @Test
    @DisplayName(
            "Full account status lifecycle (ACTIVE -> LOCKED -> ACTIVE -> CLOSED), transaction"
                    + " and reversal rejection on LOCKED/CLOSED (R3-12), and status-history"
                    + " ownership (L2-05, FR-3, FR-14)")
    void accountStatusLifecycle_shouldEnforceFsmAndBlockMoneyMovementAndRecordAuditHistory()
            throws Exception {
        // 1. Setup 2 customers and login accounts for ownership verification
        Customer customerA =
                customerRepository.save(
                        new Customer(
                                "CUS993401",
                                "Nguyễn Trạng Thái A",
                                "000999340001",
                                "status.a@example.com",
                                "0900340001",
                                "Số 1 Lý Thường Kiệt",
                                "Hà Nội",
                                LocalDate.of(1990, 5, 10)));
        Customer customerB =
                customerRepository.save(
                        new Customer(
                                "CUS993402",
                                "Trần Trạng Thái B",
                                "000999340002",
                                "status.b@example.com",
                                "0900340002",
                                "Số 2 Nguyễn Huệ",
                                "Hồ Chí Minh",
                                LocalDate.of(1992, 8, 20)));

        appUserRepository.save(
                AppUser.createCustomer(
                        "cust.status.a",
                        passwordEncoder.encode("Customer@123"),
                        customerA.getId()));
        appUserRepository.save(
                AppUser.createCustomer(
                        "cust.status.b",
                        passwordEncoder.encode("Customer@123"),
                        customerB.getId()));

        long accA = createAccountForCustomer(customerA.getId());
        long accB = createAccountForCustomer(customerB.getId());

        // 2. Deposit 10,000,000 VND + 5,000,000 VND into accA and transfer 3,000,000 VND to accB
        // while ACTIVE (so accA balance = 11,998,000.00 >= depositTxId 10,000,000.00)
        MvcResult depositResult =
                mockMvc.perform(
                                post("/api/v1/transactions/deposits")
                                        .header("Idempotency-Key", "st-dep-" + UUID.randomUUID())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                String.format(
                                                        """
                                                        {
                                                          "accountId": %d,
                                                          "amount": 10000000.00,
                                                          "location": "Hà Nội",
                                                          "description": "Nạp tiền ban đầu"
                                                        }
                                                        """,
                                                        accA)))
                        .andExpect(status().isCreated())
                        .andReturn();
        long depositTxId =
                objectMapper
                        .readTree(depositResult.getResponse().getContentAsString())
                        .get("id")
                        .asLong();

        MvcResult deposit2Result =
                mockMvc.perform(
                                post("/api/v1/transactions/deposits")
                                        .header("Idempotency-Key", "st-dep2-" + UUID.randomUUID())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                String.format(
                                                        """
                                                        {
                                                          "accountId": %d,
                                                          "amount": 5000000.00,
                                                          "location": "Hà Nội",
                                                          "description": "Nạp tiền đợt 2"
                                                        }
                                                        """,
                                                        accA)))
                        .andExpect(status().isCreated())
                        .andReturn();
        long deposit2TxId =
                objectMapper
                        .readTree(deposit2Result.getResponse().getContentAsString())
                        .get("id")
                        .asLong();

        MvcResult transferResult =
                mockMvc.perform(
                                post("/api/v1/transactions/transfers")
                                        .header("Idempotency-Key", "st-trf-" + UUID.randomUUID())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                String.format(
                                                        """
                                                        {
                                                          "sourceAccountId": %d,
                                                          "targetAccountId": %d,
                                                          "amount": 3000000.00,
                                                          "location": "Hà Nội",
                                                          "description": "Chuyển khoản sang B"
                                                        }
                                                        """,
                                                        accA, accB)))
                        .andExpect(status().isCreated())
                        .andReturn();
        long transferTxId =
                objectMapper
                        .readTree(transferResult.getResponse().getContentAsString())
                        .get("id")
                        .asLong();

        // 3. Transition accA: ACTIVE -> LOCKED
        mockMvc.perform(
                        post("/api/v1/accounts/{id}/status", accA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "targetStatus": "LOCKED",
                                          "reason": "Phong tỏa tạm thời để rà soát giao dịch"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));

        // Self-transition LOCKED -> LOCKED must fail with 409 INVALID_STATUS_TRANSITION
        mockMvc.perform(
                        post("/api/v1/accounts/{id}/status", accA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "targetStatus": "LOCKED",
                                          "reason": "Khóa lặp lại"
                                        }
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        // 4. Verify all money movements and transaction reversals involving LOCKED accA fail with
        // 422 ACCOUNT_NOT_ACTIVE (AC 2, R3-12)
        // 4a. Deposit to LOCKED accA -> 422 ACCOUNT_NOT_ACTIVE
        mockMvc.perform(
                        post("/api/v1/transactions/deposits")
                                .header("Idempotency-Key", "st-dep-locked-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {
                                                  "accountId": %d,
                                                  "amount": 500000.00,
                                                  "location": "Hà Nội",
                                                  "description": "Nạp vào tài khoản bị khóa"
                                                }
                                                """,
                                                accA)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        // 4b. Withdrawal from LOCKED accA (even exceeding transactionLimit) -> 422
        // ACCOUNT_NOT_ACTIVE
        mockMvc.perform(
                        post("/api/v1/transactions/withdrawals")
                                .header("Idempotency-Key", "st-wdr-locked-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {
                                                  "accountId": %d,
                                                  "amount": 90000000.00,
                                                  "location": "Hà Nội",
                                                  "description": "Rút từ tài khoản bị khóa"
                                                }
                                                """,
                                                accA)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        // 4c. Transfer from LOCKED accA or to LOCKED accA -> 422 ACCOUNT_NOT_ACTIVE
        mockMvc.perform(
                        post("/api/v1/transactions/transfers")
                                .header("Idempotency-Key", "st-trf-src-lck-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {
                                                  "sourceAccountId": %d,
                                                  "targetAccountId": %d,
                                                  "amount": 100000.00,
                                                  "location": "Hà Nội",
                                                  "description": "Chuyển đi từ TK bị khóa"
                                                }
                                                """,
                                                accA, accB)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        mockMvc.perform(
                        post("/api/v1/transactions/transfers")
                                .header("Idempotency-Key", "st-trf-tgt-lck-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {
                                                  "sourceAccountId": %d,
                                                  "targetAccountId": %d,
                                                  "amount": 100000.00,
                                                  "location": "Hà Nội",
                                                  "description": "Chuyển đến TK bị khóa"
                                                }
                                                """,
                                                accB, accA)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        // 4d. Payment from LOCKED accA -> 422 ACCOUNT_NOT_ACTIVE
        mockMvc.perform(
                        post("/api/v1/transactions/payments")
                                .header("Idempotency-Key", "st-pay-locked-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {
                                                  "sourceAccountId": %d,
                                                  "amount": 100000.00,
                                                  "location": "Hà Nội",
                                                  "description": "Thanh toán từ TK bị khóa",
                                                  "recipientInfo": "EVN"
                                                }
                                                """,
                                                accA)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        // 4e. Reversal of DEPOSIT or TRANSFER involving LOCKED accA -> 422 ACCOUNT_NOT_ACTIVE
        // (R3-12)
        mockMvc.perform(delete("/api/v1/transactions/{id}", depositTxId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        mockMvc.perform(delete("/api/v1/transactions/{id}", transferTxId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        // 5. Transition LOCKED -> CLOSED while balance > 0 must fail with 409 ACCOUNT_HAS_BALANCE
        mockMvc.perform(
                        post("/api/v1/accounts/{id}/status", accA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "targetStatus": "CLOSED",
                                          "reason": "Đóng tài khoản khi còn tiền"
                                        }
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_BALANCE"));

        // 6. Unlock accA: LOCKED -> ACTIVE, then reverse transfer & drain balance to 0
        mockMvc.perform(
                        post("/api/v1/accounts/{id}/status", accA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "targetStatus": "ACTIVE",
                                          "reason": "Đã xác minh hợp lệ, mở khóa tài khoản"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Now accB has 3,000,000.00. Reverse transferTxId -> accB returns to 0.00, accA returns to
        // 10,000,000.00
        mockMvc.perform(delete("/api/v1/transactions/{id}", transferTxId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REVERSAL"));

        // Close accB via POST /status (ACTIVE -> CLOSED) now that accB balance is 0.00
        mockMvc.perform(
                        post("/api/v1/accounts/{id}/status", accB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "targetStatus": "CLOSED",
                                          "reason": "Khách hàng yêu cầu tất toán tài khoản B"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        // Any transition from terminal CLOSED state (CLOSED -> ACTIVE, CLOSED -> LOCKED, DELETE)
        // must fail with 409 INVALID_STATUS_TRANSITION
        mockMvc.perform(
                        post("/api/v1/accounts/{id}/status", accB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "targetStatus": "ACTIVE",
                                          "reason": "Thử mở lại tài khoản đã đóng"
                                        }
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        mockMvc.perform(
                        post("/api/v1/accounts/{id}/status", accB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "targetStatus": "LOCKED",
                                          "reason": "Thử khóa tài khoản đã đóng"
                                        }
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        mockMvc.perform(delete("/api/v1/accounts/{id}", accB))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        // Reverse depositTxId and deposit2TxId on accA so accA balance becomes 0.00, then lock accA
        // and close from LOCKED -> CLOSED
        mockMvc.perform(delete("/api/v1/transactions/{id}", depositTxId))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/transactions/{id}", deposit2TxId))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/accounts/{id}/status", accA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "targetStatus": "LOCKED",
                                          "reason": "Khóa trước khi đóng vĩnh viễn"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));

        mockMvc.perform(delete("/api/v1/accounts/{id}", accA)).andExpect(status().isNoContent());

        // 7. Verify GET /api/v1/accounts/{id}/status-history returns all 5 transitions for accA in
        // reverse chronological order (changedAt DESC, id DESC):
        // [0] LOCKED -> CLOSED ("Account closed")
        // [1] ACTIVE -> LOCKED ("Khóa trước khi đóng vĩnh viễn")
        // [2] LOCKED -> ACTIVE ("Đã xác minh hợp lệ, mở khóa tài khoản")
        // [3] ACTIVE -> LOCKED ("Phong tỏa tạm thời để rà soát giao dịch")
        // [4] null   -> ACTIVE ("Account opened")
        mockMvc.perform(get("/api/v1/accounts/{id}/status-history", accA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].fromStatus").value("LOCKED"))
                .andExpect(jsonPath("$[0].toStatus").value("CLOSED"))
                .andExpect(jsonPath("$[0].reason").value("Account closed"))
                .andExpect(jsonPath("$[0].changedBy").value("admin.auditor"))
                .andExpect(jsonPath("$[1].fromStatus").value("ACTIVE"))
                .andExpect(jsonPath("$[1].toStatus").value("LOCKED"))
                .andExpect(jsonPath("$[2].fromStatus").value("LOCKED"))
                .andExpect(jsonPath("$[2].toStatus").value("ACTIVE"))
                .andExpect(jsonPath("$[3].fromStatus").value("ACTIVE"))
                .andExpect(jsonPath("$[3].toStatus").value("LOCKED"))
                .andExpect(jsonPath("$[4].fromStatus").value(nullValue()))
                .andExpect(jsonPath("$[4].toStatus").value("ACTIVE"))
                .andExpect(jsonPath("$[4].reason").value("Account opened"));

        // 8. Verify ownership on GET /api/v1/accounts/{id}/status-history using real JWT login:
        // Customer A can view accA history (200 OK) but gets 403 ACCESS_DENIED on accB
        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "username": "cust.status.a",
                                                  "password": "Customer@123"
                                                }
                                                """))
                        .andExpect(status().isOk())
                        .andReturn();
        String tokenCustomerA =
                objectMapper
                        .readTree(loginResult.getResponse().getContentAsString())
                        .get("accessToken")
                        .asText();

        mockMvc.perform(
                        get("/api/v1/accounts/{id}/status-history", accA)
                                .header("Authorization", "Bearer " + tokenCustomerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));

        mockMvc.perform(
                        get("/api/v1/accounts/{id}/status-history", accB)
                                .header("Authorization", "Bearer " + tokenCustomerA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Customer A cannot call POST /api/v1/accounts/{id}/status (403 ACCESS_DENIED)
        mockMvc.perform(
                        post("/api/v1/accounts/{id}/status", accA)
                                .header("Authorization", "Bearer " + tokenCustomerA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "targetStatus": "ACTIVE",
                                          "reason": "Customer tự mở khóa"
                                        }
                                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}

package com.example.corebanking.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.auth.domain.AppUser;
import com.example.corebanking.auth.infrastructure.JwtTokenService;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.fraud.application.AlertService;
import com.example.corebanking.fraud.domain.AlertRuleCode;
import com.example.corebanking.fraud.domain.AlertSeverity;
import com.example.corebanking.fraud.domain.AnomalyFinding;
import com.example.corebanking.recurring.application.RecurringExecutor;
import com.example.corebanking.support.IntegrationTestBase;
import com.example.corebanking.transaction.application.TransactionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Full-stack integration test for Story 4.8 verifying Actuator endpoint exposure and RBAC security,
 * custom Micrometer business metrics ({@code banking.transactions}, {@code banking.alerts.raised},
 * {@code banking.recurring.executed}), Java 21 Virtual Threads on HTTP and {@code @Async}
 * executors, HikariCP profile pool sizing, and absence of {@code synchronized} blocks in production
 * code (L3-09, L3-10, FR-24, NFR-4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(ActuatorMetricsAndVirtualThreadsIT.VirtualThreadProbeFilterConfig.class)
class ActuatorMetricsAndVirtualThreadsIT extends IntegrationTestBase {

    private static final AtomicBoolean LAST_HTTP_REQUEST_WAS_VIRTUAL = new AtomicBoolean(false);
    private static final Pattern SYNCHRONIZED_CODE_PATTERN =
            Pattern.compile("\\bsynchronized\\s*(\\(|\\w)");

    @LocalServerPort private int localServerPort;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private AlertService alertService;
    @Autowired private RecurringExecutor recurringExecutor;
    @Autowired private Environment environment;

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private Executor applicationTaskExecutor;

    @Autowired
    @Qualifier("fraudAsyncExecutor")
    private Executor fraudAsyncExecutor;

    @TestConfiguration
    static class VirtualThreadProbeFilterConfig {
        @Bean
        OncePerRequestFilter virtualThreadProbeFilter() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(
                        HttpServletRequest request,
                        HttpServletResponse response,
                        FilterChain filterChain)
                        throws ServletException, IOException {
                    if ("/actuator/info".equals(request.getRequestURI())) {
                        LAST_HTTP_REQUEST_WAS_VIRTUAL.set(Thread.currentThread().isVirtual());
                    }
                    filterChain.doFilter(request, response);
                }
            };
        }
    }

    @Test
    @DisplayName(
            "GET /actuator/health hides db/redis details for anonymous and CUSTOMER, shows details"
                    + " for ADMIN; /actuator/metrics requires ADMIN; env and heapdump are not"
                    + " exposed (AC 1, L3-09, FR-24)")
    void actuatorEndpoints_shouldEnforceLeastPrivilegeAndHideSensitiveEndpoints() throws Exception {
        String adminToken =
                jwtTokenService.generateAccessToken(AppUser.createAdmin("admin", "hash"));
        String customerToken =
                jwtTokenService.generateAccessToken(
                        AppUser.createCustomer("cust.an", "hash", 1001L));

        // 1. Anonymous GET /actuator/health -> 200 UP without components details
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());

        // 2. CUSTOMER GET /actuator/health -> 200 UP without components details
        mockMvc.perform(
                        get("/actuator/health")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());

        // 3. ADMIN GET /actuator/health -> 200 UP with db details visible
        mockMvc.perform(
                        get("/actuator/health")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));

        // 4. Public GET /actuator/info -> 200 OK
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());

        // 5. GET /actuator/metrics RBAC: 401 anonymous, 403 CUSTOMER, 200 ADMIN
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(
                        get("/actuator/metrics")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(
                        get("/actuator/metrics")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());

        // 6. Sensitive endpoints env and heapdump are never exposed (404 even for ADMIN)
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(
                        get("/actuator/env")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        get("/actuator/env")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        get("/actuator/heapdump")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName(
            "Custom Micrometer metrics (banking.transactions, banking.alerts.raised,"
                    + " banking.recurring.executed) record tags for type, outcome, and rule (AC 2,"
                    + " L3-09, FR-24)")
    void customBusinessMetrics_shouldExposeTimersAndCountersWithTags() throws Exception {
        Customer customer =
                customerRepository.save(
                        new Customer(
                                "CUS9990481",
                                "Nguyễn Quan Sát",
                                "000999888481",
                                "obs48@example.com",
                                "0900000481",
                                "Số 48 Quận 1",
                                "Hà Nội",
                                LocalDate.of(1992, 4, 8)));
        Account account =
                accountRepository.save(
                        new Account(
                                "100999999481",
                                customer.getId(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00"),
                                LocalDate.of(2025, 1, 1)));
        Long accountId = account.getId();
        String adminToken =
                jwtTokenService.generateAccessToken(AppUser.createAdmin("admin", "hash"));

        try {
            // 1. Execute successful deposit -> records banking.transactions(type=DEPOSIT,
            // outcome=SUCCESS)
            mockMvc.perform(
                            post("/api/v1/transactions/deposits")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                                    .header("Idempotency-Key", "idem-obs-dep-001")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"accountId\":"
                                                    + accountId
                                                    + ",\"amount\":250000.00,\"location\":\"Hà"
                                                    + " Nội\",\"description\":\"Observability"
                                                    + " deposit test\"}"))
                    .andExpect(status().isCreated());

            // 2. Execute failed transfer (same account) -> records
            // banking.transactions(type=TRANSFER, outcome=FAILURE)
            mockMvc.perform(
                            post("/api/v1/transactions/transfers")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                                    .header("Idempotency-Key", "idem-obs-trf-fail-001")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"sourceAccountId\":"
                                                    + accountId
                                                    + ",\"targetAccountId\":"
                                                    + accountId
                                                    + ",\"amount\":100000.00,\"location\":\"Hà"
                                                    + " Nội\",\"description\":\"Same account"
                                                    + " transfer\"}"))
                    .andExpect(status().isBadRequest());

            // 3. Raise fraud alert -> records banking.alerts.raised(rule=AMOUNT_THRESHOLD)
            Long latestTxId =
                    jdbcClient
                            .sql(
                                    "SELECT id FROM core.transaction WHERE target_account_id ="
                                            + " :accountId ORDER BY id DESC LIMIT 1")
                            .param("accountId", accountId)
                            .query(Long.class)
                            .single();
            alertService.raise(
                    latestTxId,
                    accountId,
                    new AnomalyFinding(
                            AlertRuleCode.AMOUNT_THRESHOLD,
                            AlertSeverity.HIGH,
                            "Observability test high value alert"));

            // 4. Create and execute a due recurring instruction -> records
            // banking.recurring.executed(outcome=SUCCESS)
            jdbcClient
                    .sql(
                            """
INSERT INTO core.recurring_transaction (
    type, source_account_id, target_account_id, amount, frequency,
    start_date, next_run_at, status, failure_count, location, description,
    created_by, created_at, updated_at, version
) VALUES (
    'DEPOSIT', NULL, :targetId, 100000.00, 'DAILY',
    CURRENT_DATE, now() - interval '1 minute', 'ACTIVE', 0, 'Hà Nội',
    'Observability recurring deposit', 'admin', now(), now(), 0
)
""")
                    .param("targetId", accountId)
                    .update();
            assertThat(recurringExecutor.executeNextDue()).isTrue();

            // 5. Verify /actuator/metrics/banking.transactions
            mockMvc.perform(
                            get("/actuator/metrics/" + TransactionService.TRANSACTIONS_METRIC)
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(TransactionService.TRANSACTIONS_METRIC))
                    .andExpect(jsonPath("$.availableTags[*].tag", hasItems("type", "outcome")));

            mockMvc.perform(
                            get("/actuator/metrics/" + TransactionService.TRANSACTIONS_METRIC)
                                    .param("tag", "type:DEPOSIT")
                                    .param("tag", "outcome:SUCCESS")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath(
                                    "$.measurements[?(@.statistic=='COUNT')].value",
                                    hasItem(greaterThanOrEqualTo(1.0))));

            mockMvc.perform(
                            get("/actuator/metrics/" + TransactionService.TRANSACTIONS_METRIC)
                                    .param("tag", "type:TRANSFER")
                                    .param("tag", "outcome:FAILURE")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath(
                                    "$.measurements[?(@.statistic=='COUNT')].value",
                                    hasItem(greaterThanOrEqualTo(1.0))));

            // 6. Verify /actuator/metrics/banking.alerts.raised
            mockMvc.perform(
                            get("/actuator/metrics/" + AlertService.ALERTS_RAISED_METRIC)
                                    .param("tag", "rule:AMOUNT_THRESHOLD")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(AlertService.ALERTS_RAISED_METRIC))
                    .andExpect(
                            jsonPath(
                                    "$.measurements[?(@.statistic=='COUNT')].value",
                                    hasItem(greaterThanOrEqualTo(1.0))));

            // 7. Verify /actuator/metrics/banking.recurring.executed
            mockMvc.perform(
                            get("/actuator/metrics/" + RecurringExecutor.RECURRING_EXECUTED_METRIC)
                                    .param("tag", "outcome:SUCCESS")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath("$.name").value(RecurringExecutor.RECURRING_EXECUTED_METRIC))
                    .andExpect(
                            jsonPath(
                                    "$.measurements[?(@.statistic=='COUNT')].value",
                                    hasItem(greaterThanOrEqualTo(1.0))));
        } finally {
            jdbcClient
                    .sql("DELETE FROM core.alert WHERE account_id = :accountId")
                    .param("accountId", accountId)
                    .update();
            jdbcClient
                    .sql(
                            "DELETE FROM core.transaction WHERE target_account_id = :accountId OR"
                                    + " source_account_id = :accountId")
                    .param("accountId", accountId)
                    .update();
            jdbcClient
                    .sql(
                            "DELETE FROM core.recurring_transaction WHERE target_account_id ="
                                    + " :accountId")
                    .param("accountId", accountId)
                    .update();
            jdbcClient
                    .sql(
                            "DELETE FROM core.idempotency_key WHERE idem_key IN"
                                    + " ('idem-obs-dep-001', 'idem-obs-trf-fail-001')")
                    .update();
            jdbcClient
                    .sql("DELETE FROM core.account WHERE id = :accountId")
                    .param("accountId", accountId)
                    .update();
            jdbcClient
                    .sql("DELETE FROM core.customer WHERE id = :customerId")
                    .param("customerId", customer.getId())
                    .update();
        }
    }

    @Test
    @DisplayName(
            "Java 21 Virtual Threads handle HTTP requests and @Async tasks; HikariCP pool matches"
                    + " profile (10 local / 5 prod); zero synchronized blocks in production code"
                    + " (AC 3, L3-10, NFR-4)")
    void virtualThreadsAndHikariPoolAndNoSynchronizedPinning_shouldSatisfyConcurrencyInvariants()
            throws Exception {
        // 1. Verify spring.threads.virtual.enabled=true
        assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean.class))
                .isTrue();

        // 2. Send a real HTTP request to embedded Tomcat and verify the request thread is virtual
        LAST_HTTP_REQUEST_WAS_VIRTUAL.set(false);
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + localServerPort + "/actuator/info"))
                        .GET()
                        .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(LAST_HTTP_REQUEST_WAS_VIRTUAL.get())
                .as("Embedded Tomcat HTTP request handler thread must be a Java 21 Virtual Thread")
                .isTrue();

        // 3. Verify @Async task executors (applicationTaskExecutor & fraudAsyncExecutor) run on
        // Virtual Threads
        Boolean appAsyncIsVirtual =
                CompletableFuture.supplyAsync(
                                () -> Thread.currentThread().isVirtual(), applicationTaskExecutor)
                        .get(5, TimeUnit.SECONDS);
        Boolean fraudAsyncIsVirtual =
                CompletableFuture.supplyAsync(
                                () -> Thread.currentThread().isVirtual(), fraudAsyncExecutor)
                        .get(5, TimeUnit.SECONDS);
        assertThat(appAsyncIsVirtual).isTrue();
        assertThat(fraudAsyncIsVirtual).isTrue();

        // 4. Verify HikariCP maximum-pool-size per profile (10 local / 5 prod)
        String localYaml =
                new ClassPathResource("application-local.yml")
                        .getContentAsString(StandardCharsets.UTF_8);
        String prodYaml =
                new ClassPathResource("application-prod.yml")
                        .getContentAsString(StandardCharsets.UTF_8);
        assertThat(localYaml).contains("maximum-pool-size: 10");
        assertThat(prodYaml)
                .contains("maximum-pool-size: ${HIKARI_MAX_POOL_SIZE:5}")
                .contains("connection-timeout: 5000");

        // 5. Verify zero synchronized blocks/methods in backend/src/main/java to prevent carrier
        // pinning
        Path mainJavaDir = resolveMainJavaDir();
        try (Stream<Path> paths = Files.walk(mainJavaDir)) {
            List<String> filesWithSynchronized =
                    paths.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".java"))
                            .filter(ActuatorMetricsAndVirtualThreadsIT::containsSynchronizedKeyword)
                            .map(p -> mainJavaDir.relativize(p).toString())
                            .toList();
            assertThat(filesWithSynchronized)
                    .as(
                            "Production Java code must not use 'synchronized' (use ReentrantLock"
                                    + " to avoid Java 21 virtual thread pinning)")
                    .isEmpty();
        }
    }

    private static Path resolveMainJavaDir() {
        Path cwd = Path.of("").toAbsolutePath();
        Path direct = cwd.resolve("src/main/java");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        return cwd.resolve("backend/src/main/java");
    }

    private static boolean containsSynchronizedKeyword(Path javaFile) {
        try {
            String source = Files.readString(javaFile, StandardCharsets.UTF_8);
            String withoutBlockComments = source.replaceAll("(?s)/\\*.*?\\*/", "");
            String withoutLineComments = withoutBlockComments.replaceAll("(?m)//.*$", "");
            return SYNCHRONIZED_CODE_PATTERN.matcher(withoutLineComments).find();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read Java file: " + javaFile, ex);
        }
    }
}

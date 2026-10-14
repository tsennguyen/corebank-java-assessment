package com.example.corebanking.customer.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Generator for unique formatted customer codes (e.g. CUS0000001) backed by PostgreSQL sequence.
 */
@Component
public class CustomerCodeGenerator {

    private static final String SEQUENCE_SQL = "SELECT nextval('core.customer_code_seq')";
    private static final String CODE_FORMAT = "CUS%07d";

    private final JdbcTemplate jdbcTemplate;

    public CustomerCodeGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Obtains the next sequence value from core.customer_code_seq and returns formatted customer
     * code.
     *
     * @return formatted customer code (e.g. CUS0000001)
     */
    public String nextCustomerCode() {
        Long nextVal = jdbcTemplate.queryForObject(SEQUENCE_SQL, Long.class);
        if (nextVal == null) {
            throw new IllegalStateException("Failed to obtain nextval from core.customer_code_seq");
        }
        return String.format(CODE_FORMAT, nextVal);
    }
}

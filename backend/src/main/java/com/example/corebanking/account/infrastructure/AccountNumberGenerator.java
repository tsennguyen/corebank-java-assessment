package com.example.corebanking.account.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Generates unique 12-digit bank account numbers using: Prefix "100" (3 digits) + sequence (8
 * digits) + Luhn check digit (1 digit).
 */
@Component
public class AccountNumberGenerator {

    private static final String SEQUENCE_SQL = "SELECT nextval('core.account_number_seq')";
    private static final String PREFIX = "100";

    private final JdbcTemplate jdbcTemplate;

    public AccountNumberGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Obtains the next sequence value and returns a valid 12-digit account number.
     *
     * @return 12-digit account number satisfying Luhn check digit
     */
    public String nextAccountNumber() {
        Long nextVal = jdbcTemplate.queryForObject(SEQUENCE_SQL, Long.class);
        if (nextVal == null) {
            throw new IllegalStateException(
                    "Failed to obtain nextval from core.account_number_seq");
        }
        String payload = PREFIX + String.format("%08d", nextVal); // 11 digits
        int checkDigit = calculateLuhnCheckDigit(payload);
        return payload + checkDigit;
    }

    /**
     * Calculates the Luhn mod-10 check digit for an 11-digit payload.
     *
     * @param payload 11-digit numeric string
     * @return single check digit (0-9)
     */
    public static int calculateLuhnCheckDigit(String payload) {
        int sum = 0;
        boolean doubleDigit = true; // position 1 from right (the last payload char) is doubled
        for (int i = payload.length() - 1; i >= 0; i--) {
            int digit = payload.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        int mod = sum % 10;
        return (mod == 0) ? 0 : 10 - mod;
    }
}

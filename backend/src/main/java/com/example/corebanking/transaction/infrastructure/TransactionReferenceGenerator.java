package com.example.corebanking.transaction.infrastructure;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Generator producing unique banking transaction reference numbers (e.g.
 * TXN-20261012-ABC123DEF456).
 */
@Component
public class TransactionReferenceGenerator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final Clock clock;

    public TransactionReferenceGenerator(Clock clock) {
        this.clock = clock;
    }

    /** Generates a unique transaction reference string with date prefix and random entropy. */
    public String generate() {
        String datePart = LocalDate.now(clock).format(DATE_FORMATTER);
        String randomPart =
                UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .substring(0, 12)
                        .toUpperCase(Locale.ROOT);
        return "TXN-" + datePart + "-" + randomPart;
    }
}

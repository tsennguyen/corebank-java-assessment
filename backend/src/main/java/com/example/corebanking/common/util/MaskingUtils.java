package com.example.corebanking.common.util;

/**
 * Utility class for masking sensitive personal and banking data in logs, audit records, and error
 * details.
 */
public final class MaskingUtils {

    private static final String MASK_CHAR = "*";

    private MaskingUtils() {
        // Utility class
    }

    /**
     * Masks citizen identity card (CCCD) or national identity numbers, leaving only the last 3
     * digits unmasked. Example: "000123456789" -> "*********789".
     *
     * @param nationalId the raw national ID string
     * @return masked national ID or input if null/blank
     */
    public static String nationalId(String nationalId) {
        if (nationalId == null || nationalId.isBlank()) {
            return nationalId;
        }
        int length = nationalId.length();
        if (length <= 3) {
            return MASK_CHAR.repeat(length);
        }
        return MASK_CHAR.repeat(length - 3) + nationalId.substring(length - 3);
    }

    /**
     * Masks bank account numbers for logging and auditing, keeping the first 3 and last 4 digits
     * visible while masking middle digits. Example: "1001234561234" -> "100******1234".
     *
     * @param accountNumber the raw account number string
     * @return masked account number or input if null/blank
     */
    public static String accountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return accountNumber;
        }
        int length = accountNumber.length();
        if (length <= 4) {
            return MASK_CHAR.repeat(length);
        }
        if (length <= 7) {
            return MASK_CHAR.repeat(length - 4) + accountNumber.substring(length - 4);
        }
        return accountNumber.substring(0, 3)
                + MASK_CHAR.repeat(length - 7)
                + accountNumber.substring(length - 4);
    }
}

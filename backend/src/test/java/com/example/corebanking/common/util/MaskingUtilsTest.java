package com.example.corebanking.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MaskingUtilsTest {

    @Nested
    @DisplayName("nationalId masking tests")
    class NationalIdTests {

        @Test
        @DisplayName("should mask 12-digit citizen ID keeping only last 3 digits")
        void nationalId_twelveDigits_masksAllExceptLastThree() {
            String masked = MaskingUtils.nationalId("000123456789");
            assertThat(masked).isEqualTo("*********789");
        }

        @Test
        @DisplayName("should mask 9-digit identity card keeping only last 3 digits")
        void nationalId_nineDigits_masksAllExceptLastThree() {
            String masked = MaskingUtils.nationalId("123456789");
            assertThat(masked).isEqualTo("******789");
        }

        @Test
        @DisplayName("should handle null or blank national ID")
        void nationalId_nullOrBlank_returnsInput() {
            assertThat(MaskingUtils.nationalId(null)).isNull();
            assertThat(MaskingUtils.nationalId("")).isEqualTo("");
            assertThat(MaskingUtils.nationalId("   ")).isEqualTo("   ");
        }

        @Test
        @DisplayName("should mask short national ID with full asterisks")
        void nationalId_shortLength_masksFully() {
            assertThat(MaskingUtils.nationalId("123")).isEqualTo("***");
            assertThat(MaskingUtils.nationalId("12")).isEqualTo("**");
            assertThat(MaskingUtils.nationalId("1")).isEqualTo("*");
        }
    }

    @Nested
    @DisplayName("accountNumber masking tests")
    class AccountNumberTests {

        @Test
        @DisplayName("should mask account number keeping first 3 and last 4 digits")
        void accountNumber_standardLength_masksMiddleDigits() {
            String masked = MaskingUtils.accountNumber("1001234561234");
            assertThat(masked).isEqualTo("100******1234");
        }

        @Test
        @DisplayName("should mask 10-digit account number with 3 middle asterisks")
        void accountNumber_tenDigits_masksThreeMiddleDigits() {
            String masked = MaskingUtils.accountNumber("1001231234");
            assertThat(masked).isEqualTo("100***1234");
        }

        @Test
        @DisplayName("should handle null or blank account number")
        void accountNumber_nullOrBlank_returnsInput() {
            assertThat(MaskingUtils.accountNumber(null)).isNull();
            assertThat(MaskingUtils.accountNumber("")).isEqualTo("");
            assertThat(MaskingUtils.accountNumber("   ")).isEqualTo("   ");
        }

        @Test
        @DisplayName("should handle short account numbers")
        void accountNumber_shortLength_masksAppropriately() {
            assertThat(MaskingUtils.accountNumber("1234567")).isEqualTo("***4567");
            assertThat(MaskingUtils.accountNumber("1234")).isEqualTo("****");
            assertThat(MaskingUtils.accountNumber("12")).isEqualTo("**");
        }
    }
}

package com.example.corebanking.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AccountNumberGeneratorTest {

    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("nextAccountNumber generates valid 12-digit Luhn account number")
    void nextAccountNumber_shouldGenerate12DigitValidLuhn() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);

        AccountNumberGenerator generator = new AccountNumberGenerator(jdbcTemplate);
        String accountNumber = generator.nextAccountNumber();

        assertThat(accountNumber).hasSize(12);
        assertThat(accountNumber).isEqualTo("100000000016");
    }

    @Test
    @DisplayName("calculateLuhnCheckDigit computes accurate mod-10 check digit")
    void calculateLuhnCheckDigit_shouldProduceAccurateDigit() {
        // Test with known payload "10000000001" -> check digit is 6
        String payload = "10000000001";
        int checkDigit = AccountNumberGenerator.calculateLuhnCheckDigit(payload);
        assertThat(checkDigit).isEqualTo(6);

        // Test with known payload "10000000002" -> i=10 is 2*2=4, i=0 is 1*2=2 -> sum=6 ->
        // checkDigit=4
        assertThat(AccountNumberGenerator.calculateLuhnCheckDigit("10000000002")).isEqualTo(4);
    }
}

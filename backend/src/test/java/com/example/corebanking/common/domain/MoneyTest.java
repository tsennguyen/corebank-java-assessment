package com.example.corebanking.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("Money normalizes scale to 2 using HALF_UP")
    void constructor_shouldNormalizeScaleAndRounding() {
        Money m1 = Money.of(new BigDecimal("100.555"));
        assertThat(m1.getAmount()).isEqualByComparingTo("100.56");

        Money m2 = Money.of(100L);
        assertThat(m2.getAmount()).isEqualByComparingTo("100.00");

        Money m3 = Money.of("250.4");
        assertThat(m3.getAmount()).isEqualByComparingTo("250.40");
    }

    @Test
    @DisplayName("Arithmetic operations plus, minus, times work correctly")
    void arithmetic_shouldComputeAccurately() {
        Money a = Money.of("100.50");
        Money b = Money.of("50.25");

        assertThat(a.plus(b)).isEqualTo(Money.of("150.75"));
        assertThat(a.minus(b)).isEqualTo(Money.of("50.25"));
        assertThat(a.times(new BigDecimal("2"))).isEqualTo(Money.of("201.00"));
    }

    @Test
    @DisplayName("Comparisons and predicate methods work correctly")
    void comparisons_shouldEvaluateProperly() {
        Money zero = Money.zero();
        Money positive = Money.of("10.00");
        Money negative = Money.of("-5.00");

        assertThat(zero.isZero()).isTrue();
        assertThat(positive.isPositive()).isTrue();
        assertThat(negative.isNegative()).isTrue();

        assertThat(positive.isGreaterThan(zero)).isTrue();
        assertThat(positive.isGreaterThanOrEqual(Money.of("10.00"))).isTrue();
        assertThat(negative.isLessThan(zero)).isTrue();
        assertThat(negative.isLessThanOrEqual(Money.of("-5.00"))).isTrue();
    }

    @Test
    @DisplayName("Null safety in constructors and operations")
    void nullSafety_shouldThrowNpe() {
        assertThatThrownBy(() -> new Money(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Money.of("10.00").plus(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Money.of("10.00").minus(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Money.of("10.00").times(null))
                .isInstanceOf(NullPointerException.class);
    }
}

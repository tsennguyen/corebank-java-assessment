package com.example.corebanking.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionTest {

    @Test
    @DisplayName("createDeposit initializes a valid deposit transaction record with zero fee")
    void createDeposit_shouldInitializeValidRecord() {
        Transaction tx =
                Transaction.createDeposit(
                        "TXN-20261012-ABC123DEF456",
                        1L,
                        new BigDecimal("1500000.00"),
                        new BigDecimal("1500000.00"),
                        "Hà Nội",
                        "Nạp tiền ban đầu");

        assertThat(tx.getReferenceNo()).isEqualTo("TXN-20261012-ABC123DEF456");
        assertThat(tx.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(tx.getSourceAccountId()).isNull();
        assertThat(tx.getTargetAccountId()).isEqualTo(1L);
        assertThat(tx.getAmount()).isEqualByComparingTo("1500000.00");
        assertThat(tx.getFee()).isEqualByComparingTo("0.00");
        assertThat(tx.getSourceBalanceAfter()).isNull();
        assertThat(tx.getTargetBalanceAfter()).isEqualByComparingTo("1500000.00");
        assertThat(tx.getSystemBalanceDelta()).isEqualByComparingTo("1500000.00");
        assertThat(tx.getLocation()).isEqualTo("Hà Nội");
        assertThat(tx.getDescription()).isEqualTo("Nạp tiền ban đầu");
    }

    @Test
    @DisplayName("createDeposit rejects null required parameters")
    void createDeposit_shouldRejectNullParameters() {
        assertThatThrownBy(
                        () ->
                                Transaction.createDeposit(
                                        null,
                                        1L,
                                        new BigDecimal("1000.00"),
                                        new BigDecimal("1000.00"),
                                        "HN",
                                        "desc"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(
                        () ->
                                Transaction.createDeposit(
                                        "TXN-123",
                                        null,
                                        new BigDecimal("1000.00"),
                                        new BigDecimal("1000.00"),
                                        "HN",
                                        "desc"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(
                        () ->
                                Transaction.createDeposit(
                                        "TXN-123",
                                        1L,
                                        null,
                                        new BigDecimal("1000.00"),
                                        "HN",
                                        "desc"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(
                        () ->
                                Transaction.createDeposit(
                                        "TXN-123",
                                        1L,
                                        new BigDecimal("1000.00"),
                                        null,
                                        "HN",
                                        "desc"))
                .isInstanceOf(NullPointerException.class);
    }
}

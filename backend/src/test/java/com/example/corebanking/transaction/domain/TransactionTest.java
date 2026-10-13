package com.example.corebanking.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TransactionTest {

    @Nested
    @DisplayName("createDeposit Tests")
    class CreateDepositTests {

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

    @Nested
    @DisplayName("createWithdrawal Tests")
    class CreateWithdrawalTests {

        @Test
        @DisplayName(
                "createWithdrawal initializes a valid withdrawal record with negative system"
                        + " balance delta")
        void createWithdrawal_shouldInitializeValidRecord() {
            Transaction tx =
                    Transaction.createWithdrawal(
                            "TXN-20261012-WTH001",
                            1L,
                            new BigDecimal("1000000.00"),
                            new BigDecimal("2000.00"),
                            new BigDecimal("3998000.00"),
                            "ATM Q1",
                            "Rút tiền mặt");

            assertThat(tx.getReferenceNo()).isEqualTo("TXN-20261012-WTH001");
            assertThat(tx.getType()).isEqualTo(TransactionType.WITHDRAWAL);
            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(tx.getSourceAccountId()).isEqualTo(1L);
            assertThat(tx.getTargetAccountId()).isNull();
            assertThat(tx.getAmount()).isEqualByComparingTo("1000000.00");
            assertThat(tx.getFee()).isEqualByComparingTo("2000.00");
            assertThat(tx.getSourceBalanceAfter()).isEqualByComparingTo("3998000.00");
            assertThat(tx.getTargetBalanceAfter()).isNull();
            assertThat(tx.getSystemBalanceDelta()).isEqualByComparingTo("-1002000.00");
            assertThat(tx.getLocation()).isEqualTo("ATM Q1");
            assertThat(tx.getDescription()).isEqualTo("Rút tiền mặt");
        }
    }

    @Nested
    @DisplayName("createTransfer Tests")
    class CreateTransferTests {

        @Test
        @DisplayName(
                "createTransfer initializes valid transfer record with fee as negative system"
                        + " delta")
        void createTransfer_shouldInitializeValidRecord() {
            Transaction tx =
                    Transaction.createTransfer(
                            "TXN-20261012-TRF001",
                            1L,
                            2L,
                            new BigDecimal("5000000.00"),
                            new BigDecimal("2000.00"),
                            new BigDecimal("4998000.00"),
                            new BigDecimal("8000000.00"),
                            "Mobile Banking",
                            "Chuyển tiền mua sắm");

            assertThat(tx.getReferenceNo()).isEqualTo("TXN-20261012-TRF001");
            assertThat(tx.getType()).isEqualTo(TransactionType.TRANSFER);
            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(tx.getSourceAccountId()).isEqualTo(1L);
            assertThat(tx.getTargetAccountId()).isEqualTo(2L);
            assertThat(tx.getAmount()).isEqualByComparingTo("5000000.00");
            assertThat(tx.getFee()).isEqualByComparingTo("2000.00");
            assertThat(tx.getSourceBalanceAfter()).isEqualByComparingTo("4998000.00");
            assertThat(tx.getTargetBalanceAfter()).isEqualByComparingTo("8000000.00");
            assertThat(tx.getSystemBalanceDelta()).isEqualByComparingTo("-2000.00");
            assertThat(tx.getLocation()).isEqualTo("Mobile Banking");
            assertThat(tx.getDescription()).isEqualTo("Chuyển tiền mua sắm");
        }
    }

    @Nested
    @DisplayName("createPayment Tests")
    class CreatePaymentTests {

        @Test
        @DisplayName("createPayment initializes valid payment record with negative system delta")
        void createPayment_shouldInitializeValidRecord() {
            Transaction tx =
                    Transaction.createPayment(
                            "TXN-20261012-PAY001",
                            1L,
                            new BigDecimal("500000.00"),
                            BigDecimal.ZERO,
                            new BigDecimal("1500000.00"),
                            "Web Portal",
                            "Tiền điện | EVN");

            assertThat(tx.getReferenceNo()).isEqualTo("TXN-20261012-PAY001");
            assertThat(tx.getType()).isEqualTo(TransactionType.PAYMENT);
            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(tx.getSourceAccountId()).isEqualTo(1L);
            assertThat(tx.getTargetAccountId()).isNull();
            assertThat(tx.getAmount()).isEqualByComparingTo("500000.00");
            assertThat(tx.getFee()).isEqualByComparingTo("0.00");
            assertThat(tx.getSourceBalanceAfter()).isEqualByComparingTo("1500000.00");
            assertThat(tx.getTargetBalanceAfter()).isNull();
            assertThat(tx.getSystemBalanceDelta()).isEqualByComparingTo("-500000.00");
        }
    }
}

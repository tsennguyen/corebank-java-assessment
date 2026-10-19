package com.example.corebanking.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
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

    @Nested
    @DisplayName("updateMetadata Tests")
    class UpdateMetadataTests {

        @Test
        @DisplayName("updateMetadata updates description and location on COMPLETED transaction")
        void updateMetadata_whenCompleted_shouldUpdate() {
            Transaction tx =
                    Transaction.createDeposit(
                            "TXN-UPD001",
                            1L,
                            new BigDecimal("100000.00"),
                            new BigDecimal("100000.00"),
                            "Old Location",
                            "Old Description");

            tx.updateMetadata("New Description", "New Location");

            assertThat(tx.getDescription()).isEqualTo("New Description");
            assertThat(tx.getLocation()).isEqualTo("New Location");
        }

        @Test
        @DisplayName("updateMetadata ignores null arguments to preserve existing values")
        void updateMetadata_withNullValues_shouldRetainExisting() {
            Transaction tx =
                    Transaction.createDeposit(
                            "TXN-UPD002",
                            1L,
                            new BigDecimal("100000.00"),
                            new BigDecimal("100000.00"),
                            "Existing Loc",
                            "Existing Desc");

            tx.updateMetadata(null, null);

            assertThat(tx.getDescription()).isEqualTo("Existing Desc");
            assertThat(tx.getLocation()).isEqualTo("Existing Loc");
        }

        @Test
        @DisplayName(
                "updateMetadata rejects update on REVERSED transaction with"
                        + " INVALID_STATUS_TRANSITION")
        void updateMetadata_whenReversed_shouldThrowException() {
            Transaction tx =
                    Transaction.createDeposit(
                            "TXN-UPD003",
                            1L,
                            new BigDecimal("100000.00"),
                            new BigDecimal("100000.00"),
                            "Loc",
                            "Desc");
            tx.markReversed();

            assertThatThrownBy(() -> tx.updateMetadata("New", "New"))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        }
    }

    @Nested
    @DisplayName("markReversed Tests")
    class MarkReversedTests {

        @Test
        @DisplayName("markReversed changes status from COMPLETED to REVERSED")
        void markReversed_shouldTransitionStatus() {
            Transaction tx =
                    Transaction.createDeposit(
                            "TXN-REV001",
                            1L,
                            new BigDecimal("100000.00"),
                            new BigDecimal("100000.00"),
                            "Loc",
                            "Desc");

            tx.markReversed();

            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.REVERSED);
        }

        @Test
        @DisplayName("markReversed throws ALREADY_REVERSED when transaction is already REVERSED")
        void markReversed_whenAlreadyReversed_shouldThrowAlreadyReversed() {
            Transaction tx =
                    Transaction.createDeposit(
                            "TXN-REV002",
                            1L,
                            new BigDecimal("100000.00"),
                            new BigDecimal("100000.00"),
                            "Loc",
                            "Desc");
            tx.markReversed();

            assertThatThrownBy(tx::markReversed)
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.ALREADY_REVERSED));
        }

        @Test
        @DisplayName("markReversed throws INVALID_STATUS_TRANSITION when transaction is a REVERSAL")
        void markReversed_whenReversalType_shouldThrowInvalidStatusTransition() {
            Transaction orig =
                    Transaction.createDeposit(
                            "TXN-REV003",
                            1L,
                            new BigDecimal("100000.00"),
                            new BigDecimal("100000.00"),
                            "Loc",
                            "Desc");
            Transaction reversal =
                    Transaction.createDepositReversal(
                            "TXN-REV003-R", orig, BigDecimal.ZERO, null, null);

            assertThatThrownBy(reversal::markReversed)
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        }
    }

    @Nested
    @DisplayName("createReversal Factory Tests")
    class CreateReversalTests {

        @Test
        @DisplayName(
                "createDepositReversal initializes valid reversal record debiting target account")
        void createDepositReversal_shouldInitializeValidRecord() {
            Transaction orig =
                    Transaction.createDeposit(
                            "TXN-DEP001",
                            2L,
                            new BigDecimal("500000.00"),
                            new BigDecimal("1500000.00"),
                            "HN Branch",
                            "Initial Deposit");

            Transaction rev =
                    Transaction.createDepositReversal(
                            "TXN-DEP001-REV", orig, new BigDecimal("1000000.00"), null, null);

            assertThat(rev.getReferenceNo()).isEqualTo("TXN-DEP001-REV");
            assertThat(rev.getType()).isEqualTo(TransactionType.REVERSAL);
            assertThat(rev.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(rev.getSourceAccountId()).isEqualTo(2L);
            assertThat(rev.getTargetAccountId()).isNull();
            assertThat(rev.getAmount()).isEqualByComparingTo("500000.00");
            assertThat(rev.getFee()).isEqualByComparingTo("0.00");
            assertThat(rev.getSourceBalanceAfter()).isEqualByComparingTo("1000000.00");
            assertThat(rev.getTargetBalanceAfter()).isNull();
            assertThat(rev.getSystemBalanceDelta()).isEqualByComparingTo("-500000.00");
            assertThat(rev.getReversalOfId()).isEqualTo(orig.getId());
        }

        @Test
        @DisplayName(
                "createWithdrawalReversal initializes valid reversal record refunding amount and"
                        + " fee")
        void createWithdrawalReversal_shouldInitializeValidRecord() {
            Transaction orig =
                    Transaction.createWithdrawal(
                            "TXN-WTH001",
                            1L,
                            new BigDecimal("1000000.00"),
                            new BigDecimal("2000.00"),
                            new BigDecimal("3998000.00"),
                            "ATM Q1",
                            "Withdraw cash");

            Transaction rev =
                    Transaction.createWithdrawalReversal(
                            "TXN-WTH001-REV", orig, new BigDecimal("5000000.00"), null, null);

            assertThat(rev.getReferenceNo()).isEqualTo("TXN-WTH001-REV");
            assertThat(rev.getType()).isEqualTo(TransactionType.REVERSAL);
            assertThat(rev.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(rev.getSourceAccountId()).isNull();
            assertThat(rev.getTargetAccountId()).isEqualTo(1L);
            assertThat(rev.getAmount()).isEqualByComparingTo("1000000.00");
            assertThat(rev.getFee()).isEqualByComparingTo("2000.00");
            assertThat(rev.getSourceBalanceAfter()).isNull();
            assertThat(rev.getTargetBalanceAfter()).isEqualByComparingTo("5000000.00");
            assertThat(rev.getSystemBalanceDelta()).isEqualByComparingTo("1002000.00");
        }

        @Test
        @DisplayName("createTransferReversal initializes valid reversal swapping source and target")
        void createTransferReversal_shouldInitializeValidRecord() {
            Transaction orig =
                    Transaction.createTransfer(
                            "TXN-TRF001",
                            1L,
                            2L,
                            new BigDecimal("5000000.00"),
                            new BigDecimal("2000.00"),
                            new BigDecimal("4998000.00"),
                            new BigDecimal("8000000.00"),
                            "Online",
                            "Payment");

            Transaction rev =
                    Transaction.createTransferReversal(
                            "TXN-TRF001-REV",
                            orig,
                            new BigDecimal("3000000.00"),
                            new BigDecimal("10000000.00"),
                            null,
                            null);

            assertThat(rev.getReferenceNo()).isEqualTo("TXN-TRF001-REV");
            assertThat(rev.getType()).isEqualTo(TransactionType.REVERSAL);
            assertThat(rev.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(rev.getSourceAccountId()).isEqualTo(2L); // target B debited
            assertThat(rev.getTargetAccountId()).isEqualTo(1L); // source A credited
            assertThat(rev.getAmount()).isEqualByComparingTo("5000000.00");
            assertThat(rev.getFee()).isEqualByComparingTo("2000.00");
            assertThat(rev.getSourceBalanceAfter()).isEqualByComparingTo("3000000.00");
            assertThat(rev.getTargetBalanceAfter()).isEqualByComparingTo("10000000.00");
            assertThat(rev.getSystemBalanceDelta())
                    .isEqualByComparingTo("2000.00"); // fee refunded into system
        }

        @Test
        @DisplayName("createPaymentReversal initializes valid reversal refunding amount and fee")
        void createPaymentReversal_shouldInitializeValidRecord() {
            Transaction orig =
                    Transaction.createPayment(
                            "TXN-PAY001",
                            1L,
                            new BigDecimal("200000.00"),
                            BigDecimal.ZERO,
                            new BigDecimal("800000.00"),
                            "Bill Pay",
                            "Internet bill");

            Transaction rev =
                    Transaction.createPaymentReversal(
                            "TXN-PAY001-REV", orig, new BigDecimal("1000000.00"), null, null);

            assertThat(rev.getReferenceNo()).isEqualTo("TXN-PAY001-REV");
            assertThat(rev.getType()).isEqualTo(TransactionType.REVERSAL);
            assertThat(rev.getSourceAccountId()).isNull();
            assertThat(rev.getTargetAccountId()).isEqualTo(1L);
            assertThat(rev.getAmount()).isEqualByComparingTo("200000.00");
            assertThat(rev.getTargetBalanceAfter()).isEqualByComparingTo("1000000.00");
            assertThat(rev.getSystemBalanceDelta()).isEqualByComparingTo("200000.00");
        }
    }
}

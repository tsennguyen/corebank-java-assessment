package com.example.corebanking.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomerTest {

    @Test
    @DisplayName("Constructor sets all fields correctly and starts active")
    void constructor_shouldInitializeActiveCustomer() {
        LocalDate dob = LocalDate.of(1990, 1, 15);
        Customer customer =
                new Customer(
                        "CUS0000001",
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        dob);

        assertThat(customer.getCustomerCode()).isEqualTo("CUS0000001");
        assertThat(customer.getFullName()).isEqualTo("Nguyen Van An");
        assertThat(customer.getNationalId()).isEqualTo("000123456789");
        assertThat(customer.getEmail()).isEqualTo("an.nguyen@example.com");
        assertThat(customer.getPhone()).isEqualTo("0900000001");
        assertThat(customer.getAddress()).isEqualTo("123 Pho Hue");
        assertThat(customer.getLocation()).isEqualTo("Ha Noi");
        assertThat(customer.getDateOfBirth()).isEqualTo(dob);
        assertThat(customer.getDeletedAt()).isNull();
        assertThat(customer.isDeleted()).isFalse();
        assertThat(customer.getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Constructor throws NullPointerException if required fields are missing")
    void constructor_shouldRejectNullRequiredFields() {
        assertThatThrownBy(
                        () ->
                                new Customer(
                                        null, "Name", "000123", "a@b.com", null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("customerCode");

        assertThatThrownBy(
                        () ->
                                new Customer(
                                        "CUS0001", null, "000123", "a@b.com", null, null, null,
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fullName");

        assertThatThrownBy(
                        () ->
                                new Customer(
                                        "CUS0001", "Name", null, "a@b.com", null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nationalId");
    }

    @Test
    @DisplayName("update() modifies mutable profile fields while preserving identity")
    void update_shouldModifyAllowedFields() {
        Customer customer =
                new Customer(
                        "CUS0000001",
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));

        customer.update(
                "Nguyen Van Binh",
                "binh.nguyen@example.com",
                "0900000002",
                "456 Tran Hung Dao",
                "Da Nang",
                LocalDate.of(1992, 5, 20));

        assertThat(customer.getFullName()).isEqualTo("Nguyen Van Binh");
        assertThat(customer.getEmail()).isEqualTo("binh.nguyen@example.com");
        assertThat(customer.getPhone()).isEqualTo("0900000002");
        assertThat(customer.getAddress()).isEqualTo("456 Tran Hung Dao");
        assertThat(customer.getLocation()).isEqualTo("Da Nang");
        assertThat(customer.getDateOfBirth()).isEqualTo(LocalDate.of(1992, 5, 20));
        // Identity fields must remain untouched
        assertThat(customer.getCustomerCode()).isEqualTo("CUS0000001");
        assertThat(customer.getNationalId()).isEqualTo("000123456789");
    }

    @Test
    @DisplayName("softDelete() marks customer as deleted with timestamp")
    void softDelete_shouldMarkCustomerAsDeleted() {
        Customer customer =
                new Customer(
                        "CUS0000001",
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));

        Instant deletionTime = Instant.parse("2026-10-09T08:00:00Z");
        customer.softDelete(deletionTime);

        assertThat(customer.isDeleted()).isTrue();
        assertThat(customer.getDeletedAt()).isEqualTo(deletionTime);
    }

    @Test
    @DisplayName(
            "update() throws BusinessRuleException (INVALID_STATUS_TRANSITION) when called on"
                    + " soft-deleted customer")
    void update_shouldRejectModificationWhenDeleted() {
        Customer customer =
                new Customer(
                        "CUS0000001",
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));

        customer.softDelete(Instant.now());

        assertThatThrownBy(
                        () ->
                                customer.update(
                                        "New Name",
                                        "new@example.com",
                                        "0900000099",
                                        "Address",
                                        "Location",
                                        null))
                .isInstanceOf(com.example.corebanking.common.domain.BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(
                                                ((com.example.corebanking.common.domain
                                                                        .BusinessRuleException)
                                                                ex)
                                                        .getErrorCode())
                                        .isEqualTo(
                                                com.example.corebanking.common.domain.ErrorCode
                                                        .INVALID_STATUS_TRANSITION))
                .hasMessageContaining("Cannot update a deleted customer");
    }
}

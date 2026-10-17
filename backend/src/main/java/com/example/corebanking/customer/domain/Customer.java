package com.example.corebanking.customer.domain;

import com.example.corebanking.common.domain.BaseEntity;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Customer domain entity representing a registered individual or business client. Enforces rich
 * domain invariants: immutable national ID and customer code, explicit state transitions, customer
 * classification, and optimistic locking.
 */
@Entity
@Table(name = "customer", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer extends BaseEntity {

    public static final Long DEFAULT_CUSTOMER_TYPE_ID = 1L;
    public static final String DEFAULT_CUSTOMER_TYPE_CODE = CustomerType.CODE_INDIVIDUAL;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_code", nullable = false, unique = true, length = 32, updatable = false)
    private String customerCode;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "national_id", nullable = false, unique = true, length = 32, updatable = false)
    private String nationalId;

    @Column(name = "email")
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "address")
    private String address;

    @Column(name = "location", length = 100)
    private String location;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "customer_type_id", nullable = false)
    private Long customerTypeId = DEFAULT_CUSTOMER_TYPE_ID;

    @Column(name = "customer_type_code", nullable = false, length = 32)
    private String customerTypeCode = DEFAULT_CUSTOMER_TYPE_CODE;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Primary domain constructor for creating a new Customer entity (defaults to INDIVIDUAL). */
    public Customer(
            String customerCode,
            String fullName,
            String nationalId,
            String email,
            String phone,
            String address,
            String location,
            LocalDate dateOfBirth) {
        this(
                customerCode,
                fullName,
                nationalId,
                email,
                phone,
                address,
                location,
                dateOfBirth,
                DEFAULT_CUSTOMER_TYPE_ID,
                DEFAULT_CUSTOMER_TYPE_CODE);
    }

    /** Domain constructor for creating a new Customer entity with explicit customer type. */
    public Customer(
            String customerCode,
            String fullName,
            String nationalId,
            String email,
            String phone,
            String address,
            String location,
            LocalDate dateOfBirth,
            Long customerTypeId,
            String customerTypeCode) {
        this.customerCode = Objects.requireNonNull(customerCode, "customerCode must not be null");
        this.fullName = Objects.requireNonNull(fullName, "fullName must not be null");
        this.nationalId = Objects.requireNonNull(nationalId, "nationalId must not be null");
        this.email = email;
        this.phone = phone;
        this.address = address;
        this.location = location != null ? location.trim() : null;
        this.dateOfBirth = dateOfBirth;
        assignCustomerType(customerTypeId, customerTypeCode);
        this.version = 0L;
    }

    /** Assigns the customer type reference (ID and normalized uppercase code). */
    public void assignCustomerType(Long customerTypeId, String customerTypeCode) {
        if (isDeleted()) {
            throw new BusinessRuleException(
                    ErrorCode.INVALID_STATUS_TRANSITION, "Cannot update a deleted customer");
        }
        this.customerTypeId = customerTypeId != null ? customerTypeId : DEFAULT_CUSTOMER_TYPE_ID;
        this.customerTypeCode =
                (customerTypeCode == null || customerTypeCode.isBlank())
                        ? DEFAULT_CUSTOMER_TYPE_CODE
                        : customerTypeCode.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Updates mutable profile details. Note: customerCode and nationalId are strictly immutable.
     */
    public void update(
            String fullName,
            String email,
            String phone,
            String address,
            String location,
            LocalDate dateOfBirth) {
        if (isDeleted()) {
            throw new BusinessRuleException(
                    ErrorCode.INVALID_STATUS_TRANSITION, "Cannot update a deleted customer");
        }
        this.fullName = Objects.requireNonNull(fullName, "fullName must not be null");
        this.email = email;
        this.phone = phone;
        this.address = address;
        this.location = Objects.requireNonNull(location, "location must not be null").trim();
        this.dateOfBirth = dateOfBirth;
    }

    /** Updates mutable profile details and customer classification. */
    public void update(
            String fullName,
            String email,
            String phone,
            String address,
            String location,
            LocalDate dateOfBirth,
            Long customerTypeId,
            String customerTypeCode) {
        update(fullName, email, phone, address, location, dateOfBirth);
        assignCustomerType(customerTypeId, customerTypeCode);
    }

    /** Marks this customer as soft-deleted. */
    public void softDelete(Instant deletionTimestamp) {
        if (isDeleted()) {
            return;
        }
        this.deletedAt =
                Objects.requireNonNull(deletionTimestamp, "deletionTimestamp must not be null");
    }

    /** Checks if customer has been soft-deleted. */
    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}

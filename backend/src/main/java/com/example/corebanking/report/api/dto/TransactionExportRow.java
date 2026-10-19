package com.example.corebanking.report.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lightweight projection row streamed via JDBC cursor when exporting transaction details to Excel
 * or PDF without loading JPA entities into heap memory (L3-07, G-05, NFR-2, ADR-014).
 *
 * @param referenceNo unique transaction reference number
 * @param type transaction type name
 * @param status transaction status name
 * @param sourceAccountId optional source account ID
 * @param targetAccountId optional target account ID
 * @param amount transaction amount (scale 2)
 * @param fee transaction fee (scale 2)
 * @param location transaction location
 * @param description transaction description
 * @param createdAt transaction creation timestamp (UTC)
 */
public record TransactionExportRow(
        String referenceNo,
        String type,
        String status,
        Long sourceAccountId,
        Long targetAccountId,
        BigDecimal amount,
        BigDecimal fee,
        String location,
        String description,
        Instant createdAt) {}

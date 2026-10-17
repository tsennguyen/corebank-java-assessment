package com.example.corebanking.fraud.domain;

import com.example.corebanking.transaction.domain.TransactionType;
import com.example.corebanking.transaction.domain.event.TransactionCompletedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of a completed transaction evaluated by anomaly detection rules (L3-03, FR-22,
 * ADR-012).
 */
public record TransactionSnapshot(
        Long transactionId,
        String referenceNo,
        TransactionType type,
        Long accountId,
        BigDecimal amount,
        Instant occurredAt) {

    private static final Set<TransactionType> OUTGOING_TYPES =
            EnumSet.of(
                    TransactionType.WITHDRAWAL, TransactionType.TRANSFER, TransactionType.PAYMENT);

    public TransactionSnapshot {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }

    /**
     * Returns {@code true} if the transaction is an outgoing transaction ({@code WITHDRAWAL},
     * {@code TRANSFER}, {@code PAYMENT}) with a non-null source {@code accountId}.
     */
    public boolean isOutgoing() {
        return accountId != null && OUTGOING_TYPES.contains(type);
    }

    /** Creates a {@link TransactionSnapshot} from a {@link TransactionCompletedEvent}. */
    public static TransactionSnapshot from(TransactionCompletedEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return new TransactionSnapshot(
                event.transactionId(),
                event.referenceNo(),
                event.type(),
                event.sourceAccountId(),
                event.amount(),
                event.timestamp());
    }
}

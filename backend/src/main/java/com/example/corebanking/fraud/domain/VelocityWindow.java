package com.example.corebanking.fraud.domain;

/**
 * Port interface tracking outgoing transaction frequency per source account within a sliding time
 * window (L3-03, FR-22, ADR-012).
 */
public interface VelocityWindow {

    /**
     * Records the given outgoing transaction for {@code accountId}, evicts entries older than the
     * configured sliding window, and returns the total number of outgoing transactions for {@code
     * accountId} currently inside the window.
     *
     * @param accountId source account identifier
     * @param transactionId unique transaction identifier
     * @return count of outgoing transactions within the active sliding window (including the newly
     *     recorded transaction)
     */
    int recordAndCount(Long accountId, Long transactionId);
}

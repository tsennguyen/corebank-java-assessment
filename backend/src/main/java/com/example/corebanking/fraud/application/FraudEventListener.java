package com.example.corebanking.fraud.application;

import com.example.corebanking.fraud.domain.TransactionSnapshot;
import com.example.corebanking.transaction.domain.event.TransactionCompletedEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Post-commit asynchronous event listener evaluating outgoing transactions against the {@link
 * AnomalyRuleChain} on Java 21 Virtual Threads so fraud checks never add latency to or fail
 * financial transactions (L3-03, L3-04, L3-10, FR-22, NFR-4, ADR-012, ADR-015).
 */
@Component
public class FraudEventListener {

    private static final Logger log = LoggerFactory.getLogger(FraudEventListener.class);

    private final AnomalyRuleChain anomalyRuleChain;
    private final AtomicBoolean lastExecutedOnVirtualThread = new AtomicBoolean(false);

    public FraudEventListener(AnomalyRuleChain anomalyRuleChain) {
        this.anomalyRuleChain = anomalyRuleChain;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionCompleted(TransactionCompletedEvent event) {
        if (event == null) {
            return;
        }
        lastExecutedOnVirtualThread.set(Thread.currentThread().isVirtual());
        try {
            TransactionSnapshot snapshot = TransactionSnapshot.from(event);
            if (!snapshot.isOutgoing()) {
                return;
            }
            anomalyRuleChain.evaluate(snapshot);
        } catch (Exception ex) {
            log.error(
                    "Unexpected error in FraudEventListener [txnId={}]: {}",
                    event.transactionId(),
                    ex.getMessage(),
                    ex);
        }
    }

    /** Returns whether the most recent listener invocation ran on a Java 21 Virtual Thread. */
    public boolean wasLastExecutedOnVirtualThread() {
        return lastExecutedOnVirtualThread.get();
    }
}

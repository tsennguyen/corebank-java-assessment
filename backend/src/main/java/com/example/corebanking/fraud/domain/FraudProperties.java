package com.example.corebanking.fraud.domain;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalized configuration properties for real-time fraud and anomaly detection rules (L3-03,
 * FR-22, Assumption A8).
 */
@Component
@ConfigurationProperties(prefix = "app.fraud")
public class FraudProperties {

    private BigDecimal amountThreshold = new BigDecimal("50000000.00");
    private final Velocity velocity = new Velocity();

    public BigDecimal getAmountThreshold() {
        return amountThreshold;
    }

    public void setAmountThreshold(BigDecimal amountThreshold) {
        this.amountThreshold = amountThreshold;
    }

    public Velocity getVelocity() {
        return velocity;
    }

    /** Sliding-window velocity rule thresholds. */
    public static class Velocity {

        private int maxCount = 5;
        private long windowSeconds = 60L;

        public int getMaxCount() {
            return maxCount;
        }

        public void setMaxCount(int maxCount) {
            this.maxCount = maxCount;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public void setWindow(Duration window) {
            if (window != null) {
                this.windowSeconds = window.toSeconds();
            }
        }
    }
}

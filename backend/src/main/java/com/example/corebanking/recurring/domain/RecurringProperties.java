package com.example.corebanking.recurring.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for recurring transaction scheduling and retry thresholds ({@code
 * app.recurring.*}) (L2-07, FR-17).
 */
@ConfigurationProperties(prefix = "app.recurring")
public class RecurringProperties {

    private String cron = "0 * * * * *";
    private int batchSize = 50;
    private int maxFailures = 3;
    private boolean schedulerEnabled = true;

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxFailures() {
        return maxFailures;
    }

    public void setMaxFailures(int maxFailures) {
        this.maxFailures = maxFailures;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }
}

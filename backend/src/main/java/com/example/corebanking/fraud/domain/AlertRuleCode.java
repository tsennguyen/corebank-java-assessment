package com.example.corebanking.fraud.domain;

/** Anomaly detection rule codes supported by the fraud detection engine (L3-03, L3-04, FR-22). */
public enum AlertRuleCode {
    AMOUNT_THRESHOLD,
    VELOCITY
}

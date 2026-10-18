package com.example.corebanking.report.domain;

/**
 * Supported time-series bucket granularities for financial trend analytics and chart rendering
 * (L3-08, FR-20, ADR-013).
 *
 * <p>Maps each enum constant to a whitelisted PostgreSQL {@code date_trunc} unit and {@code
 * generate_series} interval step so user input is never interpolated into raw SQL.
 */
public enum TrendGranularity {
    DAY("day", "1 day"),
    WEEK("week", "1 week"),
    MONTH("month", "1 month");

    private final String sqlUnit;
    private final String sqlStep;

    TrendGranularity(String sqlUnit, String sqlStep) {
        this.sqlUnit = sqlUnit;
        this.sqlStep = sqlStep;
    }

    /** Returns the lowercase unit literal expected by PostgreSQL {@code date_trunc(unit, ts)}. */
    public String sqlUnit() {
        return sqlUnit;
    }

    /**
     * Returns the interval step literal expected by PostgreSQL {@code generate_series(start, stop,
     * step)}.
     */
    public String sqlStep() {
        return sqlStep;
    }
}

package com.example.corebanking.report.domain;

/**
 * Supported aggregation time periods for periodic transaction volume reports (L3-01, L3-02, FR-19).
 * Whitelists PostgreSQL {@code date_trunc} units to prevent arbitrary string injection.
 */
public enum ReportPeriod {
    WEEK("week"),
    QUARTER("quarter"),
    YEAR("year");

    private final String sqlUnit;

    ReportPeriod(String sqlUnit) {
        this.sqlUnit = sqlUnit;
    }

    /**
     * Returns the exact lowercase unit string accepted by PostgreSQL {@code date_trunc(unit, ts)}.
     *
     * @return PostgreSQL date_trunc unit ("week", "quarter", or "year")
     */
    public String sqlUnit() {
        return sqlUnit;
    }
}

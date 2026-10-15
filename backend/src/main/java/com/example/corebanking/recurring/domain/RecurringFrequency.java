package com.example.corebanking.recurring.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Execution frequency for recurring transactions with anchor-aware monthly clamping (L2-06, FR-16).
 */
public enum RecurringFrequency {
    DAILY {
        @Override
        public LocalDate nextDate(LocalDate currentDate, int anchorDayOfMonth) {
            Objects.requireNonNull(currentDate, "currentDate must not be null");
            return currentDate.plusDays(1);
        }
    },
    WEEKLY {
        @Override
        public LocalDate nextDate(LocalDate currentDate, int anchorDayOfMonth) {
            Objects.requireNonNull(currentDate, "currentDate must not be null");
            return currentDate.plusWeeks(1);
        }
    },
    MONTHLY {
        @Override
        public LocalDate nextDate(LocalDate currentDate, int anchorDayOfMonth) {
            Objects.requireNonNull(currentDate, "currentDate must not be null");
            if (anchorDayOfMonth < 1 || anchorDayOfMonth > 31) {
                throw new IllegalArgumentException(
                        "anchorDayOfMonth must be between 1 and 31, got: " + anchorDayOfMonth);
            }
            YearMonth nextMonth = YearMonth.from(currentDate).plusMonths(1);
            int clampedDay = Math.min(anchorDayOfMonth, nextMonth.lengthOfMonth());
            return nextMonth.atDay(clampedDay);
        }
    };

    /**
     * Computes the next scheduled calendar date anchored to {@code anchorDayOfMonth} (for {@link
     * #MONTHLY}, clamps to the end of shorter months such as February 28/29 or 30-day months, then
     * restores the original anchor day in subsequent 31-day months).
     *
     * @param currentDate current scheduled date
     * @param anchorDayOfMonth original day-of-month from {@code startDate.getDayOfMonth()} (1..31)
     * @return next scheduled date
     */
    public abstract LocalDate nextDate(LocalDate currentDate, int anchorDayOfMonth);

    /**
     * Computes the next scheduled date using {@code currentDate.getDayOfMonth()} as the anchor day.
     *
     * @param currentDate current scheduled date
     * @return next scheduled date
     */
    public LocalDate next(LocalDate currentDate) {
        Objects.requireNonNull(currentDate, "currentDate must not be null");
        return nextDate(currentDate, currentDate.getDayOfMonth());
    }

    /**
     * Alias for {@link #nextDate(LocalDate, int)} supporting anchor-day clamping across months.
     *
     * @param currentDate current scheduled date
     * @param anchorDayOfMonth original day-of-month from {@code startDate.getDayOfMonth()} (1..31)
     * @return next scheduled date
     */
    public LocalDate next(LocalDate currentDate, int anchorDayOfMonth) {
        return nextDate(currentDate, anchorDayOfMonth);
    }

    /**
     * Computes the next scheduled execution instant at {@code 00:00} in the business timezone.
     *
     * @param currentRunAt current scheduled execution instant
     * @param anchorDayOfMonth original day-of-month from {@code startDate.getDayOfMonth()} (1..31)
     * @param businessZone business timezone (e.g. {@code Asia/Ho_Chi_Minh})
     * @return next scheduled execution instant at start of day in {@code businessZone}
     */
    public Instant nextInstant(Instant currentRunAt, int anchorDayOfMonth, ZoneId businessZone) {
        Objects.requireNonNull(currentRunAt, "currentRunAt must not be null");
        Objects.requireNonNull(businessZone, "businessZone must not be null");
        LocalDate currentLocalDate = currentRunAt.atZone(businessZone).toLocalDate();
        LocalDate nextLocalDate = nextDate(currentLocalDate, anchorDayOfMonth);
        return nextLocalDate.atStartOfDay(businessZone).toInstant();
    }
}

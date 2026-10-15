package com.example.corebanking.recurring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecurringFrequencyTest {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Test
    @DisplayName("DAILY nextDate should advance by 1 day across month and year boundaries")
    void daily_nextDate_shouldAdvanceByOneDay() {
        LocalDate jan31 = LocalDate.of(2026, 1, 31);
        assertThat(RecurringFrequency.DAILY.nextDate(jan31, 31))
                .isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(RecurringFrequency.DAILY.next(LocalDate.of(2026, 12, 31)))
                .isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    @DisplayName("WEEKLY nextDate should advance by 7 days")
    void weekly_nextDate_shouldAdvanceBySevenDays() {
        LocalDate start = LocalDate.of(2026, 10, 15);
        assertThat(RecurringFrequency.WEEKLY.nextDate(start, 15))
                .isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(RecurringFrequency.WEEKLY.next(start)).isEqualTo(LocalDate.of(2026, 10, 22));
    }

    @Test
    @DisplayName(
            "MONTHLY nextDate from Jan 31 should clamp to Feb 28 in non-leap year, then restore"
                    + " Mar 31 and clamp to Apr 30 (L2-06)")
    void monthly_fromJan31_shouldClampToFeb28ThenReturnToMar31AndApr30() {
        int anchorDay = 31;
        LocalDate jan31 = LocalDate.of(2026, 1, 31);

        LocalDate feb = RecurringFrequency.MONTHLY.next(jan31, anchorDay);
        assertThat(feb).isEqualTo(LocalDate.of(2026, 2, 28));

        LocalDate mar = RecurringFrequency.MONTHLY.next(feb, anchorDay);
        assertThat(mar).isEqualTo(LocalDate.of(2026, 3, 31));

        LocalDate apr = RecurringFrequency.MONTHLY.next(mar, anchorDay);
        assertThat(apr).isEqualTo(LocalDate.of(2026, 4, 30));

        LocalDate may = RecurringFrequency.MONTHLY.next(apr, anchorDay);
        assertThat(may).isEqualTo(LocalDate.of(2026, 5, 31));
    }

    @Test
    @DisplayName("MONTHLY nextDate from Jan 31 in leap year 2028 should clamp to Feb 29 (L2-06)")
    void monthly_leapYearFromJan31_shouldClampToFeb29() {
        LocalDate jan31Leap = LocalDate.of(2028, 1, 31);
        LocalDate feb29 = RecurringFrequency.MONTHLY.next(jan31Leap);
        assertThat(feb29).isEqualTo(LocalDate.of(2028, 2, 29));

        LocalDate mar31 = RecurringFrequency.MONTHLY.next(feb29, 31);
        assertThat(mar31).isEqualTo(LocalDate.of(2028, 3, 31));
    }

    @Test
    @DisplayName("nextInstant should compute 00:00 in Asia/Ho_Chi_Minh on the next scheduled date")
    void nextInstant_shouldReturnMidnightInBusinessZone() {
        Instant jan31Run = ZonedDateTime.of(2026, 1, 31, 14, 30, 0, 0, VN_ZONE).toInstant();
        Instant febRun = RecurringFrequency.MONTHLY.nextInstant(jan31Run, 31, VN_ZONE);

        ZonedDateTime febZoned = febRun.atZone(VN_ZONE);
        assertThat(febZoned.toLocalDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(febZoned.toLocalTime().toString()).isEqualTo("00:00");
    }

    @Test
    @DisplayName("MONTHLY nextDate should reject invalid anchorDayOfMonth outside 1..31")
    void monthly_invalidAnchorDay_shouldThrowIllegalArgumentException() {
        LocalDate date = LocalDate.of(2026, 1, 15);
        assertThatThrownBy(() -> RecurringFrequency.MONTHLY.nextDate(date, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecurringFrequency.MONTHLY.nextDate(date, 32))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.example.corebanking.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

    @Test
    @DisplayName("Default configuration should use Vietnam business timezone")
    void defaultClockShouldUseVietnamTimezone() {
        ClockConfig config = new ClockConfig("Asia/Ho_Chi_Minh");
        Clock clock = config.clock();
        ZoneId zoneId = config.businessZoneId();

        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThat(zoneId).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThat(Duration.between(clock.instant(), Instant.now()).abs())
                .isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("Custom timezone should be respected when configured")
    void customTimezoneShouldBeRespected() {
        ClockConfig config = new ClockConfig("UTC");
        Clock clock = config.clock();
        ZoneId zoneId = config.businessZoneId();

        assertThat(clock.getZone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(zoneId).isEqualTo(ZoneId.of("UTC"));
    }
}

package com.example.corebanking.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application clock configuration providing a centralized, injectable Clock bean. Defaults to the
 * Vietnam business timezone ('Asia/Ho_Chi_Minh').
 */
@Configuration
public class ClockConfig {

    private final ZoneId businessZone;

    public ClockConfig(@Value("${app.business-zone:Asia/Ho_Chi_Minh}") String businessZone) {
        this.businessZone = ZoneId.of(businessZone);
    }

    @Bean
    public Clock clock() {
        return Clock.system(businessZone);
    }

    @Bean
    public ZoneId businessZoneId() {
        return businessZone;
    }
}

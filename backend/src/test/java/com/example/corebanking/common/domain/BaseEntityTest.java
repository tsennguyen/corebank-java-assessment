package com.example.corebanking.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BaseEntityTest {

    private static class TestEntity extends BaseEntity {}

    @Test
    @DisplayName("Should set and get created_at and updated_at timestamps")
    void shouldSetAndGetTimestamps() {
        TestEntity entity = new TestEntity();
        Instant now = Instant.now();
        Instant later = now.plusSeconds(60);

        entity.setCreatedAt(now);
        entity.setUpdatedAt(later);

        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUpdatedAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("Initial timestamps should be null before persistence or assignment")
    void initialTimestampsShouldBeNull() {
        TestEntity entity = new TestEntity();

        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
    }
}

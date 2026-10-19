package com.example.corebanking.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageResponseTest {

    @Test
    @DisplayName("from(Page) maps content and pagination metadata accurately")
    void from_shouldMapPageCorrectly() {
        List<String> items = List.of("Alpha", "Beta", "Gamma");
        PageImpl<String> page = new PageImpl<>(items, PageRequest.of(1, 3), 10);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.content()).containsExactly("Alpha", "Beta", "Gamma");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(3);
        assertThat(response.totalElements()).isEqualTo(10L);
        assertThat(response.totalPages()).isEqualTo(4);
    }

    @Test
    @DisplayName("from(Page, mapper) transforms content using provided mapper function")
    void fromWithMapper_shouldTransformContent() {
        List<Integer> numbers = List.of(1, 2, 3);
        PageImpl<Integer> page = new PageImpl<>(numbers, PageRequest.of(0, 10), 3);

        PageResponse<String> response = PageResponse.from(page, n -> "Item-" + n);

        assertThat(response.content()).containsExactly("Item-1", "Item-2", "Item-3");
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(3L);
        assertThat(response.totalPages()).isEqualTo(1);
    }
}

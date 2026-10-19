package com.example.corebanking.common.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Standard paginated response envelope decoupled from Spring Data Page JSON internals.
 *
 * @param <T> element type in the page content
 */
public record PageResponse<T>(
        List<T> content, int page, int size, long totalElements, int totalPages) {

    /**
     * Constructs a PageResponse directly from a Spring Data Page.
     *
     * @param page Spring Data page
     * @param <T> element type
     * @return standard PageResponse envelope
     */
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /**
     * Constructs a PageResponse by transforming elements with a mapper function.
     *
     * @param page Spring Data page of source elements
     * @param mapper mapping function from source to target
     * @param <S> source type
     * @param <T> target type
     * @return standard PageResponse envelope
     */
    public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}

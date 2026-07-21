package com.studyloop.backend.common;

import org.springframework.data.domain.Page;

import java.util.List;

// A stable, explicit page envelope. Returning Spring Data's Page straight from a
// controller serializes its internal shape (which Spring warns is unstable), so we
// map to this record instead.
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}

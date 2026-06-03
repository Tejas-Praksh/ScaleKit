package com.scalekit.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated response wrapper for list endpoints.
 *
 * @param <T> the type of elements in the page
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedResponse<T> {

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;
    private boolean empty;
    private Long executionTimeMs;

    /**
     * Converts a Spring Data {@link Page} into a {@link PagedResponse}.
     */
    public static <T> PagedResponse<T> of(Page<T> page) {
        return PagedResponse.<T>builder()
                .content(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .empty(page.isEmpty())
                .build();
    }

    /**
     * Converts a Spring Data {@link Page} with execution time tracking.
     */
    public static <T> PagedResponse<T> of(Page<T> page, Long executionTimeMs) {
        PagedResponse<T> response = of(page);
        response.setExecutionTimeMs(executionTimeMs);
        return response;
    }
}

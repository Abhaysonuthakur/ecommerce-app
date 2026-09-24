package com.shop.ecommerce.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A stable pagination envelope.
 *
 * <p><b>WHY not return Spring's {@code Page} directly?</b> Three reasons, in order of
 * how much trouble each causes:
 *
 * <ol>
 *   <li><b>It is not a stable contract.</b> Serialising {@code Page} produces a shape
 *       driven by Spring Data's internals - {@code PageImpl} includes a
 *       {@code pageable} object with a serialised {@code Sort} inside it, nested
 *       {@code content} arrays and so on. Spring Boot 3.3+ actually logs a warning
 *       telling you not to do this. A Spring Data upgrade can change a response body,
 *       and nothing in the API's own code would show it.</li>
 *   <li><b>It is noisy.</b> A client needs six facts to render pagination. {@code Page}
 *       sends about forty, including the full sort specification repeated per response.</li>
 *   <li><b>It leaks the persistence layer.</b> The moment a response body is a
 *       {@code Page}, the repository's return type IS the API contract, so a change in
 *       the data layer is a breaking API change.</li>
 * </ol>
 *
 * <p>This record sends exactly what a paginated list needs and nothing else, and it is
 * a type this project owns.
 *
 * @param content       the page's items
 * @param page          zero-based page index. Zero-based because that is what Spring Data
 *                      and every database use internally; converting at the boundary
 *                      would mean two conventions in one system, and an off-by-one in
 *                      the conversion.
 * @param size          maximum items per page that was requested
 * @param totalElements rows matching the query across all pages
 * @param totalPages    total number of pages
 * @param first         true if this is the first page
 * @param last          true if this is the last page. <b>Reported even for a page past
 *                      the end</b> - see {@link #from} - so a client looping
 *                      "while (!last)" terminates instead of requesting page 999 forever.
 * @param numberOfItems how many items are in <em>this</em> page, which differs from
 *                      {@code size} on the final page and is the value a UI actually
 *                      needs when rendering "showing 13-24 of 31"
 */
@Schema(description = "A page of results with the metadata needed to render pagination")
public record PageResponse<T>(

        @Schema(description = "The items on this page")
        List<T> content,

        @Schema(example = "0", description = "Zero-based page index")
        int page,

        @Schema(example = "12", description = "Requested page size")
        int size,

        @Schema(example = "31", description = "Total matching rows across all pages")
        long totalElements,

        @Schema(example = "3", description = "Total number of pages")
        int totalPages,

        @Schema(example = "true")
        boolean first,

        @Schema(example = "false")
        boolean last,

        @Schema(example = "12", description = "Number of items in this page")
        int numberOfItems

) {

    /**
     * Adapts a Spring Data {@code Page} into this envelope, converting the items.
     *
     * <p>The {@code converter} function is what makes this generic: a repository returns
     * entities (which must never leave the service layer), and the mapper turns them into
     * DTOs. Doing the conversion here rather than in each service method means the
     * pagination metadata is copied in exactly one place - and there is only one place
     * for it to be wrong.
     *
     * <p><b>A note on {@code last} for an over-paged request.</b> Asking for page 999 of a
     * 3-page result returns an <em>empty</em> page with {@code last = true}, not an error.
     * That combination looks backwards and is deliberate:
     * <ul>
     *   <li>Over-paging is a harmless client mistake - a bookmarked URL, a stale link.
     *       Answering 400 or 404 would force every client to check the page count before
     *       every request, to avoid a failure that means nothing.</li>
     *   <li>{@code last = true} is what lets a "keep fetching until done" client stop.
     *       Returning {@code last = false} for an empty page would loop forever.</li>
     * </ul>
     */
    public static <E, D> PageResponse<D> from(Page<E> springPage, Function<E, D> converter) {
        return new PageResponse<>(
                springPage.getContent().stream().map(converter).toList(),
                springPage.getNumber(),
                springPage.getSize(),
                springPage.getTotalElements(),
                springPage.getTotalPages(),
                springPage.isFirst(),
                springPage.isLast(),
                springPage.getNumberOfElements()
        );
    }

    /**
     * Builds an envelope from an already-mapped list, for endpoints that do not use
     * Spring Data pagination (the admin dashboard's aggregates, for example).
     */
    public static <D> PageResponse<D> of(List<D> content, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                page == 0,
                page >= totalPages - 1,
                content.size()
        );
    }
}

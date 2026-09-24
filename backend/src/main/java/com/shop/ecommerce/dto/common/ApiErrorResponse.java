package com.shop.ecommerce.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * The body of every error response in this application.
 *
 * <p>One shape, always. Whether the failure came from validation, from a missing row,
 * from the {@code @RestControllerAdvice}, or from the security filter chain, a client
 * parses exactly one structure - which means the client's error handling is written once.
 *
 * @param timestamp   when it happened, UTC
 * @param status      the HTTP status code, repeated here so the body is self-contained
 *                    in a log or a bug report
 * @param error       a stable, machine-readable code. <b>This is the part a client should
 *                    branch on</b> - {@code PRODUCT_NOT_FOUND} will not change.
 * @param message     a human-readable explanation. <b>Not for branching</b> - wording may
 *                    change between versions, and it is the field most likely to be
 *                    localised later.
 * @param path        the request path that failed, so a log aggregator can group errors
 * @param fieldErrors per-field validation failures; {@code null} for anything that is not
 *                    a validation error. Present only when it means something: an empty
 *                    array on a 404 would be noise a client has to filter.
 */
@Schema(description = "Standard error response returned by every failing endpoint")
public record ApiErrorResponse(

        @Schema(example = "2026-09-24T07:12:33Z")
        Instant timestamp,

        @Schema(example = "404")
        int status,

        @Schema(example = "PRODUCT_NOT_FOUND",
                description = "Stable machine-readable code. Branch on this, not on `message`.")
        String error,

        @Schema(example = "Product not found with id: 10")
        String message,

        @Schema(example = "/api/products/10")
        String path,

        @Schema(description = "Present only for validation failures (HTTP 400)")
        List<FieldError> fieldErrors

) {

    /**
     * A single invalid field.
     *
     * <p><b>Note what is NOT here: the rejected value.</b>
     * Spring's {@code FieldError} carries one, and it is tempting to include it because
     * it makes debugging easier. But the first request a new user makes is
     * {@code POST /api/auth/register} - so echoing rejected values means a failed
     * registration bounces the submitted password back into browser devtools, proxy
     * logs and error trackers. A field name and a reason are enough to fix a form.
     *
     * @param field   the request field name, e.g. {@code "quantity"}
     * @param message what is wrong with it, phrased for a human
     */
    @Schema(description = "A single field-level validation failure")
    public record FieldError(
            @Schema(example = "quantity") String field,
            @Schema(example = "must be greater than 0") String message
    ) {
    }

    /**
     * Convenience factory for the common case: no field errors.
     *
     * <p>Exists because almost every error is not a validation error, and constructing
     * the record with an explicit {@code null} at every call site is noise.
     */
    public static ApiErrorResponse of(int status, String error, String message, String path) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, null);
    }

    /**
     * Factory for validation failures.
     *
     * <p>Takes a fully built list rather than building one here, because the handler is
     * the only place that knows whether the failures came from a request body
     * ({@code MethodArgumentNotValidException}) or from query-parameter constraints
     * ({@code ConstraintViolationException}) - and those two need different extraction.
     */
    public static ApiErrorResponse validation(String message, String path, List<FieldError> fieldErrors) {
        return new ApiErrorResponse(Instant.now(), 400, ErrorCode.VALIDATION_FAILED,
                message, path, fieldErrors);
    }
}

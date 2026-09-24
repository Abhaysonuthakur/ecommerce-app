package com.shop.ecommerce.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A response that is just a message.
 *
 * <p>For operations with nothing meaningful to return - clearing a cart, deleting a
 * product - returning a bare HTTP status leaves a client guessing whether anything
 * happened. <b>204 No Content</b> is the by-the-book answer, but it is also unhelpful in
 * a network tab: a developer debugging cannot tell a successful delete from a request that
 * never arrived.
 *
 * <p>A small JSON body fixes that at negligible cost and gives the UI something to show in
 * a toast.
 *
 * @param message a human-readable confirmation
 * @param success always true - the body is only ever sent on success, so a failure carries
 *                {@link ApiErrorResponse} instead. It exists so a client's generic response
 *                handler can branch on one field.
 */
@Schema(description = "Simple operation result")
public record MessageResponse(

        @Schema(example = "Cart cleared successfully")
        String message,

        @Schema(example = "true")
        boolean success

) {

    public static MessageResponse of(String message) {
        return new MessageResponse(message, true);
    }
}

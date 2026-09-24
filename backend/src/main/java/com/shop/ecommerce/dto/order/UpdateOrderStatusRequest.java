package com.shop.ecommerce.dto.order;

import com.shop.ecommerce.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Change an order's status. Admin only.
 *
 * <p>The request carries only the target status. The service validates the transition
 * against {@link OrderStatus#canTransitionTo} - which is a different check from
 * validation, and belongs in the service:
 *
 * <ul>
 *   <li>{@code @NotNull} here answers "did you send a status?" - a question about the
 *       request's shape, answerable without any knowledge of the order.</li>
 *   <li>The transition check answers "is {@code DELIVERED -> PENDING} legal?" - a question
 *       about the order's current state, which is not knowable at binding time.</li>
 * </ul>
 *
 * <p>Keeping them separate means the 400 for a malformed request and the 409 for an
 * illegal transition stay distinguishable, with different messages.
 *
 * <p>Note that a JSON value outside the enum - {@code "REFUNDED"} - is rejected during
 * deserialisation, and the exception handler turns that into a 400 listing the valid
 * values. Without that handling it would surface as a 500, because a failed deserialisation
 * is an {@code HttpMessageNotReadableException} rather than a validation error.
 *
 * @param status the status to move the order to
 */
@Schema(description = "Update an order's status (admin only)")
public record UpdateOrderStatusRequest(

        @Schema(example = "CONFIRMED", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "PENDING, CONFIRMED, PROCESSING, SHIPPED, DELIVERED, CANCELLED")
        @NotNull(message = "Status is required")
        OrderStatus status

) {
}

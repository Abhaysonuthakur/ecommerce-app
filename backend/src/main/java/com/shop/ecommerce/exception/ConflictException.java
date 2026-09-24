package com.shop.ecommerce.exception;

import com.shop.ecommerce.dto.common.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * The request conflicts with the current state of the resource.
 *
 * <h2>Why 409 rather than 400</h2>
 *
 * <p>A 400 says "your request is malformed - fix it and resend". A 409 says "your request
 * was well-formed, and the server's state says no". The difference matters for retries: a
 * client should <b>not</b> blindly retry a 400, because it will fail identically, whereas a
 * 409 often means "re-read the current state and try again", which may well succeed.
 *
 * <p>Used for: a duplicate email or category name, insufficient stock, an illegal order
 * status transition, and an optimistic-locking conflict.
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, ErrorCode.CONFLICT, message);
    }

    public ConflictException(String errorCode, String message) {
        super(HttpStatus.CONFLICT, errorCode, message);
    }

    public static ConflictException emailExists(String email) {
        // The email is echoed here because the caller supplied it and needs to know which
        // one clashed. Note that this is safe for an email, whereas echoing a password would
        // not be - see ApiErrorResponse.FieldError's javadoc.
        return new ConflictException(ErrorCode.EMAIL_ALREADY_EXISTS,
                "An account with the email '%s' already exists".formatted(email));
    }

    public static ConflictException categoryNameExists(String name) {
        return new ConflictException(ErrorCode.CATEGORY_ALREADY_EXISTS,
                "A category named '%s' already exists".formatted(name));
    }

    /**
     * Not enough stock to satisfy the request.
     *
     * <p>The message names the product and both quantities, because that is what the
     * customer needs to decide what to do: "only 3 left" lets them order 3, whereas
     * "insufficient stock" leaves them guessing. It leaks nothing - stock levels are shown
     * on the product page anyway.
     */
    public static ConflictException insufficientStock(String productName, int requested, int available) {
        return new ConflictException(ErrorCode.INSUFFICIENT_STOCK,
                "Insufficient stock for '%s': requested %d, but only %d available"
                        .formatted(productName, requested, available));
    }

    /**
     * A product cannot be deleted because an order references it.
     *
     * <p>{@code order_items.product_id} is {@code ON DELETE RESTRICT}, so the database
     * would refuse this delete. Checking first - via
     * {@code ProductRepository.isProductOrdered} - turns an unavoidable
     * {@code DataIntegrityViolationException} into a message that tells the admin what to
     * do instead: deactivate the product rather than delete it.
     */
    public static ConflictException productInUse(String productName) {
        return new ConflictException(ErrorCode.PRODUCT_IN_USE,
                "Product '%s' cannot be deleted because it appears in existing orders. "
                        + "Deactivate it instead so it is withdrawn from sale but order history stays intact."
                        .formatted(productName));
    }

    /**
     * An order status change that the state machine does not permit.
     *
     * <p>For example {@code DELIVERED -> PROCESSING}, or marking an order
     * {@code CANCELLED} twice. It is a 409 rather than a 400 because the request is
     * perfectly well formed - it is the order's current state that makes it impossible.
     * A client that retries after re-reading the order may well succeed.
     *
     * <p>The allowed next statuses are named, because otherwise the caller has to guess,
     * and guessing at a state machine is how a UI ends up offering buttons that always
     * fail.
     */
    public static ConflictException illegalOrderTransition(String orderNumber, String from, String to, java.util.Collection<String> allowedNext) {
        String allowed = allowedNext.isEmpty() ? "none - this status is final" : String.join(", ", allowedNext);
        return new ConflictException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION,
                "Cannot change order %s from %s to %s. Allowed next status: %s."
                        .formatted(orderNumber, from, to, allowed));
    }
}

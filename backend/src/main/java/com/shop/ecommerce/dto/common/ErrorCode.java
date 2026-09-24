package com.shop.ecommerce.dto.common;

/**
 * Every machine-readable error code this API can return.
 *
 * <p><b>WHY a constants class rather than string literals?</b> The `error` field of an
 * error response is a contract: a frontend branches on it, and a test asserts it. A
 * literal {@code "PRODUCT_NOT_FOUND"} typed in two places is two constants that will
 * eventually disagree - one gets a typo, and the client's {@code if (error === ...)}
 * silently stops matching while the API looks perfectly correct.
 *
 * <p>Declaring it once means a rename is a compile error everywhere, and the full set
 * of failure modes is discoverable by reading one file.
 *
 * <p>The convention is {@code RESOURCE_PROBLEM}, so codes sort and group naturally in
 * a log or a dashboard.
 */
public final class ErrorCode {

    private ErrorCode() {
        // Not instantiable - this is a namespace, not an object.
        // (A private constructor is also what stops a utility class appearing in
        //  generated documentation as something you could instantiate.)
    }

    // -----------------------------------------------------------------
    //  4xx - the client did something wrong
    // -----------------------------------------------------------------

    /** Malformed request: bad JSON, a value that cannot be parsed, a missing body. */
    public static final String BAD_REQUEST = "BAD_REQUEST";

    /** One or more fields failed validation. The response carries `fieldErrors`. */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    /** No valid credentials were presented, or the presented token was rejected. */
    public static final String UNAUTHORIZED = "UNAUTHORIZED";

    /** Credentials were valid but the account is disabled. */
    public static final String ACCOUNT_DISABLED = "ACCOUNT_DISABLED";

    /** Email/password combination is wrong. Deliberately does not say which. */
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";

    /** The token is well-formed but past its expiry. The client should re-authenticate. */
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";

    /** The token's signature, structure or claims are not acceptable. */
    public static final String INVALID_TOKEN = "INVALID_TOKEN";

    /** Authenticated, but not permitted. Distinct from 401: signing in again will not help. */
    public static final String ACCESS_DENIED = "ACCESS_DENIED";

    /** A requested resource does not exist, or is not visible to this caller. */
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";

    /** The HTTP method is not supported for this path. */
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";

    /** The request's Content-Type is not acceptable for this endpoint. */
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";

    // -----------------------------------------------------------------
    //  409 - a conflict with the current state
    // -----------------------------------------------------------------

    /** The request conflicts with existing data, e.g. an email already registered. */
    public static final String CONFLICT = "CONFLICT";

    /** That email address already has an account. */
    public static final String EMAIL_ALREADY_EXISTS = "EMAIL_ALREADY_EXISTS";

    /** That category name or slug is already taken. */
    public static final String CATEGORY_ALREADY_EXISTS = "CATEGORY_ALREADY_EXISTS";

    /** Not enough stock to fulfil the request. */
    public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";

    /** Two requests modified the same row; the caller should re-read and retry. */
    public static final String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";

    // -----------------------------------------------------------------
    //  Resource-specific 404s
    //
    //  Separate codes rather than one RESOURCE_NOT_FOUND, because a frontend
    //  wants different behaviour for "this product is gone" than for "this
    //  order is not yours" - and branching on the `message` is exactly the
    //  fragility the code field exists to avoid.
    // -----------------------------------------------------------------

    public static final String PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";
    public static final String CATEGORY_NOT_FOUND = "CATEGORY_NOT_FOUND";
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String CART_NOT_FOUND = "CART_NOT_FOUND";
    public static final String CART_ITEM_NOT_FOUND = "CART_ITEM_NOT_FOUND";
    public static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";

    // -----------------------------------------------------------------
    //  Business-rule failures that are not really 404s or 409s
    // -----------------------------------------------------------------

    /** The operation is not legal in the resource's current state. */
    public static final String INVALID_OPERATION = "INVALID_OPERATION";

    /** An order status transition that the state machine does not allow. */
    public static final String INVALID_ORDER_STATUS_TRANSITION = "INVALID_ORDER_STATUS_TRANSITION";

    /** The cart has no items, so there is nothing to order. */
    public static final String EMPTY_CART = "EMPTY_CART";

    /** The product exists but is withdrawn from sale. */
    public static final String PRODUCT_UNAVAILABLE = "PRODUCT_UNAVAILABLE";

    /** This product has been sold and cannot be deleted. Deactivate it instead. */
    public static final String PRODUCT_IN_USE = "PRODUCT_IN_USE";

    // -----------------------------------------------------------------
    //  5xx
    // -----------------------------------------------------------------

    /** Something broke that we did not anticipate. The stack trace is logged, not returned. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /** A database constraint was violated in a way we did not handle explicitly. */
    public static final String DATA_INTEGRITY_VIOLATION = "DATA_INTEGRITY_VIOLATION";
}

package com.shop.ecommerce.exception;

import com.shop.ecommerce.dto.common.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * A requested resource does not exist - <b>or is not visible to this caller.</b>
 *
 * <h2>The second half of that sentence is a security decision</h2>
 *
 * <p>When a customer asks for an order that belongs to somebody else, this exception is
 * raised - producing a <b>404</b>, not a 403.
 *
 * <p>403 would be technically accurate: the resource exists and you are not allowed it. It
 * is also an information leak. A 403 confirms the row exists, so an attacker walking ids
 * can tell "order 5000 does not exist" from "order 5000 exists but is not mine" - and the
 * second answer tells them how many orders the store has processed and which ids are worth
 * attacking. A 404 answers both questions identically: nothing here for you.
 *
 * <p>This is why the ownership check is a WHERE clause in the repository rather than an
 * {@code if} in Java. A repository method that scopes by user id and finds nothing raises
 * this same exception, so there is no code path where somebody could accidentally add a
 * helpful "but it does exist" distinction.
 */
public class ResourceNotFoundException extends ApiException {

    private ResourceNotFoundException(String errorCode, String message) {
        super(HttpStatus.NOT_FOUND, errorCode, message);
    }

    /**
     * Builds the standard "&lt;thing&gt; not found with &lt;field&gt;: &lt;value&gt;"
     * message from its parts, with the generic {@code RESOURCE_NOT_FOUND} code.
     *
     * <p>A factory rather than a format string at each call site, because the wording is
     * part of the API's UX - a client shows this message - and call sites that each write
     * their own drift: "Product not found with id: 10" in one place and "product 10 does
     * not exist" in another looks like two different problems to a user.
     */
    public static ResourceNotFoundException of(String resource, String field, Object value) {
        return new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                "%s not found with %s: %s".formatted(resource, field, value));
    }

    /*
     * The factories below carry a SPECIFIC error code rather than the generic
     * RESOURCE_NOT_FOUND.
     *
     * The distinction matters to a frontend: "this product is gone" should show a
     * "back to products" page, while a generic not-found might warrant a retry. Branching
     * on the message text to tell the two apart is exactly the fragility the code field
     * exists to remove.
     *
     * Note the shape: each factory passes its code through the private constructor. An
     * earlier draft used anonymous subclasses overriding getErrorCode() - it compiles and
     * behaves identically, but it is worse in two ways: the stack trace logs
     * `ResourceNotFoundException$1` instead of the real type, and each anonymous class is
     * a separate type the JVM has to load. Passing a parameter has neither problem.
     */

    public static ResourceNotFoundException product(Long id) {
        return new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND,
                "Product not found with id: " + id);
    }

    public static ResourceNotFoundException category(Long id) {
        return new ResourceNotFoundException(ErrorCode.CATEGORY_NOT_FOUND,
                "Category not found with id: " + id);
    }

    public static ResourceNotFoundException user(Long id) {
        return new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND,
                "User not found with id: " + id);
    }

    public static ResourceNotFoundException cart() {
        return new ResourceNotFoundException(ErrorCode.CART_NOT_FOUND, "Cart not found");
    }

    public static ResourceNotFoundException cartItem(Long id) {
        return new ResourceNotFoundException(ErrorCode.CART_ITEM_NOT_FOUND,
                "Cart item not found with id: " + id);
    }

    /**
     * Used both for "no such order" and for "not your order". See the class javadoc for why
     * those are deliberately the same response.
     */
    public static ResourceNotFoundException order(Long id) {
        return new ResourceNotFoundException(ErrorCode.ORDER_NOT_FOUND,
                "Order not found with id: " + id);
    }
}

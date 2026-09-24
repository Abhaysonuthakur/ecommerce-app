package com.shop.ecommerce.controller;

import com.shop.ecommerce.dto.cart.AddToCartRequest;
import com.shop.ecommerce.dto.cart.CartResponse;
import com.shop.ecommerce.dto.cart.UpdateCartItemRequest;
import com.shop.ecommerce.dto.common.MessageResponse;
import com.shop.ecommerce.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cart endpoints.
 *
 * <h2>The one thing to notice: there is no user id in any of these paths</h2>
 *
 * <pre>
 *   GET    /api/cart              not  /api/cart/{userId}
 *   POST   /api/cart/items        not  /api/cart/{userId}/items
 *   PUT    /api/cart/items/{id}   not  /api/cart/{userId}/items/{id}
 * </pre>
 *
 * <p>The current user comes from the JWT, via {@code CurrentUserResolver} inside the
 * service. That is not a stylistic preference - it removes an entire vulnerability class.
 * Consider the alternative: {@code GET /api/cart/{userId}} is a route where the ownership
 * check is a branch someone must remember to write, on every method, forever. One
 * forgotten branch and any customer can read any other customer's cart by incrementing a
 * number. Here there is no parameter to forget to check, and
 * {@code CartService} has no method that accepts one.
 *
 * <p>{@code itemId} <em>is</em> in the path, and it is safe, because the service looks the
 * item up <b>within the caller's own cart</b> rather than by primary key. Item 42 belonging
 * to somebody else is simply not found - which produces a 404, not a 403, so the endpoint
 * cannot be used to enumerate which item ids exist.
 */
@RestController
@RequestMapping("/api/cart")
@Tag(name = "Cart", description = "The authenticated customer's cart. Every route is scoped to the caller.")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    /**
     * The caller's cart.
     *
     * <p><b>Creates an empty cart on first access rather than 404-ing.</b> A new customer
     * who has never added anything has a cart; it is empty. Returning 404 for that would
     * force every client to treat "no cart yet" as a normal state to recover from, on the
     * navbar badge, on the cart page, and after every login. Creating it on demand means
     * the client has exactly one state to render.
     *
     * <p>The response carries {@code subtotal}, {@code totalItems}, {@code itemCount},
     * {@code empty} and {@code checkoutReady} alongside the lines, so the navbar badge and
     * the checkout button can both be driven from this one payload.
     */
    @GetMapping
    @Operation(summary = "Get the current user's cart",
            description = "Creates an empty cart if the user has never had one. Includes computed subtotal, item count and a checkoutReady flag.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The cart",
                    content = @Content(schema = @Schema(implementation = CartResponse.class))),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    public ResponseEntity<CartResponse> getMyCart() {
        return ResponseEntity.ok(cartService.getMyCart());
    }

    /**
     * Adds a product, or increases its quantity if it is already there.
     *
     * <p>The request carries a product id and a quantity. It does <b>not</b> carry a price -
     * see {@code AddToCartRequest}'s javadoc. The price shown in the cart is read from the
     * product row at render time, so a customer who opens their cart tomorrow sees tomorrow's
     * price. The freeze happens at checkout.
     *
     * <p>Adding a product already in the cart increments the existing line rather than
     * inserting a second one. The database enforces this with
     * {@code UNIQUE (cart_id, product_id)}, so the alternative would be a constraint
     * violation surfacing as a 500 for a completely ordinary user action.
     *
     * <p>Stock is checked here, but the check is advisory: it exists so the customer learns
     * "only 3 left" immediately rather than at checkout. It cannot be a guarantee, because
     * nothing is locked while a cart sits idle. The authoritative check happens under a
     * pessimistic lock in {@code OrderServiceImpl.placeOrder}.
     */
    @PostMapping("/items")
    @Operation(summary = "Add a product to the cart",
            description = "Increments the existing line if the product is already in the cart. The price is never sent by the client.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated cart"),
            @ApiResponse(responseCode = "400", description = "Invalid quantity, or the line would exceed 99 units"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "No product with that id"),
            @ApiResponse(responseCode = "409", description = "The product is unavailable, or not enough stock")
    })
    public ResponseEntity<CartResponse> addItem(@Valid @RequestBody AddToCartRequest request) {
        return ResponseEntity.ok(cartService.addItem(request));
    }

    /**
     * Sets a line's quantity to an absolute value.
     *
     * <p>Absolute rather than a delta, which makes a quantity input naturally idempotent:
     * sending the same value twice leaves the same state. A delta-based endpoint would need
     * the client to know the current value, so two tabs open on one cart could send deltas
     * that cancel out or double up.
     *
     * <p>Removing a line is a separate {@code DELETE} rather than setting the quantity to
     * zero. They read differently to a user - "set to 0" is ambiguous about whether the row
     * survives - and they land in different places in an audit trail.
     */
    @PutMapping("/items/{itemId}")
    @Operation(summary = "Set a cart line's quantity",
            description = "An absolute value, not a delta. The item must belong to the caller's own cart.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated cart"),
            @ApiResponse(responseCode = "400", description = "Invalid quantity"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "No such item in this user's cart"),
            @ApiResponse(responseCode = "409", description = "The product is unavailable, or not enough stock")
    })
    public ResponseEntity<CartResponse> updateItem(@PathVariable Long itemId,
                                                  @Valid @RequestBody UpdateCartItemRequest request) {
        return ResponseEntity.ok(cartService.updateItem(itemId, request));
    }

    /**
     * Removes one line.
     *
     * <p>Returns the whole updated cart rather than a bare confirmation. A cart UI has to
     * re-render the subtotal, the item count and the empty state after every mutation, so
     * returning the new state saves a follow-up request on every click - and removes the
     * window in which the client's optimistic guess at the new total is wrong.
     */
    @DeleteMapping("/items/{itemId}")
    @Operation(summary = "Remove one line from the cart",
            description = "Returns the whole updated cart, so the client does not need to re-fetch after a mutation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated cart"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "No such item in this user's cart")
    })
    public ResponseEntity<CartResponse> removeItem(@PathVariable Long itemId) {
        return ResponseEntity.ok(cartService.removeItem(itemId));
    }

    /**
     * Empties the cart.
     *
     * <p>Returns the (now empty) cart rather than a message, for the same reason as the two
     * methods above: one consistent response shape for every cart mutation means the client
     * has one code path to maintain rather than two.
     */
    @DeleteMapping
    @Operation(summary = "Empty the cart",
            description = "Removes every line. Returns the resulting empty cart.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The emptied cart"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    public ResponseEntity<CartResponse> clear() {
        return ResponseEntity.ok(cartService.clear());
    }
}

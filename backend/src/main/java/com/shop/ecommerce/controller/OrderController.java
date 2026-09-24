package com.shop.ecommerce.controller;

import com.shop.ecommerce.dto.common.PageResponse;
import com.shop.ecommerce.dto.order.OrderFilter;
import com.shop.ecommerce.dto.order.OrderResponse;
import com.shop.ecommerce.dto.order.PlaceOrderRequest;
import com.shop.ecommerce.dto.order.UpdateOrderStatusRequest;
import com.shop.ecommerce.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Order endpoints.
 *
 * <h2>The public-shaped routes and the admin routes are deliberately separate</h2>
 *
 * <p>{@code GET /api/orders} returns <em>the caller's</em> orders. {@code GET
 * /api/admin/orders} returns <em>everyone's</em>. Both are lists of the same resource, and
 * merging them into one route with an optional {@code ?userId=} parameter - or an
 * "am I an admin?" branch inside - is how an authorization bug is born: the branch is one
 * {@code if} away from being wrong, and nothing about the route's shape warns a reader.
 *
 * <p>Two routes, two URL prefixes, two rules in the filter chain. {@code /api/admin/**} is
 * protected as a prefix, so an admin endpoint added here later and not annotated is still
 * refused.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Orders", description = "Place orders and track them. Admin routes manage fulfilment.")
public class OrderController {

    private final OrderService orderService;

    /**
     * Injected to validate {@link OrderFilter}, which is constructed by hand from individual
     * {@code @RequestParam}s rather than bound by Spring.
     *
     * <p>See the note on {@code ProductController}: a constraint on a record is inert unless
     * something evaluates it, and {@code new OrderFilter(...)} is not that thing. Both order
     * list endpoints share this helper so the two cannot drift apart.
     */
    private final Validator validator;

    public OrderController(OrderService orderService, Validator validator) {
        this.orderService = orderService;
        this.validator = validator;
    }

    private void validateFilter(OrderFilter filter) {
        Set<ConstraintViolation<OrderFilter>> violations = validator.validate(filter);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    // =================================================================
    //  Customer
    // =================================================================

    /**
     * Turns the caller's cart into an order.
     *
     * <h3>The request body has one optional field, and that is not an oversight</h3>
     *
     * <p>No items, no quantities, no prices, no total. Every one of those is read server-side:
     * the lines come from the cart, the prices are copied from the <b>locked</b> product rows
     * inside the transaction, and the total is summed from those snapshots. The only thing
     * the client chooses is where it wants the parcel delivered.
     *
     * <p>This is what makes the classic price-tampering attack - posting
     * {@code {"price": 1}} for a television - structurally impossible rather than merely
     * defended against. There is no price field to send.
     *
     * <h3>Why 201 and why the body matters</h3>
     *
     * <p>A resource was created. The body is the full order with its generated order number,
     * its snapshotted lines, and its {@code allowedNextStatuses} list - the last of which the
     * frontend uses to decide which buttons to render, so it does not need a second request
     * to know what is possible next.
     *
     * <h3>What this endpoint can fail with</h3>
     *
     * <p>400 if the cart is empty. 404 if a product in the cart has vanished. 409 if any line
     * lacks stock, naming the product and the quantity available. On any of these the whole
     * transaction rolls back: no order, no stock change, and the cart is left exactly as it
     * was so the customer can fix the one line that is a problem.
     */
    @PostMapping("/orders")
    @Operation(summary = "Place an order from the current cart",
            description = """
                    Converts the caller's cart into an order, atomically. Lines and prices come from the server -
                    the request body carries only an optional shipping address. Stock is validated under a
                    pessimistic lock, so two customers cannot buy the same last unit.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order placed",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "400", description = "The cart is empty"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "A product in the cart no longer exists"),
            @ApiResponse(responseCode = "409", description = "A line lacks stock, or a product is no longer available")
    })
    public ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        OrderResponse placed = orderService.placeOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(placed);
    }

    /**
     * The caller's own order history.
     *
     * <p>Paginated, always. The orders table is append-only and never shrinks, so an
     * unpaginated list would work perfectly in testing and fail once a customer has a
     * thousand orders.
     *
     * <p>The optional {@code status} filter is an enum, so an unknown value is rejected with
     * a 400 naming the allowed values rather than silently matching nothing - a client that
     * typo'd {@code SHIPPED} as {@code SHIPED} should be told, not shown an empty list it
     * interprets as "no orders shipped".
     */
    @GetMapping("/orders")
    @Operation(summary = "List the caller's own orders",
            description = "Paginated, newest first. Optional `status` filter. Never includes another customer's orders.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of the caller's orders"),
            @ApiResponse(responseCode = "400", description = "Invalid page, size, status or sort"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    public ResponseEntity<PageResponse<OrderResponse>> listMyOrders(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) com.shop.ecommerce.entity.OrderStatus status,
            @RequestParam(required = false) String sort) {

        OrderFilter filter = new OrderFilter(page, size, status, sort);
        validateFilter(filter);
        return ResponseEntity.ok(orderService.listMyOrders(filter));
    }

    /**
     * One of the caller's own orders.
     *
     * <p>An order belonging to somebody else returns <b>404, not 403</b>. A 403 would
     * confirm that the order exists, turning this endpoint into a way to discover how many
     * orders the shop has processed and which ids are real. The repository method filters by
     * user id in SQL, so the service genuinely cannot tell the two cases apart - there is no
     * branch that could be written to leak the difference.
     */
    @GetMapping("/orders/{orderId}")
    @Operation(summary = "Get one of the caller's own orders",
            description = "Returns 404 for an order that exists but belongs to another customer, so order ids cannot be enumerated.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The order"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "No such order for this user")
    })
    public ResponseEntity<OrderResponse> getMyOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.getMyOrder(orderId));
    }

    // =================================================================
    //  Admin
    // =================================================================

    /**
     * Every order in the store.
     *
     * <p>Same filter shape as the customer list but a different scope, and the scope comes
     * from the route rather than from a parameter. An admin who wants one customer's orders
     * can filter client-side or by status; a per-customer query parameter is deliberately
     * absent, because adding it would mean this admin endpoint accepts a user id - and the
     * same parameter on the customer endpoint would be an authorization decision handed to
     * the client.
     */
    @GetMapping("/admin/orders")
    @Operation(summary = "List every order (admin)",
            description = "Paginated, newest first. Optional `status` filter. Requires the ADMIN role.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of all orders"),
            @ApiResponse(responseCode = "400", description = "Invalid page, size, status or sort"),
            @ApiResponse(responseCode = "403", description = "Not an admin")
    })
    public ResponseEntity<PageResponse<OrderResponse>> listAllOrders(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) com.shop.ecommerce.entity.OrderStatus status,
            @RequestParam(required = false) String sort) {

        OrderFilter filter = new OrderFilter(page, size, status, sort);
        validateFilter(filter);
        return ResponseEntity.ok(orderService.listAllOrders(filter));
    }

    /**
     * Any order, by id.
     *
     * <p>Uses a different repository method from the customer endpoint - one without a user
     * filter. That is why the two cannot be merged behind a shared helper with a nullable
     * user id: a nullable filter inside a repository method is the exact shape in which an
     * ownership check becomes skippable, and it would be one null away from returning any
     * order to any caller.
     */
    @GetMapping("/admin/orders/{orderId}")
    @Operation(summary = "Get any order by id (admin)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The order"),
            @ApiResponse(responseCode = "403", description = "Not an admin"),
            @ApiResponse(responseCode = "404", description = "No order with that id")
    })
    public ResponseEntity<OrderResponse> getOrderById(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.getOrderById(orderId));
    }

    /**
     * Moves an order to a new status.
     *
     * <h3>Cancelling restores stock; nothing else touches inventory</h3>
     *
     * <p>Placing an order removed units from inventory. A cancelled order will never ship,
     * so those units must go back or they are lost permanently - unsellable and invisible.
     * Every other transition leaves stock alone: confirming and processing do not consume
     * more, and delivering does not return anything.
     *
     * <p>The restore re-locks the product rows before incrementing, for the same reason
     * placement locks before decrementing: a blind increment would be a lost update against
     * a concurrent stock edit.
     *
     * <h3>Why an illegal transition is a 409 and not a 400</h3>
     *
     * <p>The request is well formed - the status is real and the body is valid. What makes
     * it impossible is the order's <em>current</em> state. That is the definition of a
     * conflict, and it is actionable: re-read the order and try the move that is actually
     * available. The response's message names the allowed next statuses, so a client never
     * has to guess at the state machine.
     *
     * <p>{@code PATCH} rather than {@code PUT} because only one field changes. A PUT would
     * imply the body replaces the order, which would be alarming on a resource that holds a
     * frozen price snapshot.
     */
    @PatchMapping("/admin/orders/{orderId}/status")
    @Operation(summary = "Change an order's status (admin)",
            description = """
                    Enforces the order state machine: PENDING -> CONFIRMED -> PROCESSING -> SHIPPED -> DELIVERED,
                    with CANCELLED reachable from any non-terminal status. Cancelling restores the stock the order
                    reserved. An illegal move returns 409 and names the allowed next statuses.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated order"),
            @ApiResponse(responseCode = "400", description = "Missing or invalid status value"),
            @ApiResponse(responseCode = "403", description = "Not an admin"),
            @ApiResponse(responseCode = "404", description = "No order with that id"),
            @ApiResponse(responseCode = "409", description = "That transition is not allowed from the current status")
    })
    public ResponseEntity<OrderResponse> updateStatus(@PathVariable Long orderId,
                                                     @Valid @RequestBody UpdateOrderStatusRequest request) {
        return ResponseEntity.ok(orderService.updateStatus(orderId, request.status()));
    }
}

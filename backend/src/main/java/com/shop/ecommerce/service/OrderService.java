package com.shop.ecommerce.service;

import com.shop.ecommerce.dto.common.PageResponse;
import com.shop.ecommerce.dto.order.OrderFilter;
import com.shop.ecommerce.dto.order.OrderResponse;
import com.shop.ecommerce.dto.order.PlaceOrderRequest;
import com.shop.ecommerce.entity.OrderStatus;

/**
 * Order placement and management.
 */
public interface OrderService {

    /**
     * Converts the current user's cart into an order, atomically.
     *
     * <p>The whole operation - validate, snapshot prices, create the order and its lines,
     * decrement stock, clear the cart - happens in one transaction. Any failure rolls all of
     * it back, so there is no state in which stock has been reduced but no order exists, or
     * an order exists with lines that were never priced.
     *
     * @throws com.shop.ecommerce.exception.BadRequestException if the cart is empty
     * @throws com.shop.ecommerce.exception.ConflictException if any line lacks stock
     */
    OrderResponse placeOrder(PlaceOrderRequest request);

    /** One of the current user's own orders. */
    OrderResponse getMyOrder(Long orderId);

    /** The current user's order history. */
    PageResponse<OrderResponse> listMyOrders(OrderFilter filter);

    // -----------------------------------------------------------------
    //  Admin
    // -----------------------------------------------------------------

    /** Every order in the store. */
    PageResponse<OrderResponse> listAllOrders(OrderFilter filter);

    /** Any order, by id. */
    OrderResponse getOrderById(Long orderId);

    /**
     * Moves an order to a new status.
     *
     * <p>Validates the transition against the state machine in
     * {@link OrderStatus#canTransitionTo}. Cancelling restores the stock the order consumed;
     * no other transition touches inventory.
     */
    OrderResponse updateStatus(Long orderId, OrderStatus newStatus);
}

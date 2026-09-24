package com.shop.ecommerce.service;

import com.shop.ecommerce.dto.cart.AddToCartRequest;
import com.shop.ecommerce.dto.cart.CartResponse;
import com.shop.ecommerce.dto.cart.UpdateCartItemRequest;

/**
 * The customer's cart.
 *
 * <p><b>Every method takes the user id from the security context, not from a parameter.</b>
 * There is no {@code getCart(Long userId)} - a caller cannot ask for somebody else's cart at
 * all, which is a stronger guarantee than checking ownership after the fact.
 */
public interface CartService {

    /** The current user's cart, creating an empty one on first access. */
    CartResponse getMyCart();

    CartResponse addItem(AddToCartRequest request);

    CartResponse updateItem(Long itemId, UpdateCartItemRequest request);

    CartResponse removeItem(Long itemId);

    CartResponse clear();
}

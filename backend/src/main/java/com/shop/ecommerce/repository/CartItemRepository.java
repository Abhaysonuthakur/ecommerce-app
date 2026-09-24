package com.shop.ecommerce.repository;

import com.shop.ecommerce.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Data access for {@link CartItem}.
 *
 * <p>Most cart-line manipulation happens through {@link com.shop.ecommerce.entity.Cart}'s
 * collection, which with {@code orphanRemoval = true} handles insertion and deletion
 * implicitly. This repository exists for the cases where addressing a line directly is
 * clearer or necessary.
 */
@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /** Finds a specific line, scoped to its cart so one customer cannot read another's. */
    Optional<CartItem> findByIdAndCartId(Long id, Long cartId);

    /** Finds the existing line for a product, or empty if it is not in the cart yet. */
    Optional<CartItem> findByCartIdAndProductId(Long cartId, Long productId);

    long countByCartId(Long cartId);

    /**
     * Empties a cart in a single statement.
     *
     * <p><b>Why the two {@code @Modifying} flags are both required.</b>
     *
     * <p>A bulk {@code DELETE} is a plain SQL statement. It does <em>not</em> go through the
     * persistence context, so Hibernate has no idea it happened.
     *
     * <ul>
     *   <li>{@code flushAutomatically = true} - writes any pending changes to the database
     *       <em>before</em> the delete runs. Without it, a line just added in this
     *       transaction has not been inserted yet, so the delete misses it and the cart is
     *       left holding one item.</li>
     *   <li>{@code clearAutomatically = true} - empties the persistence context
     *       <em>after</em> the delete. Without it, the in-memory {@code Cart} still holds
     *       its {@code items} list, and any code that then reads {@code cart.getItems()}
     *       gets the deleted items back from the cache - a cart that looks non-empty while
     *       the database says otherwise.</li>
     * </ul>
     *
     * <p>The side effect is that entities the caller was holding become detached. That is
     * acceptable here because the caller re-reads the cart afterwards to build its response.
     *
     * <p>Order placement does <b>not</b> use this method - it clears through the
     * collection so the cascade and the object graph stay consistent within the
     * transaction. This exists for the standalone "clear my cart" action.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CartItem ci where ci.cart.id = :cartId")
    int deleteAllByCartId(@Param("cartId") Long cartId);

    /**
     * Removes any cart lines that reference a product, across all carts.
     *
     * <p>Used before hard-deleting a product. A product in somebody's cart is not a
     * constraint violation (the foreign key cascades), but leaving a line that points at a
     * product which no longer exists would break the cart page.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CartItem ci where ci.product.id = :productId")
    int deleteAllByProductId(@Param("productId") Long productId);
}

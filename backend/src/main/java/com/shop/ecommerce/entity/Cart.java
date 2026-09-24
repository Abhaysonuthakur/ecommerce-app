package com.shop.ecommerce.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A customer's working basket.
 *
 * <h2>Relationships</h2>
 *
 * <pre>
 *   User 1 ──────── 1 Cart           (this is the inverse side; see #user)
 *   Cart 1 ──────── N CartItem       (this is the owning side's inverse; see #items)
 * </pre>
 *
 * <p>A cart is <b>not</b> a historical record - it is transient state that exists to be
 * converted into an order and then emptied. That is why nothing here is snapshotted:
 * a cart line shows the product's <em>current</em> price, and if the price changes while
 * something sits in a basket, the customer should see the new price. The order is where
 * values freeze.
 */
@Entity
@Table(
        name = "carts",
        uniqueConstraints = @UniqueConstraint(name = "uk_carts_user", columnNames = "user_id")
)
@Getter
@Setter
@NoArgsConstructor
@ToString(onlyExplicitlyIncluded = true, callSuper = true)
public class Cart extends BaseEntity {

    // =================================================================
    //  Cart N:1 User   (owning side of User 1:1 Cart)
    // =================================================================
    /**
     * <b>This is the owning side of the {@code User 1:1 Cart} relationship.</b>
     *
     * <p>The {@code carts} table holds {@code user_id} - and only this side can declare
     * the {@code @JoinColumn}. {@link User#getCart()} is the inverse side and carries
     * {@code mappedBy = "user"}.
     *
     * <p>{@code @OneToOne} rather than {@code @ManyToOne}, because the column has a
     * {@code UNIQUE} index: one user, one cart. Using {@code @ManyToOne} would tell
     * Hibernate (and every reader) that a user may have several, which contradicts the
     * schema and would let a service bug create a second cart without complaint.
     *
     * <p>{@code optional = false} makes the column NOT NULL, so a cart without an owner
     * is rejected at both the object level and the database level.
     *
     * <p>{@code fetch = LAZY}: the only time a cart needs its owner is when checking
     * ownership, and the security context already gives us the user's id for that.
     * Fetching the whole {@code User} row - password hash included - to answer "does
     * this cart belong to me" would be paying for data we must not use.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_carts_user"))
    private User user;

    // =================================================================
    //  Cart 1:N CartItem   (inverse side)
    // =================================================================
    /**
     * <b>One cart holds many cart items.</b>
     *
     * <p>{@code mappedBy = "cart"} - {@link CartItem} owns the relationship because
     * {@code cart_items} is the table with the {@code cart_id} column.
     *
     * <p><b>{@code cascade = ALL} + {@code orphanRemoval = true} is the important part
     * here.</b> It means the cart's lines are a composition: they have no independent
     * existence. Clearing the cart and saving it is sufficient - every line is deleted.
     * Without {@code orphanRemoval}, removing a line from this list would only set
     * {@code cart_id} to null, and the line would survive as an orphan, still counting
     * toward inventory in reports while belonging to no cart.
     *
     * <p>{@code LAZY}, explicitly, and for a specific reason: the order-placement
     * service is the one caller that needs the items, and it knows that - it calls a
     * repository method with a fetch join. Loading them eagerly would make every
     * "does this user have a cart" check load every line.
     */
    @OneToMany(mappedBy = "cart", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();

    // =================================================================
    //  Computed values
    // =================================================================

    /**
     * The sum of every line's subtotal.
     *
     * <p><b>Computed, never stored.</b> A stored subtotal on the cart would be a
     * denormalised value that can go stale - the classic failure being a cart total
     * that disagrees with the sum of the lines shown beside it. There is nothing to
     * cache here: this runs over a handful of in-memory objects.
     *
     * <p>Note the {@code BigDecimal.ZERO} seed rather than starting from the first
     * element: a stream reduction over an empty list would otherwise return null, and
     * an empty cart is the most common case of all.
     */
    public BigDecimal getSubtotal() {
        return items.stream()
                .map(CartItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Total number of units, for the navbar badge. */
    public int getTotalQuantity() {
        return items.stream().mapToInt(CartItem::getQuantity).sum();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int getItemCount() {
        return items.size();
    }

    // =================================================================
    //  Collection helpers
    // =================================================================

    /**
     * Finds the line for a product, if the cart already contains it.
     *
     * <p>Why this matters: the database has {@code UNIQUE (cart_id, product_id)}.
     * Adding a product that is already in the cart must therefore <em>increase the
     * quantity of the existing row</em>, never insert a second one - which would be
     * rejected by the database, turning a normal user action into a 500.
     */
    public Optional<CartItem> findItemForProduct(Long productId) {
        return items.stream()
                .filter(item -> item.getProduct() != null
                        && productId.equals(item.getProduct().getId()))
                .findFirst();
    }

    /**
     * Adds a line, or increases the quantity if the product is already present.
     *
     * <p>Keeping both sides consistent in memory (adding the item <em>and</em> setting
     * its back-reference) is what makes the change visible to the persistence context
     * without a re-read. A collection that is managed whose elements do not point back
     * is a half-built object graph, and Hibernate will insert the row with a null
     * foreign key.
     */
    public CartItem addItem(Product product, int quantity) {
        CartItem existing = findItemForProduct(product.getId()).orElse(null);

        if (existing != null) {
            existing.setQuantity(existing.getQuantity() + quantity);
            return existing;
        }

        CartItem item = new CartItem();
        item.setCart(this);
        item.setProduct(product);
        item.setQuantity(quantity);
        items.add(item);
        return item;
    }

    /**
     * Removes a line.
     *
     * <p>Because the collection has {@code orphanRemoval = true}, removing it from the
     * list is the whole operation - Hibernate issues the DELETE. There is no need to
     * also call a repository's delete method, and doing both can produce a confusing
     * double-delete or a no-op depending on flush order.
     */
    public boolean removeItem(CartItem item) {
        return items.remove(item);
    }

    /** Empties the cart. With {@code orphanRemoval}, this deletes every line. */
    public void clearItems() {
        items.clear();
    }

    /**
     * Total units across every line. Used by the order service to decide whether
     * stock can satisfy the whole basket at once - a check that must happen before
     * any decrement, not during.
     */
    public int getTotalItemQuantity() {
        return getTotalQuantity();
    }
}

package com.shop.ecommerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Something for sale.
 *
 * <h2>Relationship owned by this entity</h2>
 *
 * <pre>
 *   Product N ──────── 1 Category     (many products in one category)
 * </pre>
 *
 * <p>This is the <b>owning side</b> of the {@code Category 1:N Product} relationship:
 * the {@code products} table holds {@code category_id}, and this class declares
 * {@code @JoinColumn} to name that column. Contrast with {@link Category#getProducts()},
 * which is the inverse side and can only declare {@code mappedBy}.
 *
 * <p>{@code Product} is also the {@code 1} side of two further relationships that are
 * declared from the other end: {@code Product 1:N CartItem} and
 * {@code Product 1:N OrderItem}. Neither is mapped as a collection here, and that is a
 * deliberate choice explained in {@link CartItem} and {@link OrderItem}: a product does
 * not need to know who has it in a cart, and mapping it would mean loading thousands of
 * rows to answer a question nobody asks.
 */
@Entity
@Table(
        name = "products",
        indexes = {
                @Index(name = "idx_products_category", columnList = "category_id"),
                @Index(name = "idx_products_active", columnList = "active"),
                @Index(name = "idx_products_active_cat", columnList = "active,category_id"),
                @Index(name = "idx_products_name", columnList = "name"),
                @Index(name = "idx_products_price", columnList = "price")
        }
)
@Getter
@Setter
@NoArgsConstructor
@ToString(onlyExplicitlyIncluded = true, callSuper = true)
public class Product extends BaseEntity {

    @ToString.Include
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    /**
     * The price in INR.
     *
     * <p><b>WHY {@link BigDecimal} and not {@code double}?</b>
     * A {@code double} cannot represent 0.10 exactly - it stores the nearest binary
     * fraction. Sum a thousand ₹0.10 items and you get ₹99.99999999999999, and a total
     * that disagrees with the sum of its lines by a paisa is a bug you cannot explain
     * to an accountant. {@code BigDecimal} is exact decimal arithmetic, and the column
     * is {@code DECIMAL(19,2)} to match.
     *
     * <p>Always compare with {@code compareTo(...) == 0}, never {@code equals(...)}:
     * {@code equals} also compares scale, so {@code 10.0} and {@code 10.00} are
     * <em>not</em> equal even though they are the same amount of money.
     *
     * <p>This value is never taken from a request when placing an order. The order
     * line snapshots whatever is in this column at that moment - see
     * {@link OrderItem#getUnitPrice()}.
     */
    @ToString.Include
    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    /**
     * Units available to sell.
     *
     * <p>The database column is {@code INT UNSIGNED} with a {@code CHECK (stock >= 0)},
     * so a negative stock is rejected by MySQL even if the service layer made a mistake.
     * The Java side enforces it too, at the boundary: "do not sell more than you have"
     * is a business rule first and a constraint second.
     */
    @ToString.Include
    @Column(name = "stock", nullable = false)
    private Integer stock = 0;

    /**
     * A URL, not a file. Uploading is deliberately out of scope - see
     * docs/01-REQUIREMENTS.md. 500 characters because it will hold a CDN URL with
     * transformation parameters, and those get long.
     */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /**
     * False means withdrawn from sale.
     *
     * <p>{@code active = false} rather than a row deletion, because
     * {@code order_items.product_id} has {@code ON DELETE RESTRICT}: a product that has
     * ever been sold cannot be deleted at all, and the API refuses it with a clear
     * message rather than letting the database raise a foreign-key error.
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    // =================================================================
    //  Product N:1 Category   (owning side)
    // =================================================================
    /**
     * <b>Many products belong to one category.</b>
     *
     * <ul>
     *   <li>{@code @ManyToOne} - the default fetch is EAGER, which is correct here:
     *       almost every product response shows the category name, so loading it with
     *       the product turns N+1 queries into one join. Leaving a {@code @ManyToOne}
     *       lazy is the more common mistake, and it produces a query per row.</li>
     *   <li>{@code @JoinColumn(name = "category_id", nullable = false)} - names the
     *       foreign-key column and forbids a product without a category. Without
     *       {@code nullable = false}, Hibernate generates a nullable column and an
     *       uncategorised product becomes possible.</li>
     * </ul>
     *
     * <p>Not marked {@code orphanRemoval}: removing a product from a category's list
     * must not delete the product, only reclassify it.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "category_id", nullable = false, foreignKey = @jakarta.persistence.ForeignKey(name = "fk_products_category"))
    private Category category;

    // =================================================================
    //  Business helpers
    // =================================================================

    /**
     * Whether this many units could be sold right now.
     *
     * <p>Lives on the entity because "what does available mean" is a property of a
     * product, not of any particular service. It is checked in three places - adding to
     * a cart, updating a cart line, and placing an order - and all three must agree.
     */
    public boolean hasStockFor(int quantity) {
        return active && stock != null && stock >= quantity;
    }

    /**
     * Reduces stock by {@code quantity}.
     *
     * <p>Throws rather than clamping, because a silent clamp hides the bug: the order
     * would be placed and the inventory would be wrong. Throwing rolls the transaction
     * back and the customer is told.
     *
     * <p>Called only from inside the order-placement transaction, where the row has
     * already been locked with {@code SELECT ... FOR UPDATE} - so no other thread can
     * interleave between this check and the write.
     */
    public void reduceStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity to reduce must be positive, got: " + quantity);
        }
        if (stock == null || stock < quantity) {
            throw new IllegalStateException(
                    "Insufficient stock for product '%s': requested %d, available %d"
                            .formatted(name, quantity, stock));
        }
        this.stock -= quantity;
    }

    /** Puts stock back. Used when an order is cancelled. */
    public void restoreStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity to restore must be positive, got: " + quantity);
        }
        this.stock = (stock == null ? 0 : stock) + quantity;
    }

    /** Null-safe price, so callers never have to guard against a null BigDecimal. */
    public BigDecimal effectivePrice() {
        return price == null ? BigDecimal.ZERO : price;
    }
}

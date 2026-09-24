package com.shop.ecommerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * One line in a cart: "this product, this many".
 *
 * <h2>Relationships owned by this entity</h2>
 *
 * <pre>
 *   CartItem N ──────── 1 Cart       (many lines in one cart)
 *   CartItem N ──────── 1 Product    (many lines may reference one product)
 * </pre>
 *
 * <p>Both are owning-side {@code @ManyToOne} declarations, because {@code cart_items}
 * is the table holding both {@code cart_id} and {@code product_id}.
 *
 * <p><b>WHY is there no {@code @OneToMany List<CartItem>} on {@link Product}?</b>
 * Because nothing ever asks "which carts contain this product". The query has no
 * business meaning, and mapping it would give every product a lazily-loaded collection
 * that someone eventually triggers - loading thousands of rows to answer a question
 * nobody asked. A relationship only earns a mapping when a real use case needs it.
 */
@Entity
@Table(
        name = "cart_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cart_items_cart_product",
                // One line per product per cart. Adding a product twice raises the
                // quantity of this row instead of inserting a second one, and the
                // database enforces it even if the service logic has a bug.
                columnNames = {"cart_id", "product_id"}
        )
)
// No class-level @Setter on purpose: setQuantity() below is hand-written with a
// guard, and a Lombok-generated setter of the same name would not compile against it.
// The other three fields are assigned once, at construction, by Cart.addItem().
@Getter
@NoArgsConstructor
@ToString(onlyExplicitlyIncluded = true, callSuper = true)
public class CartItem extends BaseEntity {

    // =================================================================
    //  CartItem N:1 Cart
    // =================================================================
    /**
     * <b>Many lines belong to one cart.</b>
     *
     * <p>{@code optional = false} makes {@code cart_id} NOT NULL: a line with no cart
     * is not a thing. This is the owning side of {@link Cart#getItems()}.
     *
     * <p>{@code LAZY} - there is no reason for a cart line to drag its cart along.
     * The cart reference exists so that Hibernate can write the foreign key, and the
     * cart-specific behaviour lives on {@code Cart}.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_cart_items_cart"))
    private Cart cart;

    // =================================================================
    //  CartItem N:1 Product
    // =================================================================
    /**
     * <b>Many cart lines may reference one product.</b>
     *
     * <h2>This is LAZY, and the reason is not performance</h2>
     *
     * <p>It used to be {@code EAGER}, argued on the grounds that every cart operation needs the
     * product - its name, image, price and stock - so eager fetching saves a query per line.
     * That argument is true about <em>reads</em> and it is not the whole story, because the
     * fetch type is not scoped to the operation. It applies to <b>every</b> query that returns
     * a {@code CartItem}, including the one order placement uses to discover <em>which</em>
     * products are in the cart.
     *
     * <h3>Why that broke order placement</h3>
     *
     * <p>Order placement must take a pessimistic lock on the products before reading them.
     * MySQL's REPEATABLE READ pins the transaction's read view at its first <em>plain</em>
     * read, and a {@code SELECT ... FOR UPDATE} only bypasses that view while none exists -
     * after a plain read it still locks and still waits, and returns the pre-lock values.
     *
     * <p>With {@code EAGER} here, Hibernate adds its own join to satisfy the association even
     * when the query does not mention {@code product}. Measured from the general log, for a
     * query that only asked for cart lines:
     *
     * <pre>
     *   select i1_0.cart_id, i1_0.id, i1_0.created_at, i1_0.product_id,
     *          p1_0.id, p1_0.active, ... p1_0.stock, ...
     *   from cart_items i1_0
     *   left join products p1_0 on ...
     * </pre>
     *
     * <p>The product row is read regardless of what the query asked for, so the lock that
     * follows it is worthless: two customers can both read {@code stock=1}, both take the lock
     * in turn, and both write {@code stock=0}. Two orders, one unit, no error - because the
     * write is an absolute value and both wrote the same one.
     *
     * <h3>What makes LAZY safe here</h3>
     *
     * <p>Nothing needs the association implicitly. The cart page renders products, and it goes
     * through {@code CartRepository#findByUserIdWithItems}, which fetch-joins {@code i.product}
     * explicitly - so it still takes one query, not N+1, and it still works with
     * {@code open-in-view: false} because the join happens inside the transaction.
     *
     * <p>Order placement goes through {@code findByUserIdWithItemsOnly}, which does not join,
     * and reads {@link #productId} off the foreign key column. The locking query is then the
     * first statement to touch the products, which is the entire point.
     *
     * <p>The general rule this is an instance of: a fetch type is a property of the
     * <em>association as seen by every path</em>, not of the path you have in mind when you
     * set it. When one path must not read a row and another must, the mapping has to be LAZY
     * and the reading path opts in with a fetch join.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_cart_items_product"))
    private Product product;

    /**
     * The {@code product_id} foreign key, readable <b>without loading the product</b>.
     *
     * <h2>Why this duplicate mapping exists</h2>
     *
     * <p>In JPA, {@code @ManyToOne} plus {@code @Column} on the same column is allowed when the
     * column mapping is explicitly <b>insertable = false, updatable = false</b>: the association
     * remains the one that writes the value, and this field is a read-only window onto it.
     *
     * <p>The reason to want that window is specific and easy to lose. Order placement must take
     * a pessimistic lock on the products before it reads them - if a plain read happens first it
     * pins the transaction's REPEATABLE READ view, and the later {@code FOR UPDATE} then locks
     * and waits correctly but returns the pre-lock stock value. See
     * {@code CartRepository#findByUserIdWithItemsOnly} for the measurement.
     *
     * <p>So the service has to learn <em>which</em> product ids a cart references without
     * reading the products. Reading {@code getProduct().getId()} would not do it: the
     * association is EAGER, so touching the entity loads a full row per line - exactly the read
     * that must not happen yet. This field gives the id straight off the cart line.
     *
     * <p>It is deliberately not {@code @Transient}: the value lives in the database and should
     * be populated when a line is loaded, so a cart line always knows its own product id
     * whether the association was initialised or not.
     */
    @Column(name = "product_id", insertable = false, updatable = false)
    private Long productId;

    /**
     * How many units.
     *
     * <p>Always positive. The database enforces {@code CHECK (quantity > 0)} and the
     * service layer refuses a non-positive value at the API boundary with a clear 400.
     * Both, because a cart line reading "Linen Shirt × 0" is not a state any customer
     * can explain, and a negative quantity would <em>add</em> stock at checkout.
     */
    @ToString.Include
    @Column(name = "quantity", nullable = false)
    private Integer quantity = 1;

    // -----------------------------------------------------------------
    //  Setters for the two associations.
    //
    //  Hand-written rather than Lombok-generated so the package can stay
    //  free of a class-level @Setter (which would clash with the guarded
    //  setQuantity below) while still letting Cart.addItem() build a line.
    //  Making them `public` rather than package-private keeps Cart,
    //  CartItem and CartService in different packages able to use them.
    // -----------------------------------------------------------------

    public void setCart(Cart cart) {
        this.cart = cart;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    // =================================================================
    //  Computed values
    // =================================================================

    /**
     * Line total: unit price x quantity.
     *
     * <p>Uses {@code multiply}, which for {@link BigDecimal} keeps full precision
     * (the scale becomes the sum of the operands' scales), then sets the scale to 2
     * with {@code HALF_UP} - the rounding a shop uses, and the one the
     * {@code DECIMAL(19,2)} column expects.
     *
     * <p><b>Computed from the live product price, not a snapshot.</b> A cart shows
     * what you would pay today. The snapshot belongs on {@link OrderItem}, and putting
     * it here too would create two sources of truth for the same number.
     */
    public BigDecimal getSubtotal() {
        if (product == null) {
            return BigDecimal.ZERO;
        }
        return product.effectivePrice()
                .multiply(BigDecimal.valueOf(quantity))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Whether this line can still be fulfilled.
     *
     * <p>Asked on every cart read so the UI can flag a line whose product went out of
     * stock while it sat in someone's basket - which is exactly when a customer needs
     * to be told, rather than at checkout.
     */
    public boolean isFulfillable() {
        return product != null && product.hasStockFor(quantity);
    }

    /**
     * How many units are available for this line.
     *
     * <p>Used by the "only 3 left" message and to cap the quantity stepper in the UI.
     * Reading through to the product keeps one source of truth for stock.
     */
    public int getAvailableStock() {
        return product == null || product.getStock() == null ? 0 : product.getStock();
    }

    /**
     * Replaces the quantity.
     *
     * <p>A setter with a guard rather than a plain setter, because a non-positive
     * quantity is not a value this entity considers valid - and finding out at
     * checkout, from a database CHECK violation, is far too late to tell the customer
     * which line was wrong.
     */
    public void setQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Cart item quantity must be positive, got: " + quantity);
        }
        this.quantity = quantity;
    }
}

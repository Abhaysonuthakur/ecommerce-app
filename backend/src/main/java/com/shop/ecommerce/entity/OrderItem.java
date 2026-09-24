package com.shop.ecommerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One line of a placed order - <b>the table that makes order history correct.</b>
 *
 * <h2>Relationships owned by this entity</h2>
 *
 * <pre>
 *   OrderItem N ──────── 1 Order     (many lines in one order)
 *   OrderItem N ──────── 1 Product   (a product appears in many order lines)
 * </pre>
 *
 * <p>Both are owning-side {@code @ManyToOne} declarations; {@code order_items} holds
 * both foreign keys. As with {@code CartItem}, there is intentionally no
 * {@code List<OrderItem>} on {@link Product} - nothing asks "which orders contain this
 * product" as a routine query, and mapping it would create an expensive lazy collection
 * for no benefit.
 *
 * <h2>The price snapshot - the point of this class</h2>
 *
 * <pre>
 *   Product current price = ₹1500        order placed
 *   OrderItem.price       = ₹1500        &lt;-- frozen, forever
 *
 *   Product price changed to ₹1800       some time later
 *   OrderItem.price       = ₹1500        &lt;-- still correct
 * </pre>
 *
 * <p>If this table stored only {@code product_id} and read the price by joining to
 * {@code products}, then changing a product's price would retroactively rewrite the
 * value of every order that ever contained it. Last year's revenue would change when
 * someone edits an unrelated field today, and no amount of application logic can
 * recover the original number once it is gone.
 *
 * <p><b>This is why the order service never accepts a price from the client.</b> It
 * copies the price from the product row inside the transaction. See
 * {@code OrderServiceImpl.placeOrder}.
 */
@Entity
@Table(name = "order_items")
// No class-level @Setter: setQuantity() below is hand-written so it can keep
// `subtotal` in step with `unitPrice * quantity` on every change. A Lombok setter
// of the same name would not compile against it - and, worse, a plain generated
// setter would let the two fields drift, which is the exact bug the pairing exists
// to prevent.
@Getter
@NoArgsConstructor
@ToString(onlyExplicitlyIncluded = true, callSuper = true)
public class OrderItem extends BaseEntity {

    // =================================================================
    //  OrderItem N:1 Order
    // =================================================================
    /**
     * <b>Many lines belong to one order.</b>
     *
     * <p>{@code LAZY} deliberately, on both sides of the association. The {@link Order}
     * side also declares {@code LAZY} on its {@code items} collection, and a bidirectional
     * pair where BOTH sides are eager is a genuine hazard: loading one line would load its
     * order, which would load all its sibling lines, which would load their orders. That is
     * the classic circular-fetch blow-up, and it surfaces as a query storm rather than an
     * error.
     *
     * <p>The back-reference is never navigated from a line in this application - a line is
     * always reached through its order, never the reverse - so lazy costs nothing here and
     * removes the cycle. Note that this is a different question from {@code product} below,
     * which IS traversed and is therefore eager.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_order_items_order"))
    private Order order;

    // =================================================================
    //  OrderItem N:1 Product
    // =================================================================
    /**
     * <b>A product appears in many order lines.</b>
     *
     * <p>{@code EAGER}, matching {@link CartItem#getProduct()}: rendering an order line
     * shows the product's image and a link to it, so the product is always needed.
     *
     * <p><b>Important:</b> the product reference is for <em>display and linkage only</em>.
     * No amount of it is used to compute a monetary value on this line - those come from
     * the snapshots below. If this reference were ever null (it cannot be, thanks to
     * {@code ON DELETE RESTRICT}), the line's price would still be correct.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "product_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_order_items_product"))
    private Product product;

    /**
     * The {@code product_id} foreign key, readable <b>without loading the product</b>.
     *
     * <p>The same read-only window as {@code CartItem#productId}, and for the same reason:
     * cancelling an order has to know which products to re-lock and must not read those
     * product rows before it does. A plain read of a product first would pin the
     * transaction's REPEATABLE READ view, and the {@code FOR UPDATE} that follows would then
     * lock and wait correctly and still return the pre-lock stock value - so a concurrent
     * admin stock edit would be silently overwritten.
     *
     * <p>See {@code CartRepository#findByUserIdWithItemsOnly} for the measurement and
     * {@code OrderServiceImpl#updateStatus} for the restore path that uses this.
     */
    @Column(name = "product_id", insertable = false, updatable = false)
    private Long productId;

    /**
     * The product's name, copied at purchase time.
     *
     * <p><b>WHY snapshot a name we can already reach through {@link #product}?</b>
     * Two reasons, and the second is the one that matters:
     * <ol>
     *   <li>A product renamed from "Linen Shirt" to "Linen Shirt (Discontinued)" would
     *       change what an old invoice says.</li>
     *   <li>{@code ON DELETE RESTRICT} stops a sold product being deleted, so today the
     *       join always works. But if a future requirement relaxes that - or someone
     *       runs a manual DELETE - the historic orders become unreadable. A receipt is
     *       a legal document; it should not depend on a row elsewhere surviving.</li>
     * </ol>
     */
    @ToString.Include
    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    // =================================================================
    //  The price snapshot
    // =================================================================
    /**
     * The price of <b>one unit</b> at the moment this order was placed.
     *
     * <p>Read the class javadoc: this is the single most important field in the schema.
     * It is written once from {@code product.price} inside the placement transaction,
     * and never updated afterwards. There is no setter call anywhere in the service
     * layer that modifies it after creation - and a test asserts that changing a
     * product's price leaves this value untouched.
     *
     * <p>{@code BigDecimal}/{@code DECIMAL(19,2)}, like every other money field.
     */
    @ToString.Include
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    /** Units bought. Positive; the database has {@code CHECK (quantity > 0)}. */
    @ToString.Include
    @Column(name = "quantity", nullable = false)
    private Integer quantity = 1;

    /**
     * {@code unitPrice * quantity}, stored rather than computed.
     *
     * <p>Stored because the alternative is that every report sums this on the fly, and a
     * report that recomputes a historic amount is a report that can disagree with the
     * invoice it is describing. The value is written once by
     * {@link #setQuantity(Integer)}, so the two can never drift.
     */
    @Column(name = "subtotal", nullable = false, precision = 19, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    // -----------------------------------------------------------------
    //  Setters for the snapshot fields.
    //
    //  Hand-written rather than Lombok-generated for the same reason as
    //  CartItem: a class-level @Setter would collide with the guarded
    //  setQuantity below.
    //
    //  These are deliberately NOT public API for changing an order. They
    //  exist so createSnapshot() and the service layer can populate a new
    //  line; nothing calls them on a line that has already been persisted.
    //  There is no update path for unitPrice anywhere in the service layer,
    //  which is what makes the price snapshot a snapshot.
    // -----------------------------------------------------------------

    public void setOrder(Order order) {
        this.order = order;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    // =================================================================
    //  Construction
    // =================================================================

    /**
     * Builds a line and freezes the values.
     *
     * <p>A static factory rather than a constructor call plus three setters, because
     * the snapshot must be taken atomically from a consistent product row: a caller that
     * forgot to set {@code productName} would produce an order line with a blank
     * description, and nothing would report it. Here it is impossible to build an
     * incomplete line.
     *
     * <p>The price is read from {@code product.effectivePrice()} - the database value -
     * and <b>never</b> from the request. That is the whole defence against a client
     * posting {@code {"price": 1}} for a ₹50,000 television.
     */
    public static OrderItem createSnapshot(Product product, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Order item quantity must be positive, got: " + quantity);
        }

        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setProductName(product.getName());
        item.setUnitPrice(product.effectivePrice());
        item.setQuantity(quantity);
        return item;
    }

    /**
     * Sets the quantity and recomputes the subtotal.
     *
     * <p>The recomputation is inside the setter so the two fields cannot be updated
     * independently - there is no code path that changes one without the other, and
     * therefore no way for {@code subtotal} to disagree with {@code unitPrice × quantity}.
     */
    public void setQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Order item quantity must be positive, got: " + quantity);
        }
        this.quantity = quantity;
        this.subtotal = unitPrice
                .multiply(BigDecimal.valueOf(quantity))
                .setScale(2, RoundingMode.HALF_UP);
    }
}

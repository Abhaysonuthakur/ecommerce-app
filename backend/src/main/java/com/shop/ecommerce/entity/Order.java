package com.shop.ecommerce.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A placed order. <b>A historical record, not a working object.</b>
 *
 * <h2>Relationship owned by this entity</h2>
 *
 * <pre>
 *   Order N ──────── 1 User         (many orders placed by one customer)
 *   Order 1 ──────── N OrderItem    (an order contains many lines)
 * </pre>
 *
 * <p>This class holds {@code user_id}, so it is the owning side of
 * {@code User 1:N Order} - hence {@link User#getOrders()} is the inverse and carries
 * {@code mappedBy = "user"}.
 *
 * <h2>What makes an order different from a cart</h2>
 *
 * <table>
 *   <tr><th></th><th>Cart</th><th>Order</th></tr>
 *   <tr><td>Lifetime</td><td>Transient, emptyable</td><td>Permanent</td></tr>
 *   <tr><td>Prices</td><td>Live, from the product</td><td>Frozen, snapshotted</td></tr>
 *   <tr><td>Stock</td><td>Not reserved</td><td>Reserved at placement</td></tr>
 *   <tr><td>Mutable</td><td>Yes, freely</td><td>Only the status, and only onwards</td></tr>
 * </table>
 *
 * <p>Every design choice below follows from the right-hand column.
 */
@Entity
@Table(
        name = "orders",
        indexes = {
                @Index(name = "idx_orders_user", columnList = "user_id"),
                @Index(name = "idx_orders_status", columnList = "status"),
                @Index(name = "idx_orders_created", columnList = "created_at"),
                @Index(name = "idx_orders_user_created", columnList = "user_id,created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@ToString(onlyExplicitlyIncluded = true, callSuper = true)
public class Order extends BaseEntity {

    // =================================================================
    //  Order N:1 User   (owning side)
    // =================================================================
    /**
     * <b>Many orders belong to one customer.</b>
     *
     * <p>{@code optional = false} - an order without a customer is not a thing.
     *
     * <p>{@code LAZY} here, unlike {@link OrderItem#getProduct()}. The difference is
     * which direction the relationship is read:
     * <ul>
     *   <li>An order line's product is read on real paths - the cart page renders it, and
     *       {@code cancelOrder} groups lines by {@code item.getProduct().getId()} to
     *       restore stock - so it is {@code EAGER}. See the note on that field.</li>
     *   <li>Rendering an order <em>never</em> needs the customer's full profile, and in
     *       the common case we already know who is asking because they are authenticated.
     *       An admin viewing all orders shows the customer's name via a fetch join in
     *       the query, not by loading every user row one at a time.</li>
     * </ul>
     *
     * <p>Note the distinction this makes, because "eager for to-one, lazy for collections"
     * is a useful default and not a rule: the test here is whether the association is
     * actually traversed on a path that runs. {@code Order.user} is not, so it is lazy.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_orders_user"))
    private User user;

    /**
     * Where the order is in its lifecycle.
     *
     * <p>Stored as a string; the legal transitions live in {@link OrderStatus} rather
     * than in this class, so the rule has one home and can be tested without building
     * an order. The service calls {@code status.canTransitionTo(target)} before writing.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    /**
     * What the customer owed.
     *
     * <p><b>Stored, not recomputed.</b> This is the same reasoning as
     * {@link OrderItem#getUnitPrice()} one level up: if this were derived by summing the
     * lines on every read, a later change to an order line (or a bug in the sum) would
     * silently rewrite history. A receipt shows what was charged on the day.
     *
     * <p>It is written once, inside the placement transaction, from the sum of the lines
     * as they were created - so it cannot disagree with them at the moment it is written.
     */
    @ToString.Include
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /**
     * The delivery address, <b>copied</b> from the customer's profile at placement time.
     *
     * <p><b>WHY copied rather than referenced?</b> If it were read from
     * {@code users.address} on every render, a customer moving house would silently
     * change where every past order claims it was shipped. Denormalising here is not a
     * compromise - it is the only correct model for an immutable record.
     *
     * <p>Nullable because a customer may not have filled in a profile address, and
     * refusing the order for that reason would be a worse experience than accepting it
     * and letting the admin follow up.
     */
    @Column(name = "shipping_address", length = 255)
    private String shippingAddress;

    // =================================================================
    //  Order 1:N OrderItem   (inverse side)
    // =================================================================
    /**
     * <b>One order contains many lines.</b>
     *
     * <p>{@code mappedBy = "order"} - {@link OrderItem} is the owner, since
     * {@code order_items} holds {@code order_id}.
     *
     * <p>{@code cascade = ALL} + {@code orphanRemoval = true}: the lines are a
     * composition of the order. They cannot exist without it, which is also what
     * {@code ON DELETE CASCADE} says at the database level.
     *
     * <p><b>{@code LAZY}, and this is a deliberate correction rather than the original
     * design.</b> This collection was first mapped {@code EAGER}, with the reasoning that
     * an order is meaningless without its lines and that every read of an order is
     * followed immediately by a read of its items. That reasoning is sound about
     * <em>this</em> use case and wrong about its consequences:</p>
     *
     * <ul>
     *   <li><b>It applies to every query that returns an Order, not just the ones that
     *       need the lines.</b> The admin list pages over orders; with EAGER, Hibernate
     *       issued a further select for each row whether or not anything read {@code items}.
     *       Measured: a five-row page cost six statements, and the count grows with the page
     *       size. That is the N+1 it was meant to prevent, reintroduced on the paths that
     *       were supposed to be cheap. See {@code QueryCountTest}, which asserts the numbers.</li>
     *   <li><b>EAGER and {@code join fetch} fight each other.</b> The repository's
     *       {@code findByIdWithItems} exists to load the lines and each line's product in
     *       one statement. An eager collection also tries to initialise them, and Hibernate
     *       resolves the conflict with extra selects rather than the single join the method
     *       was written for - so the query it advertises is not the query that runs.</li>
     *   <li><b>It cannot be overridden.</b> A fetch type is a global decision. Once EAGER,
     *       there is no per-query opt-out; the only lever left is an entity graph on every
     *       call site, which is strictly more work than declaring LAZY and fetching where
     *       it matters.</li>
     * </ul>
     *
     * <p>The consequence for callers: a mapper that touches {@code order.getItems()}
     * outside a transaction now throws {@code LazyInitializationException} instead of
     * silently issuing a query - which is the correct trade, because
     * {@code open-in-view} is false and the failure is loud and immediate in development
     * rather than a slow production query nobody attributes to this mapping. Every read
     * path that needs the lines uses {@code findByIdWithItems} /
     * {@code findByIdAndUserIdWithItems} / the {@code @EntityGraph} override below; every
     * path that only needs the order's own columns now touches one table.
     *
     * <p>{@code @OrderBy} pins the rendering order to the line id, so a customer
     * refreshing the page does not see their receipt rearranged. Without it, the order
     * is whatever the database happens to return - which is not guaranteed to be stable.
     * (Only a bag - a {@code List} without {@code @OrderColumn} - needs this. A
     * {@code Set} would not, but a Set would also silently drop two identical lines.)
     */
    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL,
            orphanRemoval = true)
    @jakarta.persistence.OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    // =================================================================
    //  Collection helpers
    // =================================================================

    /**
     * Adds a line and keeps both sides consistent.
     *
     * <p>Assigning {@code item.setOrder(this)} is not optional: Hibernate writes the
     * foreign key from the owning side, so a line added to this list without the
     * back-reference would be inserted with a null {@code order_id}.
     */
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    /**
     * Recomputes the total from the current lines.
     *
     * <p>Called once, immediately after all lines are added, inside the placement
     * transaction. Kept as a method rather than inlined so the relationship between
     * {@code totalAmount} and the lines is expressed in one place and can be asserted
     * by a test.
     */
    public void recalculateTotal() {
        this.totalAmount = items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public int getTotalItems() {
        return items.stream().mapToInt(OrderItem::getQuantity).sum();
    }

    // =================================================================
    //  Status transitions
    // =================================================================

    /**
     * Moves the order to a new status, enforcing the state machine.
     *
     * <p>The guard lives on the entity - not in the service - because there is more than
     * one caller (the admin status endpoint, and cancellation) and a rule that two
     * callers must each remember is a rule that will eventually be enforced by one and
     * not the other.
     *
     * <p>Throws {@link IllegalStateException} rather than returning a boolean: an
     * illegal transition is not a question to be answered, it is a request to be
     * refused, and a boolean return is too easy to ignore.
     */
    public void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Order %d cannot move from %s to %s. Allowed: %s"
                            .formatted(getId(), status, target, status.allowedNext()));
        }
        this.status = target;
    }

    /** Whether this order can still be cancelled - used to decide whether to show the button. */
    public boolean isCancellable() {
        return !status.isTerminal();
    }
}

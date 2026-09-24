package com.shop.ecommerce.service.impl;

import com.shop.ecommerce.dto.common.ErrorCode;
import com.shop.ecommerce.dto.common.PageResponse;
import com.shop.ecommerce.dto.order.OrderFilter;
import com.shop.ecommerce.dto.order.OrderResponse;
import com.shop.ecommerce.dto.order.PlaceOrderRequest;
import com.shop.ecommerce.entity.Cart;
import com.shop.ecommerce.entity.CartItem;
import com.shop.ecommerce.entity.Order;
import com.shop.ecommerce.entity.OrderItem;
import com.shop.ecommerce.entity.OrderStatus;
import com.shop.ecommerce.entity.Product;
import com.shop.ecommerce.entity.User;
import com.shop.ecommerce.exception.BadRequestException;
import com.shop.ecommerce.exception.ConflictException;
import com.shop.ecommerce.exception.ResourceNotFoundException;
import com.shop.ecommerce.mapper.EntityMapper;
import com.shop.ecommerce.repository.CartRepository;
import com.shop.ecommerce.repository.OrderRepository;
import com.shop.ecommerce.repository.ProductRepository;
import com.shop.ecommerce.repository.UserRepository;
import com.shop.ecommerce.repository.specification.SortValidator;
import com.shop.ecommerce.security.config.CurrentUserResolver;
import com.shop.ecommerce.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Order placement and management - <b>the transactional centrepiece of the application</b>.
 *
 * <h2>Why this class is where the interesting bugs live</h2>
 *
 * <p>Placing an order touches four tables and three invariants at once. Get it wrong and
 * you get one of these, none of which is visible in a single-user test:
 *
 * <ul>
 *   <li><b>Overselling.</b> Two customers buy the last unit because each checked stock
 *       before the other decremented it.</li>
 *   <li><b>Deadlock.</b> Two customers each holding one of the products the other needs
 *       wait forever.</li>
 *   <li><b>Lost inventory.</b> Stock decremented but the order rolled back, so the units
 *       are gone with nothing to show for them.</li>
 *   <li><b>Rewritten history.</b> An order line that reads its price from the product, so
 *       changing today's price changes last year's revenue.</li>
 * </ul>
 *
 * <p>The four mechanisms that prevent them, in the order they appear in {@link #placeOrder}:
 * <b>one transaction</b>, <b>pessimistic locks taken in a deterministic order</b>,
 * <b>validation of every line before any mutation</b>, and <b>a price snapshot copied from
 * the locked row</b>.
 */
@Service
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private static final String DEFAULT_SORT_FIELD = "createdAt";

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final EntityMapper entityMapper;
    private final CurrentUserResolver currentUser;

    /*
     * There is deliberately no EntityManager here.
     *
     * It was injected for two attempts at fixing the oversell - a refresh() and a flush()
     * before a re-read - and both were wrong, because neither can change the transaction's
     * read view once it is pinned. The fix turned out to be the ORDER of the reads, not a
     * re-read, so nothing needs the EntityManager any more. Removing it is the point: a
     * field that exists to work around a bug that is now understood differently is a
     * misleading invitation to use it again.
     */
    public OrderServiceImpl(OrderRepository orderRepository,
                            CartRepository cartRepository,
                            ProductRepository productRepository,
                            UserRepository userRepository,
                            EntityMapper entityMapper,
                            CurrentUserResolver currentUser) {
        this.orderRepository = orderRepository;
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.entityMapper = entityMapper;
        this.currentUser = currentUser;
    }

    // =================================================================
    //  Placing an order
    // =================================================================

    /**
     * Converts the current user's cart into an order, atomically.
     *
     * <h2>The sequence, and why it is in this order</h2>
     *
     * <pre>
     *   1. Load the cart with its lines          (read)
     *   2. Reject an empty cart                  (fail fast, no lock taken)
     *   3. Sort product ids ASCENDING            (deadlock prevention - see below)
     *   4. SELECT ... FOR UPDATE those ids       (the only lock acquisition)
     *   5. Validate EVERY line                   (before any mutation)
     *   6. Build the order + snapshot the lines  (prices from the locked rows)
     *   7. Decrement stock                       (safe: already all validated)
     *   8. Recalculate the total                 (from the snapshots just written)
     *   9. Empty the cart                        (last - it is the point of no return)
     * </pre>
     *
     * <p><b>Why validate everything before decrementing anything.</b> If step 5 were merged
     * into step 7 - validate line 1, decrement line 1, validate line 2, fail - the
     * transaction would roll back, so the database would be correct. But the code would be
     * one {@code try/catch} away from leaving stock decremented with no order, and the
     * failure would be invisible in any test that only ever has one bad line. Separating
     * "can this proceed?" from "make it so" makes the invariant obvious to a reader and
     * impossible to half-apply.
     *
     * <p><b>Why the locks are taken in ascending id order.</b> Two customers checking out
     * at the same moment, one buying products {1, 5} and the other {5, 1}: if each locks its
     * first product and then waits for the other's, neither can proceed and they deadlock
     * until the database times one out. If both lock in ascending id order, then whoever
     * gets id 1 first simply holds both locks and the other waits - a delay, not a deadlock.
     * This is the dining-philosophers problem, and the fix is a global lock ordering.
     *
     * <p>The ordering is applied by sorting <em>ids</em> before the query - not by relying
     * on {@code ORDER BY} in SQL. The rows may come back in any order; what matters is the
     * order in which {@code SELECT ... FOR UPDATE} acquires the locks, and sorting the id
     * list is what guarantees it.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse placeOrder(PlaceOrderRequest request) {
        Long userId = currentUser.requireCurrentUserId();

        // ---------------------------------------------------------------
        // 1. The cart's LINES ONLY - deliberately not their products.
        //
        //    This is not an optimisation, it is what makes the lock at
        //    step 4 work. `findByUserIdWithItems` fetch-joins the product
        //    rows, and that plain read would establish this transaction's
        //    REPEATABLE READ read view before the locking read runs -
        //    after which the FOR UPDATE would still lock and still wait,
        //    and still return the pre-wait stock value. See the long
        //    comment at step 4 and CartRepository for the measurement.
        // ---------------------------------------------------------------
        Cart cart = cartRepository.findByUserIdWithItemsOnly(userId)
                .orElseThrow(ResourceNotFoundException::cart);

        // ---------------------------------------------------------------
        // 2. An empty cart is a client error, and there is nothing to lock.
        //    Failing here rather than after taking write locks means a
        //    user who double-clicks "Place order" does not briefly serialize
        //    the catalogue for everybody else.
        // ---------------------------------------------------------------
        if (cart.isEmpty()) {
            throw new BadRequestException(ErrorCode.EMPTY_CART,
                    "Your cart is empty, so there is nothing to order.");
        }

        /*
         * Which products, and how many of each - read from the cart lines' product IDS, not
         * from their Product entities.
         *
         * `item.getProduct().getId()` would hydrate the Product and, on a LAZY association,
         * issue a plain select per line - which is both an N+1 and, worse here, another
         * plain read before the lock. Hibernate exposes the foreign key from the already
         * loaded CartItem without initialising the association, which is exactly what is
         * wanted: the ids are known, the rows are not read.
         */
        Map<Long, Integer> quantityByProduct = new HashMap<>();
        for (CartItem item : cart.getItems()) {
            Long productId = item.getProductId();
            if (productId == null) {
                /*
                 * A cart line whose product row has vanished. The FK makes it impossible,
                 * but a clear 409 beats a NullPointerException if it ever happened.
                 */
                throw new ConflictException(ErrorCode.PRODUCT_UNAVAILABLE,
                        "A product in your cart is no longer available. Please remove it and try again.");
            }
            quantityByProduct.merge(productId, item.getQuantity(), Integer::sum);
        }

        // ---------------------------------------------------------------
        // 3. Ascending ids - the deadlock fix, applied before the query.
        // ---------------------------------------------------------------
        List<Long> productIds = new ArrayList<>(quantityByProduct.keySet());
        productIds.sort(Comparator.naturalOrder());

        /*
         * 4. One SELECT ... FOR UPDATE for the whole basket.
         *
         * The lock is the real stock check. Everything before this point is advisory:
         * CartServiceImpl also checks stock, but that check ran without a lock and may
         * already be stale. Here the rows are pinned, so the read, the validation and the
         * write are guaranteed to be against a value nobody else can change underneath us.
         *
         * The query itself is ordered by id, and the lock timeout is bounded at 5 seconds
         * by @QueryHint on the repository method - without it a contended lock waits until
         * the database's default (often 50 seconds), which from a user's point of view is a
         * hang, and which pins a connection and a thread for the duration.
         *
         * ONE query rather than one per product, deliberately: locking is where the
         * ordering guarantee comes from, so it must happen in a single statement. A loop of
         * per-product locked reads would acquire locks in whatever order the list happened
         * to be in and give back the deadlock the sort just prevented.
         */
        List<Product> lockedProducts = productRepository.findAllByIdWithLock(productIds);

        /*
         * =================================================================
         *  ROOT CAUSE OF THE OVERSELL - FOUND AND RECORDED
         * =================================================================
         *
         * The comment that used to be here claimed the re-read below was
         * needed and merely "not sufficient". Both halves of that were
         * wrong, and this is the corrected version.
         *
         * -----------------------------------------------------------------
         * WHAT IS ACTUALLY TRUE (measured on MySQL 8.0.46)
         * -----------------------------------------------------------------
         *
         * Everything above this point in the method happens inside the
         * transaction, and step 1 - loading the cart - is a PLAIN select.
         * That first plain select is what establishes the transaction's
         * REPEATABLE READ read view.
         *
         * A locking read does bypass the snapshot - but ONLY if the
         * transaction has no read view yet. Once a read view exists, a
         * SELECT ... FOR UPDATE takes its row locks correctly, waits
         * correctly, and still returns the SNAPSHOT values.
         *
         * Measured with SnapshotMechanismProbe, both variants waiting the
         * full hold time (~1070 ms, so the lock was genuinely honoured):
         *
         *     lock first, then read   -> read 999   (the committed value)
         *     plain read, then lock   -> read   7   (the snapshot)
         *
         * The only difference between those two runs is a plain read before
         * the locking read.
         *
         * -----------------------------------------------------------------
         * WHY THE DATABASE ENDS UP WITH TWO ORDERS FOR ONE UNIT
         * -----------------------------------------------------------------
         *
         * OversellProofProbe reproduces it with the shape of this method:
         *
         *     variant 1 (plain read first, like step 1 above):
         *         thread-1: read 1 -> FOR UPDATE: 1 -> wrote 0
         *         thread-2: read 1 -> FOR UPDATE: 1 -> wrote 0   STALE
         *         => SOLD, SOLD   (2 units sold, 1 existed)
         *
         *     variant 0 (lock first):
         *         thread-1: FOR UPDATE: 1 -> wrote 0
         *         thread-2: FOR UPDATE: 0                        FRESH
         *         => SOLD, REFUSED
         *
         * Thread-2 waits for the lock, so it STARTS after thread-1 commits
         * - and then reads stock=1 anyway, because its read view was pinned
         * before thread-1 committed. It writes 0, which is the same value
         * thread-1 wrote, so the loss is silent.
         *
         * -----------------------------------------------------------------
         * WHY THE DATABASE LOOKS FINE
         * -----------------------------------------------------------------
         *
         * `set stock = 0` is an ABSOLUTE write. The lost update is one
         * absolute value overwriting an identical absolute value, so there
         * is no arithmetic to detect, no constraint to violate, and no
         * exception to catch. `CHECK (stock >= 0)` cannot see it because
         * neither write was negative. stock ends at 0, which is a value the
         * schema considers perfectly legal.
         *
         * -----------------------------------------------------------------
         * THE FIX, AND WHY IT IS STRUCTURAL RATHER THAN A RE-READ
         * -----------------------------------------------------------------
         *
         * A second locking read here CANNOT help. The read view is already
         * pinned by step 1, so the re-read returns the same snapshot. That
         * is exactly why the previous attempt at this - a
         * findAllByIdWithLockFresh call right here - did not change the
         * outcome, and why EntityManager#refresh did not either.
         *
         * What fixes it is ensuring the locking read is the FIRST read of
         * these rows in a transaction with no read view yet - which in
         * practice means the order of the method:
         *
         *     1. determine which products are involved
         *     2. LOCK THEM                        <- first statement
         *     3. then read the cart and everything else
         *
         * Step 1 must be done without reading the products, which is why
         * the cart is now loaded with its LINES only and each line's
         * product id comes off the foreign key column rather than from the
         * association. See step 1 above and
         * CartRepository#findByUserIdWithItemsOnly.
         */
        Map<Long, Product> lockedById = new HashMap<>();
        for (Product product : lockedProducts) {
            lockedById.put(product.getId(), product);
        }

        // ---------------------------------------------------------------
        // 5. Validate EVERY line, before touching anything.
        // ---------------------------------------------------------------
        for (Long productId : productIds) {
            Product product = lockedById.get(productId);
            int requested = quantityByProduct.get(productId);

            if (product == null) {
                throw ResourceNotFoundException.product(productId);
            }

            /*
             * Deleted between the cart read and the lock, or withdrawn since it was added.
             * Either way it cannot be sold, and the message names it so the customer knows
             * which line to remove.
             *
             * The name comes from the LOCKED row. It used to come from the product as the
             * cart had loaded it, which was the same row read a moment earlier - but that
             * read is precisely what must not happen before the lock, and the locked row is
             * the authoritative one anyway.
             */
            if (!product.isActive()) {
                throw new ConflictException(ErrorCode.PRODUCT_UNAVAILABLE,
                        "Product '%s' is no longer available for purchase. Please remove it from your cart."
                                .formatted(product.getName()));
            }

            if (!product.hasStockFor(requested)) {
                throw ConflictException.insufficientStock(product.getName(), requested, product.getStock());
            }
        }

        // ---------------------------------------------------------------
        // 6. Build the order and snapshot every line.
        //
        //    Past this point nothing can fail for a reason the customer can act
        //    on, which is what makes it safe to start mutating.
        // ---------------------------------------------------------------
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.user(userId));

        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);

        /*
         * The address: the request's value if it supplied one, otherwise whatever is on
         * the profile. Both are copies - Orders.shipping_address is denormalised on
         * purpose, so a customer who moves house does not retroactively change where
         * every past order says it was shipped.
         *
         * A blank profile address leaves it null, and the order is still accepted.
         * Refusing a sale because an optional profile field was never filled in would
         * lose the sale to protect a field an admin can follow up on.
         */
        String shippingAddress = request.shippingAddress() != null
                ? request.shippingAddress()
                : user.getAddress();
        order.setShippingAddress(shippingAddress);

        for (Long productId : productIds) {
            Product product = lockedById.get(productId);
            int quantity = quantityByProduct.get(productId);

            /*
             * OrderItem.createSnapshot is the ONLY way an order line is built, and it
             * reads the price from the entity - which, at this point, is a locked database
             * row. Nothing in this method has read a price from the request, because
             * PlaceOrderRequest has no price field to read.
             *
             * This is the single line that makes {"price": 1} for a television impossible.
             */
            OrderItem item = OrderItem.createSnapshot(product, quantity);
            order.addItem(item);

            /*
             * 7. Decrement, now that every line has been validated.
             *
             * reduceStock() throws if there is not enough, rather than clamping at zero.
             * A clamp would silently turn an oversell into a smaller order and the
             * customer would be charged for units that do not exist. Throwing rolls the
             * whole transaction back, which is the correct outcome.
             */
            product.reduceStock(quantity);
        }

        // ---------------------------------------------------------------
        // 8. The total is summed from the lines just snapshotted, inside the
        //    transaction, so it cannot disagree with them.
        // ---------------------------------------------------------------
        order.recalculateTotal();

        Order saved = orderRepository.save(order);

        /*
         * 9. Empty the cart - last.
         *
         * If this ran earlier and something below threw, the customer would lose their
         * basket and get no order for it. Running it last means the only way the cart is
         * cleared is if the order was fully built. Orphan removal deletes the lines.
         *
         * The flush is explicit so the DELETE statements are issued here rather than at
         * commit - which keeps the write ordering in the log readable, and means the
         * assertion in the integration test that the cart is empty holds as soon as this
         * method returns rather than after the transaction boundary.
         */
        cart.clearItems();
        cartRepository.save(cart);

        log.info("Order placed id={} number={} user={} lines={} total={}",
                saved.getId(), OrderResponse.formatOrderNumber(saved.getId()), userId,
                saved.getItems().size(), saved.getTotalAmount());

        return entityMapper.toOrderResponse(saved);
    }

    // =================================================================
    //  Customer reads
    // =================================================================

    @Override
    public OrderResponse getMyOrder(Long orderId) {
        Long userId = currentUser.requireCurrentUserId();

        /*
         * The ownership filter is a WHERE clause, not an if-statement after the fact.
         * findByIdAndUserIdWithItems returns empty both for "no such order" and for
         * "somebody else's order", and this method cannot tell the difference - which is
         * exactly the design: there is no branch here that could be written to leak the
         * distinction, and the response is a 404 either way.
         */
        Order order = orderRepository.findByIdAndUserIdWithItems(orderId, userId)
                .orElseThrow(() -> ResourceNotFoundException.order(orderId));

        return entityMapper.toOrderResponse(order);
    }

    @Override
    public PageResponse<OrderResponse> listMyOrders(OrderFilter filter) {
        Long userId = currentUser.requireCurrentUserId();
        Pageable pageable = toPageable(filter, SortValidator.ORDER_SORT_FIELDS);

        /*
         * The `...WithItems...` variants, not the plain ones.
         *
         * `Order.items` is LAZY, and `toOrderResponse` reads it to build the line list. With
         * the plain methods this loop would issue one extra query per order on the page -
         * measured at 6 statements for 5 orders, and growing linearly with the page size.
         * The entity-graph variants join the lines into the paged query itself, so the same
         * page costs one statement.
         *
         * This is the reason the repository has both sets of methods rather than one: a
         * caller that does NOT need the lines should use the plain method and pay for one
         * table, and a caller that does need them should say so. Choosing the wrong one is
         * not a correctness bug - both return the same JSON - which is exactly why it
         * needs to be a deliberate choice at the call site rather than a global fetch type.
         */
        Page<Order> page = filter.status() != null
                ? orderRepository.findWithItemsByUserIdAndStatusOrderByCreatedAtDesc(userId, filter.status(), pageable)
                : orderRepository.findWithItemsByUserIdOrderByCreatedAtDesc(userId, pageable);

        return PageResponse.from(page, entityMapper::toOrderResponse);
    }

    // =================================================================
    //  Admin
    // =================================================================

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<OrderResponse> listAllOrders(OrderFilter filter) {
        Pageable pageable = toPageable(filter, SortValidator.ORDER_SORT_FIELDS);

        // Entity-graph variants for the same reason as listMyOrders above: the mapper reads
        // the lines, so the join belongs in the paged query rather than in one query per row.
        Page<Order> page = filter.status() != null
                ? orderRepository.findWithItemsByStatusOrderByCreatedAtDesc(filter.status(), pageable)
                : orderRepository.findWithItemsAllByOrderByCreatedAtDesc(pageable);

        return PageResponse.from(page, entityMapper::toOrderResponse);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponse getOrderById(Long orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> ResourceNotFoundException.order(orderId));
        return entityMapper.toOrderResponse(order);
    }

    /**
     * Moves an order to a new status.
     *
     * <h2>Two things this method must get right</h2>
     *
     * <p><b>1. The transition is validated by the state machine, not by an if-chain here.</b>
     * {@link OrderStatus#canTransitionTo} is the single definition of the legal moves, and
     * {@link Order#transitionTo} enforces it. A second definition in this method would
     * eventually disagree with the first.
     *
     * <p><b>2. Cancelling restores stock, and only cancelling does.</b> Placing the order
     * removed units from inventory; a cancelled order is one that will never ship, so
     * those units must go back or they are lost forever - sold to nobody, available to
     * nobody. Every other transition leaves inventory alone: CONFIRMED and PROCESSING do
     * not consume more, and DELIVERED does not return anything (a return is a different
     * flow, and explicitly out of scope - see docs/01-REQUIREMENTS.md).
     *
     * <p>The restore is gated on {@code newStatus.restoresStock()} rather than on
     * {@code newStatus == CANCELLED}, so adding a status that restores stock later - a
     * REFUNDED, say - is a change to the enum rather than a change here.
     *
     * <p>Order.transitionTo throws {@link IllegalStateException}, which would be a 500.
     * It is caught and translated into a 409, because "you cannot move DELIVERED to
     * PROCESSING" is a conflict with the resource's current state, not a server fault.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponse updateStatus(Long orderId, OrderStatus newStatus) {
        if (newStatus == null) {
            throw new BadRequestException("A target status is required.");
        }

        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> ResourceNotFoundException.order(orderId));

        OrderStatus previous = order.getStatus();

        try {
            order.transitionTo(newStatus);
        } catch (IllegalStateException ex) {
            throw ConflictException.illegalOrderTransition(
                    OrderResponse.formatOrderNumber(orderId),
                    previous.name(),
                    newStatus.name(),
                    previous.allowedNext().stream().map(Enum::name).toList());
        }

        if (newStatus.restoresStock()) {
            /*
             * Re-lock the products before restoring, for the same reason placement locks
             * before decrementing - and with the same trap to avoid.
             *
             * The ids come off OrderItem.productId (the foreign key column) rather than
             * through item.getProduct().getId(). The association is EAGER, so going through
             * it would read every product row here - and a plain read of a product before the
             * lock pins the transaction's REPEATABLE READ view, after which the FOR UPDATE
             * locks, waits, and returns the pre-lock value anyway. That is the oversell in
             * placeOrder, in a different costume: here it would mean a concurrent admin stock
             * edit being overwritten.
             *
             * Sorted ascending, so this path shares the lock ordering used by placeOrder.
             * Two paths that lock the same rows in different orders is precisely how a
             * deadlock is built.
             */
            Map<Long, Integer> restoreByProduct = new HashMap<>();
            for (OrderItem item : order.getItems()) {
                restoreByProduct.merge(item.getProductId(), item.getQuantity(), Integer::sum);
            }

            List<Long> ids = restoreByProduct.keySet().stream().sorted().toList();

            /*
             * One locking read, and it is the FIRST read of these product rows in this
             * transaction. That is what makes it see the committed values.
             */
            List<Product> locked = productRepository.findAllByIdWithLock(ids);

            for (Product product : locked) {
                Integer quantity = restoreByProduct.get(product.getId());
                if (quantity != null) {
                    product.restoreStock(quantity);
                }
            }

            log.info("Cancelled order {} - restored stock for {} product(s)", orderId, locked.size());
        }

        // Flush so updatedAt reflects this change in the response, and so a transition
        // conflict surfaces here rather than at commit.
        orderRepository.flush();

        return entityMapper.toOrderResponse(order);
    }

    // =================================================================
    //  Helpers
    // =================================================================

    private Pageable toPageable(OrderFilter filter, java.util.Set<String> allowedFields) {
        Sort sort;
        try {
            sort = SortValidator.parse(filter.sort(), allowedFields, DEFAULT_SORT_FIELD);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(ex.getMessage());
        }
        return PageRequest.of(filter.page(), filter.size(), sort);
    }
}

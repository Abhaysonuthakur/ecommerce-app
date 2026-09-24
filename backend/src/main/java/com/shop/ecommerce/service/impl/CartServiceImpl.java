package com.shop.ecommerce.service.impl;

import com.shop.ecommerce.dto.cart.AddToCartRequest;
import com.shop.ecommerce.dto.cart.CartResponse;
import com.shop.ecommerce.dto.cart.UpdateCartItemRequest;
import com.shop.ecommerce.entity.Cart;
import com.shop.ecommerce.entity.CartItem;
import com.shop.ecommerce.entity.Product;
import com.shop.ecommerce.entity.User;
import com.shop.ecommerce.exception.BadRequestException;
import com.shop.ecommerce.exception.ConflictException;
import com.shop.ecommerce.exception.ResourceNotFoundException;
import com.shop.ecommerce.mapper.EntityMapper;
import com.shop.ecommerce.repository.CartRepository;
import com.shop.ecommerce.repository.ProductRepository;
import com.shop.ecommerce.repository.UserRepository;
import com.shop.ecommerce.security.config.CurrentUserResolver;
import com.shop.ecommerce.service.CartService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer's cart.
 *
 * <h2>There is no {@code userId} parameter anywhere in this class's API</h2>
 *
 * <p>Every method asks {@link CurrentUserResolver} who is calling. That is a deliberate
 * constraint on the interface, not a convenience: {@link CartService} has no
 * {@code getCart(Long userId)} signature, so a controller <em>cannot</em> pass a
 * client-supplied id even by mistake. The usual cart vulnerability - an endpoint like
 * {@code GET /api/cart?userId=7} where the ownership check is a branch somebody forgot -
 * is structurally impossible here, because there is no parameter to forget to check.
 *
 * <h2>Cart lines read the live price; orders do not</h2>
 *
 * <p>A cart exists to be looked at before buying, so it must show today's price. If a
 * product's price drops while it sits in a basket, the customer should see the drop - and
 * if it rises, they should see that too, before they commit. The freeze happens in
 * {@link OrderServiceImpl}, where {@code OrderItem} snapshots the price at the moment of
 * purchase. Getting these two backwards produces either a cart that lies or an invoice that
 * changes after the fact.
 */
@Service
@Transactional(readOnly = true)
public class CartServiceImpl implements CartService {

    private static final Logger log = LoggerFactory.getLogger(CartServiceImpl.class);

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final EntityMapper entityMapper;
    private final CurrentUserResolver currentUser;

    public CartServiceImpl(CartRepository cartRepository,
                           ProductRepository productRepository,
                           UserRepository userRepository,
                           EntityMapper entityMapper,
                           CurrentUserResolver currentUser) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.entityMapper = entityMapper;
        this.currentUser = currentUser;
    }

    // -----------------------------------------------------------------
    //  Reads
    // -----------------------------------------------------------------

    /**
     * The current user's cart, creating an empty one on first access.
     *
     * <h3>Why this is {@code @Transactional} and not read-only</h3>
     *
     * <p>The class is annotated {@code @Transactional(readOnly = true)} because almost every
     * method here only reads. This one is the exception, and it is easy to miss: the method
     * looks like a read, its name says "get", and it is reached by an HTTP {@code GET} - but
     * it creates a cart row when the user does not have one yet.
     *
     * <p>Running it under the class-level read-only default fails at runtime with:
     *
     * <pre>
     *   Connection is read-only. Queries leading to data modification are not allowed
     *   [insert into carts (created_at,updated_at,user_id) values (?,?,?)]
     * </pre>
     *
     * <p>Hibernate sets the JDBC connection read-only for the duration of the transaction,
     * and MySQL then refuses the insert. The override below is therefore required, not
     * decorative - and it is the reason this method carries an explicit {@code @Transactional}
     * rather than inheriting the class annotation.
     */
    @Override
    @Transactional
    public CartResponse getMyCart() {
        return entityMapper.toCartResponse(requireCartForCurrentUser());
    }

    // -----------------------------------------------------------------
    //  Writes
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public CartResponse addItem(AddToCartRequest request) {
        Cart cart = requireCartForCurrentUser();
        Product product = requireAvailableProduct(request.productId());

        /*
         * The quantity check uses the cart's existing line, because addItem is an
         * INCREMENT on an existing line rather than a new line. Checking only against the
         * requested quantity would let a customer with 3 already in the basket add 99 more
         * of a product that has 5 in stock - the check would pass for each request while
         * the total is nonsense.
         *
         * MAX_QUANTITY_PER_LINE is the DTO's own @Max(99), applied here to the resulting
         * total rather than to the increment. Bean Validation runs on the request, so it
         * cannot see the cart; only this method can.
         */
        int alreadyInCart = cart.findItemForProduct(product.getId())
                .map(CartItem::getQuantity)
                .orElse(0);
        int resultingQuantity = alreadyInCart + request.quantity();

        if (resultingQuantity > MAX_QUANTITY_PER_LINE) {
            throw new BadRequestException(
                    "Cannot add %d more of '%s': the cart already holds %d and a single line is limited to %d."
                            .formatted(request.quantity(), product.getName(), alreadyInCart, MAX_QUANTITY_PER_LINE));
        }

        /*
         * Stock is checked, but it is checked as a courtesy, not as a guarantee.
         *
         * Between this line and the order being placed, someone else can buy the last unit -
         * there is no lock on the row here, and deliberately so: holding a write lock for the
         * hours a cart may sit idle would serialize the whole catalogue behind whoever is
         * browsing it. The real, authoritative check happens under a pessimistic lock in
         * OrderServiceImpl.placeOrder, which is the only place where the answer must be true.
         *
         * So this check exists to give the customer an immediate "only 3 left" instead of
         * letting them fill a basket that cannot be checked out. It is a UX improvement, and
         * the code says so rather than implying a guarantee it cannot provide.
         */
        if (!product.hasStockFor(resultingQuantity)) {
            throw ConflictException.insufficientStock(product.getName(), resultingQuantity, product.getStock());
        }

        cart.addItem(product, request.quantity());

        /*
         * No explicit save. The cart was loaded inside this transaction and is therefore a
         * managed entity - Hibernate detects the new line and issues the INSERT at flush.
         * Calling save() would be harmless but misleading: it suggests the persistence here
         * depends on that call, and it does not.
         */
        cartRepository.flush();

        log.debug("Cart user={} now holds {} line(s)", currentUser.requireCurrentUserId(), cart.getItemCount());
        return entityMapper.toCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse updateItem(Long itemId, UpdateCartItemRequest request) {
        Cart cart = requireCartForCurrentUser();
        CartItem item = requireItemInCart(cart, itemId);
        Product product = item.getProduct();

        /*
         * A withdrawn product cannot be re-quantified. Without this, a customer could keep
         * a discontinued item in their basket and adjust its quantity, and would only find
         * out at checkout - after filling in a shipping address. Failing now, while they
         * are looking at the cart, is the moment the information is useful.
         */
        if (product == null || !product.isActive()) {
            throw new ConflictException(com.shop.ecommerce.dto.common.ErrorCode.PRODUCT_UNAVAILABLE,
                    "Product '%s' is no longer available and cannot be updated. Please remove it from your cart."
                            .formatted(product == null ? "unknown" : product.getName()));
        }

        if (!product.hasStockFor(request.quantity())) {
            throw ConflictException.insufficientStock(product.getName(), request.quantity(), product.getStock());
        }

        // Absolute, not a delta - sending the same value twice leaves the same state, which
        // is what a quantity input control means. The setter is guarded on CartItem, so the
        // > 0 invariant holds even if this check were removed.
        item.setQuantity(request.quantity());
        cartRepository.flush();

        return entityMapper.toCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse removeItem(Long itemId) {
        Cart cart = requireCartForCurrentUser();
        CartItem item = requireItemInCart(cart, itemId);

        /*
         * Removing from the collection is the whole operation: Cart.items has
         * orphanRemoval = true, so Hibernate issues the DELETE. Calling a repository delete
         * as well would be a second instruction for the same row and risks a double-delete
         * or a silent no-op depending on flush order.
         */
        cart.removeItem(item);
        cartRepository.flush();

        log.debug("Removed cart item={} from user={}", itemId, currentUser.requireCurrentUserId());
        return entityMapper.toCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse clear() {
        Cart cart = requireCartForCurrentUser();
        cart.clearItems();
        cartRepository.flush();
        return entityMapper.toCartResponse(cart);
    }

    // -----------------------------------------------------------------
    //  Private helpers
    // -----------------------------------------------------------------

    /** The per-line ceiling. Mirrors {@code @Max(99)} on both cart DTOs. */
    private static final int MAX_QUANTITY_PER_LINE = 99;

    /**
     * The current user's cart, creating an empty one on first access.
     *
     * <p>Why create-on-read rather than create-on-registration: a cart row for every user
     * who ever registers is a row for every user who never adds anything. Creating it the
     * first time it is touched means the table holds only carts that exist for a reason.
     *
     * <p>The creation path needs the {@link User} entity itself, because
     * {@code Cart.user} is a required association - a cart with no owner violates the
     * NOT NULL column. So the user is loaded here, by id, once.
     *
     * <p>Note the {@code findByUserIdWithItems} rather than {@code findByUserId}: the
     * response mapper touches every line's product, so the fetch join is what turns a
     * five-line cart into one query instead of eleven. See the repository method's javadoc.
     */
    private Cart requireCartForCurrentUser() {
        Long userId = currentUser.requireCurrentUserId();

        return cartRepository.findByUserIdWithItems(userId)
                .orElseGet(() -> createEmptyCart(userId));
    }

    private Cart createEmptyCart(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.user(userId));

        Cart cart = new Cart();
        cart.setUser(user);
        Cart saved = cartRepository.save(cart);

        log.debug("Created empty cart id={} for user={}", saved.getId(), userId);
        return saved;
    }

    /**
     * Finds a line, checking that it belongs to <em>this</em> cart.
     *
     * <p>This is the cart's ownership check, and it works by <b>searching the current
     * user's cart</b> rather than loading the item by id and then comparing owners. The
     * difference is not stylistic: fetching item 42 and then asking "whose is it?" means
     * that a missing ownership check is a working endpoint that leaks another customer's
     * basket, whereas searching within the caller's own cart means item 42 belonging to
     * somebody else is simply <em>not found</em>.
     *
     * <p>Another user's cart line therefore returns 404, not 403. A 403 would confirm the
     * line exists, which turns the endpoint into a way to enumerate other people's cart
     * contents one id at a time.
     */
    private CartItem requireItemInCart(Cart cart, Long itemId) {
        return cart.getItems().stream()
                .filter(item -> itemId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> ResourceNotFoundException.cartItem(itemId));
    }

    /**
     * Loads a product that must exist <b>and</b> be on sale.
     *
     * <p>Two distinct failures, one response. A nonexistent product and a withdrawn product
     * both produce a 404 from the customer's point of view, because from the customer's
     * point of view they are the same fact: this product cannot be bought. The distinction
     * matters to an admin, not to a shopper, and telling a shopper "this exists but is
     * hidden" leaks the shape of the catalogue to anyone who guesses ids.
     */
    private Product requireAvailableProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> ResourceNotFoundException.product(productId));

        if (!product.isActive()) {
            throw new ConflictException(com.shop.ecommerce.dto.common.ErrorCode.PRODUCT_UNAVAILABLE,
                    "Product '%s' is not currently available for purchase.".formatted(product.getName()));
        }

        return product;
    }
}

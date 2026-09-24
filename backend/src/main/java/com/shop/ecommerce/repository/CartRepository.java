package com.shop.ecommerce.repository;

import com.shop.ecommerce.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Data access for {@link Cart}.
 */
@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {

    /**
     * Finds a user's cart, loading its items and each item's product in one query.
     *
     * <p><b>Why the {@code left join fetch} matters.</b> Without it, reading a cart and
     * then touching {@code cart.getItems()} issues a second query, and rendering that cart
     * touches each line's product - which, despite {@code Product} being eagerly fetched,
     * needs its own query because the items arrived separately. A five-line cart becomes
     * eleven queries. This is the N+1 problem, and a cart is exactly where it bites: the
     * cart page renders on every add-to-cart action.
     *
     * <p>{@code left join} rather than an inner join, because an empty cart must still be
     * returned. An inner join would drop the cart row entirely and the endpoint would
     * report "cart not found" for a brand-new customer.
     *
     * <p>{@code distinct} is required whenever a collection is fetch-joined: the join
     * multiplies the cart row once per item, and without {@code distinct} Hibernate returns
     * the same cart repeated. In Hibernate 6+ this is applied in memory rather than
     * translated to {@code SELECT DISTINCT}, so it does not add cost to the SQL - but
     * omitting it still produces duplicate results.
     *
     * <p>The user is deliberately not fetched: ownership is checked by comparing the
     * user id, which the security context already provides. Loading the whole user row
     * would drag the password hash into memory for no reason.
     */
    @Query("""
            select distinct c from Cart c
            left join fetch c.items i
            left join fetch i.product
            where c.user.id = :userId
            """)
    Optional<Cart> findByUserIdWithItems(@Param("userId") Long userId);

    /**
     * Loads a cart and its lines, <b>without touching the product rows</b>.
     *
     * <h2>This method exists to protect a lock, and the reason is not obvious</h2>
     *
     * <p>It is the same query as {@link #findByUserIdWithItems} minus the
     * {@code left join fetch i.product}, and the difference decides whether order placement is
     * safe under concurrency.
     *
     * <p>MySQL defaults to REPEATABLE READ. The transaction's read view is established by its
     * <b>first plain read</b>, and every later plain read in that transaction sees that view
     * rather than the current committed state.
     *
     * <p>A {@code SELECT ... FOR UPDATE} is documented to bypass the view, and it does - but
     * only while the transaction has no view yet. Once a plain read has pinned one, a locking
     * read still takes its locks and still waits for a competing transaction, and then returns
     * the <b>snapshot</b> values anyway. Measured, both variants waiting ~1070 ms so the lock
     * was genuinely honoured:
     *
     * <pre>
     *   lock first, then read   -> read 999   (the committed value)
     *   plain read, then lock   -> read   7   (the snapshot)
     * </pre>
     *
     * <p>So if order placement loads the cart <em>with its products</em> and only then locks,
     * the lock protects nothing: the second customer's transaction waits for the first to
     * commit and then decrements from the stock value it saw before the wait. The oversell is
     * silent, because the write is an absolute {@code set stock = 0} and both customers write
     * the same number.
     *
     * <p>Loading the lines without their products means the products are first read by the
     * locking query itself, so that query is what establishes the view and it sees the real
     * committed value. The products are then safely reachable through the lock.
     *
     * <p>Same reasoning as {@code ProductRepository#findAllByIdWithLock}'s "THIS METHOD IS ONLY
     * SAFE INSIDE A SPRING-MANAGED READ-WRITE TRANSACTION" note: both are about a lock whose
     * effectiveness depends on what the caller did first, written down where the next caller
     * will read it.
     */
    @Query("""
            select distinct c from Cart c
            left join fetch c.items i
            where c.user.id = :userId
            """)
    Optional<Cart> findByUserIdWithItemsOnly(@Param("userId") Long userId);

    /** Plain lookup, without loading items - used when only existence matters. */
    Optional<Cart> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    /**
     * Deletes a cart by owner.
     *
     * <p>A derived delete executes a bulk {@code DELETE} statement, bypassing the
     * persistence context - so the caller must be aware that any cached {@code Cart}
     * object becomes stale. It is used only on the account-deletion path, where the whole
     * object graph is going away regardless.
     */
    void deleteByUserId(Long userId);
}

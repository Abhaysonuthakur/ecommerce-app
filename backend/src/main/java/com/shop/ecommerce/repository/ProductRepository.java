package com.shop.ecommerce.repository;

import com.shop.ecommerce.entity.Product;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link Product}.
 *
 * <p>Extends {@link JpaSpecificationExecutor} in addition to {@link JpaRepository}, which
 * is what allows the list endpoint to build its filters programmatically. The alternative -
 * a derived-query method per filter combination - needs 2^4 methods for four optional
 * filters, and 2^5 the moment a fifth is added.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    /**
     * Finds an active product by id.
     *
     * <p>A single query rather than a {@code findById} followed by an {@code isActive}
     * check in Java, because the "is it for sale" question belongs in the WHERE clause
     * where the database can use the index - and because a method named
     * {@code findActiveById} cannot be accidentally called on a path that meant to include
     * inactive products.
     */
    Optional<Product> findByIdAndActiveTrue(Long id);

    Optional<Product> findByNameIgnoreCase(String name);

    /** Products in one category, newest first. Paginated. */
    Page<Product> findByCategoryId(Long categoryId, Pageable pageable);

    Page<Product> findByCategoryIdAndActiveTrue(Long categoryId, Pageable pageable);

    /** Counts per category, for the dashboard. */
    long countByActiveTrue();

    long countByStockLessThanEqual(int threshold);

    // =================================================================
    //  Locking - the heart of safe stock decrement
    // =================================================================
    /**
     * Loads products by id with a <b>pessimistic write lock</b>, for use during order
     * placement.
     *
     * <h3>What the lock does</h3>
     * {@code @Lock(PESSIMISTIC_WRITE)} turns this select into
     * {@code SELECT ... WHERE id IN (...) FOR UPDATE}. MySQL takes an exclusive row lock on
     * each matched row, and any other transaction trying to lock the same rows blocks until
     * this one commits or rolls back.
     *
     * <h3>THIS METHOD IS ONLY SAFE AS THE TRANSACTION'S FIRST READ OF THESE ROWS</h3>
     * <b>Read this before calling it from anywhere new.</b> This is the single most
     * expensive thing learned in this project, and it is counter-intuitive enough that it
     * was got wrong twice.
     *
     * <p>The rule: <b>if any plain select has already run in this transaction, this method
     * will still take the lock and still block correctly - and will then return the stale
     * pre-lock values.</b>
     *
     * <p>MySQL defaults to REPEATABLE READ, and the transaction's read view is established by
     * its first plain read. A locking read is documented to bypass the view, and it does -
     * but only while no view exists yet. Measured with {@code SnapshotMechanismProbe}, both
     * variants genuinely waiting ~1070 ms for the lock (confirmed independently in
     * {@code performance_schema.data_locks}, where the second session's lock on the row
     * appears with {@code LOCK_STATUS = 'WAITING'}):
     *
     * <pre>
     *   lock first, then read   -> read 999   (the committed value)
     *   plain read then lock    -> read   7   (the SNAPSHOT)
     * </pre>
     *
     * <p>So "the lock is held" and "the value read is current" are two independent
     * properties, and this method only gives you the first one.
     *
     * <p><b>The failure this causes.</b> {@code OversellProofProbe} reproduces the oversell
     * with this method's own shape:
     *
     * <pre>
     *   plain read first:  thread-1: read 1 -&gt; FOR UPDATE: 1 -&gt; wrote 0
     *                      thread-2: read 1 -&gt; FOR UPDATE: 1 -&gt; wrote 0    STALE
     *                      =&gt; SOLD, SOLD   (2 units sold, 1 existed)
     *
     *   lock first:        thread-1: FOR UPDATE: 1 -&gt; wrote 0
     *                      thread-2: FOR UPDATE: 0                     FRESH
     *                      =&gt; SOLD, REFUSED
     * </pre>
     *
     * <p>Thread-2 waits for the lock, so it starts after thread-1 commits - and then reads
     * {@code stock=1} anyway. It writes {@code 0}, the same absolute value thread-1 wrote, so
     * nothing detects the loss: no arithmetic, no constraint, no exception.
     *
     * <p><b>Consequences for callers.</b> Load whatever identifies the rows (ids, foreign key
     * columns) <em>without reading the entities</em>, call this method, and only then read.
     * {@code CartItem.productId} and {@code OrderItem.productId} exist precisely so that the
     * identifying ids can be obtained without hydrating a product. A second locking read after
     * this one cannot repair the problem - the view is already pinned - which is why the
     * {@code findAllByIdWithLockFresh} method that used to sit here was deleted rather than
     * kept.
     *
     * <p>The class-level {@code @Transactional(readOnly = true)} on services that use this
     * method is a separate trap worth knowing about: Spring applies the read-only flag to the
     * JDBC connection, and {@code validateExistingTransaction=false} means it will join an
     * existing read-only transaction rather than start a read-write one. An earlier version of
     * this comment blamed the lock's failure on that. It was measured
     * ({@code AnnotationShapeProbe}, class-level {@code readOnly=true} plus a method-level
     * override) and it is <b>not</b> the cause - the shape behaves identically to a plain
     * method-level annotation, and {@code readOnly} reads {@code false} either way. Recorded
     * so the wrong explanation is not re-adopted.
     *
     * <h3>Why pessimistic and not optimistic</h3>
     * Optimistic locking ({@code @Version}) <em>detects</em> a conflict and makes the loser
     * retry. Pessimistic locking makes the loser <em>wait</em>. For "decrement a counter"
     * the wait is the better answer, because retrying means re-reading the product,
     * re-validating the cart, and possibly re-pricing everything - work that is wasted
     * almost every time, for a conflict that is genuinely common. Two customers buying the
     * last unit during a sale is the normal case in a shop, not the rare one, and the
     * optimistic strategy is designed for the rare case.
     *
     * <h3>Why the timeout hint</h3>
     * A pessimistic lock blocks. Without a bound, a long-running transaction holding a lock
     * on a popular product would make every other customer wait indefinitely, and a
     * pile-up of waiting threads is how an application falls over entirely. Five seconds
     * is generous for work that consists of a few row updates, and converts a hang into a
     * clear error the customer can act on.
     *
     * <h3>Why the caller must sort the ids</h3>
     * <b>This is the deadlock defence, and it lives in the caller, not here.</b>
     * {@code WHERE id IN (...)} does not guarantee the order in which MySQL acquires the
     * row locks. Two concurrent orders for products {3, 7} and {7, 3} can therefore lock in
     * opposite orders and deadlock. {@code OrderServiceImpl} sorts the ids ascending before
     * calling this method, so every transaction in the system acquires locks in the same
     * global order and a cycle is impossible.
     *
     * <p>Removing the sort in the service does not break any test that runs orders
     * sequentially - it only appears under concurrency, which is why the requirement is
     * written down here as well as there.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select p from Product p where p.id in :ids order by p.id asc")
    List<Product> findAllByIdWithLock(@Param("ids") List<Long> ids);

    // =================================================================
    //  Search and filtering
    // =================================================================

    /**
     * Case-insensitive keyword search across name and description.
     *
     * <p><b>Note the {@code escape} clause.</b> In SQL's {@code LIKE}, {@code %} matches
     * any sequence and {@code _} matches any single character. If a user searches for
     * {@code "50%_off"} without those characters being escaped, the pattern matches far
     * more than intended - and with a crafted pattern it becomes a way to probe the
     * database's contents. The service escapes them; this query declares the escape
     * character so MySQL interprets the escaping the same way.
     *
     * <p>The explicit {@code escape '\\'} also makes the behaviour independent of the
     * server's {@code NO_BACKSLASH_ESCAPES} mode, which is a setting that varies between
     * installations and would otherwise change what this query matches.
     *
     * <p><b>The honest limitation.</b> {@code LIKE '%term%'} cannot use an index - a
     * leading wildcard rules it out - so this scans. For a catalogue of tens of thousands
     * of rows that is fine. The upgrade path is a MySQL {@code FULLTEXT} index with the
     * {@code ngram} parser (which handles CJK, where space-delimited word matching does
     * not work at all) and a native query, since JPA criteria has no {@code MATCH AGAINST}.
     * Recorded here so the ceiling is a known quantity rather than a surprise.
     */
    @Query("""
            select p from Product p
            where (lower(p.name) like :pattern escape '\\'
                or lower(p.description) like :pattern escape '\\')
            """)
    Page<Product> searchByKeyword(@Param("pattern") String pattern, Pageable pageable);

    @Query("""
            select p from Product p
            where p.active = true
              and (lower(p.name) like :pattern escape '\\'
                or lower(p.description) like :pattern escape '\\')
            """)
    Page<Product> searchActiveByKeyword(@Param("pattern") String pattern, Pageable pageable);

    // =================================================================
    //  Price statistics, for the dashboard
    // =================================================================

    @Query("select coalesce(min(p.price), 0) from Product p where p.active = true")
    BigDecimal findMinActivePrice();

    @Query("select coalesce(max(p.price), 0) from Product p where p.active = true")
    BigDecimal findMaxActivePrice();

    /**
     * Whether a product has ever been sold - i.e. whether it appears in any order line.
     *
     * <p>Drives the decision in {@code ProductServiceImpl.delete}: a product with no order
     * history can be hard-deleted, and one that has been sold cannot. Checking this before
     * attempting the delete is what turns a raw foreign-key violation (a 500) into a clear
     * 409 explaining that the product should be deactivated instead.
     */
    @Query("select count(oi) > 0 from OrderItem oi where oi.product.id = :productId")
    boolean isProductOrdered(@Param("productId") Long productId);
}

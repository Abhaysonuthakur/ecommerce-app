package com.shop.ecommerce.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * A person with an account.
 *
 * <h2>Relationships owned by this entity</h2>
 *
 * <pre>
 *   User 1 ──────── N Order     (a customer places many orders)
 *   User 1 ──────── 1 Cart      (a customer has one active cart)
 * </pre>
 *
 * <p>Both are annotated {@code @OneToMany} here and {@code @ManyToOne} /
 * {@code @OneToOne} on the far side. The rule that decides which side gets what:
 * <b>the side holding the foreign-key column is the owning side, and the
 * {@code mappedBy} attribute goes on the other one.</b>
 *
 * <p>{@code orders} and {@code cart} hold {@code user_id}, so {@link Order} and
 * {@link Cart} are the owners and both collections here carry {@code mappedBy}.
 * If {@code mappedBy} were omitted, Hibernate would create a <em>second</em>
 * join table for the same relationship and keep two inconsistent copies of it.
 *
 * <p><b>WHY must we still have the collections if the other side owns them?</b>
 * For traversal - and for cascade. {@code cascade = ALL} on {@code cart} means
 * deleting a user deletes their cart, which is what the database's
 * {@code ON DELETE CASCADE} also says. The JPA cascade makes it work at the
 * object level; the database constraint makes it true even for a manual DELETE.
 * Both are wanted, because either can be reached without the other.
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
        indexes = @Index(name = "idx_users_role", columnList = "role")
)
// Explicit accessors, NOT @Data. Lombok's @Data would generate equals/hashCode that
// recurse through `orders` -> `Order.user` -> `orders`..., and a toString that pulls
// every lazy collection into a log line. Both are real production bugs.
@Getter
@Setter
@NoArgsConstructor
// Excluding the collections keeps any incidental logging to one line.
@ToString(onlyExplicitlyIncluded = true, callSuper = true)
public class User extends BaseEntity {

    @ToString.Include
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * The login identifier.
     *
     * <p>{@code unique = true} here is a performance hint for Hibernate's schema
     * generation, not the real constraint - the database column carries a
     * {@code UNIQUE} index, and the collation {@code utf8mb4_0900_ai_ci} makes it
     * <em>case-insensitive</em>, so {@code Ada@Example.com} and {@code ada@example.com}
     * are the same account. Verified in {@code db/constraint-test.sql}.
     */
    @ToString.Include
    @Column(name = "email", nullable = false, length = 150, unique = true)
    private String email;

    /**
     * The BCrypt hash. Never the raw password, and never returned to a client.
     *
     * <p><b>Nullable on purpose.</b> An account created through Google has no local
     * password. A NOT NULL column would force us to invent a hash for it - a
     * credential that should not exist and could accidentally match something.
     *
     * <p>{@code length = 100}: a BCrypt hash is 60 characters. 100 leaves room for a
     * future algorithm change (Argon2 hashes are longer) without a migration.
     */
    @Column(name = "password", length = 100)
    private String password;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "address", length = 255)
    private String address;

    /**
     * The user's authorization level.
     *
     * <p>{@code EnumType.STRING} stores the name. Read {@link Role}'s javadoc for why
     * {@code ORDINAL} is a data-corruption bug rather than a style choice.
     *
     * <p>The token's {@code role} claim is a snapshot taken when the token was issued
     * and is <em>never</em> consulted for authorization - the JWT filter reloads this
     * row on every request. That is what makes a demotion effective immediately
     * instead of at the token's expiry.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role = Role.CUSTOMER;

    /**
     * Which road created this account. Used at sign-in to refuse a password login
     * against a Google-only account instead of comparing it to a null hash.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private AuthProvider provider = AuthProvider.LOCAL;

    /**
     * A disabled account authenticates nowhere. Checked in the JWT filter on every
     * request, so disabling a user takes effect immediately.
     */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    // =================================================================
    //  User 1:N Order
    // =================================================================
    /**
     * <b>One user places many orders.</b>
     *
     * <p>The {@code orders} table holds {@code user_id}, so {@link Order} is the
     * owning side and this collection is the inverse - hence {@code mappedBy = "user"}
     * naming the field on {@code Order}, not the column.
     *
     * <ul>
     *   <li>{@code fetch = LAZY} - the default for collections, stated explicitly so a
     *       later reader does not "helpfully" switch it to EAGER and turn every user
     *       query into a join across their whole purchase history.</li>
     *   <li>{@code cascade = ALL} - saving a user saves their orders. Deleting a user
     *       deletes their orders, matching the {@code ON DELETE RESTRICT} on the
     *       column... except that RESTRICT actually <em>forbids</em> deleting a user
     *       who has orders, which is the intent: order history outlives the account.
     *       The cascade here applies to persistence, not deletion.</li>
     *   <li>{@code orphanRemoval = true} - removing an {@code Order} from this list
     *       deletes it. Without it, removing from the collection only breaks the link
     *       and the orphaned row stays behind.</li>
     * </ul>
     *
     * <p>Initialised to an empty {@code ArrayList} so callers never have to null-check.
     * <b>Do not replace it with {@code List.of()}</b> - Hibernate needs a mutable
     * collection to manage, and an immutable one fails when it tries to add an element.
     */
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Order> orders = new ArrayList<>();

    // =================================================================
    //  User 1:1 Cart
    // =================================================================
    /**
     * <b>One user has one active cart.</b>
     *
     * <p>A {@code @OneToOne} rather than a {@code @OneToMany}, because the database
     * has a {@code UNIQUE (user_id)} index on {@code carts}. Modelling it as a
     * collection would tell every future reader that a user may have several carts
     * - the opposite of what the schema enforces.
     *
     * <p>Again {@code mappedBy = "user"}: {@code carts} holds the foreign key.
     * {@code cascade = ALL} plus {@code orphanRemoval} means the cart lives and dies
     * with its owner, which is what {@code ON DELETE CASCADE} says at the database
     * level too.
     *
     * <p>{@code LAZY} because most requests that touch a user - the JWT filter on
     * every single request, for one - have no interest in their cart. Fetching it
     * eagerly would cost a query per request for nothing.
     */
    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private Cart cart;

    // =================================================================
    //  Convenience
    // =================================================================

    /**
     * Whether this account can sign in with an email and password.
     * A Google-only account has no hash, so it cannot - and must be refused
     * rather than compared against null.
     */
    public boolean canUsePasswordLogin() {
        return password != null && !password.isBlank();
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** Keeps both sides of the relationship consistent in memory. */
    public void addOrder(Order order) {
        orders.add(order);
        order.setUser(this);
    }
}

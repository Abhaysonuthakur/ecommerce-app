package com.shop.ecommerce.repository;

import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Data access for {@link User}.
 *
 * <p>Spring Data generates the implementation from the method signatures. There is no class
 * implementing this interface anywhere in the project - which is the point of the
 * repository abstraction, and also the reason a method that does not match the naming
 * conventions fails at startup rather than at compile time.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Finds a user by email, case-insensitively.
     *
     * <p><b>Why the query and not {@code findByEmail(String)}?</b> A derived query would
     * compare exactly what it is given, so {@code ada@example.com} and
     * {@code Ada@Example.com} would be different accounts - and a user who registered with
     * one capital letter could not log in if they typed it differently.
     *
     * <p>The database's {@code utf8mb4_0900_ai_ci} collation already makes the unique
     * index case-insensitive, so in practice a plain derived query would work here. The
     * explicit {@code lower()} is kept anyway so that the behaviour does not depend on a
     * schema property: a move to a case-sensitive collation would otherwise silently change
     * who can log in, and nothing in the Java code would reveal that dependency.
     *
     * <p>This is the single most frequently executed query in the application - it runs on
     * every login and every registration - and it is served entirely by the unique index on
     * {@code email}.
     */
    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    /**
     * Whether an email is already registered.
     *
     * <p>Exists as a separate method rather than being expressed as
     * {@code findByEmailIgnoreCase(email).isPresent()}, because this compiles to a
     * {@code SELECT COUNT} - which does not have to materialise the row or hydrate an
     * entity. On the registration path, where the answer is usually "no" and the row does
     * not exist, that difference is the whole cost of the query.
     */
    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    /**
     * Counts accounts holding a given role.
     *
     * <p>Used for two things: the dashboard's customer count, and the "is this the last
     * admin?" check that stops the store from being locked out.
     */
    long countByRole(Role role);

    /**
     * Lists users, newest first, for the admin console.
     *
     * <p>Paginated because the user table is the one that grows without bound - a year from
     * now this could be a hundred thousand rows, and an unpaginated {@code findAll()} would
     * eventually be the reason the admin page times out.
     */
    Page<User> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Lists users filtered by role, for the admin's "show me the admins" view.
     */
    Page<User> findByRoleOrderByCreatedAtDesc(Role role, Pageable pageable);

    /**
     * Detects a duplicate email while excluding a specific user - the check an email-change
     * feature would need.
     *
     * <p>Not currently called, and kept deliberately: the reasoning is that the query is
     * subtle enough to get wrong (the obvious version reports a user's own email as a
     * conflict), and having the correct form here costs three lines. If it is still unused
     * after a year it should be deleted rather than kept "just in case" - unused code has a
     * cost too.
     */
    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email) and u.id <> :excludeId")
    boolean existsByEmailIgnoreCaseExcluding(@Param("email") String email, @Param("excludeId") Long excludeId);
}

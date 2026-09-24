package com.shop.ecommerce.service.impl;

import com.shop.ecommerce.dto.common.PageResponse;
import com.shop.ecommerce.dto.user.UpdateProfileRequest;
import com.shop.ecommerce.dto.user.UserResponse;
import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.entity.User;
import com.shop.ecommerce.exception.BadRequestException;
import com.shop.ecommerce.exception.ForbiddenException;
import com.shop.ecommerce.exception.ResourceNotFoundException;
import com.shop.ecommerce.mapper.EntityMapper;
import com.shop.ecommerce.repository.UserRepository;
import com.shop.ecommerce.security.config.CurrentUserResolver;
import com.shop.ecommerce.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profiles and account administration.
 *
 * <h2>The one method in the application that can create an administrator</h2>
 *
 * <p>{@link #updateRole} is the only code path that writes {@code Role.ADMIN}. Everything
 * else - registration, profile updates, Google sign-in - assigns
 * {@code Role.CUSTOMER} as a literal. That makes the question "how does somebody become an
 * admin?" answerable by reading one method, which is worth more than the handful of lines
 * it costs.
 *
 * <p>It is guarded at three levels, and all three are necessary:
 * <ol>
 *   <li>{@code @PreAuthorize("hasRole('ADMIN')")} - the caller must already be an admin.</li>
 *   <li>An admin may not change their own role. Without this, an admin could demote
 *       themselves and lock everyone out of administration.</li>
 *   <li>The last remaining admin may not be demoted. Without this, two admins can each
 *       demote the other "safely" and the store ends up with none.</li>
 * </ol>
 * Rules 2 and 3 are the kind no URL matcher can express - they depend on who is calling
 * and on the state of the table - which is exactly why they belong in the domain.
 */
@Service
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    /** Page-size ceiling for the admin user list. */
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final EntityMapper entityMapper;
    private final CurrentUserResolver currentUser;

    public UserServiceImpl(UserRepository userRepository,
                           EntityMapper entityMapper,
                           CurrentUserResolver currentUser) {
        this.userRepository = userRepository;
        this.entityMapper = entityMapper;
        this.currentUser = currentUser;
    }

    // =================================================================
    //  Self-service
    // =================================================================

    @Override
    public UserResponse getMyProfile() {
        return entityMapper.toUserResponse(requireCurrentUserEntity());
    }

    /**
     * Updates the caller's own profile.
     *
     * <p>Every field is treated as "null means leave unchanged", which is what makes this a
     * PATCH-shaped operation rather than a PUT that silently blanks whatever was omitted.
     * The DTO has no {@code email}, {@code password} or {@code role} component at all, so
     * there is no code here that could accidentally accept one - the protection is
     * structural rather than a check that might be deleted.
     */
    @Override
    @Transactional
    public UserResponse updateMyProfile(UpdateProfileRequest request) {
        if (request.isEmpty()) {
            /*
             * A no-op update would still touch updated_at via auditing, producing a
             * timestamp that moved without a change. Small, but it is a lie in the audit
             * trail, and it is cheap to refuse instead.
             */
            throw new BadRequestException(
                    "No fields to update. Supply at least one of: name, phone, address.");
        }

        User user = requireCurrentUserEntity();

        if (request.name() != null) {
            user.setName(request.name().trim());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone().trim());
        }
        if (request.address() != null) {
            user.setAddress(request.address().trim());
        }

        // Flush so the auditing timestamp is refreshed before the response is mapped -
        // @LastModifiedDate is applied at flush, not when the setter runs.
        userRepository.flush();

        return entityMapper.toUserResponse(user);
    }

    // =================================================================
    //  Admin
    // =================================================================

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<UserResponse> listUsers(int page, int size, Role roleFilter) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        /*
         * Fixed sort, no client-supplied sort expression. The user list is an
         * administrative lookup rather than a browsing surface, so there is no reason to
         * let a caller choose the ordering - and every reason not to: the whitelist for
         * this entity would have to include nothing sensitive, which is a rule that has to
         * be re-checked every time a column is added to users.
         *
         * Newest first, and since Pageable's sort is only createdAt, the repository
         * methods' names (...OrderByCreatedAtDesc) pin it anyway.
         */
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Page<User> result = roleFilter != null
                ? userRepository.findByRoleOrderByCreatedAtDesc(roleFilter, pageable)
                : userRepository.findAllByOrderByCreatedAtDesc(pageable);

        return PageResponse.from(result, entityMapper::toUserResponse);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse getUserById(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.user(userId));
        return entityMapper.toUserResponse(user);
    }

    /**
     * Changes a user's role. The only path to {@code Role.ADMIN}.
     *
     * <h3>Why the two domain rules cannot live in the filter chain</h3>
     *
     * <p>{@code hasRole('ADMIN')} answers "is the caller an admin". It cannot answer "is the
     * caller <em>this</em> admin" - that needs the request body - nor "would this leave the
     * store with no admins", which needs a count. Both are properties of the request and the
     * current table contents together, so they belong here.
     *
     * <p>They produce a <b>403</b> rather than a 409: the request is not in conflict with a
     * resource's state, the caller is simply not permitted to do this thing. And the
     * distinction is genuinely useful to a client - a 403 says "signing in differently will
     * not help", which is true, whereas a 409 would suggest retrying.
     *
     * <h3>Why a no-op role change is refused</h3>
     *
     * <p>Setting a user to the role they already have would pass both guards and write an
     * updated_at. Refusing it makes the "last admin" count a meaningful thing to reason
     * about: any call that reaches the count is a call that actually changes something.
     */
    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse updateRole(Long userId, Role newRole) {
        if (newRole == null) {
            throw new BadRequestException("A target role is required.");
        }

        User target = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.user(userId));

        Long callerId = currentUser.requireCurrentUserId();

        if (target.getId().equals(callerId) && target.getRole() != newRole) {
            /*
             * Rule 2: no self-demotion. Not "no self-change" - an admin setting their own
             * role to ADMIN when it is already ADMIN is caught by the no-op check below and
             * is harmless. What must be impossible is an admin removing their own access,
             * because the most likely moment for that is a mis-click, and the consequence
             * is being locked out of the console. Recovering would require direct database
             * access.
             */
            throw ForbiddenException.cannotDemoteSelf();
        }

        if (target.getRole() == newRole) {
            throw new BadRequestException(
                    "User %d already has the role %s.".formatted(userId, newRole));
        }

        if (target.getRole() == Role.ADMIN && newRole != Role.ADMIN) {
            /*
             * Rule 3: the last admin is permanent.
             *
             * Counting rather than checking for "another admin exists" is deliberate. The
             * count is the fact the rule is about: if the number of admins is 1, this
             * demotion would leave 0 and nobody could ever promote anyone again.
             *
             * There is a race here - two admins demoting each other concurrently could both
             * see a count of 2 - and it is accepted rather than fixed with a lock. It
             * requires two admins to demote themselves within the same few hundred
             * milliseconds, both requests to pass the count, and both to commit. The cost of
             * a pessimistic lock on the whole users table for every role change is worse
             * than the risk, and the recovery (one SQL statement) is trivial. Naming the
             * race is more useful than pretending it does not exist.
             */
            long adminCount = userRepository.countByRole(Role.ADMIN);
            if (adminCount <= 1) {
                throw ForbiddenException.lastAdmin();
            }
        }

        target.setRole(newRole);
        userRepository.flush();

        /*
         * Note what is NOT done here: no tokens are revoked, and no session is
         * invalidated.
         *
         * That is not an oversight - it is why JwtAuthenticationFilter reads the role from
         * the database row on every single request instead of trusting the role claim
         * inside the token. A demoted admin's existing token becomes powerless on the very
         * next request, with no revocation list and no denylist to maintain. A promoted
         * user gains access just as immediately.
         *
         * The cost is one indexed primary-key lookup per authenticated request. In exchange,
         * authorization state has exactly one source of truth. See the filter's javadoc.
         */
        log.info("Role changed: user={} {} -> {} by admin={}", userId, target.getRole(), newRole, callerId);

        return entityMapper.toUserResponse(target);
    }

    // =================================================================
    //  Helpers
    // =================================================================

    /**
     * The current user as a managed entity.
     *
     * <p>The security context carries an {@code AuthenticatedUser} record holding the id,
     * email and role - enough to answer "who is this" without a query. It is deliberately
     * <em>not</em> enough to answer "is this account still enabled", and it is not a mutable
     * object that can be edited.
     *
     * <p>So anything that needs to <em>change</em> the user loads the real row. Doing that
     * by id - rather than trusting any other field in the record - keeps the query scoped to
     * a single indexed lookup.
     */
    private User requireCurrentUserEntity() {
        Long userId = currentUser.requireCurrentUserId();
        return userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.user(userId));
    }
}

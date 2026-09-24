package com.shop.ecommerce.service.impl;

import com.shop.ecommerce.dto.auth.AuthResponse;
import com.shop.ecommerce.dto.auth.LoginRequest;
import com.shop.ecommerce.dto.auth.RegisterRequest;
import com.shop.ecommerce.dto.user.UserResponse;
import com.shop.ecommerce.entity.AuthProvider;
import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.entity.User;
import com.shop.ecommerce.exception.ConflictException;
import com.shop.ecommerce.exception.UnauthorizedException;
import com.shop.ecommerce.mapper.EntityMapper;
import com.shop.ecommerce.repository.UserRepository;
import com.shop.ecommerce.security.config.CurrentUserResolver;
import com.shop.ecommerce.security.jwt.JwtService;
import com.shop.ecommerce.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and sign-in.
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EntityMapper entityMapper;
    private final CurrentUserResolver currentUserResolver;

    public AuthServiceImpl(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           EntityMapper entityMapper,
                           CurrentUserResolver currentUserResolver) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.entityMapper = entityMapper;
        this.currentUserResolver = currentUserResolver;
    }

    /**
     * Creates an account and signs the new user in.
     *
     * <h3>The role is a literal, and that is the point</h3>
     *
     * <p>{@code user.setRole(Role.CUSTOMER)} is the only role assignment on this path. It
     * does not read {@code request}, it does not consult a configuration flag, and it cannot
     * be influenced by anything the client sent - because {@link RegisterRequest} has no
     * {@code role} component at all.
     *
     * <p>This is deliberate rather than defensive. The classic privilege-escalation bug is a
     * registration endpoint that binds a request onto an entity, so a client adding
     * {@code "role": "ADMIN"} to the JSON becomes an administrator. The endpoint works
     * perfectly for every legitimate user, so nothing looks wrong.
     */
    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normaliseEmail(request.email());

        /*
         * A pre-check for a friendly message, NOT the guarantee.
         *
         * Two registrations for the same email arriving simultaneously both pass this check
         * and both proceed to the insert - at which point the database's unique index rejects
         * one of them. That is the race this check cannot win, and the catch below is what
         * handles it. The pre-check exists only so the common case produces a clean 409
         * without touching the database's error handling.
         */
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ConflictException.emailExists(email);
        }

        User user = new User();
        user.setEmail(email);
        user.setName(request.name().trim());
        user.setPhone(request.phone() != null && !request.phone().isBlank() ? request.phone().trim() : null);
        user.setProvider(AuthProvider.LOCAL);
        user.setRole(Role.CUSTOMER);   // <-- see the method javadoc
        user.setEnabled(true);

        /*
         * BCrypt, one way, with a per-password random salt embedded in the output.
         * The raw password exists only as this method's argument and is never stored,
         * logged, or put in a DTO.
         */
        user.setPassword(passwordEncoder.encode(request.password()));

        User saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            /*
             * The race lost. Two requests for the same email both got past the pre-check
             * and the unique index rejected this one.
             *
             * Translated to the same 409 the pre-check produces, so the client cannot tell
             * - and does not need to - which path it took.
             */
            log.debug("Duplicate email on registration, caught by the unique index: {}", email);
            throw ConflictException.emailExists(email);
        }

        log.info("Registered a new CUSTOMER account: userId={}", saved.getId());

        return buildAuthResponse(saved);
    }

    /**
     * Verifies credentials and issues a token.
     *
     * <h3>Every failure returns the identical response</h3>
     *
     * <p>Unknown email, wrong password, a Google-only account with no password, and a
     * disabled account all raise {@link UnauthorizedException#badCredentials()} - except the
     * disabled case, which is reported separately (see below). No error message ever says
     * <em>which</em> part was wrong.
     *
     * <p>That uniformity is a security control, not laziness. "No account with that email"
     * turns the endpoint into a free account-enumeration service: submit a list of addresses,
     * learn which ones hold accounts here. That is valuable alone, and far more valuable
     * combined with a password dump from an unrelated breach - it says which credentials are
     * worth trying.
     *
     * <h3>Why the disabled case is different</h3>
     *
     * <p>It is the one exception, and it is a judgement call. The credentials were
     * <b>correct</b>, so the caller has already proven they own the account - telling them it
     * is disabled leaks nothing they could not learn by asking support, and it prevents an
     * infuriating "invalid password" loop for someone whose password is fine.
     */
    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = normaliseEmail(request.email());

        User user = userRepository.findByEmailIgnoreCase(email)
                /*
                 * `orElseThrow` with the SAME exception as a wrong password, so that an
                 * unknown email and a wrong password are indistinguishable from outside.
                 */
                .orElseThrow(UnauthorizedException::badCredentials);

        /*
         * A Google-only account cannot log in with a password.
         *
         * Note the check before the comparison rather than after: calling
         * `passwordEncoder.matches(raw, null)` would throw a NullPointerException, which
         * would surface as a 500 - telling an attacker that this email exists and is
         * Google-only. Failing here produces the same generic 401 as any other failure.
         */
        if (!user.canUsePasswordLogin()) {
            log.debug("Password login attempted against a social-only account");
            throw UnauthorizedException.passwordLoginNotAvailable();
        }

        /*
         * BCrypt verification. The raw password is passed in alongside the stored hash;
         * BCrypt extracts the salt from the hash itself, so nothing else is needed.
         *
         * This call is deliberately slow (~100 ms). That is the protection - it makes
         * offline brute force of a leaked table expensive.
         */
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            /*
             * Logged at debug WITHOUT the attempted value. A log line containing a
             * guessed password is a credential in a log aggregator, and users reuse
             * passwords - so it may well be a credential somewhere else too.
             */
            log.debug("Failed password login for an existing account");
            throw UnauthorizedException.badCredentials();
        }

        if (!user.isEnabled()) {
            throw UnauthorizedException.accountDisabled();
        }

        log.info("Password login succeeded: userId={}", user.getId());

        return buildAuthResponse(user);
    }

    /**
     * The current user's live profile.
     *
     * <h3>Why this re-reads the database instead of trusting the token</h3>
     *
     * <p>The JWT filter has already authenticated the request and put an
     * {@code AuthenticatedUser} record - id, email, role - into the security context. It
     * would be one line to return that record as a {@code UserResponse} and skip the query.
     *
     * <p>It would also be wrong. That record is a <em>snapshot from the moment the filter
     * read the row</em>, and it does not carry the name, phone, address or provider the
     * profile page needs - those are not in the token either. More importantly, the
     * profile page is exactly where a user checks whether a change they just made took
     * effect, so it must read the current row. Answering from a token issued an hour ago
     * would show stale data on the one screen whose purpose is to show fresh data.
     *
     * <p>This is the same reason {@code JwtAuthenticationFilter} reloads the user on every
     * request rather than trusting the role claim inside the token: there is one source of
     * truth for user state, and it is the database.
     */
    @Override
    @Transactional(readOnly = true)
    public UserResponse currentUser() {
        /*
         * requireCurrentUserId throws UnauthorizedException when the request is anonymous.
         * That is reachable in practice - this route is protected, but a caller with an
         * expired token reaches the filter chain's rejection rather than this method. The
         * check is here so the method is safe to call from anywhere, not because a normal
         * request hits it.
         */
        Long userId = currentUserResolver.requireCurrentUserId();

        User user = userRepository.findById(userId)
                /*
                 * The row is gone: the account was deleted while a valid token was still in
                 * circulation. A 401 rather than a 404, because from the caller's point of
                 * view their session is over, and the correct instruction is to sign in
                 * again - which is what 401 means to every HTTP client.
                 */
                .orElseThrow(() -> UnauthorizedException.invalidToken("This account no longer exists."));

        return entityMapper.toUserResponse(user);
    }

    // =================================================================
    //  Helpers
    // =================================================================

    /**
     * Builds the response both authentication paths produce.
     *
     * <p>One factory so the password path and the OAuth2 path cannot drift in how they shape
     * a successful sign-in. A client that had to handle two subtly different responses
     * depending on how the user signed in would be a client with a bug waiting to happen.
     */
    private AuthResponse buildAuthResponse(User user) {
        String token = jwtService.generateToken(user);
        UserResponse userResponse = entityMapper.toUserResponse(user);
        return AuthResponse.of(token, jwtService.getExpirationSeconds(), userResponse);
    }

    /**
     * Trims and lowercases an email.
     *
     * <p>Lowercasing matters for consistency between the paths: the database's
     * {@code utf8mb4_0900_ai_ci} collation already makes the unique index case-insensitive,
     * so {@code Ada@Example.com} and {@code ada@example.com} cannot both exist. Normalising
     * here means the <em>stored</em> form is consistent too, so a search or a report does not
     * have to think about case.
     *
     * <p>An accidental trailing space is the most common form of "I typed it correctly and it
     * says wrong password", and trimming removes that whole class of support ticket.
     */
    private String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}

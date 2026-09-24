package com.shop.ecommerce.service;

import com.shop.ecommerce.dto.auth.AuthResponse;
import com.shop.ecommerce.dto.auth.LoginRequest;
import com.shop.ecommerce.dto.auth.RegisterRequest;
import com.shop.ecommerce.dto.user.UserResponse;

/**
 * Registration and sign-in.
 */
public interface AuthService {

    /**
     * Creates a new account.
     *
     * <p>The role assigned is always {@code CUSTOMER} - it is a literal in the
     * implementation, not a value read from anywhere. See {@code RegisterRequest}'s javadoc
     * for the three layers that guarantee a client cannot influence it.
     *
     * @throws com.shop.ecommerce.exception.ConflictException if the email is already registered
     */
    AuthResponse register(RegisterRequest request);

    /**
     * Verifies credentials and issues a token.
     *
     * @throws com.shop.ecommerce.exception.UnauthorizedException for an unknown email, a wrong
     *         password, a Google-only account attempting a password login, or a disabled
     *         account. <b>All four produce the same response</b>, deliberately - see
     *         {@code UnauthorizedException}'s javadoc on account enumeration.
     */
    AuthResponse login(LoginRequest request);

    /**
     * The currently authenticated user's public profile.
     *
     * <p>Reads the identity from the security context and loads the live row, rather than
     * returning the {@code AuthenticatedUser} record the JWT filter put there. That record
     * carries only the id, email and role - enough for authorization, but not the name,
     * phone or address the profile page displays, and not {@code provider}, which the UI
     * needs in order to decide whether to show a change-password form.
     *
     * @throws com.shop.ecommerce.exception.UnauthorizedException if there is no
     *         authenticated user, or the row has since been deleted
     */
    UserResponse currentUser();
}

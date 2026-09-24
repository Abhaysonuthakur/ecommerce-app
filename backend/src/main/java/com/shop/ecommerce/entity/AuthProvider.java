package com.shop.ecommerce.entity;

/**
 * How an account was created.
 *
 * <p>This exists because the two roads to an account behave differently and the
 * difference matters at sign-in: a {@code LOCAL} account has a password hash to
 * verify, a {@code GOOGLE} account does not - and a Google account attempting a
 * password login must be refused rather than compared against a null hash.
 */
public enum AuthProvider {

    /** Registered here with an email and a password. Has a BCrypt hash. */
    LOCAL,

    /** Created or linked through Google. Has no password; may also have one if linked. */
    GOOGLE
}

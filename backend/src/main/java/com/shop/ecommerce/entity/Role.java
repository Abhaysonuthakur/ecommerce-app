package com.shop.ecommerce.entity;

/**
 * Who a user is, for authorization purposes.
 *
 * <p><b>WHY only two roles?</b> Because a third role nobody uses is a permission
 * that nobody remembers to check. Every endpoint in this application has exactly
 * one of three requirements: public, any authenticated customer, or admin. When a
 * requirement genuinely appears for a third role, adding it is one line here plus
 * one migration - far cheaper than auditing a permission matrix that was never used.
 *
 * <p><b>WHERE the role is assigned - the complete list:</b>
 * <ol>
 *   <li>{@code AuthServiceImpl.register} - a literal {@code Role.CUSTOMER}.</li>
 *   <li>{@code OAuth2LoginSuccessHandler} - a literal {@code Role.CUSTOMER}.</li>
 *   <li>{@code AdminUserController.updateRole} - the only place a role can change,
 *       reachable only by an existing admin.</li>
 *   <li>{@code DataSeeder} - the bootstrap admin, and only when explicitly enabled.</li>
 * </ol>
 *
 * <p>No request DTO in this project has a {@code role} field. That is the whole
 * privilege-escalation defence: a field that does not exist cannot be forged.
 *
 * <p><b>WHY store the name and not the number?</b> The {@code @Enumerated(EnumType.STRING)}
 * on {@link User#getRole() role} writes {@code "ADMIN"} rather than {@code 1}. With
 * {@code ORDINAL}, inserting a new constant in the middle of this enum would silently
 * reinterpret every existing row - every admin becomes a customer, or worse, every
 * customer becomes an admin - and no error is raised.
 */
public enum Role {

    /** A normal shopper. Can browse, hold a cart, and place orders. */
    CUSTOMER,

    /** Store operator. Can manage the catalogue and see every order. */
    ADMIN;

    /**
     * Spring Security's {@code hasRole("ADMIN")} compares against an authority named
     * {@code ROLE_ADMIN} - it prepends the prefix itself. Building the authority name
     * in one place avoids the {@code hasRole("ROLE_ADMIN")} mistake, which looks
     * correct, reads as correct, and denies everyone including administrators.
     */
    public String authority() {
        return "ROLE_" + name();
    }
}

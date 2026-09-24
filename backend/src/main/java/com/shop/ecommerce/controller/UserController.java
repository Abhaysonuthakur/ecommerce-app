package com.shop.ecommerce.controller;

import com.shop.ecommerce.dto.common.PageResponse;
import com.shop.ecommerce.dto.user.UpdateProfileRequest;
import com.shop.ecommerce.dto.user.UpdateRoleRequest;
import com.shop.ecommerce.dto.user.UserResponse;
import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Profile and user-administration endpoints.
 *
 * <h2>Two scopes, two routes, no branch</h2>
 *
 * <pre>
 *   /api/users/me                 the caller's own profile
 *   /api/admin/users/*            everyone's, admin only
 * </pre>
 *
 * <p>There is no {@code /api/users/{id}} on the customer side. To read your own profile you
 * go to {@code /me}; to read somebody else's you must be an admin. That means a customer
 * cannot even express "show me user 7", let alone attempt it - which is stronger than a
 * route that accepts an id and then should check ownership.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Users", description = "Own profile (self-service) and user administration (admin)")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // =================================================================
    //  Self-service
    // =================================================================

    @GetMapping("/users/me")
    @Operation(summary = "Get your own profile")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's profile"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    public ResponseEntity<UserResponse> getMyProfile() {
        return ResponseEntity.ok(userService.getMyProfile());
    }

    /**
     * Updates your own profile.
     *
     * <p>{@code PUT} with a PATCH's semantics: every field is optional and a null means
     * "leave unchanged". That is a deliberate trade - a strict PUT would require every field
     * on every save, so a client editing only a phone number would have to resend the name
     * and address it did not change, and a stale client would overwrite a change it never
     * saw.
     *
     * <p>The DTO has no {@code email}, {@code password} or {@code role}. The role in
     * particular is not checked-and-rejected here - it is <b>absent</b>, so there is no code
     * path that could accept one even if a client sent it. Email and password are separate
     * flows with their own verification requirements; see {@code UpdateProfileRequest}'s
     * javadoc.
     */
    @PutMapping("/users/me")
    @Operation(summary = "Update your own profile",
            description = "Omitted fields are left unchanged. Email, password and role cannot be changed here - the request type has no such fields.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated profile"),
            @ApiResponse(responseCode = "400", description = "No fields supplied, or validation failed"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    public ResponseEntity<UserResponse> updateMyProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateMyProfile(request));
    }

    // =================================================================
    //  Admin
    // =================================================================

    /**
     * Lists users, optionally filtered by role.
     *
     * <p>Paginated and capped at 100 per page. There is no client-supplied sort: a user list
     * is an administrative lookup, not a browsing surface, and a sort whitelist over this
     * entity would have to be re-audited every time a column is added to {@code users}.
     * Fixing the order removes that maintenance burden entirely.
     *
     * <p>{@code role} is an enum, so {@code ?role=customer} is rejected with a message
     * listing the valid values. A free-text role parameter would silently return an empty
     * page for a near-miss, which reads as "there are no customers".
     */
    @GetMapping("/admin/users")
    @Operation(summary = "List users (admin)",
            description = "Paginated, newest first. Optional `role` filter. Never includes a password field.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of users"),
            @ApiResponse(responseCode = "400", description = "Invalid page, size, or role value"),
            @ApiResponse(responseCode = "403", description = "Not an admin")
    })
    public ResponseEntity<PageResponse<UserResponse>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Role role) {

        return ResponseEntity.ok(userService.listUsers(page, size, role));
    }

    @GetMapping("/admin/users/{userId}")
    @Operation(summary = "Get one user by id (admin)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The user"),
            @ApiResponse(responseCode = "403", description = "Not an admin"),
            @ApiResponse(responseCode = "404", description = "No user with that id")
    })
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long userId) {
        return ResponseEntity.ok(userService.getUserById(userId));
    }

    /**
     * Changes a user's role. <b>The only way an administrator is created.</b>
     *
     * <p>Registration assigns {@code CUSTOMER} as a literal; Google sign-in assigns
     * {@code CUSTOMER} as a literal. This endpoint is the sole exception, and it is guarded
     * three times over:
     *
     * <ol>
     *   <li>The URL rule: {@code /api/admin/**} requires the ADMIN role in the filter chain.</li>
     *   <li>{@code @PreAuthorize("hasRole('ADMIN')")} on the service method, so a future
     *       caller that bypasses the controller is still refused.</li>
     *   <li>Two domain rules that no URL pattern can express: an admin may not change their
     *       own role, and the last remaining admin may not be demoted. Both are 403s,
     *       because they depend on <em>who is asking</em> and on the state of the table - not
     *       on the shape of the request.</li>
     * </ol>
     *
     * <p>Promotion and demotion take effect on the target's <b>very next request</b>, with
     * no token revocation and no session store. That works because
     * {@code JwtAuthenticationFilter} reads the role from the database row each time instead
     * of trusting the {@code role} claim inside the token. The cost is one indexed primary
     * key lookup per authenticated request; the benefit is that authorization state has
     * exactly one source of truth. See the filter's javadoc.
     */
    @PatchMapping("/admin/users/{userId}/role")
    @Operation(summary = "Change a user's role (admin)",
            description = """
                    The only endpoint that can grant the ADMIN role. Refuses self-demotion and refuses to demote
                    the last remaining administrator. Changes take effect on the target's next request - tokens are
                    not revoked, because the filter reads the role from the database rather than from the token.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated user"),
            @ApiResponse(responseCode = "400", description = "Missing role, or the user already has that role"),
            @ApiResponse(responseCode = "403", description = "Not an admin, self-demotion, or the last admin"),
            @ApiResponse(responseCode = "404", description = "No user with that id")
    })
    public ResponseEntity<UserResponse> updateRole(@PathVariable Long userId,
                                                  @Valid @RequestBody UpdateRoleRequest request) {
        return ResponseEntity.ok(userService.updateRole(userId, request.role()));
    }
}

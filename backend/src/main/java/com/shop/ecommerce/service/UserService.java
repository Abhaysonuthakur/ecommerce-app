package com.shop.ecommerce.service;

import com.shop.ecommerce.dto.common.PageResponse;
import com.shop.ecommerce.dto.user.UpdateProfileRequest;
import com.shop.ecommerce.dto.user.UserResponse;
import com.shop.ecommerce.entity.Role;

/**
 * Profiles and account administration.
 */
public interface UserService {

    /** The current user's own profile. */
    UserResponse getMyProfile();

    /** Updates the current user's own profile. */
    UserResponse updateMyProfile(UpdateProfileRequest request);

    // -----------------------------------------------------------------
    //  Admin
    // -----------------------------------------------------------------

    PageResponse<UserResponse> listUsers(int page, int size, Role roleFilter);

    UserResponse getUserById(Long userId);

    /**
     * Changes a user's role.
     *
     * <p>This is the only method in the application that can produce an administrator. It is
     * guarded by {@code @PreAuthorize("hasRole('ADMIN')")} and by two domain rules that no
     * request pattern can express: an admin may not demote themselves, and the last remaining
     * admin may not be demoted.
     */
    UserResponse updateRole(Long userId, Role newRole);
}

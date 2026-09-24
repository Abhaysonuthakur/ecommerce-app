package com.shop.ecommerce.exception;

import com.shop.ecommerce.dto.common.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Authenticated, but not permitted.
 *
 * <p>The distinction from {@link UnauthorizedException} is the whole reason both exist:
 * <b>401 means "sign in"</b> and <b>403 means "signing in again will not help"</b>. A client
 * that treats them the same sends a customer into a login loop they cannot escape.
 *
 * <p>Raised when the domain knows something the URL matcher does not - for example an admin
 * trying to demote themselves, which is a 403 for a reason no request pattern can express.
 * Ordinary role checks never reach this class: those are refused by the filter chain or by
 * {@code @PreAuthorize}, both of which produce the same JSON body through the same writer.
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, message);
    }

    public static ForbiddenException cannotDemoteSelf() {
        return new ForbiddenException("You cannot change your own role");
    }

    public static ForbiddenException lastAdmin() {
        return new ForbiddenException(
                "This is the only administrator account. Promote another user first.");
    }

    public static ForbiddenException notOwner(String resource) {
        return new ForbiddenException("You do not have access to this " + resource);
    }
}

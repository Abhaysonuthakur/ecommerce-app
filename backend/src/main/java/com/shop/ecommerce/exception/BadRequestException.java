package com.shop.ecommerce.exception;

import com.shop.ecommerce.dto.common.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * The request was understood, and is wrong.
 *
 * <p>The distinction from validation failures: a validation failure means a field's value
 * does not satisfy a constraint that can be evaluated on that field alone ("quantity must be
 * at least 1"). This exception is for rules that need other data to evaluate - "minimum
 * price must not exceed maximum price", "you cannot sort by that field".
 *
 * <p>Both produce a 400, because a client fixes both by changing the request. The separate
 * types exist so the handler can attach {@code fieldErrors} to one and not the other.
 */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message);
    }

    public BadRequestException(String errorCode, String message) {
        super(HttpStatus.BAD_REQUEST, errorCode, message);
    }
}

package com.equadis.portal.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Every error the API can return, in one place.
 *
 * <p>Each code has a stable machine-readable name (part of the API contract: supplier and
 * retailer systems may branch on it), an HTTP status, and - when it comes from the business
 * appendix - the business rule it protects. Keeping them in one enum makes the error
 * catalogue easy to review and to document.
 *
 * <p>Status conventions:
 * <ul>
 *     <li>400 - the request itself is malformed (syntax, types, missing fields),</li>
 *     <li>401 / 403 - identity missing or not allowed,</li>
 *     <li>404 - not found <i>or not visible to the caller</i> (we never reveal that a resource
 *         of another marketplace or another supplier exists),</li>
 *     <li>409 - conflicts with the current state (concurrent change, overlap, duplicate),</li>
 *     <li>422 - well-formed, but breaks a business rule,</li>
 *     <li>500 - our fault; the response carries a correlation id to find the logs.</li>
 * </ul>
 */
public enum ErrorCode {

    // --- Request & identity ---------------------------------------------------------------
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, null),
    REQUEST_VALIDATION_FAILED(HttpStatus.BAD_REQUEST, null),
    INVALID_PAGE_CURSOR(HttpStatus.BAD_REQUEST, null),
    REQUEST_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, null),
    MISSING_IDENTITY(HttpStatus.UNAUTHORIZED, null),
    INVALID_IDENTITY(HttpStatus.UNAUTHORIZED, null),
    ROLE_NOT_ALLOWED(HttpStatus.FORBIDDEN, null),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, null),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, null),

    // --- Directory (suppliers, retailers) -------------------------------------------------
    SUPPLIER_NOT_FOUND(HttpStatus.NOT_FOUND, null),
    RETAILER_NOT_FOUND(HttpStatus.NOT_FOUND, null),
    SUPPLIER_NAME_TAKEN(HttpStatus.CONFLICT, "BR-01"),
    RETAILER_LOCATION_INCOMPLETE(HttpStatus.UNPROCESSABLE_ENTITY, "BR-02"),

    // --- Partnerships ---------------------------------------------------------------------
    PARTNERSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, null),
    PARTNERSHIP_INVALID_PERIOD(HttpStatus.UNPROCESSABLE_ENTITY, "BR-03"),
    PARTNERSHIP_OVERLAP(HttpStatus.CONFLICT, "BR-04"),
    PARTNERSHIP_NOT_RENEWABLE(HttpStatus.UNPROCESSABLE_ENTITY, "BR-06"),
    PARTNERSHIP_NOT_TERMINABLE(HttpStatus.UNPROCESSABLE_ENTITY, "BR-07"),

    // --- Offers ---------------------------------------------------------------------------
    OFFER_NOT_FOUND(HttpStatus.NOT_FOUND, null),
    OFFER_INVALID_TERMS(HttpStatus.UNPROCESSABLE_ENTITY, "BR-09"),
    OFFER_NOT_EDITABLE(HttpStatus.UNPROCESSABLE_ENTITY, "BR-08"),
    OFFER_INVALID_TRANSITION(HttpStatus.UNPROCESSABLE_ENTITY, "BR-08"),
    OFFER_EXPIRED(HttpStatus.UNPROCESSABLE_ENTITY, "BR-09"),
    OFFER_REFERENCE_TAKEN(HttpStatus.CONFLICT, "BR-08"),

    // --- Subscriptions --------------------------------------------------------------------
    SUBSCRIPTION_ALREADY_ACTIVE(HttpStatus.CONFLICT, "BR-11"),

    // --- Imports --------------------------------------------------------------------------
    IMPORT_NOT_FOUND(HttpStatus.NOT_FOUND, null),
    IMPORT_FILE_MISSING(HttpStatus.BAD_REQUEST, null),
    IMPORT_FILE_EMPTY(HttpStatus.UNPROCESSABLE_ENTITY, null),
    IMPORT_FILE_NOT_UTF8(HttpStatus.UNPROCESSABLE_ENTITY, null),
    IMPORT_FILE_INVALID_HEADER(HttpStatus.UNPROCESSABLE_ENTITY, null),
    IMPORT_FILE_UNREADABLE(HttpStatus.UNPROCESSABLE_ENTITY, null),
    IMPORT_FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, null),

    // --- Our fault ------------------------------------------------------------------------
    UNEXPECTED_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, null);

    private final HttpStatus status;
    private final String businessRule;

    ErrorCode(HttpStatus status, String businessRule) {
        this.status = status;
        this.businessRule = businessRule;
    }

    public HttpStatus status() {
        return status;
    }

    /** The business rule (e.g. "BR-08") this error protects, or {@code null}. */
    public String businessRule() {
        return businessRule;
    }
}

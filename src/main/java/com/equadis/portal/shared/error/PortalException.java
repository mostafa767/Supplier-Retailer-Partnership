package com.equadis.portal.shared.error;

import com.equadis.portal.shared.validation.Violation;

import java.util.List;
import java.util.Objects;

/**
 * The single exception type for every <i>expected</i> failure (not found, rule broken,
 * conflict...). The {@link ErrorCode} decides the HTTP status; the message is the
 * human-readable detail returned to the caller.
 *
 * <p>Why one class plus an enum instead of a deep exception hierarchy: the error catalogue
 * stays in one place ({@link ErrorCode}), handlers need no {@code instanceof} chains, and a
 * new error is one enum constant.
 *
 * <p>Messages must never contain secrets or personal data: they are returned to callers and
 * written to logs.
 */
public class PortalException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient List<Violation> violations;

    public PortalException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public PortalException(ErrorCode errorCode, String message, List<Violation> violations) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.violations = List.copyOf(violations);
    }

    /** Convenience for the most common case: "{@code Offer 1234 was not found}". */
    public static PortalException notFound(ErrorCode errorCode, String resourceName, Object id) {
        return new PortalException(errorCode, "%s %s was not found".formatted(resourceName, id));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<Violation> violations() {
        return violations;
    }
}

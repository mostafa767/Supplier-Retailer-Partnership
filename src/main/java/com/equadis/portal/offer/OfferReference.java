package com.equadis.portal.offer;

import com.equadis.portal.shared.validation.Violation;

import java.util.Locale;
import java.util.Optional;

/**
 * The supplier's own identifier for an offer (BR-08: unique per supplier).
 *
 * <p>Assumption (documented): references are compared <b>case-insensitively</b> and without
 * surrounding spaces - "SUMMER-01", "summer-01" and " Summer-01 " are the same offer. Supplier
 * systems often change case between exports; treating them as different offers would create
 * exactly the duplicates R3 forbids. The database index uses the same rule
 * ({@code lower(reference)}).
 */
public final class OfferReference {

    public static final int MAX_LENGTH = 64;

    private OfferReference() {
    }

    /** Trims the raw value; an empty result means "missing". */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.strip();
    }

    /** The comparison key: the same value the unique index uses. */
    public static String key(String normalizedReference) {
        return normalizedReference.toLowerCase(Locale.ROOT);
    }

    public static Optional<Violation> validate(String normalizedReference, OfferField.Naming naming) {
        String field = OfferField.REFERENCE.nameIn(naming);
        if (normalizedReference.isEmpty()) {
            return Optional.of(new Violation(field, "REFERENCE_REQUIRED", "Reference is required"));
        }
        if (normalizedReference.length() > MAX_LENGTH) {
            return Optional.of(new Violation(field, "REFERENCE_TOO_LONG",
                    "Reference must be at most %d characters".formatted(MAX_LENGTH)));
        }
        if (normalizedReference.chars().anyMatch(Character::isISOControl)) {
            return Optional.of(new Violation(field, "REFERENCE_INVALID_CHARACTERS",
                    "Reference must not contain control characters"));
        }
        return Optional.empty();
    }
}

package com.equadis.portal.shared.validation;

import java.util.Objects;

/**
 * One broken rule on one field.
 *
 * <p>Business validation returns a <i>list</i> of violations instead of throwing on the first
 * one: the CSV import must report every problem of a line at once, and an API caller should
 * be able to fix all its mistakes in a single round-trip.
 *
 * @param field   the field name as the caller knows it (JSON property or CSV column), or
 *                {@link #WHOLE_RECORD} when the problem is not tied to a single field
 * @param code    stable, machine-readable code (e.g. {@code DISCOUNT_OUT_OF_RANGE})
 * @param message human-readable explanation, including the rejected value when useful
 */
public record Violation(String field, String code, String message) {

    public static final String WHOLE_RECORD = "*";

    public Violation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}

package com.equadis.portal.offer;

import com.equadis.portal.offer.OfferField.Naming;
import com.equadis.portal.shared.validation.Violation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The commercial content of an offer: what the supplier can edit while the offer is a draft.
 *
 * <p>This is the <b>single place</b> where BR-09 and the field limits are checked. Both the
 * JSON API and the CSV import go through {@link #violations}, so an offer is valid or invalid
 * in exactly the same way whatever channel it arrives through.
 *
 * <p>Money is a {@link BigDecimal} (never {@code double}) with at most 2 decimals, matching
 * the {@code NUMERIC(12,2)} column.
 */
public record OfferTerms(String title, String description, DiscountType discountType, BigDecimal discountValue,
                         String currency, LocalDate validFrom, LocalDate validTo) {

    public static final int TITLE_MAX_LENGTH = 200;
    public static final int DESCRIPTION_MAX_LENGTH = 2000;
    static final BigDecimal MAX_PERCENTAGE = BigDecimal.valueOf(100);
    static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99"); // NUMERIC(12,2)
    /** A date outside this range is a typing mistake, and PostgreSQL may not be able to store it. */
    static final LocalDate EARLIEST_DATE = LocalDate.of(1900, 1, 1);
    static final LocalDate LATEST_DATE = LocalDate.of(9999, 12, 31);

    /** Normalises free-text inputs (trimming, upper-case currency, blank -> null). */
    public OfferTerms {
        title = title == null ? null : title.strip();
        description = description == null || description.isBlank() ? null : description.strip();
        currency = currency == null || currency.isBlank() ? null : currency.strip().toUpperCase(Locale.ROOT);
    }

    /** @return every broken rule (empty when valid), with field names in the caller's convention. */
    public List<Violation> violations(Naming naming) {
        List<Violation> violations = new ArrayList<>();
        validateTexts(naming, violations);
        validateDiscount(naming, violations);
        validateValidityWindow(naming, violations);
        return violations;
    }

    /** A published offer must still be valid at some point from today on. */
    public List<Violation> publicationViolations(LocalDate today, Naming naming) {
        if (validTo != null && validTo.isBefore(today)) {
            return List.of(new Violation(OfferField.VALID_TO.nameIn(naming), "OFFER_EXPIRED",
                    "Cannot publish an offer whose validity ended on %s".formatted(validTo)));
        }
        return List.of();
    }

    /**
     * True when both terms describe the same offer content. Used by the import to report a
     * re-sent line as UNCHANGED instead of rewriting it. Amounts are compared by value
     * ({@code 10.5} equals {@code 10.50}).
     */
    public boolean sameContentAs(OfferTerms other) {
        return Objects.equals(title, other.title)
                && Objects.equals(description, other.description)
                && discountType == other.discountType
                && discountValue != null && other.discountValue != null
                && discountValue.compareTo(other.discountValue) == 0
                && Objects.equals(currency, other.currency)
                && Objects.equals(validFrom, other.validFrom)
                && Objects.equals(validTo, other.validTo);
    }

    private void validateTexts(Naming naming, List<Violation> violations) {
        String titleField = OfferField.TITLE.nameIn(naming);
        if (title == null || title.isEmpty()) {
            violations.add(new Violation(titleField, "TITLE_REQUIRED", "Title is required"));
        } else if (title.length() > TITLE_MAX_LENGTH) {
            violations.add(new Violation(titleField, "TITLE_TOO_LONG",
                    "Title must be at most %d characters".formatted(TITLE_MAX_LENGTH)));
        } else if (title.chars().anyMatch(Character::isISOControl)) {
            violations.add(new Violation(titleField, "TITLE_INVALID_CHARACTERS", "Title must not contain control characters"));
        }
        if (description == null) {
            return;
        }
        String descriptionField = OfferField.DESCRIPTION.nameIn(naming);
        if (description.length() > DESCRIPTION_MAX_LENGTH) {
            violations.add(new Violation(descriptionField, "DESCRIPTION_TOO_LONG",
                    "Description must be at most %d characters".formatted(DESCRIPTION_MAX_LENGTH)));
        } else if (description.chars().anyMatch(OfferTerms::isForbiddenInDescription)) {
            violations.add(new Violation(descriptionField, "DESCRIPTION_INVALID_CHARACTERS",
                    "Description must not contain control characters, except tabs and line breaks"));
        }
    }

    /** Includes the NUL character, which PostgreSQL cannot store in text columns. */
    private static boolean isForbiddenInDescription(int character) {
        return Character.isISOControl(character) && character != '\t' && character != '\n' && character != '\r';
    }

    /**
     * BR-09: percentage in ]0, 100]; fixed amount positive, with a valid currency.
     *
     * <p>Messages show {@code discountValue} as received and never with {@code toPlainString()}:
     * the input {@code 1E+999999999} is a tiny text, but its plain form is one billion characters.
     */
    private void validateDiscount(Naming naming, List<Violation> violations) {
        String typeField = OfferField.DISCOUNT_TYPE.nameIn(naming);
        String valueField = OfferField.DISCOUNT_VALUE.nameIn(naming);
        String currencyField = OfferField.CURRENCY.nameIn(naming);

        if (discountType == null) {
            violations.add(new Violation(typeField, "DISCOUNT_TYPE_REQUIRED", "Discount type is required (PERCENTAGE or FIXED_AMOUNT)"));
        }
        if (discountValue == null) {
            violations.add(new Violation(valueField, "DISCOUNT_VALUE_REQUIRED", "Discount value is required"));
        } else if (discountValue.stripTrailingZeros().scale() > 2) {
            violations.add(new Violation(valueField, "DISCOUNT_VALUE_TOO_PRECISE",
                    "Discount value must have at most 2 decimals, got %s".formatted(discountValue)));
        }
        if (discountType == null || discountValue == null) {
            return;
        }

        switch (discountType) {
            case PERCENTAGE -> {
                if (discountValue.signum() <= 0 || discountValue.compareTo(MAX_PERCENTAGE) > 0) {
                    violations.add(new Violation(valueField, "DISCOUNT_OUT_OF_RANGE",
                            "A percentage must be greater than 0 and at most 100, got %s".formatted(discountValue)));
                }
                if (currency != null) {
                    violations.add(new Violation(currencyField, "CURRENCY_NOT_ALLOWED",
                            "A percentage discount has no currency"));
                }
            }
            case FIXED_AMOUNT -> {
                if (discountValue.signum() <= 0) {
                    violations.add(new Violation(valueField, "DISCOUNT_OUT_OF_RANGE",
                            "A fixed amount must be positive, got %s".formatted(discountValue)));
                } else if (discountValue.compareTo(MAX_AMOUNT) > 0) {
                    violations.add(new Violation(valueField, "DISCOUNT_OUT_OF_RANGE",
                            "A fixed amount must be at most %s".formatted(MAX_AMOUNT.toPlainString())));
                }
                if (currency == null) {
                    violations.add(new Violation(currencyField, "CURRENCY_REQUIRED",
                            "A fixed amount requires an ISO 4217 currency (e.g. EUR, CHF)"));
                } else if (!isIsoCurrency(currency)) {
                    violations.add(new Violation(currencyField, "CURRENCY_INVALID",
                            "'%s' is not an ISO 4217 currency code".formatted(currency)));
                }
            }
        }
    }

    /** BR-09: valid to >= valid from (both inclusive calendar dates). */
    private void validateValidityWindow(Naming naming, List<Violation> violations) {
        if (validFrom == null) {
            violations.add(new Violation(OfferField.VALID_FROM.nameIn(naming), "VALID_FROM_REQUIRED", "Valid-from date is required"));
        } else if (isOutOfRange(validFrom)) {
            violations.add(dateOutOfRange(OfferField.VALID_FROM.nameIn(naming), validFrom));
        }
        if (validTo == null) {
            violations.add(new Violation(OfferField.VALID_TO.nameIn(naming), "VALID_TO_REQUIRED", "Valid-to date is required"));
        } else if (isOutOfRange(validTo)) {
            violations.add(dateOutOfRange(OfferField.VALID_TO.nameIn(naming), validTo));
        }
        if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
            violations.add(new Violation(OfferField.VALID_TO.nameIn(naming), "VALIDITY_WINDOW_INVALID",
                    "Valid-to date %s must be on or after valid-from date %s".formatted(validTo, validFrom)));
        }
    }

    private static boolean isOutOfRange(LocalDate date) {
        return date.isBefore(EARLIEST_DATE) || date.isAfter(LATEST_DATE);
    }

    private static Violation dateOutOfRange(String field, LocalDate date) {
        return new Violation(field, "DATE_OUT_OF_RANGE",
                "Date %s must be between %s and %s".formatted(date, EARLIEST_DATE, LATEST_DATE));
    }

    private static boolean isIsoCurrency(String code) {
        if (code.length() != 3) {
            return false;
        }
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException unknownCurrency) {
            return false;
        }
    }
}

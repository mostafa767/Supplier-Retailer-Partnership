package com.equadis.portal.shared.error;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Turns a database constraint violation into the business error it represents.
 *
 * <p>Several business rules are guaranteed by the database (unique indexes, the BR-04
 * exclusion constraint...), because only the database can guarantee them when two requests
 * race. Java checks run first and give the friendly message in 99.9% of cases; when a race
 * slips past them, the database rejects the write and this class makes sure the caller still
 * gets the <b>same</b> business error - a 409, not a 500.
 *
 * <p>The mapping uses the explicit constraint names declared in the Flyway migrations.
 */
@Component
public class DatabaseConstraintTranslator {

    private static final Map<String, Translation> TRANSLATIONS = Map.of(
            "uq_supplier_name_per_marketplace", new Translation(ErrorCode.SUPPLIER_NAME_TAKEN,
                    "A supplier with this company name already exists in this marketplace"),
            "ck_retailer_location_matches_store_type", new Translation(ErrorCode.RETAILER_LOCATION_INCOMPLETE,
                    "Location data does not match the store type (ONLINE: website; PHYSICAL: address and city; HYBRID: both)"),
            "ex_partnership_no_overlap", new Translation(ErrorCode.PARTNERSHIP_OVERLAP,
                    "This supplier and retailer already have a partnership overlapping these dates"),
            "ck_partnership_end_after_start", new Translation(ErrorCode.PARTNERSHIP_INVALID_PERIOD,
                    "The partnership end date must be after its start date"),
            "ck_partnership_max_36_months", new Translation(ErrorCode.PARTNERSHIP_INVALID_PERIOD,
                    "A partnership lasts at most 36 months"),
            "uq_offer_reference_per_supplier", new Translation(ErrorCode.OFFER_REFERENCE_TAKEN,
                    "This supplier already has an offer with this reference"),
            "uq_subscription_active", new Translation(ErrorCode.SUBSCRIPTION_ALREADY_ACTIVE,
                    "The retailer already has an active subscription to this offer")
    );

    /**
     * @return the business error for a known constraint, or empty when the failure is not a
     * known business constraint (it is then a bug and must surface as a 500).
     */
    public Optional<PortalException> translate(Throwable failure) {
        return constraintName(failure)
                .map(TRANSLATIONS::get)
                .map(translation -> new PortalException(translation.errorCode(), translation.message()));
    }

    /** Walks the cause chain (Spring and Hibernate wrap the driver exception) to the constraint name. */
    static Optional<String> constraintName(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException psqlException) {
                ServerErrorMessage serverError = psqlException.getServerErrorMessage();
                return Optional.ofNullable(serverError).map(ServerErrorMessage::getConstraint);
            }
        }
        return Optional.empty();
    }

    private record Translation(ErrorCode errorCode, String message) {
    }
}

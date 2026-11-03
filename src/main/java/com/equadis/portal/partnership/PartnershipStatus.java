package com.equadis.portal.partnership;

/**
 * Lifecycle of a partnership. Never stored: always derived from the dates for a given day
 * (BR-05 - "always correct for today's date", without any nightly job).
 */
public enum PartnershipStatus {
    /** Starts in the future. */
    SCHEDULED,
    /** Today is within [start date, end date] (both inclusive). */
    ACTIVE,
    /** The end date is in the past. */
    EXPIRED,
    /** Ended early by one of the parties or the marketplace. Final. */
    TERMINATED
}

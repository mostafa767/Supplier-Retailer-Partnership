package com.equadis.portal.notification;

/** How a notification ended without failing. The names are stored as its status. */
enum DeliveryOutcome {
    /** The digest was delivered. */
    SENT,
    /** Nothing to deliver: the digest is empty, or the retailer no longer has this channel. */
    SKIPPED
}

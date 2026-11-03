package com.equadis.portal.notification;

/**
 * A delivery failure that retrying cannot fix (forbidden webhook URL, HTTP 400/401/404 from
 * the receiver...). The notification is marked FAILED at once instead of wasting retries.
 */
class PermanentDeliveryFailure extends RuntimeException {

    PermanentDeliveryFailure(String message) {
        super(message);
    }

    PermanentDeliveryFailure(String message, Throwable cause) {
        super(message, cause);
    }
}

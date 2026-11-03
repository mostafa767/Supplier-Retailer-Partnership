package com.equadis.portal.notification;

/** How a retailer is informed of new offers (R5). */
public enum Channel {
    /** A digest e-mail to the retailer's contact address. */
    EMAIL,
    /** A signed HTTP POST to the retailer's own system (large retailers). */
    WEBHOOK
}

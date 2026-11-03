package com.equadis.portal.offer;

/** BR-09: how the discount value is interpreted. */
public enum DiscountType {
    /** Value in ]0, 100], no currency. */
    PERCENTAGE,
    /** Positive amount, with an ISO 4217 currency. */
    FIXED_AMOUNT
}

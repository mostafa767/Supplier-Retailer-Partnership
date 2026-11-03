package com.equadis.portal.subscription;

/**
 * Outcome of a subscribe request.
 *
 * @param created false when the retailer was already subscribed (BR-11)
 */
public record SubscriptionResult(SubscriptionView subscription, boolean created) {
}

package com.equadis.portal.notification;

import com.equadis.portal.directory.RetailerContact;

import java.util.UUID;

/**
 * One way of delivering a digest. Adding a channel (SMS, push...) means adding one
 * implementation - the sender does not change.
 */
interface NotificationChannel {

    Channel channel();

    /** False when the retailer no longer has this channel configured (e.g. e-mail removed). */
    boolean isConfiguredFor(RetailerContact retailer);

    /**
     * Delivers the digest. Throws on failure: {@link PermanentDeliveryFailure} when retrying
     * cannot help, any other exception when a retry might succeed.
     *
     * @param deliveryId stable id of this notification, sent along so that the receiver can
     *                   ignore duplicates (delivery is at-least-once)
     */
    void deliver(RetailerContact retailer, Digest digest, UUID deliveryId);
}

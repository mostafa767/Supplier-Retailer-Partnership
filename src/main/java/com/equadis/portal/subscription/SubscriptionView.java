package com.equadis.portal.subscription;

import java.time.Instant;
import java.util.UUID;

/** API representation of a subscription. */
public record SubscriptionView(UUID id, UUID offerId, UUID supplierId, SubscriptionStatus status, Instant createdAt,
                               Instant cancelledAt, String cancellationReason) {
}

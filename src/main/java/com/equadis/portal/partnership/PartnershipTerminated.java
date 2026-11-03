package com.equadis.portal.partnership;

import java.time.Instant;
import java.util.UUID;

/**
 * Published (synchronously, inside the termination transaction) when a partnership is
 * terminated. The subscription module listens to it to cancel subscriptions (BR-07).
 *
 * <p>Using an in-process event keeps the partnership module unaware of subscriptions (no
 * dependency cycle) while staying atomic: the listener runs in the same transaction, so
 * either the termination AND the cancellations are committed, or neither is.
 */
public record PartnershipTerminated(UUID partnershipId, UUID supplierId, UUID retailerId, Instant terminatedAt) {
}

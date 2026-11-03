package com.equadis.portal.outbox;

import java.time.Instant;
import java.util.UUID;

/** A stored domain event, as handed to an {@link OutboxEventHandler}. */
public record OutboxEvent(UUID id, UUID marketplaceId, String eventType, UUID aggregateId,
                          String payloadJson, Instant occurredAt) {
}

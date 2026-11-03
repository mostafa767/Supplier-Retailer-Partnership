package com.equadis.portal.audit;

import com.equadis.portal.tenant.Actor;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One change, as shown to an administrator: who ({@code actorRole}, {@code actorId}) did what
 * ({@code action}, {@code details}) on which item, when.
 *
 * @param correlationId search the logs with it to see everything that happened in that request
 */
public record AuditEventView(Instant occurredAt, Actor.Role actorRole, UUID actorId, AuditAction action,
                             String entityType, UUID entityId, JsonNode details, String correlationId) {
}

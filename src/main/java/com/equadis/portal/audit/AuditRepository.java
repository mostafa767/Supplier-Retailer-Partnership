package com.equadis.portal.audit;

import com.equadis.portal.shared.jdbc.JdbcTime;
import com.equadis.portal.tenant.Actor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stores the audit trail. Insert and read only: the database refuses UPDATE and DELETE from
 * the application (migration V8).
 */
@Repository
class AuditRepository {

    private final JdbcClient jdbc;

    AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Actor actor, AuditAction action, UUID entityId, String detailsJson, String correlationId, Instant occurredAt) {
        jdbc.sql("""
                        INSERT INTO audit_event (id, marketplace_id, occurred_at, actor_role, actor_id, action,
                                                 entity_type, entity_id, details, correlation_id)
                        VALUES (:id, :marketplaceId, :occurredAt, :actorRole, :actorId, :action,
                                :entityType, :entityId, CAST(:details AS jsonb), :correlationId)
                        """)
                .param("id", UUID.randomUUID())
                .param("marketplaceId", actor.marketplaceId())
                .param("occurredAt", JdbcTime.toDb(occurredAt))
                .param("actorRole", actor.role().name())
                .param("actorId", actor.actorId())
                .param("action", action.name())
                .param("entityType", action.entityType())
                .param("entityId", entityId)
                .param("details", detailsJson, Types.VARCHAR)
                .param("correlationId", correlationId, Types.VARCHAR)
                .update();
    }

    /** @return the item's history, newest first, at most {@code limit} events */
    List<StoredAuditEvent> findByEntity(UUID entityId, int limit) {
        return jdbc.sql("""
                        SELECT occurred_at, actor_role, actor_id, action, entity_type, entity_id,
                               details::text AS details, correlation_id
                        FROM audit_event
                        WHERE entity_id = :entityId
                        ORDER BY occurred_at DESC, id
                        LIMIT :limit
                        """)
                .param("entityId", entityId)
                .param("limit", limit)
                .query((row, rowNumber) -> new StoredAuditEvent(
                        JdbcTime.instant(row, "occurred_at"),
                        Actor.Role.valueOf(row.getString("actor_role")),
                        row.getObject("actor_id", UUID.class),
                        AuditAction.valueOf(row.getString("action")),
                        row.getString("entity_type"),
                        row.getObject("entity_id", UUID.class),
                        row.getString("details"),
                        row.getString("correlation_id")))
                .list();
    }

    record StoredAuditEvent(Instant occurredAt, Actor.Role actorRole, UUID actorId, AuditAction action,
                            String entityType, UUID entityId, String detailsJson, String correlationId) {
    }
}

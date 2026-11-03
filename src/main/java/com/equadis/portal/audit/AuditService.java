package com.equadis.portal.audit;

import com.equadis.portal.audit.AuditRepository.StoredAuditEvent;
import com.equadis.portal.shared.web.RequestTracingFilter;
import com.equadis.portal.tenant.Actor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The audit trail (R7): who changed what, and when.
 *
 * <p>{@link #record} uses {@code Propagation.MANDATORY}, like the outbox: it can only be called
 * inside the transaction of the change it describes. The change and its audit row are
 * committed together, or neither is.
 *
 * <p>What is recorded is the business action (OFFER_PUBLISHED...), not every SQL statement:
 * that is what an auditor or a support engineer asks about. Details must never contain
 * secrets; personal data is limited to ids.
 */
@Service
public class AuditService {

    static final int MAX_HISTORY_SIZE = 200;

    private final AuditRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(AuditRepository auditRepository, ObjectMapper objectMapper, Clock clock) {
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Records one change, inside the caller's transaction.
     *
     * @param actor    who made the change (for background work: on whose behalf)
     * @param entityId the item that changed
     * @param details  what changed, stored as JSON; may be null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Actor actor, AuditAction action, UUID entityId, Object details) {
        String correlationId = MDC.get(RequestTracingFilter.CORRELATION_ID_MDC_KEY);
        auditRepository.insert(actor, action, entityId, toJson(details), correlationId, clock.instant());
    }

    /** @return the history of one item, newest first. Administrators only. */
    @Transactional(readOnly = true)
    public List<AuditEventView> history(Actor actor, UUID entityId) {
        actor.requireRole(Actor.Role.MARKETPLACE_ADMIN);
        return auditRepository.findByEntity(entityId, MAX_HISTORY_SIZE).stream().map(this::toView).toList();
    }

    private AuditEventView toView(StoredAuditEvent event) {
        return new AuditEventView(event.occurredAt(), event.actorRole(), event.actorId(), event.action(),
                event.entityType(), event.entityId(), fromJson(event.detailsJson()), event.correlationId());
    }

    private String toJson(Object details) {
        if (details == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit details are not serialisable: " + details.getClass().getName(), e);
        }
    }

    private JsonNode fromJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored audit details are not valid JSON", e);
        }
    }
}

package com.equadis.portal.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Appends a domain event to the transactional outbox.
 *
 * <p>{@code Propagation.MANDATORY} is the whole point: calling this outside a transaction is
 * a programming error and fails immediately. The event is therefore always committed - or
 * rolled back - together with the business change that caused it. No "offer published but
 * nobody informed", no "retailers informed about an offer that was rolled back".
 */
@Service
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param aggregateId what the event is about, e.g. the supplier that published offers
     * @param payload     the event's content, stored as JSON
     * @return the new event's id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(UUID marketplaceId, String eventType, UUID aggregateId, Object payload, Instant occurredAt) {
        UUID eventId = UUID.randomUUID();
        outboxRepository.insert(eventId, marketplaceId, eventType, aggregateId, toJson(payload), occurredAt);
        return eventId;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Outbox payload is not serialisable: " + payload.getClass().getName(), e);
        }
    }
}

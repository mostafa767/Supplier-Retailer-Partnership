package com.equadis.portal.notification;

import com.equadis.portal.offer.OffersPublished;
import com.equadis.portal.outbox.OutboxEvent;
import com.equadis.portal.outbox.OutboxEventHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Outbox event "offers published" -> pending notifications for the supplier's partners. */
@Component
class OffersPublishedHandler implements OutboxEventHandler {

    private final NotificationFanOutService fanOutService;
    private final ObjectMapper objectMapper;

    OffersPublishedHandler(NotificationFanOutService fanOutService, ObjectMapper objectMapper) {
        this.fanOutService = fanOutService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return OffersPublished.EVENT_TYPE;
    }

    @Override
    public void handle(OutboxEvent event) {
        fanOutService.queueForActivePartnersOf(read(event).supplierId());
    }

    private OffersPublished read(OutboxEvent event) {
        try {
            return objectMapper.readValue(event.payloadJson(), OffersPublished.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable payload for outbox event " + event.id(), e);
        }
    }
}

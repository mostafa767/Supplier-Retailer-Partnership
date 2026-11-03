package com.equadis.portal.outbox;

/**
 * Reacts to one type of outbox event. Implementations are Spring beans; the dispatcher finds
 * them by {@link #eventType()}.
 *
 * <p>Contract: {@link #handle} runs inside a transaction, with the event's marketplace as the
 * current tenant. Delivery is <b>at-least-once</b> (a crash after handling but before the
 * commit replays the event), so handlers must be idempotent.
 */
public interface OutboxEventHandler {

    String eventType();

    void handle(OutboxEvent event);
}

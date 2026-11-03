package com.equadis.portal.outbox;

import com.equadis.portal.shared.web.RequestTracingFilter;
import com.equadis.portal.shared.work.ClaimedWork;
import com.equadis.portal.shared.work.FailureText;
import com.equadis.portal.shared.work.RetryPolicy;
import com.equadis.portal.shared.work.WorkClaimRepository;
import com.equadis.portal.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Delivers outbox events to their handlers.
 *
 * <p>Runs inside the application (scheduled by {@code BackgroundWorkers}), not in a separate
 * service or a cloud function: the whole proof of concept starts with one command, and
 * {@code FOR UPDATE SKIP LOCKED} already makes it safe to run on several instances. When
 * volumes require it, the same code runs in dedicated "worker" instances, or the outbox is
 * relayed to a broker (see the design document).
 *
 * <p>Each event is handled in its own transaction, under its own marketplace: one failing
 * event never blocks the others.
 */
@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final WorkClaimRepository workClaimRepository;
    private final OutboxRepository outboxRepository;
    private final TransactionTemplate transactions;
    private final Map<String, OutboxEventHandler> handlersByType;
    private final OutboxProperties properties;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    public OutboxDispatcher(WorkClaimRepository workClaimRepository, OutboxRepository outboxRepository, TransactionTemplate transactions,
                            List<OutboxEventHandler> handlers, OutboxProperties properties, Clock clock) {
        this.workClaimRepository = workClaimRepository;
        this.outboxRepository = outboxRepository;
        this.transactions = transactions;
        this.handlersByType = handlers.stream().collect(Collectors.toUnmodifiableMap(OutboxEventHandler::eventType, Function.identity()));
        this.properties = properties;
        this.retryPolicy = properties.retryPolicy();
        this.clock = clock;
    }

    /** Claims and dispatches one batch. @return how many events were claimed. */
    public int dispatchBatch() {
        List<ClaimedWork> claimed = workClaimRepository.claimOutboxEvents(clock.instant(), properties.lease(), properties.batchSize());
        for (ClaimedWork work : claimed) {
            TenantContext.runAs(work.marketplaceId(), () -> dispatch(work));
        }
        return claimed.size();
    }

    private void dispatch(ClaimedWork work) {
        MDC.put(RequestTracingFilter.CORRELATION_ID_MDC_KEY, "outbox-" + work.id());
        try {
            transactions.executeWithoutResult(status -> outboxRepository.findUnprocessed(work.id()).ifPresent(event -> {
                handlerFor(event.eventType()).handle(event);
                outboxRepository.markProcessed(event.id(), clock.instant());
            }));
        } catch (RuntimeException failure) {
            recordFailure(work, failure);
        } finally {
            MDC.remove(RequestTracingFilter.CORRELATION_ID_MDC_KEY);
        }
    }

    private OutboxEventHandler handlerFor(String eventType) {
        OutboxEventHandler handler = handlersByType.get(eventType);
        if (handler == null) {
            throw new IllegalStateException("No handler registered for outbox event type " + eventType);
        }
        return handler;
    }

    private void recordFailure(ClaimedWork work, RuntimeException failure) {
        boolean giveUp = retryPolicy.isExhausted(work.attempts());
        if (giveUp) {
            log.error("Outbox event {} failed for good after {} attempts", work.id(), work.attempts(), failure);
        } else {
            log.warn("Outbox event {} failed (attempt {}/{}), will retry: {}",
                    work.id(), work.attempts(), retryPolicy.maxAttempts(), failure.toString());
        }
        Instant now = clock.instant();
        Instant retryAt = now.plus(retryPolicy.backoffAfter(work.attempts()));
        transactions.executeWithoutResult(status ->
                outboxRepository.markAttemptFailed(work.id(), giveUp, FailureText.of(failure), retryAt, now));
    }
}

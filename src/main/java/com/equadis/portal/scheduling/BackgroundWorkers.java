package com.equadis.portal.scheduling;

import com.equadis.portal.importing.ImportWorker;
import com.equadis.portal.notification.NotificationSender;
import com.equadis.portal.outbox.OutboxDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The only place where background work is scheduled. The workers themselves are plain beans
 * with a "do one batch" method, so:
 * <ul>
 *     <li>tests disable scheduling ({@code portal.workers.enabled=false}) and call the workers
 *         directly - deterministic, no sleeping;</li>
 *     <li>in production, the same application can run as "API" instances (workers disabled)
 *         and "worker" instances (workers enabled), scaled independently.</li>
 * </ul>
 * A failure in one round is logged and never stops the schedule.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "portal.workers.enabled", havingValue = "true", matchIfMissing = true)
public class BackgroundWorkers {

    /** Upper bound per round, so a huge backlog cannot monopolise a scheduler thread. */
    private static final int MAX_BATCHES_PER_ROUND = 100;

    private static final Logger log = LoggerFactory.getLogger(BackgroundWorkers.class);

    private final ImportWorker importWorker;
    private final OutboxDispatcher outboxDispatcher;
    private final NotificationSender notificationSender;

    public BackgroundWorkers(ImportWorker importWorker, OutboxDispatcher outboxDispatcher, NotificationSender notificationSender) {
        this.importWorker = importWorker;
        this.outboxDispatcher = outboxDispatcher;
        this.notificationSender = notificationSender;
    }

    @Scheduled(fixedDelayString = "${portal.workers.import-poll-interval:PT2S}")
    void processImports() {
        runRound("imports", () -> importWorker.processNext() ? 1 : 0);
    }

    @Scheduled(fixedDelayString = "${portal.workers.outbox-poll-interval:PT2S}")
    void dispatchOutbox() {
        runRound("outbox", outboxDispatcher::dispatchBatch);
    }

    @Scheduled(fixedDelayString = "${portal.workers.notification-poll-interval:PT5S}")
    void sendNotifications() {
        runRound("notifications", notificationSender::sendDueBatch);
    }

    private static void runRound(String name, Batch batch) {
        try {
            for (int i = 0; i < MAX_BATCHES_PER_ROUND && batch.run() > 0; i++) {
                // keep going while there is work
            }
        } catch (RuntimeException failure) {
            log.error("Background round '{}' failed; it will run again at the next tick", name, failure);
        }
    }

    @FunctionalInterface
    private interface Batch {
        /** @return how many items were processed (0 = nothing to do) */
        int run();
    }
}

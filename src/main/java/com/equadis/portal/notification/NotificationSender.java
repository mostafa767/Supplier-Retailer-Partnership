package com.equadis.portal.notification;

import com.equadis.portal.directory.DirectoryService;
import com.equadis.portal.directory.RetailerContact;
import com.equadis.portal.notification.NotificationRepository.NotificationInProgress;
import com.equadis.portal.shared.web.RequestTracingFilter;
import com.equadis.portal.shared.work.ClaimedWork;
import com.equadis.portal.shared.work.FailureText;
import com.equadis.portal.shared.work.RetryPolicy;
import com.equadis.portal.shared.work.WorkClaimRepository;
import com.equadis.portal.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends due notifications.
 *
 * <p>For each claimed notification:
 * <ol>
 *     <li><b>prepare</b> (short read transaction): compute the digest - offers the retailer can
 *         see, published between its cursor and (now - consistency lag);</li>
 *     <li><b>deliver</b> - outside any transaction: never hold a database connection or lock
 *         while waiting for an SMTP server or a retailer's web server;</li>
 *     <li><b>complete</b> (short write transaction): mark SENT and move the cursor forward.</li>
 * </ol>
 * The cursor only moves after a successful delivery. If delivery fails for good, the next
 * notification of that retailer starts from the same cursor: offers are never silently lost.
 *
 * <p>Delivery is at-least-once (a crash between "deliver" and "complete" re-sends); receivers
 * de-duplicate with the delivery id.
 *
 * <p>Deliveries run one after the other, so slow receivers can eat the whole lease. If it
 * expired mid-batch, another worker would claim what is left and both would send it. So no
 * delivery starts unless it can finish within the lease: the rest of the batch is released.
 */
@Service
public class NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    private final WorkClaimRepository workClaimRepository;
    private final NotificationRepository notificationRepository;
    private final TransactionTemplate transactions;
    private final DirectoryService directoryService;
    private final DigestService digestService;
    private final Map<Channel, NotificationChannel> channels = new EnumMap<>(Channel.class);
    private final NotificationProperties properties;
    private final RetryPolicy retryPolicy;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    NotificationSender(WorkClaimRepository workClaimRepository, NotificationRepository notificationRepository,
                       TransactionTemplate transactions, DirectoryService directoryService, DigestService digestService,
                       List<NotificationChannel> channelImplementations, NotificationProperties properties,
                       MeterRegistry meterRegistry, Clock clock) {
        this.workClaimRepository = workClaimRepository;
        this.notificationRepository = notificationRepository;
        this.transactions = transactions;
        this.directoryService = directoryService;
        this.digestService = digestService;
        channelImplementations.forEach(implementation -> channels.put(implementation.channel(), implementation));
        this.properties = properties;
        this.retryPolicy = properties.retryPolicy();
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** Claims and sends one batch of due notifications. @return how many were claimed. */
    public int sendDueBatch() {
        Instant claimedAt = clock.instant();
        List<ClaimedWork> claimed = workClaimRepository.claimRetailerNotifications(claimedAt, properties.lease(), properties.batchSize());
        Instant lastSafeStart = claimedAt.plus(properties.lease()).minus(properties.leaseSafetyMargin());
        for (ClaimedWork work : claimed) {
            if (clock.instant().isBefore(lastSafeStart)) {
                TenantContext.runAs(work.marketplaceId(), () -> send(work));
            } else {
                TenantContext.runAs(work.marketplaceId(), () -> release(work));
            }
        }
        return claimed.size();
    }

    /**
     * Gives back a claimed notification that was not attempted, for the next batch. The claim
     * counted an attempt: it is taken back, so that slowness never uses up the retries.
     */
    void release(ClaimedWork work) {
        log.info("Notification {} released: too little of the lease left to deliver it safely", work.id());
        transactions.executeWithoutResult(status -> notificationRepository.release(work.id()));
        meterRegistry.counter("portal.notifications", "channel", "ANY", "outcome", "RELEASED").increment();
    }

    private void send(ClaimedWork work) {
        MDC.put(RequestTracingFilter.CORRELATION_ID_MDC_KEY, "notification-" + work.id());
        try {
            Optional<PreparedDelivery> prepared = transactions.execute(status -> prepare(work.id()));
            if (prepared == null || prepared.isEmpty()) {
                return; // already completed by another worker after a lease expiry
            }
            PreparedDelivery delivery = prepared.get();
            if (!delivery.channel().isConfiguredFor(delivery.retailer()) || delivery.digest().isEmpty()) {
                complete(delivery, DeliveryOutcome.SKIPPED);
                return;
            }
            delivery.channel().deliver(delivery.retailer(), delivery.digest(), work.id());
            complete(delivery, DeliveryOutcome.SENT);
            log.info("Notification {} sent via {}: {} offer(s) from {} supplier(s)", work.id(), delivery.channel().channel(),
                    delivery.digest().totalOffers(), delivery.digest().suppliers().size());
        } catch (RuntimeException failure) {
            recordFailure(work, failure);
        } finally {
            MDC.remove(RequestTracingFilter.CORRELATION_ID_MDC_KEY);
        }
    }

    private Optional<PreparedDelivery> prepare(UUID notificationId) {
        Optional<NotificationInProgress> notification = notificationRepository.findInProgress(notificationId);
        if (notification.isEmpty()) {
            return Optional.empty();
        }
        NotificationInProgress inProgress = notification.get();
        RetailerContact retailer = directoryService.findRetailerContact(inProgress.retailerId())
                .orElseThrow(() -> new IllegalStateException("Retailer " + inProgress.retailerId() + " not found"));

        Instant windowTo = clock.instant().minus(properties.consistencyLag());
        if (windowTo.isBefore(inProgress.windowFrom())) {
            windowTo = inProgress.windowFrom(); // empty window, never move the cursor backwards
        }
        Digest digest = digestService.build(inProgress.retailerId(), inProgress.windowFrom(), windowTo,
                LocalDate.now(clock), properties.maxOffersPerDigest());
        return Optional.of(new PreparedDelivery(inProgress.id(), channelFor(inProgress.channel()), retailer, digest));
    }

    private void complete(PreparedDelivery delivery, DeliveryOutcome outcome) {
        Instant now = clock.instant();
        Channel channel = delivery.channel().channel();
        transactions.executeWithoutResult(status -> {
            notificationRepository.markFinished(delivery.notificationId(), outcome, delivery.digest(), now);
            notificationRepository.advanceCursor(delivery.retailer().id(), channel, delivery.digest().windowTo());
        });
        meterRegistry.counter("portal.notifications", "channel", channel.name(), "outcome", outcome.name()).increment();
    }

    /** Retry with back-off, or give up. */
    private void recordFailure(ClaimedWork work, RuntimeException failure) {
        boolean giveUp = failure instanceof PermanentDeliveryFailure || retryPolicy.isExhausted(work.attempts());
        if (giveUp) {
            log.error("Notification {} failed for good after {} attempt(s): {}", work.id(), work.attempts(), failure.toString(), failure);
        } else {
            log.warn("Notification {} failed (attempt {}/{}), will retry: {}", work.id(), work.attempts(),
                    retryPolicy.maxAttempts(), failure.toString());
        }
        Instant retryAt = clock.instant().plus(retryPolicy.backoffAfter(work.attempts()));
        transactions.executeWithoutResult(status ->
                notificationRepository.markAttemptFailed(work.id(), giveUp, FailureText.of(failure), retryAt));
        meterRegistry.counter("portal.notifications", "channel", "ANY", "outcome", giveUp ? "FAILED" : "RETRY").increment();
    }

    private NotificationChannel channelFor(Channel channel) {
        NotificationChannel implementation = channels.get(channel);
        if (implementation == null) {
            throw new IllegalStateException("No implementation for channel " + channel);
        }
        return implementation;
    }

    private record PreparedDelivery(UUID notificationId, NotificationChannel channel, RetailerContact retailer, Digest digest) {
    }
}

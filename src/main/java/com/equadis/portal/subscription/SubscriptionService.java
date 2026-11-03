package com.equadis.portal.subscription;

import com.equadis.portal.audit.AuditAction;
import com.equadis.portal.audit.AuditService;
import com.equadis.portal.offer.OfferService;
import com.equadis.portal.partnership.PartnershipService;
import com.equadis.portal.partnership.PartnershipTerminated;
import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import com.equadis.portal.tenant.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Retailer subscriptions to offers.
 *
 * <ul>
 *     <li>BR-10: a retailer can only subscribe to an offer it can see;</li>
 *     <li>BR-11: at most one ACTIVE subscription per offer - guaranteed by a partial unique
 *         index; a second request (double click, network retry) returns the existing
 *         subscription instead of an error (idempotent);</li>
 *     <li>BR-07: terminating a partnership cancels the subscriptions, in the same transaction.</li>
 * </ul>
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final OfferService offerService;
    private final PartnershipService partnershipService;
    private final AuditService auditService;
    private final Clock clock;

    public SubscriptionService(SubscriptionRepository subscriptionRepository, OfferService offerService,
                               PartnershipService partnershipService, AuditService auditService, Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.offerService = offerService;
        this.partnershipService = partnershipService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Subscribes the calling retailer to an offer it can see.
     *
     * @return the subscription, and whether this call created it
     * @throws PortalException OFFER_NOT_FOUND when the offer does not exist or is not visible
     *                         to this retailer: we do not reveal which
     */
    @Transactional
    public SubscriptionResult subscribe(Actor actor, UUID offerId) {
        UUID retailerId = actor.requireRetailer();
        LocalDate today = LocalDate.now(clock);

        UUID supplierId = offerService.findSupplierOfCurrentOffer(offerId, today)
                .orElseThrow(() -> notVisible(offerId));
        // Shared lock on the partnership: a concurrent termination waits for us (and vice versa).
        if (!partnershipService.lockIfActive(supplierId, retailerId, today)) {
            throw notVisible(offerId);
        }

        Optional<UUID> createdId = subscriptionRepository.insertIfNotActive(
                actor.marketplaceId(), retailerId, supplierId, offerId, clock.instant());
        createdId.ifPresent(id -> {
            auditService.record(actor, AuditAction.SUBSCRIPTION_CREATED, id, Map.of("offerId", offerId));
            log.info("Retailer {} subscribed to offer {}", retailerId, offerId);
        });

        SubscriptionView subscription = subscriptionRepository.findActive(retailerId, offerId).orElseThrow();
        return new SubscriptionResult(subscription, createdId.isPresent());
    }

    /** @return the calling retailer's active subscriptions, newest first */
    @Transactional(readOnly = true)
    public List<SubscriptionView> listActive(Actor actor) {
        return subscriptionRepository.findAllActive(actor.requireRetailer());
    }

    /**
     * BR-07. Runs synchronously inside the termination transaction (plain {@code @EventListener},
     * not {@code @TransactionalEventListener}): termination and cancellations commit together.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPartnershipTerminated(PartnershipTerminated event) {
        int cancelled = subscriptionRepository.cancelActive(event.retailerId(), event.supplierId(), event.terminatedAt());
        log.info("Partnership {} terminated: {} subscription(s) cancelled", event.partnershipId(), cancelled);
    }

    private static PortalException notVisible(UUID offerId) {
        return PortalException.notFound(ErrorCode.OFFER_NOT_FOUND, "Offer", offerId);
    }
}

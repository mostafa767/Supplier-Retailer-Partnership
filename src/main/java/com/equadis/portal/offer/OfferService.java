package com.equadis.portal.offer;

import com.equadis.portal.audit.AuditAction;
import com.equadis.portal.audit.AuditService;
import com.equadis.portal.outbox.OutboxWriter;
import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import com.equadis.portal.tenant.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Offer use cases for a supplier using the API one offer at a time.
 * (Bulk imports go through the {@code importing} module.)
 */
@Service
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    private final OfferRepository offerRepository;
    private final OutboxWriter outboxWriter;
    private final AuditService auditService;
    private final Clock clock;

    public OfferService(OfferRepository offerRepository, OutboxWriter outboxWriter, AuditService auditService, Clock clock) {
        this.offerRepository = offerRepository;
        this.outboxWriter = outboxWriter;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** @throws PortalException OFFER_REFERENCE_TAKEN when this supplier already uses the reference */
    @Transactional
    public OfferView createDraft(Actor actor, String reference, OfferTerms terms) {
        UUID supplierId = actor.requireSupplier();
        Offer offer = Offer.draft(actor.marketplaceId(), supplierId, reference, terms, clock.instant());
        if (offerRepository.existsReference(supplierId, offer.reference())) {
            throw new PortalException(ErrorCode.OFFER_REFERENCE_TAKEN,
                    "You already have an offer with reference %s".formatted(offer.reference()));
        }
        offerRepository.saveAndFlush(offer); // a concurrent duplicate surfaces here as OFFER_REFERENCE_TAKEN
        auditService.record(actor, AuditAction.OFFER_CREATED, offer.id(), Map.of("reference", offer.reference(), "terms", offer.terms()));
        return OfferView.of(offer);
    }

    /** @return one of the calling supplier's offers */
    @Transactional(readOnly = true)
    public OfferView get(Actor actor, UUID offerId) {
        return OfferView.of(requireOwnedOffer(actor, offerId));
    }

    /**
     * Optimistic locking: the caller sends the version it read. If someone else changed the
     * offer in the meantime, the update is refused (409) instead of silently overwriting.
     */
    @Transactional
    public OfferView updateDraft(Actor actor, UUID offerId, long expectedVersion, OfferTerms terms) {
        Offer offer = requireOwnedOffer(actor, offerId);
        if (offer.version() != expectedVersion) {
            throw new PortalException(ErrorCode.CONCURRENT_MODIFICATION,
                    "Offer %s was changed in the meantime (version %d, you sent %d). Reload it and try again."
                            .formatted(offer.reference(), offer.version(), expectedVersion));
        }
        OfferTerms termsBefore = offer.terms();
        offer.updateDraft(terms, clock.instant());
        offerRepository.flush();
        auditService.record(actor, AuditAction.OFFER_UPDATED, offer.id(), Map.of("before", termsBefore, "after", offer.terms()));
        return OfferView.of(offer);
    }

    /**
     * Publishes the offer AND records the "offers published" event in the same transaction
     * (transactional outbox). Retailers are informed asynchronously by the notification
     * module; publishing never waits for e-mails or webhooks.
     */
    @Transactional
    public OfferView publish(Actor actor, UUID offerId) {
        Offer offer = requireOwnedOffer(actor, offerId);
        Instant now = clock.instant();
        offer.publish(LocalDate.now(clock), now);
        offerRepository.flush();
        outboxWriter.append(offer.marketplaceId(), OffersPublished.EVENT_TYPE, offer.supplierId(),
                new OffersPublished(offer.supplierId(), 1, "API"), now);
        auditService.record(actor, AuditAction.OFFER_PUBLISHED, offer.id(), Map.of("reference", offer.reference()));
        log.info("Offer {} ({}) published", offer.id(), offer.reference());
        return OfferView.of(offer);
    }

    /** BR-08: a closed offer disappears from the catalogue. */
    @Transactional
    public OfferView close(Actor actor, UUID offerId) {
        Offer offer = requireOwnedOffer(actor, offerId);
        offer.close(clock.instant());
        offerRepository.flush();
        auditService.record(actor, AuditAction.OFFER_CLOSED, offer.id(), Map.of("reference", offer.reference()));
        return OfferView.of(offer);
    }

    /**
     * For other modules: the supplier of an offer that is PUBLISHED and valid on this day, the
     * offer half of BR-10. Empty when there is no such offer.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findSupplierOfCurrentOffer(UUID offerId, LocalDate day) {
        return offerRepository.findSupplierOfCurrentOffer(offerId, day);
    }

    /** 404 - not 403 - for another supplier's offer: we do not reveal that it exists. */
    private Offer requireOwnedOffer(Actor actor, UUID offerId) {
        UUID supplierId = actor.requireSupplier();
        return offerRepository.findOwnedBy(offerId, supplierId)
                .orElseThrow(() -> PortalException.notFound(ErrorCode.OFFER_NOT_FOUND, "Offer", offerId));
    }
}

package com.equadis.portal.partnership;

import com.equadis.portal.audit.AuditAction;
import com.equadis.portal.audit.AuditService;
import com.equadis.portal.directory.DirectoryService;
import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import com.equadis.portal.tenant.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Use cases on partnerships. Business rules are in {@link Partnership}; this class handles
 * authorization, transactions, locking and the rules that need other rows (BR-04).
 */
@Service
public class PartnershipService {

    private static final Logger log = LoggerFactory.getLogger(PartnershipService.class);

    private final PartnershipRepository partnershipRepository;
    private final DirectoryService directoryService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public PartnershipService(PartnershipRepository partnershipRepository, DirectoryService directoryService,
                              AuditService auditService, ApplicationEventPublisher eventPublisher, Clock clock) {
        this.partnershipRepository = partnershipRepository;
        this.directoryService = directoryService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /** Assumption: partnerships are created by the marketplace administrator. */
    @Transactional
    public PartnershipView create(Actor actor, UUID supplierId, UUID retailerId, LocalDate startDate, LocalDate endDate) {
        actor.requireRole(Actor.Role.MARKETPLACE_ADMIN);
        directoryService.requireSupplier(supplierId);
        directoryService.requireRetailer(retailerId);

        Partnership partnership = Partnership.create(actor.marketplaceId(), supplierId, retailerId, startDate, endDate, clock.instant());
        rejectOverlap(partnership);
        // saveAndFlush: if a concurrent request won the race, the exclusion constraint fires HERE
        // and is translated to PARTNERSHIP_OVERLAP (409) by the global error handler.
        partnershipRepository.saveAndFlush(partnership);
        auditService.record(actor, AuditAction.PARTNERSHIP_CREATED, partnership.id(), Map.of(
                "supplierId", supplierId, "retailerId", retailerId, "startDate", startDate, "endDate", endDate));
        log.info("Partnership {} created: supplier={} retailer={} period=[{}, {}]",
                partnership.id(), supplierId, retailerId, startDate, endDate);
        return PartnershipView.of(partnership, today());
    }

    /** @throws PortalException PARTNERSHIP_NOT_FOUND when it is not visible to this caller */
    @Transactional(readOnly = true)
    public PartnershipView get(Actor actor, UUID partnershipId) {
        Partnership partnership = partnershipRepository.findById(partnershipId)
                .filter(found -> isVisibleTo(actor, found))
                .orElseThrow(() -> PortalException.notFound(ErrorCode.PARTNERSHIP_NOT_FOUND, "Partnership", partnershipId));
        return PartnershipView.of(partnership, today());
    }

    /**
     * R6 / BR-07: terminating cancels the retailer's active subscriptions to the supplier's
     * offers, in the same transaction. The row lock (FOR UPDATE) serialises this with any
     * concurrent subscription on the same partnership (which takes FOR SHARE).
     * The retailer loses catalogue access immediately, because the catalogue only joins
     * ACTIVE partnerships - nothing to invalidate.
     *
     * <p>A renewal waiting to start (SCHEDULED successor, BR-06) is terminated too, in the same
     * transaction: otherwise the retailer would get access back on the renewal's start date.
     */
    @Transactional
    public PartnershipView terminate(Actor actor, UUID partnershipId) {
        Partnership partnership = lockIfVisible(actor, partnershipId);
        Instant now = clock.instant();
        partnership.terminate(today(), now);
        eventPublisher.publishEvent(new PartnershipTerminated(partnership.id(), partnership.supplierId(), partnership.retailerId(), now));
        auditService.record(actor, AuditAction.PARTNERSHIP_TERMINATED, partnership.id(), Map.of(
                "supplierId", partnership.supplierId(), "retailerId", partnership.retailerId()));
        log.info("Partnership {} terminated by {} {}", partnershipId, actor.role(), actor.actorId());
        terminateRenewals(actor, partnership, now);
        return PartnershipView.of(partnership, today());
    }

    /** Follows the renewal chain: each successor is locked, terminated and audited. */
    private void terminateRenewals(Actor actor, Partnership terminated, Instant now) {
        for (Partnership renewal : partnershipRepository.findLiveRenewalsForUpdate(terminated.id())) {
            renewal.terminate(today(), now);
            auditService.record(actor, AuditAction.PARTNERSHIP_TERMINATED, renewal.id(), Map.of(
                    "supplierId", renewal.supplierId(), "retailerId", renewal.retailerId(),
                    "terminatedWith", terminated.id()));
            log.info("Renewal {} of partnership {} terminated with it", renewal.id(), terminated.id());
            terminateRenewals(actor, renewal, now);
        }
    }

    /** BR-06. The successor starts the day after the current end (or today if already expired). */
    @Transactional
    public PartnershipView renew(Actor actor, UUID partnershipId, LocalDate newEndDate) {
        actor.requireRole(Actor.Role.MARKETPLACE_ADMIN);
        Partnership current = lockIfVisible(actor, partnershipId);
        Partnership successor = current.renew(newEndDate, today(), clock.instant());
        rejectOverlap(successor);
        partnershipRepository.saveAndFlush(successor);
        auditService.record(actor, AuditAction.PARTNERSHIP_RENEWED, successor.id(), Map.of(
                "renewedFromId", current.id(), "startDate", successor.startDate(), "endDate", successor.endDate()));
        log.info("Partnership {} renewed as {} until {}", partnershipId, successor.id(), newEndDate);
        return PartnershipView.of(successor, today());
    }

    /**
     * Returns whether the pair has an ACTIVE partnership today, and - if so - holds a shared
     * lock ({@code FOR SHARE}) on it until the caller's transaction ends.
     *
     * <p>Many subscriptions can share the lock at the same time, but a termination (which needs
     * {@code FOR UPDATE}) waits for them, and vice versa. So a subscription can never be created
     * "in the middle" of a termination and survive it (R6).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockIfActive(UUID supplierId, UUID retailerId, LocalDate today) {
        return partnershipRepository.findActiveIdForShare(supplierId, retailerId, today).isPresent();
    }

    private Partnership lockIfVisible(Actor actor, UUID partnershipId) {
        return partnershipRepository.findByIdForUpdate(partnershipId)
                .filter(found -> isVisibleTo(actor, found))
                .orElseThrow(() -> PortalException.notFound(ErrorCode.PARTNERSHIP_NOT_FOUND, "Partnership", partnershipId));
    }

    /** Admins see every partnership of their marketplace; suppliers/retailers only their own. */
    private static boolean isVisibleTo(Actor actor, Partnership partnership) {
        return actor.isAdmin() || partnership.involves(actor.actorId());
    }

    private void rejectOverlap(Partnership candidate) {
        if (partnershipRepository.existsOverlapping(candidate.supplierId(), candidate.retailerId(), candidate.startDate(), candidate.endDate())) {
            throw new PortalException(ErrorCode.PARTNERSHIP_OVERLAP,
                    "Supplier %s and retailer %s already have a partnership overlapping [%s, %s]"
                            .formatted(candidate.supplierId(), candidate.retailerId(), candidate.startDate(), candidate.endDate()));
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}

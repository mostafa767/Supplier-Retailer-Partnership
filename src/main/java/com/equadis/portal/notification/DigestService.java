package com.equadis.portal.notification;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Builds a retailer's digest at SEND time.
 *
 * <p>Computing the content when sending - instead of storing one row per offer and retailer
 * when publishing - is what makes Black Friday affordable (500,000 offers x ~900 retailers
 * would be ~450 million rows). It also gives R6 for free: only what the retailer can see NOW
 * goes in.
 */
@Service
class DigestService {

    private final DigestRepository digestRepository;

    DigestService(DigestRepository digestRepository) {
        this.digestRepository = digestRepository;
    }

    /** @param maxOffers how many offers to list; the supplier counts always cover all of them */
    Digest build(UUID retailerId, Instant windowFrom, Instant windowTo, LocalDate today, int maxOffers) {
        List<Digest.SupplierSummary> suppliers = digestRepository.countNewOffersBySupplier(retailerId, windowFrom, windowTo, today);
        int totalOffers = suppliers.stream().mapToInt(Digest.SupplierSummary::newOffers).sum();
        if (totalOffers == 0) {
            return new Digest(retailerId, windowFrom, windowTo, 0, List.of(), List.of());
        }
        List<Digest.OfferSummary> offers = digestRepository.findNewOffers(retailerId, windowFrom, windowTo, today, maxOffers);
        return new Digest(retailerId, windowFrom, windowTo, totalOffers, suppliers, offers);
    }
}

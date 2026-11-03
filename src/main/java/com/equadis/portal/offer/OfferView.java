package com.equadis.portal.offer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** API representation of an offer, for its supplier. The JPA entity is never exposed. */
public record OfferView(UUID id, String reference, String title, String description, DiscountType discountType,
                        BigDecimal discountValue, String currency, LocalDate validFrom, LocalDate validTo,
                        OfferStatus status, Instant publishedAt, long version) {

    static OfferView of(Offer offer) {
        OfferTerms terms = offer.terms();
        return new OfferView(offer.id(), offer.reference(), terms.title(), terms.description(), terms.discountType(),
                terms.discountValue(), terms.currency(), terms.validFrom(), terms.validTo(),
                offer.status(), offer.publishedAt(), offer.version());
    }
}

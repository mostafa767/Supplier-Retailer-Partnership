package com.equadis.portal.notification;

import com.equadis.portal.offer.DiscountType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What a retailer is told: the offers it can see (BR-10) published in ]windowFrom, windowTo].
 *
 * @param offers the most recent offers (at most the configured limit); {@code totalOffers}
 *               tells how many there are in total
 */
public record Digest(UUID retailerId, Instant windowFrom, Instant windowTo, int totalOffers,
                     List<SupplierSummary> suppliers, List<OfferSummary> offers) {

    public boolean isEmpty() {
        return totalOffers == 0;
    }

    public record SupplierSummary(UUID supplierId, String supplierName, int newOffers) {
    }

    public record OfferSummary(UUID offerId, UUID supplierId, String supplierName, String reference, String title,
                               DiscountType discountType, BigDecimal discountValue, String currency,
                               LocalDate validFrom, LocalDate validTo, Instant publishedAt) {
    }
}

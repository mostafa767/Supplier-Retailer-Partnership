package com.equadis.portal.catalogue;

import com.equadis.portal.offer.DiscountType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One offer as shown to a retailer in the catalogue. */
record CatalogueOffer(UUID offerId, UUID supplierId, String supplierName, String reference, String title,
                      String description, DiscountType discountType, BigDecimal discountValue, String currency,
                      LocalDate validFrom, LocalDate validTo, Instant publishedAt) {
}

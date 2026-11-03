package com.equadis.portal.partnership;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** API representation of a partnership, with its status computed for today. */
public record PartnershipView(UUID id, UUID supplierId, UUID retailerId, LocalDate startDate, LocalDate endDate,
                              PartnershipStatus status, Instant terminatedAt, UUID renewedFromId) {

    static PartnershipView of(Partnership partnership, LocalDate today) {
        return new PartnershipView(partnership.id(), partnership.supplierId(), partnership.retailerId(),
                partnership.startDate(), partnership.endDate(), partnership.statusOn(today),
                partnership.terminatedAt(), partnership.renewedFromId());
    }
}

package com.equadis.portal.importing;

import com.equadis.portal.offer.OfferStatus;
import com.equadis.portal.offer.OfferTerms;

import java.util.UUID;

/** What the import needs to know about an offer already in the database. */
record StoredOffer(UUID id, String reference, OfferStatus status, long version, OfferTerms terms) {
}

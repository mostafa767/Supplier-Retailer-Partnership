package com.equadis.portal.offer;

import java.util.UUID;

/**
 * Outbox event payload: a supplier published one or more offers.
 *
 * <p>Deliberately <b>one event per publication batch</b>, not one per offer: a CSV import
 * publishing 100,000 offers emits ~100 events (one per committed chunk), not 100,000. The
 * notification module only needs to know <i>which supplier</i> has news; which offers are new
 * is computed when the digest is sent.
 *
 * @param source "API" or "IMPORT:&lt;importJobId&gt;" - for tracing only
 */
public record OffersPublished(UUID supplierId, int offerCount, String source) {

    public static final String EVENT_TYPE = "OFFERS_PUBLISHED";
}

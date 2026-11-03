package com.equadis.portal.shared.work;

import java.util.UUID;

/**
 * A unit of background work handed to this worker by one of the {@code claim_*} database
 * functions: what to process, for which marketplace, and how many times it was tried
 * (this attempt included).
 */
public record ClaimedWork(UUID id, UUID marketplaceId, int attempts) {
}

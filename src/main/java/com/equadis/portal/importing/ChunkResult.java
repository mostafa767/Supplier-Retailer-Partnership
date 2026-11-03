package com.equadis.portal.importing;

/**
 * What happened to the lines of one chunk.
 *
 * @param created   new offers
 * @param updated   existing DRAFT offers whose content changed (or that got published)
 * @param unchanged lines identical to the stored offer - nothing written
 * @param failed    lines rejected (each one has at least one row in the error report)
 * @param published offers that became PUBLISHED in this chunk (drives notifications)
 */
record ChunkResult(int created, int updated, int unchanged, int failed, int published) {
}

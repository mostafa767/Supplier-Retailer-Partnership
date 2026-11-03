package com.equadis.portal.catalogue;

import java.time.Instant;
import java.util.UUID;

/**
 * What a retailer asks of the catalogue. Every field is optional.
 *
 * @param size   requested page size; the service applies the default and the limits
 * @param cursor the opaque {@code nextCursor} of the previous page
 */
record CatalogueFilter(UUID supplierId, Instant publishedSince, String searchText, Integer size, String cursor) {

    boolean hasSearchText() {
        return searchText != null && !searchText.isBlank();
    }
}

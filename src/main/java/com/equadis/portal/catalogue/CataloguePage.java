package com.equadis.portal.catalogue;

import java.util.List;

/**
 * One page of the catalogue.
 *
 * @param nextCursor pass it back as {@code cursor} to get the next page; null on the last page
 */
record CataloguePage(List<CatalogueOffer> items, String nextCursor) {
}

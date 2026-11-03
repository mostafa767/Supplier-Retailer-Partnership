package com.equadis.portal.importing;

import java.util.List;

/**
 * One page of an import's error report.
 *
 * @param nextAfterId pass it as {@code afterId} to get the next page; null on the last page
 */
record ImportErrorPage(List<ImportErrorView> items, Long nextAfterId) {
}

package com.equadis.portal.importing;

import java.util.List;

/**
 * What an import WOULD do, computed without changing anything: the same checks and the same
 * decisions as a real import, against the offers currently stored.
 *
 * @param problems          the first problems found, in file order; {@code errorSummary} counts all of them
 * @param problemsTruncated true when there are more problems than listed
 */
record ImportDryRunReport(ImportFormat format, int totalRows, int wouldCreate, int wouldUpdate, int unchanged, int failed,
                          int wouldPublish, List<ErrorCount> errorSummary, List<ImportErrorView> problems,
                          boolean problemsTruncated) {
}

package com.equadis.portal.importing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * An inbound connector: turns offers sent in one format into the import pipeline's canonical
 * form, a CSV file.
 *
 * <p>Everything after this step - structure checks, validation, the error report, protection
 * against duplicates, chunked writing, crash recovery - is shared by every format. Supporting
 * a new format (an Excel matrix, a GDSN message, a partner's own API) means writing one more
 * implementation, nothing else.
 */
interface OfferSourceAdapter {

    ImportFormat format();

    /**
     * Writes the offers of {@code source} as canonical CSV. Must stream: an import can hold
     * 100,000 offers.
     *
     * @throws com.equadis.portal.shared.error.PortalException when the source is not readable
     *                                                         in this format (4xx to the caller)
     */
    void toCanonicalCsv(InputStream source, OutputStream canonicalCsv) throws IOException;
}

package com.equadis.portal.importing;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * CSV files are already in the canonical form. They are stored exactly as received, so that the
 * checks that follow (encoding, separator, header) see the supplier's original file, and the
 * same file sent twice has the same fingerprint.
 */
@Component
class CsvOfferSource implements OfferSourceAdapter {

    @Override
    public ImportFormat format() {
        return ImportFormat.CSV;
    }

    @Override
    public void toCanonicalCsv(InputStream source, OutputStream canonicalCsv) throws IOException {
        source.transferTo(canonicalCsv);
    }
}

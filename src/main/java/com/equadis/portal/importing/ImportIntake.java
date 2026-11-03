package com.equadis.portal.importing;

import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.web.SizeLimitedInputStream;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The first step of every import, real or dry run: receive the offers in their format, store
 * them in canonical form (computing the fingerprint on the way), and check the structure.
 *
 * <p>A file that fails the structure checks is deleted at once and the caller gets a 4xx; it
 * never reaches the line-by-line processing.
 */
@Component
class ImportIntake {

    private final Map<ImportFormat, OfferSourceAdapter> adaptersByFormat = new EnumMap<>(ImportFormat.class);
    private final ImportFileStorage fileStorage;
    private final OfferCsvInspector csvInspector;
    private final ImportProperties properties;

    ImportIntake(List<OfferSourceAdapter> adapters, ImportFileStorage fileStorage, OfferCsvInspector csvInspector,
                 ImportProperties properties) {
        adapters.forEach(adapter -> adaptersByFormat.put(adapter.format(), adapter));
        this.fileStorage = fileStorage;
        this.csvInspector = csvInspector;
        this.properties = properties;
    }

    /**
     * @param fileId names the stored file; the import job id for a real import
     * @return the stored canonical file and what its structure check found
     */
    ReceivedFile receive(UUID marketplaceId, UUID fileId, ImportFormat format, InputStream source) {
        OfferSourceAdapter adapter = adaptersByFormat.get(format);
        if (adapter == null) {
            throw new IllegalStateException("No inbound connector for format " + format);
        }
        InputStream limitedSource = new SizeLimitedInputStream(source, properties.maxRequestSize().toBytes(), ErrorCode.IMPORT_FILE_TOO_LARGE);
        ImportFileStorage.StoredFile storedFile = fileStorage.write(marketplaceId, fileId,
                canonicalCsv -> adapter.toCanonicalCsv(limitedSource, canonicalCsv));
        try {
            return new ReceivedFile(storedFile, csvInspector.inspect(storedFile.path()));
        } catch (RuntimeException rejected) {
            fileStorage.deleteQuietly(storedFile.key());
            throw rejected;
        }
    }

    void discard(ReceivedFile file) {
        fileStorage.deleteQuietly(file.storedFile().key());
    }

    record ReceivedFile(ImportFileStorage.StoredFile storedFile, OfferCsvInspector.InspectedFile inspectedFile) {
    }
}

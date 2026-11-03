package com.equadis.portal.importing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Keeps uploaded files (local disk here; object storage such as S3 in production - only this
 * class changes).
 *
 * <p>The SHA-256 fingerprint used for idempotency is computed <b>while</b> the file is
 * written, so a 100,000-line file is written only once and never held in memory.
 *
 * <p>Storage keys are built from UUIDs only - never from the uploaded file name - so a
 * malicious name such as {@code ../../etc/passwd} cannot escape the storage directory.
 */
@Component
class ImportFileStorage {

    private static final Logger log = LoggerFactory.getLogger(ImportFileStorage.class);

    private final Path root;

    ImportFileStorage(ImportProperties properties) {
        this.root = Path.of(properties.storageDirectory()).toAbsolutePath().normalize();
    }

    /** Stores an uploaded file as it is. */
    StoredFile store(UUID marketplaceId, UUID jobId, InputStream content) {
        return write(marketplaceId, jobId, content::transferTo);
    }

    /**
     * Stores what {@code contentWriter} writes. When it fails, nothing is left behind.
     *
     * @return where the file is, and the SHA-256 of what was written
     */
    StoredFile write(UUID marketplaceId, UUID jobId, ContentWriter contentWriter) {
        String key = marketplaceId + "/" + jobId + ".csv";
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (OutputStream output = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(target)), sha256)) {
                contentWriter.writeTo(output);
            }
            return new StoredFile(key, target, HexFormat.of().formatHex(sha256.digest()), Files.size(target));
        } catch (IOException e) {
            deleteQuietly(key);
            throw new UncheckedIOException("Cannot store uploaded file " + key, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        } catch (RuntimeException rejected) {
            deleteQuietly(key);
            throw rejected;
        }
    }

    Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Storage key escapes the storage directory: " + key);
        }
        return path;
    }

    void deleteQuietly(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            log.warn("Could not delete stored file {} (will be removed by the retention purge)", key, e);
        }
    }

    record StoredFile(String key, Path path, String sha256, long sizeBytes) {
    }

    /** Writes the content of a file to be stored. */
    @FunctionalInterface
    interface ContentWriter {
        void writeTo(OutputStream output) throws IOException;
    }
}

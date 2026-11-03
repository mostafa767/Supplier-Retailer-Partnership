package com.equadis.portal.importing;

import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import org.apache.commons.csv.CSVParser;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks the <b>structure</b> of an uploaded file synchronously, before accepting it.
 *
 * <p>Line-by-line error reporting only makes sense when the file itself is right. A missing
 * column, a wrong encoding, an empty or oversized file are reported immediately in the upload
 * response (HTTP 4xx) instead of producing 100,000 identical line errors later.
 */
@Component
class OfferCsvInspector {

    private final ImportProperties properties;

    OfferCsvInspector(ImportProperties properties) {
        this.properties = properties;
    }

    InspectedFile inspect(Path file) {
        char delimiter = detectDelimiter(file);
        int rows = 0;
        try (Reader reader = OfferCsvFormat.openUtf8(file);
             CSVParser parser = OfferCsvFormat.format(delimiter).parse(reader)) {
            List<String> missing = OfferCsvFormat.missingRequiredColumns(parser.getHeaderNames());
            if (!missing.isEmpty()) {
                throw new PortalException(ErrorCode.IMPORT_FILE_INVALID_HEADER,
                        "Missing required column(s): %s. Expected header: %s (optional: description, currency, publish)"
                                .formatted(String.join(", ", missing), String.join(",", OfferCsvFormat.REQUIRED_COLUMNS)));
            }
            for (var ignored : parser) {
                rows++;
                if (rows > properties.maxRows()) {
                    throw new PortalException(ErrorCode.IMPORT_FILE_TOO_LARGE,
                            "A file may contain at most %,d offers".formatted(properties.maxRows()));
                }
            }
        } catch (CharacterCodingException notUtf8) {
            throw notUtf8();
        } catch (UncheckedIOException parsingFailure) {
            if (parsingFailure.getCause() instanceof CharacterCodingException) {
                throw notUtf8();
            }
            throw unreadable(parsingFailure);
        } catch (FileSystemException ourStorageFailed) {
            // Our problem, not the supplier's. Its message holds server paths: it goes to the logs only.
            throw new UncheckedIOException("Cannot read the stored upload", ourStorageFailed);
        } catch (IOException | IllegalArgumentException | IllegalStateException parsingFailure) {
            // Commons CSV reports structural problems (unclosed quote, duplicate header) this way.
            throw unreadable(parsingFailure);
        }
        if (rows == 0) {
            throw new PortalException(ErrorCode.IMPORT_FILE_EMPTY, "The file contains a header but no offers");
        }
        return new InspectedFile(delimiter, rows);
    }

    private static char detectDelimiter(Path file) {
        try (BufferedReader reader = new BufferedReader(OfferCsvFormat.openUtf8(file))) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new PortalException(ErrorCode.IMPORT_FILE_EMPTY, "The file is empty");
            }
            return OfferCsvFormat.detectDelimiter(headerLine);
        } catch (CharacterCodingException notUtf8) {
            throw notUtf8();
        } catch (IOException ourStorageFailed) {
            throw new UncheckedIOException("Cannot read the stored upload", ourStorageFailed);
        }
    }

    private static PortalException notUtf8() {
        return new PortalException(ErrorCode.IMPORT_FILE_NOT_UTF8,
                "The file is not valid UTF-8. Export it as 'CSV UTF-8' and upload it again");
    }

    private static PortalException unreadable(Exception cause) {
        return new PortalException(ErrorCode.IMPORT_FILE_UNREADABLE, "The file is not a readable CSV: " + cause.getMessage());
    }

    record InspectedFile(char delimiter, int rowCount) {
    }
}

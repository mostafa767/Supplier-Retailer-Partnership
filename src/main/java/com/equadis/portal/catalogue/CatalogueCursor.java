package com.equadis.portal.catalogue;

import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset-pagination cursor: "continue after the offer published at T with id X".
 *
 * <p>Keyset instead of OFFSET: with OFFSET, page 200 makes the database read and discard
 * 10,000 rows; with a keyset it jumps straight to the right place through the index, so every
 * page costs the same (R4). The cursor is opaque (Base64) so that clients do not build it
 * themselves and we can change its content later.
 */
record CatalogueCursor(Instant publishedAt, UUID offerId) {

    private static final String SEPARATOR = "|";

    String encode() {
        String raw = publishedAt + SEPARATOR + offerId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static CatalogueCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.indexOf(SEPARATOR);
            if (separator < 0) {
                throw invalid();
            }
            return new CatalogueCursor(Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException tampered) {
            throw invalid();
        }
    }

    private static PortalException invalid() {
        return new PortalException(ErrorCode.INVALID_PAGE_CURSOR, "The page cursor is invalid; restart from the first page");
    }
}

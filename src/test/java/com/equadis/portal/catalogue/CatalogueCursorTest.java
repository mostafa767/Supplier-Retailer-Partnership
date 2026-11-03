package com.equadis.portal.catalogue;

import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogueCursorTest {

    @Test
    void cursorRoundTrips() {
        CatalogueCursor cursor = new CatalogueCursor(Instant.parse("2026-11-27T08:00:00.123456Z"), UUID.randomUUID());
        assertThat(CatalogueCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-base64!!", "aGVsbG8", ""})
    void tamperedCursor_isA400NotA500(String tampered) {
        assertThatThrownBy(() -> CatalogueCursor.decode(tampered))
                .extracting(e -> ((PortalException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PAGE_CURSOR);
    }

    @Test
    void searchTextWildcardsAreEscaped() {
        assertThat(CatalogueRepository.escapeLikeWildcards("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
    }
}

package com.equadis.portal.offer;

import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BR-08 - the offer lifecycle DRAFT -> PUBLISHED -> CLOSED. */
class OfferTest {

    private static final Instant NOW = Instant.parse("2030-01-10T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2030, 1, 10);

    private static OfferTerms terms(String title) {
        return new OfferTerms(title, null, DiscountType.PERCENTAGE, BigDecimal.TEN, null,
                LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 31));
    }

    private static Offer draft() {
        return Offer.draft(UUID.randomUUID(), UUID.randomUUID(), "  SUN-01 ", terms("Deal"), NOW);
    }

    @Test
    void newOfferIsADraft_withTrimmedReference() {
        Offer offer = draft();
        assertThat(offer.status()).isEqualTo(OfferStatus.DRAFT);
        assertThat(offer.reference()).isEqualTo("SUN-01");
    }

    @Test
    void draftCanBeEdited_publishedCannot() {
        Offer offer = draft();
        offer.updateDraft(terms("Better deal"), NOW);
        assertThat(offer.terms().title()).isEqualTo("Better deal");

        offer.publish(TODAY, NOW);
        assertThatThrownBy(() -> offer.updateDraft(terms("Sneaky change"), NOW))
                .extracting(e -> ((PortalException) e).errorCode())
                .isEqualTo(ErrorCode.OFFER_NOT_EDITABLE);
    }

    @Test
    void lifecycleOnlyMovesForward() {
        Offer offer = draft();
        assertThatThrownBy(() -> offer.close(NOW)).extracting(e -> ((PortalException) e).errorCode())
                .isEqualTo(ErrorCode.OFFER_INVALID_TRANSITION);

        offer.publish(TODAY, NOW);
        assertThat(offer.publishedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> offer.publish(TODAY, NOW)).isInstanceOf(PortalException.class);

        offer.close(NOW);
        assertThat(offer.status()).isEqualTo(OfferStatus.CLOSED);
        assertThatThrownBy(() -> offer.publish(TODAY, NOW)).isInstanceOf(PortalException.class);
    }

    @Test
    void expiredDraftCannotBePublished() {
        assertThatThrownBy(() -> draft().publish(LocalDate.of(2030, 2, 1), NOW))
                .extracting(e -> ((PortalException) e).errorCode())
                .isEqualTo(ErrorCode.OFFER_EXPIRED);
    }

    @Test
    void invalidDraftReportsReferenceAndTermsProblemsTogether() {
        OfferTerms invalid = new OfferTerms("", null, DiscountType.PERCENTAGE, new BigDecimal("0"), null,
                LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 31));

        assertThatThrownBy(() -> Offer.draft(UUID.randomUUID(), UUID.randomUUID(), " ", invalid, NOW))
                .isInstanceOfSatisfying(PortalException.class, e -> assertThat(e.violations())
                        .extracting(v -> v.code())
                        .containsExactlyInAnyOrder("REFERENCE_REQUIRED", "TITLE_REQUIRED", "DISCOUNT_OUT_OF_RANGE"));
    }
}

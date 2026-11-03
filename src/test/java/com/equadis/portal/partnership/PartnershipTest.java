package com.equadis.portal.partnership;

import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Partnership rules BR-03, BR-05, BR-06, BR-07 - pure unit tests, no Spring, no database. */
class PartnershipTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 12, 31);

    private static Partnership partnership(LocalDate start, LocalDate end) {
        return Partnership.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), start, end, NOW);
    }

    @Nested
    class Period_BR03 {

        @Test
        void endDateMustBeAfterStartDate() {
            assertThatThrownBy(() -> partnership(START, START))
                    .isInstanceOf(PortalException.class)
                    .extracting(e -> ((PortalException) e).errorCode())
                    .isEqualTo(ErrorCode.PARTNERSHIP_INVALID_PERIOD);
        }

        @ParameterizedTest(name = "{0} + 36 months = {1} is allowed")
        @CsvSource({
                "2026-01-01, 2029-01-01",
                "2026-01-31, 2029-01-31",   // month end
                "2028-02-29, 2031-02-28"    // leap day: same arithmetic as the database (date + interval '36 months')
        })
        void exactly36MonthsIsAllowed(LocalDate start, LocalDate end) {
            assertThat(partnership(start, end).endDate()).isEqualTo(end);
        }

        @Test
        void oneDayMoreThan36MonthsIsRefused() {
            assertThatThrownBy(() -> partnership(LocalDate.of(2026, 1, 31), LocalDate.of(2029, 2, 1)))
                    .hasMessageContaining("at most 36 months");
        }
    }

    @Nested
    class Status_BR05 {

        @ParameterizedTest(name = "on {0} the status is {1}")
        @CsvSource({
                "2025-12-31, SCHEDULED",
                "2026-01-01, ACTIVE",      // start date inclusive
                "2026-06-15, ACTIVE",
                "2026-12-31, ACTIVE",      // end date inclusive
                "2027-01-01, EXPIRED"
        })
        void statusIsDerivedFromTheDates(LocalDate today, PartnershipStatus expected) {
            assertThat(partnership(START, END).statusOn(today)).isEqualTo(expected);
        }

        @Test
        void terminationWinsOverDates() {
            Partnership partnership = partnership(START, END);
            partnership.terminate(LocalDate.of(2026, 6, 1), NOW);
            assertThat(partnership.statusOn(LocalDate.of(2026, 6, 2))).isEqualTo(PartnershipStatus.TERMINATED);
        }
    }

    @Nested
    class Termination_BR07 {

        @Test
        void scheduledPartnershipCanBeTerminated() {
            Partnership partnership = partnership(START, END);
            partnership.terminate(LocalDate.of(2025, 12, 1), NOW);
            assertThat(partnership.terminatedAt()).isEqualTo(NOW);
        }

        @Test
        void expiredPartnershipCannotBeTerminated() {
            assertThatThrownBy(() -> partnership(START, END).terminate(LocalDate.of(2027, 1, 5), NOW))
                    .extracting(e -> ((PortalException) e).errorCode())
                    .isEqualTo(ErrorCode.PARTNERSHIP_NOT_TERMINABLE);
        }

        @Test
        void terminatingTwiceIsRefused() {
            Partnership partnership = partnership(START, END);
            partnership.terminate(LocalDate.of(2026, 6, 1), NOW);
            assertThatThrownBy(() -> partnership.terminate(LocalDate.of(2026, 6, 2), NOW))
                    .isInstanceOf(PortalException.class);
        }
    }

    @Nested
    class Renewal_BR06 {

        @Test
        void activePartnershipRenewsTheDayAfterItsEnd() {
            Partnership renewed = partnership(START, END).renew(LocalDate.of(2027, 12, 31), LocalDate.of(2026, 6, 1), NOW);
            assertThat(renewed.startDate()).isEqualTo(LocalDate.of(2027, 1, 1));
            assertThat(renewed.endDate()).isEqualTo(LocalDate.of(2027, 12, 31));
        }

        @Test
        void expiredSince30DaysCanStillBeRenewed_startingToday() {
            LocalDate day30 = END.plusDays(30);
            Partnership renewed = partnership(START, END).renew(LocalDate.of(2027, 12, 31), day30, NOW);
            assertThat(renewed.startDate()).isEqualTo(day30);
        }

        @Test
        void expiredSince31DaysCannotBeRenewed() {
            assertThatThrownBy(() -> partnership(START, END).renew(LocalDate.of(2027, 12, 31), END.plusDays(31), NOW))
                    .extracting(e -> ((PortalException) e).errorCode())
                    .isEqualTo(ErrorCode.PARTNERSHIP_NOT_RENEWABLE);
        }

        @Test
        void scheduledPartnershipCannotBeRenewed() {
            assertThatThrownBy(() -> partnership(START, END).renew(LocalDate.of(2027, 12, 31), LocalDate.of(2025, 6, 1), NOW))
                    .isInstanceOf(PortalException.class);
        }
    }
}

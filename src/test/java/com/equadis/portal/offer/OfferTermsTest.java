package com.equadis.portal.offer;

import com.equadis.portal.offer.OfferField.Naming;
import com.equadis.portal.shared.validation.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** BR-09 - discount and validity rules, shared by the JSON API and the CSV import. */
class OfferTermsTest {

    private static final LocalDate FROM = LocalDate.of(2030, 1, 1);
    private static final LocalDate TO = LocalDate.of(2030, 1, 31);

    private static OfferTerms percentage(String value) {
        return new OfferTerms("Deal", null, DiscountType.PERCENTAGE, new BigDecimal(value), null, FROM, TO);
    }

    private static List<String> codes(OfferTerms terms) {
        return terms.violations(Naming.JSON).stream().map(Violation::code).toList();
    }

    @ParameterizedTest(name = "{0}% is valid")
    @ValueSource(strings = {"0.01", "15", "99.99", "100", "100.00"})
    void percentageInZeroExclusiveToHundredInclusive_isValid(String value) {
        assertThat(codes(percentage(value))).isEmpty();
    }

    @ParameterizedTest(name = "{0}% is refused")
    @ValueSource(strings = {"0", "-1", "100.01", "150"})
    void percentageOutsideRange_isRefused(String value) {
        assertThat(codes(percentage(value))).containsExactly("DISCOUNT_OUT_OF_RANGE");
    }

    @Test
    void moreThanTwoDecimals_isRefused() {
        assertThat(codes(percentage("10.555"))).containsExactly("DISCOUNT_VALUE_TOO_PRECISE");
    }

    @Test
    void fixedAmount_requiresAValidIsoCurrency() {
        OfferTerms noCurrency = new OfferTerms("Deal", null, DiscountType.FIXED_AMOUNT, BigDecimal.TEN, null, FROM, TO);
        OfferTerms fakeCurrency = new OfferTerms("Deal", null, DiscountType.FIXED_AMOUNT, BigDecimal.TEN, "EUROS", FROM, TO);
        OfferTerms lowerCase = new OfferTerms("Deal", null, DiscountType.FIXED_AMOUNT, BigDecimal.TEN, " chf ", FROM, TO);

        assertThat(codes(noCurrency)).containsExactly("CURRENCY_REQUIRED");
        assertThat(codes(fakeCurrency)).containsExactly("CURRENCY_INVALID");
        assertThat(codes(lowerCase)).isEmpty();
        assertThat(lowerCase.currency()).isEqualTo("CHF");
    }

    @Test
    void percentage_mustNotHaveACurrency() {
        OfferTerms terms = new OfferTerms("Deal", null, DiscountType.PERCENTAGE, BigDecimal.TEN, "EUR", FROM, TO);
        assertThat(codes(terms)).containsExactly("CURRENCY_NOT_ALLOWED");
    }

    @Test
    void validToBeforeValidFrom_isRefused_butSameDayIsAllowed() {
        assertThat(codes(new OfferTerms("Deal", null, DiscountType.PERCENTAGE, BigDecimal.TEN, null, TO, FROM)))
                .containsExactly("VALIDITY_WINDOW_INVALID");
        assertThat(codes(new OfferTerms("Deal", null, DiscountType.PERCENTAGE, BigDecimal.TEN, null, FROM, FROM)))
                .isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnce_withTheCallersFieldNames() {
        OfferTerms terms = new OfferTerms(" ", null, null, null, null, null, null);

        assertThat(terms.violations(Naming.CSV)).extracting(Violation::field)
                .containsExactlyInAnyOrder("title", "discount_type", "discount_value", "valid_from", "valid_to");
        assertThat(terms.violations(Naming.JSON)).extracting(Violation::field)
                .containsExactlyInAnyOrder("title", "discountType", "discountValue", "validFrom", "validTo");
    }

    @Test
    void sameContent_comparesAmountsByValue() {
        assertThat(percentage("20").sameContentAs(percentage("20.00"))).isTrue();
        assertThat(percentage("20").sameContentAs(percentage("21"))).isFalse();
    }

    @Test
    void anExpiredOfferCannotBePublished() {
        LocalDate today = LocalDate.of(2030, 2, 1);
        assertThat(percentage("10").publicationViolations(today, Naming.JSON)).extracting(Violation::code)
                .containsExactly("OFFER_EXPIRED");
        assertThat(percentage("10").publicationViolations(TO, Naming.JSON)).isEmpty(); // last valid day
    }
}

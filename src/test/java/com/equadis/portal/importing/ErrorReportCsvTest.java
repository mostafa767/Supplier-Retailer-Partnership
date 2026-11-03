package com.equadis.portal.importing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** CSV injection protection of the downloadable error report. */
class ErrorReportCsvTest {

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "=HYPERLINK(1)|'=HYPERLINK(1)",
            "+1|'+1",
            "-1+2|'-1+2",
            "@SUM(A1)|'@SUM(A1)",
            "SUN-01|SUN-01",
            "L'Oréal|L'Oréal"
    })
    void formulaTriggers_areNeutralised_normalTextIsUntouched(String input, String expected) {
        assertThat(ErrorReportCsv.neutralizeFormula(input)).isEqualTo(expected);
    }
}

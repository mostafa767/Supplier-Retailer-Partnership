package com.equadis.portal.importing;

import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** File-level checks done synchronously at upload, before a job is accepted. */
class OfferCsvInspectorTest {

    private static final String HEADER = "reference,title,discount_type,discount_value,valid_from,valid_to\n";

    @TempDir
    Path directory;

    private final OfferCsvInspector inspector = new OfferCsvInspector(new ImportProperties("unused", 3, 1000, Duration.ofMinutes(1), 3, DataSize.ofMegabytes(50)));

    @Test
    void validFile_isAccepted_withRowCountAndDelimiter() throws IOException {
        OfferCsvInspector.InspectedFile inspected = inspector.inspect(file(HEADER + "A,T,PERCENTAGE,10,2030-01-01,2030-01-31\n\n"));

        assertThat(inspected.rowCount()).isEqualTo(1); // the empty line is ignored
        assertThat(inspected.delimiter()).isEqualTo(',');
    }

    @Test
    void missingColumns_areListed() throws IOException {
        assertThatThrownBy(() -> inspector.inspect(file("reference,title\nA,T\n")))
                .isInstanceOfSatisfying(PortalException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.IMPORT_FILE_INVALID_HEADER);
                    assertThat(e.getMessage()).contains("discount_type", "discount_value", "valid_from", "valid_to");
                });
    }

    @Test
    void headerOnly_isEmpty() throws IOException {
        assertThatThrownBy(() -> inspector.inspect(file(HEADER)))
                .extracting(e -> ((PortalException) e).errorCode()).isEqualTo(ErrorCode.IMPORT_FILE_EMPTY);
    }

    @Test
    void tooManyRows_isRefused() throws IOException {
        String line = "A,T,PERCENTAGE,10,2030-01-01,2030-01-31\n";
        assertThatThrownBy(() -> inspector.inspect(file(HEADER + line.repeat(4))))
                .extracting(e -> ((PortalException) e).errorCode()).isEqualTo(ErrorCode.IMPORT_FILE_TOO_LARGE);
    }

    @Test
    void latin1File_isRefusedInsteadOfSilentlyCorrupted() throws IOException {
        Path latin1 = directory.resolve("latin1.csv");
        Files.write(latin1, (HEADER + "A,Crème,PERCENTAGE,10,2030-01-01,2030-01-31\n").getBytes(StandardCharsets.ISO_8859_1));

        assertThatThrownBy(() -> inspector.inspect(latin1))
                .extracting(e -> ((PortalException) e).errorCode()).isEqualTo(ErrorCode.IMPORT_FILE_NOT_UTF8);
    }

    @Test
    void unclosedQuote_isUnreadable() throws IOException {
        assertThatThrownBy(() -> inspector.inspect(file(HEADER + "A,\"never closed,PERCENTAGE,10,2030-01-01,2030-01-31\n")))
                .extracting(e -> ((PortalException) e).errorCode()).isEqualTo(ErrorCode.IMPORT_FILE_UNREADABLE);
    }

    private Path file(String content) throws IOException {
        return Files.writeString(directory.resolve("offers-" + System.nanoTime() + ".csv"), content, StandardCharsets.UTF_8);
    }
}

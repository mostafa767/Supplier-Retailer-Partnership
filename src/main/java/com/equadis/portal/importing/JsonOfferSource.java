package com.equadis.portal.importing;

import com.equadis.portal.offer.OfferField;
import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Inbound connector for the bulk JSON API: turns {@code {"offers": [...]}} into canonical CSV.
 *
 * <pre>{@code
 * {"offers": [
 *   {"reference": "SUN-01", "title": "Sun cream", "discountType": "PERCENTAGE", "discountValue": 15,
 *    "validFrom": "2026-06-01", "validTo": "2026-08-31", "publish": true}
 * ]}
 * }</pre>
 *
 * <p>Why convert instead of writing a second pipeline: the CSV pipeline already gives what R3
 * asks for - chunked transactions, a line-by-line report, protection against the same offers
 * sent twice, crash recovery. One pipeline means one set of rules and one set of tests.
 *
 * <p>The request is streamed: only one offer is in memory at a time, whatever the size.
 * Values are copied as text and checked later by the same parser as a CSV cell, so a wrong
 * value is a problem on that item, never a failure of the whole request. Fields that are not
 * offer fields are ignored.
 */
@Component
class JsonOfferSource implements OfferSourceAdapter {

    static final String OFFERS_FIELD = "offers";
    private static final List<OfferField> COLUMNS = Arrays.asList(OfferField.values());

    /** Exact decimals: 12.345 must stay 12.345 (and be refused), not become a rounded double. */
    private final ObjectReader itemReader;

    JsonOfferSource(ObjectMapper objectMapper) {
        this.itemReader = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @Override
    public ImportFormat format() {
        return ImportFormat.JSON;
    }

    /** @throws PortalException IMPORT_FILE_UNREADABLE when the body is not the expected JSON */
    @Override
    public void toCanonicalCsv(InputStream json, OutputStream csv) throws IOException {
        // Not closed here: the output stream belongs to the caller, which closes it. Flushed at the end.
        Writer writer = new OutputStreamWriter(csv, StandardCharsets.UTF_8);
        CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT);
        printer.printRecord(COLUMNS.stream().map(OfferField::csvName).toList());
        try (JsonParser parser = itemReader.createParser(json)) {
            moveToOffersArray(parser);
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() != JsonToken.START_OBJECT) {
                    throw unreadable("Each element of \"offers\" must be a JSON object");
                }
                printer.printRecord(cells(itemReader.readTree(parser)));
            }
        } catch (JsonProcessingException notJson) {
            throw unreadable("The request is not valid JSON: " + notJson.getOriginalMessage());
        }
        printer.flush();
    }

    /** Expects {@code {"offers": [...]}}; other top-level fields are skipped. */
    private static void moveToOffersArray(JsonParser parser) throws IOException {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw unreadable("Send a JSON object: {\"offers\": [ ... ]}");
        }
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String fieldName = parser.currentName();
            JsonToken value = parser.nextToken();
            if (OFFERS_FIELD.equals(fieldName)) {
                if (value != JsonToken.START_ARRAY) {
                    throw unreadable("\"offers\" must be an array");
                }
                return;
            }
            parser.skipChildren();
        }
        throw unreadable("The request has no \"offers\" array");
    }

    /** One CSV row: each offer field, as text; missing or null becomes an empty cell. */
    private static List<String> cells(JsonNode offer) {
        List<String> cells = new ArrayList<>(COLUMNS.size());
        for (OfferField field : COLUMNS) {
            JsonNode value = offer.get(field.nameIn(OfferField.Naming.JSON));
            if (value == null || value.isNull()) {
                cells.add("");
            } else {
                // An object or array where a value is expected stays visible, and fails parsing on that item.
                cells.add(value.isValueNode() ? value.asText() : value.toString());
            }
        }
        return cells;
    }

    private static PortalException unreadable(String message) {
        return new PortalException(ErrorCode.IMPORT_FILE_UNREADABLE, message);
    }
}

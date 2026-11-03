package com.equadis.portal.importing;

import com.equadis.portal.offer.OfferField.Naming;

/**
 * How the supplier sent the offers. Both formats are processed by the same pipeline; the
 * format only changes how problems are reported back.
 */
enum ImportFormat {
    /** A CSV file: problems point to a line of the file, with CSV column names. */
    CSV(Naming.CSV),
    /** A JSON request: problems point to an item of the "offers" array, with JSON field names. */
    JSON(Naming.JSON);

    private final Naming fieldNaming;

    ImportFormat(Naming fieldNaming) {
        this.fieldNaming = fieldNaming;
    }

    Naming fieldNaming() {
        return fieldNaming;
    }

    /** "line 5" or "item 3", for messages that point to another offer of the same import. */
    String describePosition(int position) {
        return this == CSV ? "line " + position : "item " + position;
    }
}

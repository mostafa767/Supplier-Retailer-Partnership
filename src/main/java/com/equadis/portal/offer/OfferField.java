package com.equadis.portal.offer;

/**
 * Offer fields, with their name in each channel. Validation reports the name the caller
 * actually used: {@code discountValue} for a JSON request, {@code discount_value} for a CSV
 * column.
 */
public enum OfferField {
    REFERENCE("reference", "reference"),
    TITLE("title", "title"),
    DESCRIPTION("description", "description"),
    DISCOUNT_TYPE("discountType", "discount_type"),
    DISCOUNT_VALUE("discountValue", "discount_value"),
    CURRENCY("currency", "currency"),
    VALID_FROM("validFrom", "valid_from"),
    VALID_TO("validTo", "valid_to"),
    PUBLISH("publish", "publish");

    /** Which naming convention to use when reporting a field. */
    public enum Naming {JSON, CSV}

    private final String jsonName;
    private final String csvName;

    OfferField(String jsonName, String csvName) {
        this.jsonName = jsonName;
        this.csvName = csvName;
    }

    public String nameIn(Naming naming) {
        return naming == Naming.JSON ? jsonName : csvName;
    }

    public String csvName() {
        return csvName;
    }
}

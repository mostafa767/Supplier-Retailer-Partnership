package com.equadis.portal.audit;

/** Every change recorded in the audit trail, with the kind of item it changes. */
public enum AuditAction {
    OFFER_CREATED("OFFER"),
    OFFER_UPDATED("OFFER"),
    OFFER_PUBLISHED("OFFER"),
    OFFER_CLOSED("OFFER"),
    PARTNERSHIP_CREATED("PARTNERSHIP"),
    PARTNERSHIP_TERMINATED("PARTNERSHIP"),
    PARTNERSHIP_RENEWED("PARTNERSHIP"),
    SUBSCRIPTION_CREATED("SUBSCRIPTION"),
    IMPORT_SUBMITTED("IMPORT"),
    IMPORT_REQUEUED("IMPORT"),
    /** One chunk of an import was written: offers created, updated or published. */
    OFFERS_IMPORTED("IMPORT");

    private final String entityType;

    AuditAction(String entityType) {
        this.entityType = entityType;
    }

    public String entityType() {
        return entityType;
    }
}

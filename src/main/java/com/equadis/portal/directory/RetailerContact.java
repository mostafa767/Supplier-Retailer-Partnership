package com.equadis.portal.directory;

import java.util.UUID;

/**
 * How to reach a retailer. Contains personal data and a secret: never log its fields.
 *
 * @param webhookSecret decrypted secret used to sign webhooks; kept in memory only as long as
 *                      one delivery takes
 */
public record RetailerContact(UUID id, String name, String contactEmail, String webhookUrl, String webhookSecret) {

    @Override
    public String toString() {
        // Never print personal data or secrets, even by accident in a log statement.
        return "RetailerContact[id=" + id + "]";
    }
}

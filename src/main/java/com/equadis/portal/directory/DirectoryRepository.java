package com.equadis.portal.directory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads suppliers and retailers. All queries run under Row-Level Security: a supplier or
 * retailer of another marketplace is simply not found.
 */
@Repository
class DirectoryRepository {

    private final JdbcClient jdbc;

    DirectoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean supplierExists(UUID supplierId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM supplier WHERE id = :id)")
                .param("id", supplierId)
                .query(Boolean.class)
                .single();
    }

    boolean retailerExists(UUID retailerId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retailer WHERE id = :id)")
                .param("id", retailerId)
                .query(Boolean.class)
                .single();
    }

    /** @return the retailer's contact row, with its webhook secret still encrypted */
    Optional<StoredRetailerContact> findRetailerContact(UUID retailerId) {
        return jdbc.sql("""
                        SELECT id, name, contact_email, webhook_url, webhook_secret_encrypted
                        FROM retailer WHERE id = :id
                        """)
                .param("id", retailerId)
                .query((row, rowNumber) -> new StoredRetailerContact(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        row.getString("contact_email"),
                        row.getString("webhook_url"),
                        row.getString("webhook_secret_encrypted")))
                .optional();
    }

    record StoredRetailerContact(UUID id, String name, String contactEmail, String webhookUrl, String encryptedWebhookSecret) {

        @Override
        public String toString() {
            return "StoredRetailerContact[id=" + id + "]";
        }
    }
}

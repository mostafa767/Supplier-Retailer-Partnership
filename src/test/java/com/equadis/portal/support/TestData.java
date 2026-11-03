package com.equadis.portal.support;

import com.equadis.portal.directory.WebhookSecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Inserts fixtures directly as the database owner (bypassing Row-Level Security), the way
 * an onboarding script would. Every fixture gets random ids, so tests are independent.
 */
public final class TestData {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final WebhookSecretCipher WEBHOOK_SECRET_CIPHER =
            new WebhookSecretCipher("dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktMDAwMDA="); // same key as application-test.yml

    private final JdbcTemplate owner;

    TestData(JdbcTemplate owner) {
        this.owner = owner;
    }

    public static LocalDate today() {
        return LocalDate.now(PARIS);
    }

    public UUID marketplace() {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO marketplace (id, code, name) VALUES (?, ?, ?)", id, "MP-" + id, "Marketplace " + id);
        return id;
    }

    public UUID supplier(UUID marketplaceId, String companyName) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO supplier (id, marketplace_id, company_name) VALUES (?, ?, ?)", id, marketplaceId, companyName);
        return id;
    }

    /** An ONLINE retailer with an e-mail address (digest channel). */
    public UUID retailer(UUID marketplaceId, String name, String email) {
        return retailer(marketplaceId, name, email, null, null);
    }

    /** Created one hour ago, so that offers published during the test are "new" for it. */
    public UUID retailer(UUID marketplaceId, String name, String email, String webhookUrl, String webhookSecret) {
        UUID id = UUID.randomUUID();
        owner.update("""
                        INSERT INTO retailer (id, marketplace_id, name, store_type, website, contact_email, webhook_url,
                                              webhook_secret_encrypted, created_at)
                        VALUES (?, ?, ?, 'ONLINE', ?, ?, ?, ?, ?)
                        """, id, marketplaceId, name, "https://" + id + ".example", email, webhookUrl,
                webhookSecret == null ? null : WEBHOOK_SECRET_CIPHER.encrypt(id, webhookSecret),
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        return id;
    }

    public UUID partnership(UUID marketplaceId, UUID supplierId, UUID retailerId, LocalDate start, LocalDate end) {
        UUID id = UUID.randomUUID();
        owner.update("""
                INSERT INTO partnership (id, marketplace_id, supplier_id, retailer_id, start_date, end_date)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, marketplaceId, supplierId, retailerId, start, end);
        return id;
    }

    /** ACTIVE today: started a month ago, ends in eleven months. */
    public UUID activePartnership(UUID marketplaceId, UUID supplierId, UUID retailerId) {
        return partnership(marketplaceId, supplierId, retailerId, today().minusMonths(1), today().plusMonths(11));
    }

    public UUID publishedOffer(UUID marketplaceId, UUID supplierId, String reference) {
        return publishedOffer(marketplaceId, supplierId, reference, Instant.now());
    }

    /** A published offer with a chosen publication moment, to control the catalogue order. */
    public UUID publishedOffer(UUID marketplaceId, UUID supplierId, String reference, Instant publishedAt) {
        return offer(marketplaceId, supplierId, reference, "PUBLISHED", publishedAt);
    }

    public UUID draftOffer(UUID marketplaceId, UUID supplierId, String reference) {
        return offer(marketplaceId, supplierId, reference, "DRAFT", Instant.now());
    }

    private UUID offer(UUID marketplaceId, UUID supplierId, String reference, String status, Instant moment) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(moment);
        owner.update("""
                        INSERT INTO offer (id, marketplace_id, supplier_id, reference, title, discount_type, discount_value,
                                           valid_from, valid_to, status, published_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'PERCENTAGE', ?, ?, ?, ?, ?, ?, ?)
                        """, id, marketplaceId, supplierId, reference, "Offer " + reference, new BigDecimal("10"),
                today().minusDays(1), today().plusDays(30), status,
                "PUBLISHED".equals(status) ? now : null, now, now);
        return id;
    }

    public JdbcTemplate owner() {
        return owner;
    }

    public long count(String sql, Object... args) {
        Long count = owner.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }
}

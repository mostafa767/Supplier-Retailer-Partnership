package com.equadis.portal.offer;

import com.equadis.portal.offer.OfferField.Naming;
import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import com.equadis.portal.shared.validation.Violation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A commercial offer of a supplier.
 *
 * <p>Owns the BR-08 lifecycle (DRAFT -> PUBLISHED -> CLOSED, only drafts editable). The
 * commercial content and BR-09 are in {@link OfferTerms}. Bulk imports write offers with
 * plain JDBC for speed (see {@code OfferImportWriter}) but apply the very same rules.
 */
@Entity
@Table(name = "offer")
public class Offer {

    @Id
    private UUID id;

    @Column(name = "marketplace_id", nullable = false, updatable = false)
    private UUID marketplaceId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(nullable = false)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private BigDecimal discountValue;

    private String currency;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OfferStatus status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Wrapper type on purpose: null tells Spring Data the row is new, so it skips a useless SELECT. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Offer() {
        // for JPA
    }

    /** Creates a DRAFT. Fails with every violation at once when the content is invalid. */
    public static Offer draft(UUID marketplaceId, UUID supplierId, String rawReference, OfferTerms terms, Instant now) {
        String reference = OfferReference.normalize(rawReference);
        List<Violation> violations = new ArrayList<>();
        OfferReference.validate(reference, Naming.JSON).ifPresent(violations::add);
        violations.addAll(terms.violations(Naming.JSON));
        rejectIfInvalid(violations);

        Offer offer = new Offer();
        offer.id = UUID.randomUUID();
        offer.marketplaceId = Objects.requireNonNull(marketplaceId, "marketplaceId");
        offer.supplierId = Objects.requireNonNull(supplierId, "supplierId");
        offer.reference = reference;
        offer.status = OfferStatus.DRAFT;
        offer.createdAt = now;
        offer.apply(terms, now);
        return offer;
    }

    /** BR-08: only drafts can be edited. */
    public void updateDraft(OfferTerms terms, Instant now) {
        if (status != OfferStatus.DRAFT) {
            throw new PortalException(ErrorCode.OFFER_NOT_EDITABLE,
                    "Offer %s is %s; only DRAFT offers can be edited".formatted(reference, status));
        }
        rejectIfInvalid(terms.violations(Naming.JSON));
        apply(terms, now);
    }

    /** BR-08: DRAFT -> PUBLISHED, only if the offer is still valid from today on. */
    public void publish(LocalDate today, Instant now) {
        if (status != OfferStatus.DRAFT) {
            throw new PortalException(ErrorCode.OFFER_INVALID_TRANSITION,
                    "Offer %s is %s; only DRAFT offers can be published".formatted(reference, status));
        }
        List<Violation> violations = terms().publicationViolations(today, Naming.JSON);
        if (!violations.isEmpty()) {
            throw new PortalException(ErrorCode.OFFER_EXPIRED, violations.get(0).message(), violations);
        }
        status = OfferStatus.PUBLISHED;
        publishedAt = now;
        updatedAt = now;
    }

    /** BR-08: PUBLISHED -> CLOSED. Closed offers disappear from the catalogue. */
    public void close(Instant now) {
        if (status != OfferStatus.PUBLISHED) {
            throw new PortalException(ErrorCode.OFFER_INVALID_TRANSITION,
                    "Offer %s is %s; only PUBLISHED offers can be closed".formatted(reference, status));
        }
        status = OfferStatus.CLOSED;
        closedAt = now;
        updatedAt = now;
    }

    public OfferTerms terms() {
        return new OfferTerms(title, description, discountType, discountValue, currency, validFrom, validTo);
    }

    private void apply(OfferTerms terms, Instant now) {
        this.title = terms.title();
        this.description = terms.description();
        this.discountType = terms.discountType();
        this.discountValue = terms.discountValue();
        this.currency = terms.currency();
        this.validFrom = terms.validFrom();
        this.validTo = terms.validTo();
        this.updatedAt = now;
    }

    private static void rejectIfInvalid(List<Violation> violations) {
        if (!violations.isEmpty()) {
            throw new PortalException(ErrorCode.OFFER_INVALID_TERMS,
                    "The offer is invalid (%d problem(s))".formatted(violations.size()), violations);
        }
    }

    public UUID id() {
        return id;
    }

    public UUID marketplaceId() {
        return marketplaceId;
    }

    public UUID supplierId() {
        return supplierId;
    }

    public String reference() {
        return reference;
    }

    public OfferStatus status() {
        return status;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    /** @return the optimistic-locking version; null until the offer is first saved */
    public Long version() {
        return version;
    }
}

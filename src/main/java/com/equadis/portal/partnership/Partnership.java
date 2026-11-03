package com.equadis.portal.partnership;

import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * A time-bound partnership between one supplier and one retailer.
 *
 * <p>All partnership business rules live in this class, so they can be unit-tested without
 * a database or Spring:
 * <ul>
 *     <li>BR-03 - end after start, at most {@value #MAX_DURATION_MONTHS} months;</li>
 *     <li>BR-05 - status derived from the dates for "today" (see {@link #statusOn});</li>
 *     <li>BR-06 - renewal allowed while ACTIVE, or up to {@value #RENEWAL_GRACE_DAYS} days
 *         after expiry;</li>
 *     <li>BR-07 - termination (the subscription cancellation itself is done by the service, in
 *         the same transaction).</li>
 * </ul>
 * BR-04 (no overlap) needs other rows, so it is checked by the service and guaranteed by the
 * database exclusion constraint {@code ex_partnership_no_overlap}.
 *
 * <p>Dates are business calendar dates (Europe/Paris), both inclusive.
 */
@Entity
@Table(name = "partnership")
public class Partnership {

    public static final int MAX_DURATION_MONTHS = 36;
    public static final int RENEWAL_GRACE_DAYS = 30;

    @Id
    private UUID id;

    @Column(name = "marketplace_id", nullable = false, updatable = false)
    private UUID marketplaceId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "retailer_id", nullable = false, updatable = false)
    private UUID retailerId;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false, updatable = false)
    private LocalDate endDate;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    @Column(name = "renewed_from_id", updatable = false)
    private UUID renewedFromId;

    /** Wrapper type on purpose: null tells Spring Data the row is new, so it skips a useless SELECT. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Partnership() {
        // for JPA
    }

    private Partnership(UUID marketplaceId, UUID supplierId, UUID retailerId,
                        LocalDate startDate, LocalDate endDate, UUID renewedFromId, Instant now) {
        validatePeriod(startDate, endDate);
        this.id = UUID.randomUUID();
        this.marketplaceId = Objects.requireNonNull(marketplaceId, "marketplaceId");
        this.supplierId = Objects.requireNonNull(supplierId, "supplierId");
        this.retailerId = Objects.requireNonNull(retailerId, "retailerId");
        this.startDate = startDate;
        this.endDate = endDate;
        this.renewedFromId = renewedFromId;
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public static Partnership create(UUID marketplaceId, UUID supplierId, UUID retailerId,
                                     LocalDate startDate, LocalDate endDate, Instant now) {
        return new Partnership(marketplaceId, supplierId, retailerId, startDate, endDate, null, now);
    }

    /** BR-05: the status is a pure function of the dates, the termination and "today". */
    public PartnershipStatus statusOn(LocalDate today) {
        if (terminatedAt != null) {
            return PartnershipStatus.TERMINATED;
        }
        if (today.isBefore(startDate)) {
            return PartnershipStatus.SCHEDULED;
        }
        if (today.isAfter(endDate)) {
            return PartnershipStatus.EXPIRED;
        }
        return PartnershipStatus.ACTIVE;
    }

    /**
     * Ends the partnership now. Allowed while SCHEDULED or ACTIVE: terminating something that
     * is already over (EXPIRED, TERMINATED) is meaningless and is rejected.
     */
    public void terminate(LocalDate today, Instant now) {
        PartnershipStatus status = statusOn(today);
        if (status != PartnershipStatus.SCHEDULED && status != PartnershipStatus.ACTIVE) {
            throw new PortalException(ErrorCode.PARTNERSHIP_NOT_TERMINABLE,
                    "Partnership %s is %s; only SCHEDULED or ACTIVE partnerships can be terminated".formatted(id, status));
        }
        this.terminatedAt = now;
    }

    /**
     * BR-06: creates the successor partnership.
     *
     * <p>Assumption (documented): the renewal starts the day after the current end date - so
     * there is no gap and no overlap - or today if the partnership already expired (access
     * cannot be granted retroactively). The new period must itself respect BR-03.
     */
    public Partnership renew(LocalDate newEndDate, LocalDate today, Instant now) {
        PartnershipStatus status = statusOn(today);
        boolean renewable = status == PartnershipStatus.ACTIVE
                || (status == PartnershipStatus.EXPIRED && daysSinceExpiry(today) <= RENEWAL_GRACE_DAYS);
        if (!renewable) {
            throw new PortalException(ErrorCode.PARTNERSHIP_NOT_RENEWABLE,
                    "Partnership %s is %s; it can be renewed while ACTIVE or up to %d days after it expired"
                            .formatted(id, status, RENEWAL_GRACE_DAYS));
        }
        LocalDate dayAfterEnd = endDate.plusDays(1);
        LocalDate newStartDate = dayAfterEnd.isAfter(today) ? dayAfterEnd : today;
        return new Partnership(marketplaceId, supplierId, retailerId, newStartDate, newEndDate, id, now);
    }

    /** BR-03. Same month arithmetic as the database constraint (Jan 31 + 36 months = Jan 31). */
    static void validatePeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new PortalException(ErrorCode.PARTNERSHIP_INVALID_PERIOD, "Start date and end date are required");
        }
        if (!endDate.isAfter(startDate)) {
            throw new PortalException(ErrorCode.PARTNERSHIP_INVALID_PERIOD,
                    "End date %s must be after start date %s".formatted(endDate, startDate));
        }
        LocalDate latestEndDate = startDate.plusMonths(MAX_DURATION_MONTHS);
        if (endDate.isAfter(latestEndDate)) {
            throw new PortalException(ErrorCode.PARTNERSHIP_INVALID_PERIOD,
                    "A partnership lasts at most %d months: end date must be on or before %s"
                            .formatted(MAX_DURATION_MONTHS, latestEndDate));
        }
    }

    private long daysSinceExpiry(LocalDate today) {
        return ChronoUnit.DAYS.between(endDate, today);
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

    public UUID retailerId() {
        return retailerId;
    }

    public LocalDate startDate() {
        return startDate;
    }

    public LocalDate endDate() {
        return endDate;
    }

    public Instant terminatedAt() {
        return terminatedAt;
    }

    public UUID renewedFromId() {
        return renewedFromId;
    }

    /** True when the given actor is the supplier or the retailer of this partnership. */
    public boolean involves(UUID partyId) {
        return supplierId.equals(partyId) || retailerId.equals(partyId);
    }
}

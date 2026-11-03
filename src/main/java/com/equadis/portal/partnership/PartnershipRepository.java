package com.equadis.portal.partnership;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Partnerships of the current marketplace (Row-Level Security filters the others out).
 */
interface PartnershipRepository extends JpaRepository<Partnership, UUID> {

    /**
     * Loads and locks the partnership ({@code SELECT ... FOR UPDATE}) until the end of the
     * transaction. Used by termination and renewal so that a concurrent subscription
     * (which takes a {@code FOR SHARE} lock on the same row) cannot slip through.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Partnership p where p.id = :id")
    Optional<Partnership> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The renewals of a partnership that are not terminated yet, locked ({@code FOR UPDATE}).
     * Used by termination: ending a partnership must also end the renewal waiting to start (R6).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Partnership p where p.renewedFromId = :id and p.terminatedAt is null")
    List<Partnership> findLiveRenewalsForUpdate(@Param("id") UUID id);

    /**
     * Finds the pair's ACTIVE partnership and locks it ({@code FOR SHARE}) until the end of the
     * transaction. Native SQL: JPQL cannot lock a row while selecting only its id.
     */
    @Query(value = """
            SELECT id FROM partnership
            WHERE supplier_id = :supplierId AND retailer_id = :retailerId
              AND terminated_at IS NULL
              AND :today BETWEEN start_date AND end_date
            FOR SHARE
            """, nativeQuery = true)
    Optional<UUID> findActiveIdForShare(@Param("supplierId") UUID supplierId, @Param("retailerId") UUID retailerId,
                                        @Param("today") LocalDate today);

    /** BR-04 pre-check (friendly error). The exclusion constraint is the real guarantee. */
    @Query("""
            select count(p) > 0 from Partnership p
            where p.supplierId = :supplierId and p.retailerId = :retailerId
              and p.terminatedAt is null
              and p.startDate <= :endDate and p.endDate >= :startDate
            """)
    boolean existsOverlapping(@Param("supplierId") UUID supplierId, @Param("retailerId") UUID retailerId,
                              @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}

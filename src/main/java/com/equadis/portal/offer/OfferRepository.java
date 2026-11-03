package com.equadis.portal.offer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Offers of the current marketplace (Row-Level Security filters the others out). */
interface OfferRepository extends JpaRepository<Offer, UUID> {

    /** Ownership check built into the query: a supplier never reaches another supplier's offer. */
    @Query("select o from Offer o where o.id = :id and o.supplierId = :supplierId")
    Optional<Offer> findOwnedBy(@Param("id") UUID id, @Param("supplierId") UUID supplierId);

    /** Same comparison as the unique index: {@code lower(reference)}, done by the database. */
    @Query("select count(o) > 0 from Offer o where o.supplierId = :supplierId and lower(o.reference) = lower(:reference)")
    boolean existsReference(@Param("supplierId") UUID supplierId, @Param("reference") String reference);

    /** The supplier of an offer that is PUBLISHED and within its validity window on this day. */
    @Query("""
            select o.supplierId from Offer o
            where o.id = :id
              and o.status = com.equadis.portal.offer.OfferStatus.PUBLISHED
              and :day between o.validFrom and o.validTo
            """)
    Optional<UUID> findSupplierOfCurrentOffer(@Param("id") UUID id, @Param("day") LocalDate day);
}

package com.equadis.portal.offer;

import com.equadis.portal.tenant.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Supplier-facing offer API. Bean Validation only checks the request <i>shape</i> (present,
 * right type); business rules (BR-08, BR-09) are checked by the domain, in one place.
 */
@RestController
@RequestMapping("/api/offers")
class OfferController {

    private final OfferService service;

    OfferController(OfferService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OfferView create(Actor actor, @Valid @RequestBody CreateOfferRequest request) {
        return service.createDraft(actor, request.reference(), request.terms());
    }

    @GetMapping("/{offerId}")
    OfferView get(Actor actor, @PathVariable UUID offerId) {
        return service.get(actor, offerId);
    }

    @PutMapping("/{offerId}")
    OfferView update(Actor actor, @PathVariable UUID offerId, @Valid @RequestBody UpdateOfferRequest request) {
        return service.updateDraft(actor, offerId, request.version(), request.terms());
    }

    @PostMapping("/{offerId}/publication")
    OfferView publish(Actor actor, @PathVariable UUID offerId) {
        return service.publish(actor, offerId);
    }

    @PostMapping("/{offerId}/closure")
    OfferView close(Actor actor, @PathVariable UUID offerId) {
        return service.close(actor, offerId);
    }

    record CreateOfferRequest(@NotBlank String reference, String title, String description, DiscountType discountType,
                              BigDecimal discountValue, String currency, LocalDate validFrom, LocalDate validTo) {
        OfferTerms terms() {
            return new OfferTerms(title, description, discountType, discountValue, currency, validFrom, validTo);
        }
    }

    record UpdateOfferRequest(@NotNull Long version, String title, String description, DiscountType discountType,
                              BigDecimal discountValue, String currency, LocalDate validFrom, LocalDate validTo) {
        OfferTerms terms() {
            return new OfferTerms(title, description, discountType, discountValue, currency, validFrom, validTo);
        }
    }
}

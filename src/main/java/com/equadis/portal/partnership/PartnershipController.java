package com.equadis.portal.partnership;

import com.equadis.portal.tenant.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Thin HTTP layer: maps requests to use cases. No business logic here.
 */
@RestController
@RequestMapping("/api/partnerships")
class PartnershipController {

    private final PartnershipService service;

    PartnershipController(PartnershipService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PartnershipView create(Actor actor, @Valid @RequestBody CreatePartnershipRequest request) {
        return service.create(actor, request.supplierId(), request.retailerId(), request.startDate(), request.endDate());
    }

    @GetMapping("/{partnershipId}")
    PartnershipView get(Actor actor, @PathVariable UUID partnershipId) {
        return service.get(actor, partnershipId);
    }

    /** A sub-resource ("the termination of this partnership") instead of a verb in the URL. */
    @PostMapping("/{partnershipId}/termination")
    PartnershipView terminate(Actor actor, @PathVariable UUID partnershipId) {
        return service.terminate(actor, partnershipId);
    }

    @PostMapping("/{partnershipId}/renewal")
    @ResponseStatus(HttpStatus.CREATED)
    PartnershipView renew(Actor actor, @PathVariable UUID partnershipId, @Valid @RequestBody RenewPartnershipRequest request) {
        return service.renew(actor, partnershipId, request.endDate());
    }

    record CreatePartnershipRequest(@NotNull UUID supplierId, @NotNull UUID retailerId,
                                    @NotNull LocalDate startDate, @NotNull LocalDate endDate) {
    }

    record RenewPartnershipRequest(@NotNull LocalDate endDate) {
    }
}

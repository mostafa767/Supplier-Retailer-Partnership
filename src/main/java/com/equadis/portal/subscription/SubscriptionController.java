package com.equadis.portal.subscription;

import com.equadis.portal.tenant.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * HTTP layer of retailer subscriptions. No business logic here.
 */
@RestController
class SubscriptionController {

    private final SubscriptionService subscriptionService;

    SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    /**
     * Subscribing is idempotent: 201 the first time, 200 with the same subscription afterwards
     * (BR-11) - safe for double clicks and network retries.
     */
    @PostMapping("/api/offers/{offerId}/subscriptions")
    ResponseEntity<SubscriptionView> subscribe(Actor actor, @PathVariable UUID offerId) {
        SubscriptionResult result = subscriptionService.subscribe(actor, offerId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.subscription());
    }

    /** The calling retailer's active subscriptions. */
    @GetMapping("/api/subscriptions")
    List<SubscriptionView> listActive(Actor actor) {
        return subscriptionService.listActive(actor);
    }
}

package com.equadis.portal.catalogue;

import com.equadis.portal.tenant.Actor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * HTTP layer of the retailer catalogue (R4). No business logic here.
 */
@RestController
@RequestMapping("/api/catalogue")
class CatalogueController {

    private final CatalogueService catalogueService;

    CatalogueController(CatalogueService catalogueService) {
        this.catalogueService = catalogueService;
    }

    /**
     * GET /api/catalogue - the offers the calling retailer can see today (BR-10).
     *
     * @param supplierId     only this supplier's offers
     * @param publishedSince only offers published after this moment (used by digest links:
     *                       "what is new since my last e-mail")
     * @param searchText     text to find in the title or reference
     * @param size           page size
     * @param cursor         {@code nextCursor} of the previous page
     */
    @GetMapping
    CataloguePage browse(Actor actor,
                         @RequestParam(required = false) UUID supplierId,
                         @RequestParam(required = false) Instant publishedSince,
                         @RequestParam(name = "q", required = false) String searchText,
                         @RequestParam(required = false) Integer size,
                         @RequestParam(required = false) String cursor) {
        return catalogueService.browse(actor, new CatalogueFilter(supplierId, publishedSince, searchText, size, cursor));
    }
}

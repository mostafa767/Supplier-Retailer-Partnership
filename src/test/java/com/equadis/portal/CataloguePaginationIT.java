package com.equadis.portal;

import com.equadis.portal.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R4: the catalogue reads at most one page per partner, then merges the lists and keeps one
 * page ({@code CatalogueRepository}). This test pages through offers of two partners whose
 * publication moments alternate, so every page boundary falls between two suppliers.
 */
class CataloguePaginationIT extends AbstractIntegrationTest {

    private static final Instant BASE = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    private UUID marketplace;
    private UUID retailer;
    private List<String> expectedOrder;

    @BeforeEach
    void twoPartnersWithInterleavedOffers() {
        marketplace = testData.marketplace();
        retailer = testData.retailer(marketplace, "Pharmacie du Lac", "lac@example.com");
        UUID nivea = testData.supplier(marketplace, "Nivea");
        UUID loreal = testData.supplier(marketplace, "L'Oreal");
        testData.activePartnership(marketplace, nivea, retailer);
        testData.activePartnership(marketplace, loreal, retailer);

        // A supplier without a partnership: its offers must never appear, on any page (BR-10).
        UUID stranger = testData.supplier(marketplace, "Stranger");
        testData.publishedOffer(marketplace, stranger, "S-1", BASE.plusSeconds(55));

        // Newest first: L-5, N-5, L-4, N-4, ... N-1, then two offers published at the same
        // moment by both suppliers, ordered by id (the tie-breaker of the cursor).
        expectedOrder = new ArrayList<>();
        for (int i = 5; i >= 1; i--) {
            expectedOrder.add(testData.publishedOffer(marketplace, loreal, "L-" + i, BASE.plusSeconds(i * 10L + 5)).toString());
            expectedOrder.add(testData.publishedOffer(marketplace, nivea, "N-" + i, BASE.plusSeconds(i * 10L)).toString());
        }
        String tieA = testData.publishedOffer(marketplace, nivea, "N-TIE", BASE).toString();
        String tieB = testData.publishedOffer(marketplace, loreal, "L-TIE", BASE).toString();
        expectedOrder.addAll(tieA.compareTo(tieB) > 0 ? List.of(tieA, tieB) : List.of(tieB, tieA));
    }

    @Test
    @DisplayName("Paging 2 by 2 across two partners returns every visible offer once, newest first")
    void pagesAcrossPartners_haveNoDuplicatesAndNoGaps() throws Exception {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MockHttpServletRequestBuilder request = get("/api/catalogue").param("size", "2").with(asRetailer(marketplace, retailer));
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> page = JsonPath.read(body, "$.items[*].offerId");
            assertThat(page).hasSizeLessThanOrEqualTo(2);
            seen.addAll(page);
            cursor = JsonPath.read(body, "$.nextCursor");
            pages++;
            assertThat(pages).as("pagination must end").isLessThanOrEqualTo(expectedOrder.size());
        } while (cursor != null);

        // Same list, same order: no offer skipped, none repeated, the stranger's never shown.
        assertThat(seen).containsExactlyElementsOf(expectedOrder);
        assertThat(pages).isEqualTo(6);
    }
}

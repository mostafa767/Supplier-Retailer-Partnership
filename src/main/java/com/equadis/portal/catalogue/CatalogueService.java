package com.equadis.portal.catalogue;

import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import com.equadis.portal.tenant.Actor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Catalogue use case: checks who is asking and what they ask for, then builds one page.
 *
 * <p>There is deliberately no {@code @Transactional}: a page is one read statement, and this
 * is the hottest path of the platform, so it does not pay for BEGIN and COMMIT.
 */
@Service
class CatalogueService {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;
    static final int MAX_SEARCH_TEXT_LENGTH = 100;

    private final CatalogueRepository catalogueRepository;
    private final Clock clock;

    CatalogueService(CatalogueRepository catalogueRepository, Clock clock) {
        this.catalogueRepository = catalogueRepository;
        this.clock = clock;
    }

    /** @return one page of the offers this retailer can see today, newest first */
    CataloguePage browse(Actor actor, CatalogueFilter filter) {
        UUID retailerId = actor.requireRetailer();
        int pageSize = requireValidPageSize(filter.size());
        requireValidSearchText(filter.searchText());
        CatalogueCursor after = filter.cursor() == null ? null : CatalogueCursor.decode(filter.cursor());

        // One row more than the page: if it comes back, a next page exists.
        List<CatalogueOffer> rows = catalogueRepository.findVisibleOffers(retailerId, LocalDate.now(clock), filter, after, pageSize + 1);

        boolean hasNextPage = rows.size() > pageSize;
        List<CatalogueOffer> pageItems = hasNextPage ? rows.subList(0, pageSize) : rows;
        return new CataloguePage(List.copyOf(pageItems), hasNextPage ? cursorAfter(pageItems) : null);
    }

    private static int requireValidPageSize(Integer requestedSize) {
        int pageSize = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new PortalException(ErrorCode.REQUEST_VALIDATION_FAILED,
                    "size must be between 1 and %d".formatted(MAX_PAGE_SIZE));
        }
        return pageSize;
    }

    private static void requireValidSearchText(String searchText) {
        if (searchText != null && searchText.length() > MAX_SEARCH_TEXT_LENGTH) {
            throw new PortalException(ErrorCode.REQUEST_VALIDATION_FAILED,
                    "q must be at most %d characters".formatted(MAX_SEARCH_TEXT_LENGTH));
        }
    }

    private static String cursorAfter(List<CatalogueOffer> pageItems) {
        CatalogueOffer lastOffer = pageItems.get(pageItems.size() - 1);
        return new CatalogueCursor(lastOffer.publishedAt(), lastOffer.offerId()).encode();
    }
}

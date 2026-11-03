package com.equadis.portal.tenant;

import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Who is calling: which marketplace, in which role, as which supplier/retailer.
 *
 * <p>Controllers receive it as a method parameter (see {@link ActorArgumentResolver}); it is
 * never read from the request body or URL, so a caller cannot act for another account by
 * changing an id in the payload.
 *
 * @param marketplaceId the tenant
 * @param role          what the caller is allowed to do
 * @param actorId       the supplier id (SUPPLIER), the retailer id (RETAILER), or the
 *                      administrator's id (MARKETPLACE_ADMIN)
 */
public record Actor(UUID marketplaceId, Role role, UUID actorId) {

    public enum Role {SUPPLIER, RETAILER, MARKETPLACE_ADMIN}

    public Actor {
        Objects.requireNonNull(marketplaceId, "marketplaceId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(actorId, "actorId");
    }

    /** Fails with 403 unless the caller has one of the given roles. */
    public void requireRole(Role... allowedRoles) {
        if (Arrays.stream(allowedRoles).noneMatch(allowed -> allowed == role)) {
            throw new PortalException(ErrorCode.ROLE_NOT_ALLOWED,
                    "Role %s is not allowed to perform this action (allowed: %s)".formatted(role, Arrays.toString(allowedRoles)));
        }
    }

    /** @return the supplier id of the caller, failing with 403 if the caller is not a supplier. */
    public UUID requireSupplier() {
        requireRole(Role.SUPPLIER);
        return actorId;
    }

    /** @return the retailer id of the caller, failing with 403 if the caller is not a retailer. */
    public UUID requireRetailer() {
        requireRole(Role.RETAILER);
        return actorId;
    }

    public boolean isAdmin() {
        return role == Role.MARKETPLACE_ADMIN;
    }
}

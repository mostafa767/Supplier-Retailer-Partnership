package com.equadis.portal.tenant;

import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;
import java.util.UUID;

/**
 * Middleware #2 (around every {@code /api/**} call): establishes WHO is calling and FOR
 * WHICH MARKETPLACE, before any business code runs.
 *
 * <p><b>Proof-of-concept identity.</b> The identity is read from three headers:
 * {@code X-Marketplace-Id}, {@code X-Actor-Role}, {@code X-Actor-Id}. In production these
 * values come from a verified OAuth2/OIDC access token (Keycloak) and the headers are not
 * accepted - only {@link #resolveActor} changes; everything downstream ({@link Actor},
 * {@link TenantContext}, Row-Level Security) stays identical. This is documented in the
 * design document (R7).
 *
 * <p>Implemented as a Spring MVC interceptor rather than a servlet filter on purpose: an
 * exception thrown here flows through {@code GlobalExceptionHandler}, so a missing identity
 * gets the same Problem Details error as everything else.
 */
@Component
public class ActorContextInterceptor implements HandlerInterceptor {

    public static final String MARKETPLACE_HEADER = "X-Marketplace-Id";
    public static final String ROLE_HEADER = "X-Actor-Role";
    public static final String ACTOR_HEADER = "X-Actor-Id";

    static final String ACTOR_REQUEST_ATTRIBUTE = Actor.class.getName();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Actor actor = resolveActor(request);
        request.setAttribute(ACTOR_REQUEST_ATTRIBUTE, actor);
        TenantContext.set(actor.marketplaceId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // Servlet threads are pooled: never leave a marketplace behind for the next request.
        TenantContext.clear();
    }

    private static Actor resolveActor(HttpServletRequest request) {
        String marketplaceId = request.getHeader(MARKETPLACE_HEADER);
        String role = request.getHeader(ROLE_HEADER);
        String actorId = request.getHeader(ACTOR_HEADER);
        if (marketplaceId == null || role == null || actorId == null) {
            throw new PortalException(ErrorCode.MISSING_IDENTITY,
                    "Headers %s, %s and %s are required".formatted(MARKETPLACE_HEADER, ROLE_HEADER, ACTOR_HEADER));
        }
        return new Actor(parseUuid(MARKETPLACE_HEADER, marketplaceId), parseRole(role), parseUuid(ACTOR_HEADER, actorId));
    }

    private static UUID parseUuid(String header, String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException notAUuid) {
            throw new PortalException(ErrorCode.INVALID_IDENTITY, "Header %s must be a UUID".formatted(header));
        }
    }

    private static Actor.Role parseRole(String value) {
        try {
            return Actor.Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownRole) {
            throw new PortalException(ErrorCode.INVALID_IDENTITY,
                    "Header %s must be one of SUPPLIER, RETAILER, MARKETPLACE_ADMIN".formatted(ROLE_HEADER));
        }
    }
}

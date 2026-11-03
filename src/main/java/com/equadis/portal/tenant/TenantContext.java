package com.equadis.portal.tenant;

import org.slf4j.MDC;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Which marketplace the current thread is working for.
 *
 * <p>Set by the HTTP middleware ({@link ActorContextInterceptor}) for API calls, and by the
 * background workers for each piece of work they process. Read by
 * {@link TenantAwareDataSource}, which passes it to PostgreSQL so that Row-Level Security
 * filters every query.
 *
 * <p>Always use {@link #callAs}/{@link #runAs}: they restore the previous value in a
 * {@code finally} block, so a thread from a pool can never keep the marketplace of a previous
 * task.
 */
public final class TenantContext {

    public static final String MARKETPLACE_MDC_KEY = "marketplaceId";

    private static final ThreadLocal<UUID> CURRENT_MARKETPLACE = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<UUID> currentMarketplaceId() {
        return Optional.ofNullable(CURRENT_MARKETPLACE.get());
    }

    /** Runs {@code work} on behalf of {@code marketplaceId}, then restores the previous context. */
    public static <T> T callAs(UUID marketplaceId, Supplier<T> work) {
        Objects.requireNonNull(marketplaceId, "marketplaceId");
        UUID previous = CURRENT_MARKETPLACE.get();
        set(marketplaceId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                clear();
            } else {
                set(previous);
            }
        }
    }

    public static void runAs(UUID marketplaceId, Runnable work) {
        callAs(marketplaceId, () -> {
            work.run();
            return null;
        });
    }

    /** For the HTTP middleware only, which clears it in {@code afterCompletion}. */
    static void set(UUID marketplaceId) {
        CURRENT_MARKETPLACE.set(marketplaceId);
        MDC.put(MARKETPLACE_MDC_KEY, marketplaceId.toString());
    }

    static void clear() {
        CURRENT_MARKETPLACE.remove();
        MDC.remove(MARKETPLACE_MDC_KEY);
    }
}

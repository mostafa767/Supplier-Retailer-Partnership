package com.equadis.portal.support;

import com.equadis.portal.importing.ImportWorker;
import com.equadis.portal.notification.NotificationSender;
import com.equadis.portal.outbox.OutboxDispatcher;
import com.equadis.portal.tenant.ActorContextInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

/**
 * Base class of the integration tests.
 *
 * <p>Runs the whole application against a <b>real PostgreSQL</b> started by Testcontainers -
 * not an in-memory database: Row-Level Security, the exclusion constraint, partial unique
 * indexes and {@code ON CONFLICT} only exist in PostgreSQL, and they ARE the behaviour under
 * test. One container is shared by all test classes (started once, removed by Testcontainers
 * when the JVM exits).
 *
 * <p>Two database identities, exactly like production:
 * <ul>
 *     <li>the application connects as {@code portal_app} (subject to Row-Level Security);</li>
 *     <li>{@link #owner} uses the container's admin account - for fixtures and for asserting
 *         what is really stored, bypassing Row-Level Security.</li>
 * </ul>
 * Each test creates its own marketplaces with random ids, so tests never see each other's
 * data - which is itself a demonstration of tenant isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final TestDatabase DATABASE = TestDatabase.start();

    protected static final JdbcTemplate owner = new JdbcTemplate(
            new DriverManagerDataSource(DATABASE.jdbcUrl(), DATABASE.ownerUser(), DATABASE.ownerPassword()));

    protected static final TestData testData = new TestData(owner);

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ImportWorker importWorker;

    @Autowired
    protected OutboxDispatcher outboxDispatcher;

    @Autowired
    protected NotificationSender notificationSender;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String separator = DATABASE.jdbcUrl().contains("?") ? "&" : "?";
        registry.add("spring.datasource.url", () -> DATABASE.jdbcUrl() + separator + "reWriteBatchedInserts=true");
        registry.add("spring.flyway.url", DATABASE::jdbcUrl);
        registry.add("spring.flyway.user", DATABASE::ownerUser);
        registry.add("spring.flyway.password", DATABASE::ownerPassword);
    }

    /**
     * The PostgreSQL instance used by the tests: a Testcontainers container by default, or an
     * existing server when {@code PORTAL_TEST_DB_URL} is set (machines without Docker, CI
     * services). It must be an EMPTY database; the user must be able to create roles.
     */
    private record TestDatabase(String jdbcUrl, String ownerUser, String ownerPassword) {

        static TestDatabase start() {
            String externalUrl = System.getenv("PORTAL_TEST_DB_URL");
            if (externalUrl != null && !externalUrl.isBlank()) {
                return new TestDatabase(externalUrl,
                        System.getenv().getOrDefault("PORTAL_TEST_DB_USER", "postgres"),
                        System.getenv().getOrDefault("PORTAL_TEST_DB_PASSWORD", ""));
            }
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start(); // stopped by Testcontainers when the JVM exits
            return new TestDatabase(container.getJdbcUrl(), container.getUsername(), container.getPassword());
        }
    }

    // --- Identity of the caller (the PoC stand-in for an OAuth2 token) ----------------------

    protected static RequestPostProcessor asSupplier(UUID marketplaceId, UUID supplierId) {
        return as(marketplaceId, "SUPPLIER", supplierId);
    }

    protected static RequestPostProcessor asRetailer(UUID marketplaceId, UUID retailerId) {
        return as(marketplaceId, "RETAILER", retailerId);
    }

    protected static RequestPostProcessor asAdmin(UUID marketplaceId) {
        return as(marketplaceId, "MARKETPLACE_ADMIN", UUID.randomUUID());
    }

    private static RequestPostProcessor as(UUID marketplaceId, String role, UUID actorId) {
        return request -> {
            request.addHeader(ActorContextInterceptor.MARKETPLACE_HEADER, marketplaceId.toString());
            request.addHeader(ActorContextInterceptor.ROLE_HEADER, role);
            request.addHeader(ActorContextInterceptor.ACTOR_HEADER, actorId.toString());
            return request;
        };
    }

    // --- Background work, run on demand ---------------------------------------------------

    /** Processes every pending import. */
    protected void runImports() {
        while (importWorker.processNext()) {
            // until the queue is empty
        }
    }

    /** Turns outbox events into notifications, then sends every due notification. */
    protected void runNotificationPipeline() {
        while (outboxDispatcher.dispatchBatch() > 0) {
            // until the outbox is drained
        }
        notificationSender.sendDueBatch();
    }
}

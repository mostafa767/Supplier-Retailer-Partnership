package com.equadis.portal.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Wraps the connection pool and tells PostgreSQL, on every connection checkout, which
 * marketplace the connection now works for. Row-Level Security policies (migration V3) read
 * that value and hide every row of other marketplaces.
 *
 * <p>Why on <b>every</b> checkout: connections are reused across requests. Writing the value
 * each time (the current marketplace, or an empty string when there is none) means a pooled
 * connection can never carry the marketplace of the previous request. With no marketplace,
 * PostgreSQL sees an empty value and returns no rows at all: the system <b>fails closed</b>.
 *
 * <p>{@code set_config(name, value, false)} is used instead of {@code SET}: {@code SET} cannot
 * take a bind parameter, which would tempt into string concatenation (SQL injection).
 *
 * <p>Cost: one extra tiny statement per checkout (a transaction usually checks out once).
 * Accepted for the guarantee it buys; it is visible in the design document.
 */
public class TenantAwareDataSource extends DelegatingDataSource implements AutoCloseable {

    static final String SET_MARKETPLACE_SQL = "SELECT set_config('app.marketplace_id', ?, false)";

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bindToCurrentMarketplace(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bindToCurrentMarketplace(super.getConnection(username, password));
    }

    private static Connection bindToCurrentMarketplace(Connection connection) throws SQLException {
        String marketplaceId = TenantContext.currentMarketplaceId().map(UUID::toString).orElse("");
        try (PreparedStatement statement = connection.prepareStatement(SET_MARKETPLACE_SQL)) {
            statement.setString(1, marketplaceId);
            statement.execute();
            return connection;
        } catch (SQLException | RuntimeException failure) {
            connection.close(); // give it back to the pool rather than leaking it
            throw failure;
        }
    }

    /** Lets Spring close the underlying pool on shutdown. */
    @Override
    public void close() throws Exception {
        if (getTargetDataSource() instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}

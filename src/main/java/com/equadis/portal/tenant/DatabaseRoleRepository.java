package com.equadis.portal.tenant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads, from PostgreSQL's catalog, what the application's own database role is allowed to do.
 * Used once at start-up by {@link DatabaseRoleGuard}.
 */
@Repository
class DatabaseRoleRepository {

    /**
     * {@code pg_has_role(..., 'USAGE')} is true for the owner itself, for a member that inherits
     * the owner's rights, and for a superuser: exactly the cases where Row-Level Security is skipped.
     */
    private static final String CURRENT_ROLE = """
            SELECT r.rolname AS name, r.rolsuper AS superuser, r.rolbypassrls AS bypass_rls,
                   (SELECT count(*) FROM pg_class c
                     WHERE c.relkind IN ('r', 'p') AND c.relrowsecurity) AS protected_tables,
                   (SELECT count(*) FROM pg_class c
                     WHERE c.relkind IN ('r', 'p') AND c.relrowsecurity
                       AND pg_has_role(current_user, c.relowner, 'USAGE')) AS owned_protected_tables
            FROM pg_roles r
            WHERE r.rolname = current_user
            """;

    private final JdbcClient jdbc;

    DatabaseRoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    DatabaseRole currentRole() {
        return jdbc.sql(CURRENT_ROLE)
                .query((row, rowNumber) -> new DatabaseRole(
                        row.getString("name"),
                        row.getBoolean("superuser"),
                        row.getBoolean("bypass_rls"),
                        row.getLong("protected_tables"),
                        row.getLong("owned_protected_tables")))
                .single();
    }

    /**
     * @param protectedTables      tables with Row-Level Security enabled
     * @param ownedProtectedTables those of them this role owns (directly or through membership)
     */
    record DatabaseRole(String name, boolean superuser, boolean bypassRls, long protectedTables, long ownedProtectedTables) {
    }
}

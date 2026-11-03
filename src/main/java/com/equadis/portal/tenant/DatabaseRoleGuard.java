package com.equadis.portal.tenant;

import com.equadis.portal.tenant.DatabaseRoleRepository.DatabaseRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start the application if its database role would bypass Row-Level Security (R1).
 *
 * <p>PostgreSQL silently skips RLS for the owner of a table, for a superuser and for a role
 * with {@code BYPASSRLS}. If the application were ever configured with such a user (for
 * example the migration user by mistake), every marketplace would see every other one -
 * without a single error. This check turns that silent leak into a start-up failure.
 *
 * <p>It also fails if no table has RLS enabled (migrations missing or changed).
 *
 * <p>It runs once all beans exist (so Flyway has migrated) and before the web server and
 * the background workers start: nothing is served with a dangerous role.
 */
@Component
class DatabaseRoleGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRoleGuard.class);

    private final DatabaseRoleRepository databaseRoleRepository;

    DatabaseRoleGuard(DatabaseRoleRepository databaseRoleRepository) {
        this.databaseRoleRepository = databaseRoleRepository;
    }

    @Override
    public void afterSingletonsInstantiated() {
        DatabaseRole role = databaseRoleRepository.currentRole();
        List<String> problems = problemsOf(role);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(("Refusing to start: database user '%s' would bypass Row-Level Security, "
                    + "so marketplaces would see each other's data. %s. Connect as the non-owner role portal_app.")
                    .formatted(role.name(), String.join("; ", problems)));
        }
        log.info("Database role check passed: '{}' is subject to Row-Level Security on {} table(s)",
                role.name(), role.protectedTables());
    }

    static List<String> problemsOf(DatabaseRole role) {
        List<String> problems = new ArrayList<>();
        if (role.superuser()) {
            problems.add("it is a superuser");
        }
        if (role.bypassRls()) {
            problems.add("it has the BYPASSRLS attribute");
        }
        if (role.ownedProtectedTables() > 0) {
            problems.add("it owns %d table(s) protected by Row-Level Security".formatted(role.ownedProtectedTables()));
        }
        if (role.protectedTables() == 0) {
            problems.add("no table has Row-Level Security enabled");
        }
        return problems;
    }
}

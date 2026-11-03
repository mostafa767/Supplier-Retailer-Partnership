package com.equadis.portal.tenant;

import com.equadis.portal.tenant.DatabaseRoleRepository.DatabaseRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Each way a database role can bypass Row-Level Security is detected. */
class DatabaseRoleGuardTest {

    @Test
    @DisplayName("A normal non-owner role has no problem")
    void nonOwnerRole_isAccepted() {
        assertThat(DatabaseRoleGuard.problemsOf(new DatabaseRole("portal_app", false, false, 12, 0))).isEmpty();
    }

    @Test
    @DisplayName("Owner, superuser, BYPASSRLS and 'no RLS at all' are each reported")
    void everyBypass_isReported() {
        assertThat(DatabaseRoleGuard.problemsOf(new DatabaseRole("portal_owner", false, false, 12, 12)))
                .containsExactly("it owns 12 table(s) protected by Row-Level Security");
        assertThat(DatabaseRoleGuard.problemsOf(new DatabaseRole("postgres", true, true, 12, 12)))
                .hasSize(3);
        assertThat(DatabaseRoleGuard.problemsOf(new DatabaseRole("portal_app", false, false, 0, 0)))
                .containsExactly("no table has Row-Level Security enabled");
    }
}

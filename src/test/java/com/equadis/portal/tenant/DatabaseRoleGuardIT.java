package com.equadis.portal.tenant;

import com.equadis.portal.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R1: the application refuses to run with a database user that would bypass Row-Level Security. */
class DatabaseRoleGuardIT extends AbstractIntegrationTest {

    @Autowired
    private DatabaseRoleGuard guard;

    @Test
    @DisplayName("R1: the application's own role (portal_app) passes the start-up check")
    void applicationRole_passes() {
        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R1: connected as the table owner, the application refuses to start")
    void ownerRole_isRefused() {
        DatabaseRoleGuard guardAsOwner = new DatabaseRoleGuard(new DatabaseRoleRepository(JdbcClient.create(owner)));

        assertThatThrownBy(guardAsOwner::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("Row-Level Security");
    }
}

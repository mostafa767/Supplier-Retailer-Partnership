package com.equadis.portal.shared.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Conversions for exact moments in plain JDBC code.
 *
 * <p>The PostgreSQL driver maps {@code TIMESTAMPTZ} to {@link OffsetDateTime}, not to
 * {@link Instant}. These helpers keep the rest of the code in {@code Instant} (our type for
 * moments) and always talk UTC to the database.
 */
public final class JdbcTime {

    private JdbcTime() {
    }

    public static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

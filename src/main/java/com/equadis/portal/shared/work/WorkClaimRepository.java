package com.equadis.portal.shared.work;

import com.equadis.portal.shared.jdbc.JdbcTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Calls the {@code claim_*} database functions (migration V4). These are the only queries
 * that look across marketplaces, and they return ids only.
 */
@Repository
public class WorkClaimRepository {

    private final JdbcClient jdbc;

    public WorkClaimRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ClaimedWork> claimImportJob(Instant now, Duration lease) {
        return jdbc.sql("SELECT * FROM claim_import_job(:now, make_interval(secs => :leaseSeconds))")
                .param("now", JdbcTime.toDb(now))
                .param("leaseSeconds", lease.toSeconds())
                .query(WorkClaimRepository::toClaimedWork)
                .list();
    }

    public List<ClaimedWork> claimOutboxEvents(Instant now, Duration lease, int limit) {
        return jdbc.sql("SELECT * FROM claim_outbox_events(:now, make_interval(secs => :leaseSeconds), :limit)")
                .param("now", JdbcTime.toDb(now))
                .param("leaseSeconds", lease.toSeconds())
                .param("limit", limit)
                .query(WorkClaimRepository::toClaimedWork)
                .list();
    }

    public List<ClaimedWork> claimRetailerNotifications(Instant now, Duration lease, int limit) {
        return jdbc.sql("SELECT * FROM claim_retailer_notifications(:now, make_interval(secs => :leaseSeconds), :limit)")
                .param("now", JdbcTime.toDb(now))
                .param("leaseSeconds", lease.toSeconds())
                .param("limit", limit)
                .query(WorkClaimRepository::toClaimedWork)
                .list();
    }

    /** All claim functions return (id, marketplace_id, attempts), in that order. */
    private static ClaimedWork toClaimedWork(ResultSet row, int rowNumber) throws SQLException {
        return new ClaimedWork(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getInt(3));
    }
}

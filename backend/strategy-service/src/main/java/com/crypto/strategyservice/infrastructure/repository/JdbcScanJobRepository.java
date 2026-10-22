package com.crypto.strategyservice.infrastructure.repository;

import com.crypto.strategyservice.application.repository.ScanJobRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcScanJobRepository implements ScanJobRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcScanJobRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void createScan(
            UUID id,
            UUID ownerId,
            UUID sessionId,
            int horizonSeconds,
            String universeVersion,
            String initialCursor,
            long totalKnown,
            Instant now
    ) {
        jdbc.update("""
                INSERT INTO scan_jobs (
                    id,
                    owner_id,
                    session_id,
                    horizon,
                    universe_version,
                    cursor,
                    status,
                    total_known,
                    evaluated_count,
                    skipped_count,
                    reason_counts,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?, 0, 0, '{}'::jsonb, ?, ?)
                """,
                id,
                ownerId,
                sessionId,
                horizonSeconds,
                universeVersion,
                initialCursor,
                totalKnown,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    @Override
    public boolean claimScan(
            UUID scanId,
            UUID workerToken,
            Instant now,
            Instant leaseUntil
    ) {
        int changed = jdbc.update("""
                UPDATE scan_jobs
                SET status = 'RUNNING',
                    worker_token = ?,
                    lease_until = ?,
                    updated_at = ?
                WHERE id = ?
                  AND (
                      status IN ('QUEUED', 'PARTIAL')
                      OR (status = 'RUNNING' AND lease_until <= ?)
                  )
                """,
                workerToken,
                Timestamp.from(leaseUntil),
                Timestamp.from(now),
                scanId,
                Timestamp.from(now)
        );
        return changed == 1;
    }

    @Override
    public boolean isCancelled(UUID scanId) {
        String status = jdbc.queryForObject(
                "SELECT status FROM scan_jobs WHERE id = ?",
                String.class,
                scanId
        );
        return "CANCELLED".equals(status);
    }

    @Override
    public boolean recordCandidateStep(
            UUID scanId,
            UUID workerToken,
            CandidateOutcome outcome,
            Instant asOf,
            Instant now
    ) {
        record ScanHeader(UUID ownerId, UUID sessionId, int horizon, String reasonCountsJson) {
        }

        var headerOpt = jdbc.query("""
                SELECT owner_id, session_id, horizon, reason_counts::text AS reason_counts_json
                FROM scan_jobs
                WHERE id = ?
                  AND worker_token = ?
                  AND status = 'RUNNING'
                  AND lease_until > ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new ScanHeader(
                        rs.getObject("owner_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getInt("horizon"),
                        rs.getString("reason_counts_json")
                ),
                scanId,
                workerToken,
                Timestamp.from(now)
        ).stream().findFirst();

        if (headerOpt.isEmpty()) {
            return false;
        }

        ScanHeader header = headerOpt.get();
        Map<String, Long> reasonCounts = new LinkedHashMap<>(
                json.readValue(header.reasonCountsJson(), new TypeReference<Map<String, Long>>() {
                })
        );

        if (outcome.eligible()) {
            jdbc.update("""
                    INSERT INTO analysis_jobs (
                        id,
                        owner_id,
                        session_id,
                        instrument_id,
                        horizon_seconds,
                        as_of,
                        status,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
                    """,
                    outcome.analysisId(),
                    header.ownerId(),
                    header.sessionId(),
                    outcome.instrumentId(),
                    header.horizon(),
                    Timestamp.from(asOf),
                    Timestamp.from(now),
                    Timestamp.from(now)
            );
        } else {
            reasonCounts.merge(outcome.skippedReason(), 1L, Long::sum);
        }

        jdbc.update("""
                INSERT INTO scan_candidates (
                    scan_id,
                    position_index,
                    instrument_id,
                    eligible,
                    skipped_reason,
                    analysis_id,
                    evaluated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                scanId,
                outcome.positionIndex(),
                outcome.instrumentId(),
                outcome.eligible(),
                outcome.skippedReason(),
                outcome.analysisId(),
                Timestamp.from(now)
        );

        int changed = jdbc.update("""
                UPDATE scan_jobs
                SET evaluated_count = evaluated_count + ?,
                    skipped_count = skipped_count + ?,
                    reason_counts = CAST(? AS jsonb),
                    updated_at = ?
                WHERE id = ?
                  AND worker_token = ?
                  AND status = 'RUNNING'
                  AND lease_until > ?
                """,
                outcome.eligible() ? 1 : 0,
                outcome.eligible() ? 0 : 1,
                json.writeValueAsString(reasonCounts),
                Timestamp.from(now),
                scanId,
                workerToken,
                Timestamp.from(now)
        );

        return changed == 1;
    }

    @Override
    public boolean finishScanStep(
            UUID scanId,
            UUID workerToken,
            String nextStatus,
            String nextCursor,
            Instant now
    ) {
        int changed = jdbc.update("""
                UPDATE scan_jobs
                SET status = ?,
                    cursor = ?,
                    worker_token = NULL,
                    lease_until = NULL,
                    updated_at = ?
                WHERE id = ?
                  AND worker_token = ?
                  AND status = 'RUNNING'
                  AND lease_until > ?
                """,
                nextStatus,
                nextCursor,
                Timestamp.from(now),
                scanId,
                workerToken,
                Timestamp.from(now)
        );
        return changed == 1;
    }

    @Override
    public boolean cancel(
            UUID ownerId,
            UUID scanId,
            Instant now
    ) {
        int changed = jdbc.update("""
                UPDATE scan_jobs
                SET status = 'CANCELLED',
                    worker_token = NULL,
                    lease_until = NULL,
                    updated_at = ?
                WHERE id = ?
                  AND owner_id = ?
                  AND status IN ('QUEUED', 'RUNNING', 'PARTIAL')
                """,
                Timestamp.from(now),
                scanId,
                ownerId
        );
        return changed == 1;
    }

    @Override
    public Optional<ScanJobView> findScanView(
            UUID ownerId,
            UUID scanId
    ) {
        List<CandidateOutcome> candidates = jdbc.query("""
                SELECT position_index,
                       instrument_id,
                       eligible,
                       skipped_reason,
                       analysis_id
                FROM scan_candidates
                WHERE scan_id = ?
                ORDER BY position_index ASC
                """,
                (rs, rowNum) -> new CandidateOutcome(
                        rs.getInt("position_index"),
                        rs.getObject("instrument_id", UUID.class),
                        rs.getBoolean("eligible"),
                        rs.getString("skipped_reason"),
                        rs.getObject("analysis_id", UUID.class)
                ),
                scanId
        );

        return jdbc.query("""
                SELECT id,
                       owner_id,
                       session_id,
                       horizon,
                       universe_version,
                       cursor,
                       status,
                       total_known,
                       evaluated_count,
                       skipped_count,
                       reason_counts::text AS reason_counts_json,
                       created_at,
                       updated_at
                FROM scan_jobs
                WHERE id = ? AND owner_id = ?
                """,
                (rs, rowNum) -> new ScanJobView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("owner_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getInt("horizon"),
                        rs.getString("universe_version"),
                        rs.getString("cursor"),
                        rs.getString("status"),
                        rs.getLong("total_known"),
                        rs.getLong("evaluated_count"),
                        rs.getLong("skipped_count"),
                        json.readValue(
                                rs.getString("reason_counts_json"),
                                new TypeReference<Map<String, Long>>() {
                                }
                        ),
                        candidates,
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()
                ),
                scanId,
                ownerId
        ).stream().findFirst();
    }
}


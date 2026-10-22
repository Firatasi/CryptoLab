package com.crypto.strategyservice.infrastructure.repository;

import com.crypto.strategyservice.application.repository.AnalysisJobRepository;
import com.crypto.strategyservice.domain.model.PredictionResult;
import com.crypto.strategyservice.domain.service.NumericPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcAnalysisJobRepository implements AnalysisJobRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcAnalysisJobRepository(
            JdbcTemplate jdbc,
            JsonMapper json
    ) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void createQueued(
            UUID id,
            UUID ownerId,
            UUID sessionId,
            UUID instrumentId,
            int horizonSeconds,
            Instant asOf,
            Instant now
    ) {
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
                id,
                ownerId,
                sessionId,
                instrumentId,
                horizonSeconds,
                Timestamp.from(asOf),
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    @Override
    public UUID enqueueIdempotent(
            UUID ownerId,
            UUID sessionId,
            UUID instrumentId,
            int horizonSeconds,
            Instant asOf,
            String idempotencyKey,
            Instant now
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("INVALID_IDEMPOTENCY_KEY");
        }
        String route = "POST /api/v1/strategy/analyses";
        String scope = sessionId.toString();
        String requestHash = sha256(sessionId + ":" + instrumentId + ":" + horizonSeconds + ":" + asOf);

        var existing = jdbc.query("""
                SELECT request_hash, result_ref
                FROM idempotency_records
                WHERE subject = ? AND route = ? AND scope = ? AND key = ?
                """,
                (rs, rowNum) -> Map.entry(rs.getString("request_hash"), rs.getObject("result_ref", UUID.class)),
                ownerId,
                route,
                scope,
                idempotencyKey
        ).stream().findFirst();

        if (existing.isPresent()) {
            if (!existing.get().getKey().equals(requestHash)) {
                throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
            }
            return existing.get().getValue();
        }

        UUID jobId = UUID.randomUUID();
        createQueued(jobId, ownerId, sessionId, instrumentId, horizonSeconds, asOf, now);

        String payload = json.writeValueAsString(Map.of(
                "analysisId", jobId.toString(),
                "ownerId", ownerId.toString(),
                "sessionId", sessionId.toString(),
                "instrumentId", instrumentId.toString(),
                "horizonSeconds", horizonSeconds,
                "asOf", asOf.toString()
        ));

        jdbc.update("""
                INSERT INTO outbox (event_id, topic, event_key, payload, created_at)
                VALUES (?, 'strategy.analysis.queued', ?, CAST(? AS jsonb), ?)
                """,
                UUID.randomUUID(),
                jobId.toString(),
                payload,
                Timestamp.from(now)
        );

        jdbc.update("""
                INSERT INTO idempotency_records (subject, route, scope, key, request_hash, result_ref)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                ownerId,
                route,
                scope,
                idempotencyKey,
                requestHash,
                jobId
        );

        return jobId;
    }

    @Override
    public Optional<ClaimedJob> claimNext(
            UUID workerToken,
            Instant now,
            Instant leaseUntil
    ) {
        Objects.requireNonNull(workerToken, "workerToken");

        if (!leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("INVALID_JOB_LEASE");
        }

        return jdbc.query("""
                WITH candidate AS (
                    SELECT id
                    FROM analysis_jobs
                    WHERE status = 'QUEUED'
                       OR (
                           status = 'RUNNING'
                           AND lease_until <= ?
                       )
                    ORDER BY created_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE analysis_jobs AS job
                SET status = 'RUNNING',
                    worker_token = ?,
                    lease_until = ?,
                    updated_at = ?
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING
                    job.id,
                    job.owner_id,
                    job.session_id,
                    job.instrument_id,
                    job.horizon_seconds,
                    job.as_of,
                    job.worker_token
                """,
                (rs, rowNum) -> new ClaimedJob(
                        rs.getObject("id", UUID.class),
                        rs.getObject("owner_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("instrument_id", UUID.class),
                        rs.getInt("horizon_seconds"),
                        rs.getTimestamp("as_of").toInstant(),
                        rs.getObject("worker_token", UUID.class)
                ),
                Timestamp.from(now),
                workerToken,
                Timestamp.from(leaseUntil),
                Timestamp.from(now)
        ).stream().findFirst();
    }

    @Override
    public boolean fail(
            UUID jobId,
            UUID workerToken,
            String error,
            Instant now
    ) {
        int changed = jdbc.update("""
                UPDATE analysis_jobs
                SET status = 'FAILED',
                    action = NULL,
                    error = ?,
                    worker_token = NULL,
                    lease_until = NULL,
                    updated_at = ?
                WHERE id = ?
                  AND worker_token = ?
                  AND status = 'RUNNING'
                  AND lease_until > ?
                """,
                error,
                Timestamp.from(now),
                jobId,
                workerToken,
                Timestamp.from(now)
        );

        return changed == 1;
    }

    @Override
    public boolean pinContext(
            UUID jobId,
            UUID workerToken,
            PinnedContext context,
            Instant now
    ) {
        int changed = jdbc.update("""
            UPDATE analysis_jobs
            SET session_generation = ?,
                market_dataset_id = ?,
                market_snapshot_id = ?,
                model_bundle_id = ?,
                feature_version = ?,
                feature_schema_hash = ?,
                bundle_release_status = ?,
                updated_at = ?
            WHERE id = ?
              AND worker_token = ?
              AND status = 'RUNNING'
              AND lease_until > ?
              AND model_bundle_id IS NULL
            """,
                context.sessionGeneration(),
                context.marketDatasetId(),
                context.marketSnapshotId(),
                context.modelBundleId(),
                context.featureVersion(),
                context.featureSchemaHash(),
                context.bundleReleaseStatus(),
                Timestamp.from(now),
                jobId,
                workerToken,
                Timestamp.from(now)
        );

        return changed == 1;
    }

    @Override
    public Optional<PinnedContext> findPinnedContext(
            UUID jobId,
            UUID workerToken,
            Instant now
    ) {
        return jdbc.query("""
            SELECT session_generation,
                   market_dataset_id,
                   market_snapshot_id,
                   model_bundle_id,
                   feature_version,
                   feature_schema_hash,
                   bundle_release_status
            FROM analysis_jobs
            WHERE id = ?
              AND worker_token = ?
              AND status = 'RUNNING'
              AND lease_until > ?
              AND model_bundle_id IS NOT NULL
            """,
                (rs, rowNum) -> new PinnedContext(
                        rs.getLong("session_generation"),
                        rs.getObject("market_dataset_id", UUID.class),
                        rs.getObject("market_snapshot_id", UUID.class),
                        rs.getObject("model_bundle_id", UUID.class),
                        rs.getString("feature_version"),
                        rs.getString("feature_schema_hash"),
                        rs.getString("bundle_release_status")
                ),
                jobId,
                workerToken,
                Timestamp.from(now)
        ).stream().findFirst();
    }

    @Override
    public boolean completeWait(
            UUID jobId,
            UUID workerToken,
            PredictionResult prediction,
            Instant now
    ) {
        if (prediction.quality() == null
                || prediction.quality() == PredictionResult.Quality.READY
                || !jobId.equals(prediction.requestId())) {
            throw new IllegalArgumentException("INVALID_WAIT_RESULT");
        }

        int changed = jdbc.update("""
            UPDATE analysis_jobs
            SET status = 'SUCCEEDED',
                action = 'WAIT',
                error = NULL,
                reason_codes = CAST(? AS jsonb),
                feature_snapshot_id = ?,
                worker_token = NULL,
                lease_until = NULL,
                updated_at = ?
            WHERE id = ?
              AND worker_token = ?
              AND status = 'RUNNING'
              AND lease_until > ?
              AND model_bundle_id = ?
              AND feature_version = ?
              AND feature_schema_hash = ?
              AND horizon_seconds = ?
              AND as_of = ?
            """,
                json.writeValueAsString(prediction.reasonCodes()),
                prediction.featureSnapshotId(),
                Timestamp.from(now),
                jobId,
                workerToken,
                Timestamp.from(now),
                prediction.modelBundleId(),
                prediction.featureVersion(),
                prediction.featureSchemaHash(),
                prediction.horizonSeconds(),
                Timestamp.from(prediction.asOf())
        );

        return changed == 1;
    }

    @Override
    public boolean completePolicyWait(
            UUID jobId,
            UUID workerToken,
            PredictionResult prediction,
            String policyReason,
            Instant now
    ) {
        if (prediction.quality() != PredictionResult.Quality.READY
                || !jobId.equals(prediction.requestId())
                || policyReason == null || policyReason.isBlank()) {
            throw new IllegalArgumentException("INVALID_POLICY_WAIT_RESULT");
        }

        List<String> combinedReasons = new ArrayList<>(prediction.reasonCodes());
        if (!combinedReasons.contains(policyReason)) {
            combinedReasons.add(policyReason);
        }

        int changed = jdbc.update("""
            UPDATE analysis_jobs
            SET status = 'SUCCEEDED',
                action = 'WAIT',
                error = NULL,
                reason_codes = CAST(? AS jsonb),
                feature_snapshot_id = ?,
                explanation_status = 'NOT_APPLICABLE',
                worker_token = NULL,
                lease_until = NULL,
                updated_at = ?
            WHERE id = ?
              AND worker_token = ?
              AND status = 'RUNNING'
              AND lease_until > ?
              AND model_bundle_id = ?
              AND feature_version = ?
              AND feature_schema_hash = ?
              AND horizon_seconds = ?
              AND as_of = ?
            """,
                json.writeValueAsString(combinedReasons),
                prediction.featureSnapshotId(),
                Timestamp.from(now),
                jobId,
                workerToken,
                Timestamp.from(now),
                prediction.modelBundleId(),
                prediction.featureVersion(),
                prediction.featureSchemaHash(),
                prediction.horizonSeconds(),
                Timestamp.from(prediction.asOf())
        );

        return changed == 1;
    }

    @Override
    public BackfillTicket registerSingleFlightBackfill(
            UUID instrumentId,
            int horizonSeconds,
            Instant asOf,
            Instant now
    ) {
        UUID candidateId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO backfill_requests (instrument_id, horizon_seconds, as_of, backfill_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (instrument_id, horizon_seconds, as_of) DO NOTHING
                """,
                instrumentId,
                horizonSeconds,
                Timestamp.from(asOf),
                candidateId,
                Timestamp.from(now)
        );

        if (inserted == 1) {
            return new BackfillTicket(candidateId, true);
        }

        UUID existingId = jdbc.queryForObject("""
                SELECT backfill_id
                FROM backfill_requests
                WHERE instrument_id = ? AND horizon_seconds = ? AND as_of = ?
                """,
                UUID.class,
                instrumentId,
                horizonSeconds,
                Timestamp.from(asOf)
        );
        return new BackfillTicket(existingId, false);
    }

    @Override
    public boolean markWaitingData(
            UUID jobId,
            UUID workerToken,
            UUID backfillId,
            List<String> reasonCodes,
            Instant now
    ) {
        Objects.requireNonNull(backfillId, "backfillId");
        int changed = jdbc.update("""
                UPDATE analysis_jobs
                SET status = 'WAITING_DATA',
                    action = NULL,
                    error = NULL,
                    reason_codes = CAST(? AS jsonb),
                    worker_token = NULL,
                    lease_until = NULL,
                    updated_at = ?
                WHERE id = ?
                  AND worker_token = ?
                  AND status = 'RUNNING'
                  AND lease_until > ?
                """,
                json.writeValueAsString(reasonCodes),
                Timestamp.from(now),
                jobId,
                workerToken,
                Timestamp.from(now)
        );
        return changed == 1;
    }

    @Override
    public boolean completeProposal(
            UUID jobId,
            UUID workerToken,
            PredictionResult prediction,
            ProposalDraft draft,
            Instant now
    ) {
        if (prediction.quality() != PredictionResult.Quality.READY
                || !jobId.equals(prediction.requestId())) {
            throw new IllegalArgumentException("INVALID_PROPOSAL_PREDICTION");
        }

        record JobContextRow(UUID ownerId, UUID sessionId, UUID instrumentId, long sessionGeneration) {
        }

        var updated = jdbc.query("""
                UPDATE analysis_jobs
                SET status = 'SUCCEEDED',
                    action = ?,
                    error = NULL,
                    reason_codes = CAST(? AS jsonb),
                    feature_snapshot_id = ?,
                    proposal_id = ?,
                    explanation_status = ?,
                    explanation = ?,
                    worker_token = NULL,
                    lease_until = NULL,
                    updated_at = ?
                WHERE id = ?
                  AND worker_token = ?
                  AND status = 'RUNNING'
                  AND lease_until > ?
                  AND model_bundle_id = ?
                  AND feature_version = ?
                  AND feature_schema_hash = ?
                  AND horizon_seconds = ?
                  AND as_of = ?
                RETURNING owner_id, session_id, instrument_id, session_generation
                """,
                (rs, rowNum) -> new JobContextRow(
                        rs.getObject("owner_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("instrument_id", UUID.class),
                        rs.getLong("session_generation")
                ),
                draft.intent().side(),
                json.writeValueAsString(draft.reasonCodes()),
                prediction.featureSnapshotId(),
                draft.proposalId(),
                draft.explanationStatus(),
                draft.explanation(),
                Timestamp.from(now),
                jobId,
                workerToken,
                Timestamp.from(now),
                prediction.modelBundleId(),
                prediction.featureVersion(),
                prediction.featureSchemaHash(),
                prediction.horizonSeconds(),
                Timestamp.from(prediction.asOf())
        ).stream().findFirst();

        if (updated.isEmpty()) {
            return false;
        }

        JobContextRow row = updated.get();
        String intentJson = json.writeValueAsString(draft.intent());

        jdbc.update("""
                INSERT INTO proposals (
                    id,
                    revision,
                    owner_id,
                    session_id,
                    instrument_id,
                    generation,
                    policy_version,
                    model_bundle_id,
                    feature_snapshot_id,
                    plan_intent,
                    valid_until,
                    state
                )
                VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 'READY')
                """,
                draft.proposalId(),
                row.ownerId(),
                row.sessionId(),
                row.instrumentId(),
                row.sessionGeneration(),
                draft.policyVersion(),
                prediction.modelBundleId(),
                prediction.featureSnapshotId(),
                intentJson,
                Timestamp.from(draft.validUntil())
        );

        String eventPayload = json.writeValueAsString(Map.of(
                "proposalId", draft.proposalId().toString(),
                "revision", 1,
                "analysisId", jobId.toString(),
                "ownerId", row.ownerId().toString(),
                "sessionId", row.sessionId().toString(),
                "instrumentId", row.instrumentId().toString(),
                "side", draft.intent().side(),
                "validUntil", draft.validUntil().toString()
        ));

        jdbc.update("""
                INSERT INTO outbox (event_id, topic, event_key, payload, created_at)
                VALUES (?, 'strategy.proposal.created', ?, CAST(? AS jsonb), ?)
                """,
                UUID.randomUUID(),
                draft.proposalId().toString(),
                eventPayload,
                Timestamp.from(now)
        );

        return true;
    }

    @Override
    public void savePolicyArtifact(
            UUID id,
            NumericPolicy.Artifact artifact
    ) {
        String artifactJson = json.writeValueAsString(artifact);
        String hash = sha256(artifactJson);
        jdbc.update("""
                INSERT INTO policy_artifacts (id, version, artifact, hash)
                VALUES (?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (version) DO UPDATE
                SET artifact = EXCLUDED.artifact,
                    hash = EXCLUDED.hash
                """,
                id,
                artifact.version(),
                artifactJson,
                hash
        );
    }

    @Override
    public Optional<NumericPolicy.Artifact> findPolicyArtifact(
            String version
    ) {
        return jdbc.query("""
                SELECT artifact::text AS artifact_json
                FROM policy_artifacts
                WHERE version = ?
                """,
                (rs, rowNum) -> json.readValue(rs.getString("artifact_json"), NumericPolicy.Artifact.class),
                version
        ).stream().findFirst();
    }

    @Override
    public Optional<AnalysisView> findJobView(
            UUID ownerId,
            UUID jobId
    ) {
        return jdbc.query("""
                SELECT id,
                       owner_id,
                       session_id,
                       instrument_id,
                       horizon_seconds,
                       as_of,
                       status,
                       action,
                       error,
                       reason_codes::text AS reason_codes_json,
                       model_bundle_id,
                       bundle_release_status,
                       feature_snapshot_id,
                       proposal_id,
                       explanation_status,
                       explanation,
                       created_at,
                       updated_at
                FROM analysis_jobs
                WHERE id = ? AND owner_id = ?
                """,
                (rs, rowNum) -> {
                    List<String> reasons = json.readValue(
                            rs.getString("reason_codes_json"),
                            new TypeReference<List<String>>() {
                            }
                    );
                    return new AnalysisView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("owner_id", UUID.class),
                            rs.getObject("session_id", UUID.class),
                            rs.getObject("instrument_id", UUID.class),
                            rs.getInt("horizon_seconds"),
                            rs.getTimestamp("as_of").toInstant(),
                            rs.getString("status"),
                            rs.getString("action"),
                            rs.getString("error"),
                            reasons,
                            rs.getObject("model_bundle_id", UUID.class),
                            rs.getString("bundle_release_status"),
                            rs.getObject("feature_snapshot_id", UUID.class),
                            rs.getObject("proposal_id", UUID.class),
                            rs.getString("explanation_status"),
                            rs.getString("explanation"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("updated_at").toInstant()
                    );
                },
                jobId,
                ownerId
        ).stream().findFirst();
    }

    @Override
    public Optional<ProposalView> findProposalView(
            UUID ownerId,
            UUID proposalId,
            int revision
    ) {
        return jdbc.query("""
                SELECT id,
                       revision,
                       owner_id,
                       session_id,
                       instrument_id,
                       generation,
                       policy_version,
                       model_bundle_id,
                       feature_snapshot_id,
                       plan_intent::text AS plan_intent_json,
                       valid_until,
                       state
                FROM proposals
                WHERE id = ? AND revision = ? AND owner_id = ?
                """,
                (rs, rowNum) -> new ProposalView(
                        rs.getObject("id", UUID.class),
                        rs.getInt("revision"),
                        rs.getObject("owner_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("instrument_id", UUID.class),
                        rs.getLong("generation"),
                        rs.getString("policy_version"),
                        rs.getObject("model_bundle_id", UUID.class),
                        rs.getObject("feature_snapshot_id", UUID.class),
                        json.readValue(rs.getString("plan_intent_json"), ProposalIntent.class),
                        rs.getTimestamp("valid_until").toInstant(),
                        rs.getString("state")
                ),
                proposalId,
                revision,
                ownerId
        ).stream().findFirst();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HASH_ERROR", e);
        }
    }
}

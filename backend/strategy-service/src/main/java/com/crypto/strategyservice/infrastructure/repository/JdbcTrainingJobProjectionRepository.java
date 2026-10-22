package com.crypto.strategyservice.infrastructure.repository;

import com.crypto.strategyservice.application.repository.TrainingJobProjectionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTrainingJobProjectionRepository implements TrainingJobProjectionRepository {

    private static final String CONSUMER = "training.jobs.v1";

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcTrainingJobProjectionRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public boolean applyEvent(JobProjectionEvent event) {
        // 1. Inbox unique kontrolü (aynı TX içinde dedupe)
        int inboxInserted = jdbc.update("""
                INSERT INTO inbox (consumer, event_id)
                VALUES (?, ?)
                ON CONFLICT (consumer, event_id) DO NOTHING
                """,
                CONSUMER,
                event.eventId()
        );
        if (inboxInserted == 0) {
            return false;
        }

        // Progress yoksa yüzde uydurma; null göster.
        String progressJson = event.progress() == null
                ? null
                : json.writeValueAsString(event.progress());

        // Daha eski projection (veya aynı timestamp'te daha düşük aggregate_version) yeni state'i ezmesin.
        int updated = jdbc.update("""
                INSERT INTO training_job_projection (
                    job_id,
                    status,
                    stage,
                    progress,
                    aggregate_version,
                    updated_at
                )
                VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT (job_id) DO UPDATE
                SET status = EXCLUDED.status,
                    stage = EXCLUDED.stage,
                    progress = EXCLUDED.progress,
                    aggregate_version = EXCLUDED.aggregate_version,
                    updated_at = EXCLUDED.updated_at
                WHERE training_job_projection.updated_at < EXCLUDED.updated_at
                   OR (
                       training_job_projection.updated_at = EXCLUDED.updated_at
                       AND training_job_projection.aggregate_version < EXCLUDED.aggregate_version
                   )
                """,
                event.jobId(),
                event.status(),
                event.stage(),
                progressJson,
                event.aggregateVersion(),
                Timestamp.from(event.updatedAt())
        );

        return updated == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JobProjectionView> findById(UUID jobId) {
        return jdbc.query("""
                SELECT job_id,
                       status,
                       stage,
                       progress::text AS progress_json,
                       aggregate_version,
                       updated_at
                FROM training_job_projection
                WHERE job_id = ?
                """,
                (rs, rowNum) -> {
                    String rawProgress = rs.getString("progress_json");
                    Map<String, Object> progress = rawProgress == null
                            ? null
                            : json.readValue(rawProgress, new TypeReference<Map<String, Object>>() {
                            });
                    return new JobProjectionView(
                            rs.getObject("job_id", UUID.class),
                            rs.getString("status"),
                            rs.getString("stage"),
                            progress,
                            rs.getLong("aggregate_version"),
                            rs.getTimestamp("updated_at").toInstant()
                    );
                },
                jobId
        ).stream().findFirst();
    }
}


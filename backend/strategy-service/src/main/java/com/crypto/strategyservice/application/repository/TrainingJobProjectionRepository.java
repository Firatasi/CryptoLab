package com.crypto.strategyservice.application.repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sorumluluk: training.jobs.v1 olaylarının hızlı durum projeksiyonunu (training_job_projection) yönetir.
 * Status (QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED) ile stage (EXTRACTING/REVIEW_REQUIRED/TRAINING vb.)
 * alanlarını birbirine karıştırmaz; progress yoksa yüzde uydurmaz (null saklar).
 */
public interface TrainingJobProjectionRepository {

    Set<String> VALID_STATUSES = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");

    record JobProjectionEvent(
            UUID eventId,
            UUID jobId,
            String status,
            String stage,
            Map<String, Object> progress,
            long aggregateVersion,
            Instant updatedAt
    ) {
        public JobProjectionEvent {
            if (eventId == null || jobId == null
                    || status == null || !VALID_STATUSES.contains(status)
                    || stage == null || stage.isBlank()
                    || aggregateVersion < 1
                    || updatedAt == null) {
                throw new IllegalArgumentException("INVALID_TRAINING_JOB_EVENT");
            }
            if (progress != null) {
                progress = Map.copyOf(progress);
            }
        }
    }

    record JobProjectionView(
            UUID jobId,
            String status,
            String stage,
            Map<String, Object> progress,
            long aggregateVersion,
            Instant updatedAt
    ) {
        public boolean isModelTrained() {
            return "SUCCEEDED".equals(status) && "TRAINING".equals(stage);
        }
    }

    boolean applyEvent(JobProjectionEvent event);

    Optional<JobProjectionView> findById(UUID jobId);
}


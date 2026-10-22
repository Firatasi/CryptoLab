package com.crypto.strategyservice;

import com.crypto.strategyservice.application.repository.AnalysisJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class AnalysisJobRepositoryIT {

    private static final Instant NOW =
            Instant.parse("2026-10-22T10:00:00Z");

    @Autowired
    private AnalysisJobRepository jobs;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldClaimQueuedJobAndKeepActiveLeaseExclusive() {
        UUID jobId = createQueuedJob();
        UUID workerToken = UUID.randomUUID();

        var claimed = jobs.claimNext(
                workerToken,
                NOW,
                NOW.plusSeconds(30)
        ).orElseThrow();

        assertEquals(jobId, claimed.id());
        assertEquals(workerToken, claimed.workerToken());
        assertEquals("RUNNING", status(jobId));

        // Lease sürerken aynı iş yeniden alınamaz.
        assertTrue(jobs.claimNext(
                UUID.randomUUID(),
                NOW.plusSeconds(10),
                NOW.plusSeconds(40)
        ).isEmpty());
    }

    @Test
    void shouldReclaimExpiredLeaseAndRejectPreviousWorker() {
        UUID jobId = createQueuedJob();
        UUID firstToken = UUID.randomUUID();
        UUID secondToken = UUID.randomUUID();

        jobs.claimNext(
                firstToken,
                NOW,
                NOW.plusSeconds(30)
        ).orElseThrow();

        Instant reclaimedAt = NOW.plusSeconds(31);

        var reclaimed = jobs.claimNext(
                secondToken,
                reclaimedAt,
                reclaimedAt.plusSeconds(30)
        ).orElseThrow();

        assertEquals(jobId, reclaimed.id());
        assertEquals(secondToken, reclaimed.workerToken());

        assertFalse(jobs.fail(
                jobId,
                firstToken,
                "MODEL_UNAVAILABLE",
                reclaimedAt.plusSeconds(1)
        ));

        assertEquals("RUNNING", status(jobId));

        assertTrue(jobs.fail(
                jobId,
                secondToken,
                "MODEL_UNAVAILABLE",
                reclaimedAt.plusSeconds(2)
        ));

        assertEquals("FAILED", status(jobId));
        assertNull(jdbc.queryForObject(
                "SELECT action FROM analysis_jobs WHERE id = ?",
                String.class,
                jobId
        ));
    }

    @Test
    void shouldRejectCompletionAfterLeaseExpires() {
        UUID jobId = createQueuedJob();
        UUID workerToken = UUID.randomUUID();

        jobs.claimNext(
                workerToken,
                NOW,
                NOW.plusSeconds(30)
        ).orElseThrow();

        assertFalse(jobs.fail(
                jobId,
                workerToken,
                "MODEL_UNAVAILABLE",
                NOW.plusSeconds(31)
        ));

        assertEquals("RUNNING", status(jobId));
    }

    private UUID createQueuedJob() {
        // Bu test yalnız ayrılmış strategy_test_db şemasında çalışır.
        // Önceden kalmış işler global claimNext sonucunu etkileyebilir.
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT count(*) FROM analysis_jobs",
                        Long.class
                )
        );

        UUID id = UUID.randomUUID();

        jobs.createQueued(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                900,
                NOW,
                NOW
        );

        assertEquals("QUEUED", status(id));
        return id;
    }

    private String status(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM analysis_jobs WHERE id = ?",
                String.class,
                id
        );
    }

    @Test
    void shouldPreservePinnedContextWhenAnotherWorkerReclaimsJob() {
        UUID jobId = createQueuedJob();
        UUID firstToken = UUID.randomUUID();

        jobs.claimNext(
                firstToken,
                NOW,
                NOW.plusSeconds(30)
        ).orElseThrow();

        var original = new AnalysisJobRepository.PinnedContext(
                3L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "features-v1",
                "sha256:" + "b".repeat(64),
                "DEVELOPMENT_CANDIDATE"
        );

        assertTrue(jobs.pinContext(
                jobId,
                firstToken,
                original,
                NOW.plusSeconds(1)
        ));

        var replacement = new AnalysisJobRepository.PinnedContext(
                4L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "features-v2",
                "sha256:" + "c".repeat(64),
                "DEVELOPMENT_CANDIDATE"
        );

        // Aynı worker bile sabitlenmiş bağlamı değiştiremez.
        assertFalse(jobs.pinContext(
                jobId,
                firstToken,
                replacement,
                NOW.plusSeconds(2)
        ));

        UUID secondToken = UUID.randomUUID();
        Instant reclaimedAt = NOW.plusSeconds(31);

        jobs.claimNext(
                secondToken,
                reclaimedAt,
                reclaimedAt.plusSeconds(30)
        ).orElseThrow();

        assertEquals(
                original,
                jobs.findPinnedContext(
                        jobId,
                        secondToken,
                        reclaimedAt.plusSeconds(1)
                ).orElseThrow()
        );

        assertFalse(jobs.pinContext(
                jobId,
                secondToken,
                replacement,
                reclaimedAt.plusSeconds(1)
        ));

        assertTrue(jobs.findPinnedContext(
                jobId,
                firstToken,
                reclaimedAt.plusSeconds(1)
        ).isEmpty());
    }

    @Test
    void shouldCompleteWaitWithoutCreatingProposal() {
        UUID jobId = createQueuedJob();
        UUID token = UUID.randomUUID();

        jobs.claimNext(
                token,
                NOW,
                NOW.plusSeconds(30)
        ).orElseThrow();

        var context = new AnalysisJobRepository.PinnedContext(
                1L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "features-v1",
                "sha256:" + "b".repeat(64),
                "DEVELOPMENT_CANDIDATE"
        );

        assertTrue(jobs.pinContext(
                jobId,
                token,
                context,
                NOW.plusSeconds(1)
        ));

        var prediction =
                new com.crypto.strategyservice.domain.model.PredictionResult(
                        jobId,
                        com.crypto.strategyservice.domain.model
                                .PredictionResult.Quality.INSUFFICIENT_DATA,
                        java.util.List.of(
                                "FIXTURE_MODEL",
                                "INSUFFICIENT_HISTORY"
                        ),
                        context.modelBundleId(),
                        context.featureVersion(),
                        context.featureSchemaHash(),
                        UUID.randomUUID(),
                        NOW,
                        900,
                        null,
                        null,
                        null,
                        java.util.Map.of(),
                        1
                );

        Long proposalsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM proposals",
                Long.class
        );

        assertTrue(jobs.completeWait(
                jobId,
                token,
                prediction,
                NOW.plusSeconds(2)
        ));

        assertEquals("SUCCEEDED", status(jobId));

        assertEquals(
                "WAIT",
                jdbc.queryForObject(
                        "SELECT action FROM analysis_jobs WHERE id = ?",
                        String.class,
                        jobId
                )
        );

        assertNull(jdbc.queryForObject(
                "SELECT proposal_id FROM analysis_jobs WHERE id = ?",
                UUID.class,
                jobId
        ));

        assertEquals(
                proposalsBefore,
                jdbc.queryForObject(
                        "SELECT count(*) FROM proposals",
                        Long.class
                )
        );

        // Terminal sonuç ikinci kez değiştirilemez.
        assertFalse(jobs.completeWait(
                jobId,
                token,
                prediction,
                NOW.plusSeconds(3)
        ));
    }
}
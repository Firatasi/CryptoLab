package com.crypto.simulationservice;

import com.crypto.financecore.finance.Decimal;
import com.crypto.financecore.finance.MaintenanceTier;
import com.crypto.simulationservice.api.dto.request.AiPreviewRequest;
import com.crypto.simulationservice.api.dto.request.ManualPreviewRequest;
import com.crypto.simulationservice.application.command.SubmitOrderCommand;
import com.crypto.simulationservice.application.port.ProposalReader;
import com.crypto.simulationservice.application.service.AiPreviewService;
import com.crypto.simulationservice.application.service.CreateSession;
import com.crypto.simulationservice.application.service.SubmitOrder;
import com.crypto.simulationservice.domain.exception.BusinessException;
import com.crypto.simulationservice.domain.model.ExecutionPlan;
import com.crypto.simulationservice.domain.service.PreviewFactory;
import com.crypto.simulationservice.domain.service.RiskPolicyEvaluator;
import com.crypto.simulationservice.infrastructure.client.FixtureProposalReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AiProposalPreviewIT {

    private static BigDecimal d(String val) {
        return Decimal.parse(val);
    }

    @Autowired
    private CreateSession createSession;

    @Autowired
    private AiPreviewService aiPreviewService;

    @Autowired
    private SubmitOrder submitOrder;

    @Autowired
    private FixtureProposalReader proposalReader;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void resetProposals() {
        proposalReader.clear();
    }

    private PreviewFactory.Rule sampleRule() {
        return new PreviewFactory.Rule(
                UUID.randomUUID(),
                d("0.1"),
                d("0.001"),
                d("100"),
                d("0.0005"),
                List.of(new MaintenanceTier(d("0"), null, d("0.004"), d("0")))
        );
    }

    private PreviewFactory.Context sampleContext() {
        return new PreviewFactory.Context(
                d("50000"),
                d("50000"),
                d("10000"),
                d("0"),
                1L,
                30L,
                d("49900"),
                d("50100"),
                d("20"),
                true,
                true
        );
    }

    private RiskPolicyEvaluator.Limits sampleLimits() {
        return new RiskPolicyEvaluator.Limits(
                d("10"),
                5,
                d("50000"),
                d("100000"),
                d("2000"),
                d("0.02"), // %2 risk bütçesi: equity=10000 -> riskBudget=200 USDT
                d("0.10")
        );
    }

    @Test
    void b09_5_aiProposalPreviewSizesViaRiskPolicyAndConnectsToExactSubmitOrderPipeline() {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = createSession.execute(ownerId, d("10000"), UUID.randomUUID(), UUID.randomUUID());
        UUID instrumentId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        Instant now = clock.instant();

        jdbc.update(
                "INSERT INTO instrument_generations(session_id, instrument_id, generation) VALUES (?, ?, 1)",
                sessionId,
                instrumentId
        );

        // AI önerisi yalnız yön, SL=49000, TP=53000 ve suggestedLeverage=5 taşır (miktar/marjin taşımaz).
        // RiskPolicyEvaluator: entry=50000, stop=49000 -> distance=1000, riskBudget=10000*0.02=200 -> qRisk = 200/1000 = 0.200 BTC
        proposalReader.register(new ProposalReader.ProposalSnapshot(
                proposalId,
                1,
                ownerId,
                sessionId,
                instrumentId,
                1L,
                "LONG",
                "MARKET",
                null,
                d("49000"),
                d("53000"),
                d("5"),
                3600,
                "CLOSE_AT_DEADLINE",
                now.plusSeconds(45),
                "READY"
        ));

        ExecutionPlan aiPlan = aiPreviewService.previewAiProposal(
                ownerId,
                new AiPreviewRequest("AI_PROPOSAL", sessionId, proposalId, 1),
                d("10000"),
                d("0"),
                d("0"),
                sampleLimits(),
                sampleContext(),
                sampleRule()
        );

        assertEquals("AI_PROPOSAL", aiPlan.source());
        assertEquals(proposalId, aiPlan.proposalId());
        assertEquals(0, d("0.2").compareTo(aiPlan.quantity()));
        assertEquals(0, d("2000").compareTo(aiPlan.marginRequired()));
        assertEquals(0, d("5").compareTo(aiPlan.openFeeEstimate()));
        assertNotNull(aiPlan.planHash());

        // Exact approval: SubmitOrder ile onaylandığında ACCEPTED olur ve plan CONSUMED durumuna geçer
        SubmitOrder.Accepted accepted = submitOrder.execute(
                ownerId,
                "idem-ai-approve-" + UUID.randomUUID(),
                new SubmitOrderCommand(aiPlan.planId(), aiPlan.revision(), aiPlan.planHash())
        );
        assertEquals("ACCEPTED", accepted.status());

        String planState = jdbc.queryForObject(
                "SELECT state FROM execution_plans WHERE plan_id = ? AND revision = ?",
                String.class,
                aiPlan.planId(),
                aiPlan.revision()
        );
        assertEquals("CONSUMED", planState);
    }

    @Test
    void b09_5_rejectsExpiredProposalGenerationMismatchRevisionMismatchAndMissingBracket() {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = createSession.execute(ownerId, d("10000"), UUID.randomUUID(), UUID.randomUUID());
        UUID instrumentId = UUID.randomUUID();
        Instant now = clock.instant();

        jdbc.update(
                "INSERT INTO instrument_generations(session_id, instrument_id, generation) VALUES (?, ?, 2)",
                sessionId,
                instrumentId
        );

        // 1. Süresi dolmuş (expired) proposal -> PLAN_EXPIRED
        UUID expiredProposalId = UUID.randomUUID();
        proposalReader.register(new ProposalReader.ProposalSnapshot(
                expiredProposalId,
                1,
                ownerId,
                sessionId,
                instrumentId,
                2L,
                "LONG",
                "MARKET",
                null,
                d("49000"),
                d("53000"),
                d("5"),
                3600,
                "CLOSE_AT_DEADLINE",
                now.minusSeconds(1),
                "READY"
        ));

        BusinessException expiredEx = assertThrows(
                BusinessException.class,
                () -> aiPreviewService.previewAiProposal(
                        ownerId,
                        new AiPreviewRequest("AI_PROPOSAL", sessionId, expiredProposalId, 1),
                        d("10000"),
                        d("0"),
                        d("0"),
                        sampleLimits(),
                        sampleContext(),
                        sampleRule()
                )
        );
        assertEquals(BusinessException.Code.PLAN_EXPIRED, expiredEx.code());

        // 2. Generation uyuşmazlığı (proposal gen=1, session gen=2) -> PLAN_REVOKED
        UUID staleGenProposalId = UUID.randomUUID();
        proposalReader.register(new ProposalReader.ProposalSnapshot(
                staleGenProposalId,
                1,
                ownerId,
                sessionId,
                instrumentId,
                1L,
                "LONG",
                "MARKET",
                null,
                d("49000"),
                d("53000"),
                d("5"),
                3600,
                "CLOSE_AT_DEADLINE",
                now.plusSeconds(60),
                "READY"
        ));

        BusinessException genEx = assertThrows(
                BusinessException.class,
                () -> aiPreviewService.previewAiProposal(
                        ownerId,
                        new AiPreviewRequest("AI_PROPOSAL", sessionId, staleGenProposalId, 1),
                        d("10000"),
                        d("0"),
                        d("0"),
                        sampleLimits(),
                        sampleContext(),
                        sampleRule()
                )
        );
        assertEquals(BusinessException.Code.PLAN_REVOKED, genEx.code());

        // 3. Revision uyuşmazlığı (revision=2 bulunamaz) -> NOT_FOUND
        BusinessException revEx = assertThrows(
                BusinessException.class,
                () -> aiPreviewService.previewAiProposal(
                        ownerId,
                        new AiPreviewRequest("AI_PROPOSAL", sessionId, staleGenProposalId, 2),
                        d("10000"),
                        d("0"),
                        d("0"),
                        sampleLimits(),
                        sampleContext(),
                        sampleRule()
                )
        );
        assertEquals(BusinessException.Code.NOT_FOUND, revEx.code());

        // 4. SL/TP eksik AI önerisi -> AI_PROPOSAL_BRACKET_REQUIRED
        UUID noBracketProposalId = UUID.randomUUID();
        proposalReader.register(new ProposalReader.ProposalSnapshot(
                noBracketProposalId,
                1,
                ownerId,
                sessionId,
                instrumentId,
                2L,
                "LONG",
                "MARKET",
                null,
                null,
                d("53000"),
                d("5"),
                3600,
                "CLOSE_AT_DEADLINE",
                now.plusSeconds(60),
                "READY"
        ));

        IllegalArgumentException bracketEx = assertThrows(
                IllegalArgumentException.class,
                () -> aiPreviewService.previewAiProposal(
                        ownerId,
                        new AiPreviewRequest("AI_PROPOSAL", sessionId, noBracketProposalId, 1),
                        d("10000"),
                        d("0"),
                        d("0"),
                        sampleLimits(),
                        sampleContext(),
                        sampleRule()
                )
        );
        assertEquals("AI_PROPOSAL_BRACKET_REQUIRED", bracketEx.getMessage());
    }

    @Test
    void b09_5_modifiedProposalBecomesManualWithOriginProposalIdAndRejectsOldPlanHash() {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = createSession.execute(ownerId, d("10000"), UUID.randomUUID(), UUID.randomUUID());
        UUID instrumentId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        Instant now = clock.instant();

        jdbc.update(
                "INSERT INTO instrument_generations(session_id, instrument_id, generation) VALUES (?, ?, 1)",
                sessionId,
                instrumentId
        );

        proposalReader.register(new ProposalReader.ProposalSnapshot(
                proposalId,
                1,
                ownerId,
                sessionId,
                instrumentId,
                1L,
                "LONG",
                "MARKET",
                null,
                d("49000"),
                d("53000"),
                d("5"),
                3600,
                "CLOSE_AT_DEADLINE",
                now.plusSeconds(60),
                "READY"
        ));

        ExecutionPlan originalAiPlan = aiPreviewService.previewAiProposal(
                ownerId,
                new AiPreviewRequest("AI_PROPOSAL", sessionId, proposalId, 1),
                d("10000"),
                d("0"),
                d("0"),
                sampleLimits(),
                sampleContext(),
                sampleRule()
        );

        // Kullanıcı önerideki marjin/kaldıraç/SL değerini değiştirdiğinde source=MANUAL + originProposalId olur.
        ExecutionPlan modifiedPlan = aiPreviewService.previewModifiedProposal(
                ownerId,
                new ManualPreviewRequest(
                        "MANUAL",
                        sessionId,
                        instrumentId,
                        "LONG",
                        "MARKET",
                        "1000",
                        "10",
                        null,
                        "48500",
                        "54000",
                        3600,
                        "CLOSE_AT_DEADLINE",
                        proposalId
                ),
                sampleContext(),
                sampleRule()
        );

        assertEquals("MANUAL", modifiedPlan.source());
        assertEquals(proposalId, modifiedPlan.proposalId());
        assertNotEquals(originalAiPlan.planId(), modifiedPlan.planId());
        assertNotEquals(originalAiPlan.planHash(), modifiedPlan.planHash());

        // Değiştirilmiş planı eski AI planHash'i ile onaylamaya çalışmak REVALIDATION_REQUIRED ile reddedilir!
        BusinessException mismatch = assertThrows(
                BusinessException.class,
                () -> submitOrder.execute(
                        ownerId,
                        "idem-mod-mismatch",
                        new SubmitOrderCommand(modifiedPlan.planId(), modifiedPlan.revision(), originalAiPlan.planHash())
                )
        );
        assertEquals(BusinessException.Code.REVALIDATION_REQUIRED, mismatch.code());
    }
}


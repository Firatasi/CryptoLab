package com.crypto.simulationservice.application.service;

import com.crypto.financecore.finance.Decimal;
import com.crypto.simulationservice.api.dto.request.AiPreviewRequest;
import com.crypto.simulationservice.api.dto.request.ManualPreviewRequest;
import com.crypto.simulationservice.application.port.ProposalReader;
import com.crypto.simulationservice.domain.exception.BusinessException;
import com.crypto.simulationservice.domain.model.ExecutionPlan;
import com.crypto.simulationservice.domain.service.PreviewFactory;
import com.crypto.simulationservice.domain.service.RiskPolicyEvaluator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Sorumluluk: AI_PROPOSAL ve değiştirilmiş (MANUAL + originProposalId) önerileri
 * doğrular, RiskPolicyEvaluator ile boyutlandırır, ExecutionPlan + PlanHash üretir
 * ve exact onay (SubmitOrder) hattına hazırlar.
 */
@Service
public class AiPreviewService {

    private final ProposalReader proposalReader;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AiPreviewService(
            ProposalReader proposalReader,
            JdbcTemplate jdbc,
            Clock clock
    ) {
        this.proposalReader = proposalReader;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ExecutionPlan previewAiProposal(
            UUID ownerId,
            AiPreviewRequest request,
            BigDecimal equity,
            BigDecimal existingNotional,
            BigDecimal unitCostBuffer,
            RiskPolicyEvaluator.Limits limits,
            PreviewFactory.Context context,
            PreviewFactory.Rule rule
    ) {
        if (request == null || !"AI_PROPOSAL".equals(request.source()) || request.proposalRevision() < 1) {
            throw new IllegalArgumentException("INVALID_AI_PREVIEW_REQUEST");
        }

        verifyOwnedRunningSession(ownerId, request.sessionId());

        var proposal = proposalReader.findProposal(
                ownerId,
                request.sessionId(),
                request.proposalId(),
                request.proposalRevision()
        ).orElseThrow(() -> new BusinessException(BusinessException.Code.NOT_FOUND));

        if (!proposal.ownerId().equals(ownerId) || !proposal.sessionId().equals(request.sessionId())) {
            throw new BusinessException(BusinessException.Code.NOT_FOUND);
        }
        if (proposal.revision() != request.proposalRevision()) {
            throw new BusinessException(BusinessException.Code.REVALIDATION_REQUIRED);
        }

        long currentGeneration = currentGeneration(request.sessionId(), proposal.instrumentId());
        Instant now = clock.instant();

        ExecutionPlan unsigned = PreviewFactory.createFromAiProposal(
                proposal,
                1L,
                currentGeneration,
                equity,
                existingNotional,
                unitCostBuffer,
                limits,
                context,
                rule,
                now
        );

        return persistPreviewedPlan(unsigned);
    }

    @Transactional
    public ExecutionPlan previewModifiedProposal(
            UUID ownerId,
            ManualPreviewRequest request,
            PreviewFactory.Context context,
            PreviewFactory.Rule rule
    ) {
        if (request == null || !"MANUAL".equals(request.source()) || request.originProposalId() == null) {
            throw new IllegalArgumentException("ORIGIN_PROPOSAL_REQUIRED");
        }

        verifyOwnedRunningSession(ownerId, request.sessionId());
        long currentGeneration = currentGeneration(request.sessionId(), request.instrumentId());
        Instant now = clock.instant();

        var factoryReq = new PreviewFactory.Request(
                request.sessionId(),
                request.instrumentId(),
                1L,
                currentGeneration,
                request.side(),
                request.orderType(),
                Decimal.parse(request.marginBudget()),
                Decimal.parse(request.leverage()),
                request.limitPrice() != null ? Decimal.parse(request.limitPrice()) : null,
                request.stopLoss() != null ? Decimal.parse(request.stopLoss()) : null,
                request.takeProfit() != null ? Decimal.parse(request.takeProfit()) : null,
                request.horizonSeconds(),
                request.expiryPolicy()
        );

        ExecutionPlan unsigned = PreviewFactory.createFromModifiedProposal(
                request.originProposalId(),
                factoryReq,
                context,
                rule,
                now
        );

        return persistPreviewedPlan(unsigned);
    }

    private void verifyOwnedRunningSession(UUID ownerId, UUID sessionId) {
        var statusList = jdbc.queryForList(
                "SELECT status FROM simulation_sessions WHERE id = ? AND owner_id = ?",
                String.class,
                sessionId,
                ownerId
        );
        if (statusList.isEmpty()) {
            throw new BusinessException(BusinessException.Code.NOT_FOUND);
        }
        if (!"RUNNING".equals(statusList.getFirst())) {
            throw new BusinessException(BusinessException.Code.SESSION_HALTED);
        }
    }

    private long currentGeneration(UUID sessionId, UUID instrumentId) {
        var gens = jdbc.queryForList(
                "SELECT generation FROM instrument_generations WHERE session_id = ? AND instrument_id = ?",
                Long.class,
                sessionId,
                instrumentId
        );
        if (gens.isEmpty()) {
            jdbc.update(
                    "INSERT INTO instrument_generations(session_id, instrument_id, generation) VALUES (?, ?, 1)",
                    sessionId,
                    instrumentId
            );
            return 1L;
        }
        return gens.getFirst();
    }

    private ExecutionPlan persistPreviewedPlan(ExecutionPlan unsigned) {
        String canonicalJson = new String(PlanHash.canonical(unsigned), StandardCharsets.UTF_8);
        String hash = PlanHash.hash(unsigned);

        ExecutionPlan signed = new ExecutionPlan(
                unsigned.planId(),
                unsigned.revision(),
                hash,
                unsigned.sessionId(),
                unsigned.instrumentId(),
                unsigned.instrumentVersion(),
                unsigned.source(),
                unsigned.proposalId(),
                unsigned.positionGeneration(),
                unsigned.side(),
                unsigned.orderType(),
                unsigned.quantity(),
                unsigned.referenceEntry(),
                unsigned.limitPrice(),
                unsigned.marginRequired(),
                unsigned.openFeeEstimate(),
                unsigned.notional(),
                unsigned.leverage(),
                unsigned.stopLoss(),
                unsigned.takeProfit(),
                unsigned.horizonSeconds(),
                unsigned.expiryPolicy(),
                unsigned.priceLowerBound(),
                unsigned.priceUpperBound(),
                unsigned.maxOpenFee(),
                unsigned.maxSlippageBps(),
                unsigned.ruleProfileId(),
                unsigned.riskPolicyVersion(),
                unsigned.createdAt(),
                unsigned.validUntil(),
                unsigned.estimatedLiquidationPrice(),
                unsigned.warnings()
        );

        jdbc.update("""
                INSERT INTO execution_plans(
                    plan_id,
                    revision,
                    session_id,
                    instrument_id,
                    canonical_json,
                    hash,
                    position_generation,
                    valid_until,
                    state
                )
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'PREVIEWED')
                """,
                signed.planId(),
                signed.revision(),
                signed.sessionId(),
                signed.instrumentId(),
                canonicalJson,
                signed.planHash(),
                signed.positionGeneration(),
                Timestamp.from(signed.validUntil())
        );

        return signed;
    }
}


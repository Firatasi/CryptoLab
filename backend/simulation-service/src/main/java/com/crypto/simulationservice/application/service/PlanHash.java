package com.crypto.simulationservice.application.service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import com.crypto.financecore.finance.Decimal;
import com.crypto.simulationservice.domain.model.ExecutionPlan;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class PlanHash {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PlanHash() {
    }

    public static byte[] canonical(ExecutionPlan p) {
        // LinkedHashMap: alan sırası sözleşmedir. Hash ve warnings kasıtlı dışarıda.
        Map<String, Object> m = new LinkedHashMap<>();
        Object[][] fields = {{"planId", p.planId()}, {"revision", p.revision()}, {"sessionId", p.sessionId()},
        {"instrumentId", p.instrumentId()}, {"instrumentVersion", p.instrumentVersion()}, {"source", p.source()},
        {"proposalId", p.proposalId()}, {"positionGeneration", p.positionGeneration()}, {"side", p.side()},
        {"orderType", p.orderType()}, {"quantity", p.quantity()}, {"referenceEntry", p.referenceEntry()},
        {"limitPrice", p.limitPrice()}, {"marginRequired", p.marginRequired()}, {"openFeeEstimate", p.openFeeEstimate()},
        {"notional", p.notional()}, {"leverage", p.leverage()}, {"stopLoss", p.stopLoss()}, {"takeProfit", p.takeProfit()},
        {"horizonSeconds", p.horizonSeconds()}, {"expiryPolicy", p.expiryPolicy()},
        {"priceLowerBound", p.priceLowerBound()}, {"priceUpperBound", p.priceUpperBound()},
        {"maxOpenFee", p.maxOpenFee()}, {"maxSlippageBps", p.maxSlippageBps()}, {"ruleProfileId", p.ruleProfileId()},
        {"riskPolicyVersion", p.riskPolicyVersion()}, {"createdAt", p.createdAt()}, {"validUntil", p.validUntil()},
        {"estimatedLiquidationPrice", p.estimatedLiquidationPrice()}};
        for (Object[] field : fields) {
            Object v = field[1];
            if (v instanceof BigDecimal d) {
                v = Decimal.wire(d);
            } else if (v instanceof Instant t) {
                v = new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(t);
            } else if (v instanceof java.util.UUID u) {
                v = u.toString();
            }
            m.put((String) field[0], v); // Null alanlar da hash girdisinde kalır.
        }
        try {
            return JSON.writeValueAsBytes(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hash(ExecutionPlan p) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(p)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.crypto.strategyservice.domain.service;

import com.crypto.strategyservice.domain.model.PredictionResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Quantitative risk- & regime-adaptive policy engine (quant-adaptive-v2).
 *
 * Computes horizon- and volatility-calibrated SL/TP levels and optimal dynamic leverage
 * bounded strictly by (1) SL-before-liquidation isolation margin safety, (2) active
 * simulation session riskPolicyId cap, and (3) exchange instrument maxLeverage.
 */
public final class AdaptiveNumericPolicy {

    private AdaptiveNumericPolicy() {
    }

    public static String normalizeRiskLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "LOW", "DUSUK" -> "LOW";
            case "MEDIUM", "ORTA" -> "MEDIUM";
            case "HIGH", "YUKSEK" -> "HIGH";
            default -> "MEDIUM";
        };
    }

    public static String riskLevelFromPolicyId(java.util.UUID riskPolicyId) {
        if (com.crypto.strategyservice.application.port.SessionContextReader.SessionRiskProfile.CONSERVATIVE_POLICY_ID.equals(riskPolicyId)) {
            return "LOW";
        }
        if (com.crypto.strategyservice.application.port.SessionContextReader.SessionRiskProfile.ADVANCED_POLICY_ID.equals(riskPolicyId)) {
            return "HIGH";
        }
        return "MEDIUM";
    }

    public static NumericPolicy.Outcome evaluateAdaptive(
            PredictionResult prediction,
            NumericPolicy.Artifact policyArtifact,
            BigDecimal entryPrice,
            BigDecimal verifiedAtr,
            double coverage,
            double estimatedRoundTripCost,
            String rawRiskLevel,
            int sessionMaxLeverage,
            int instrumentMaxLeverage
    ) {
        if (prediction == null || prediction.quality() != PredictionResult.Quality.READY
                || prediction.predictedLogReturn() == null || prediction.volatilityEstimate() == null) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "MODEL_NOT_READY");
        }
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0
                || verifiedAtr == null || verifiedAtr.compareTo(BigDecimal.ZERO) <= 0
                || !Double.isFinite(coverage) || coverage < 0.90) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "INSUFFICIENT_COVERAGE");
        }

        String riskLevel = normalizeRiskLevel(rawRiskLevel);
        if (riskLevel == null) {
            riskLevel = "MEDIUM";
        }

        Map<String, Object> summary = prediction.observedFeatureSummary() != null
                ? prediction.observedFeatureSummary()
                : Map.of();
        List<String> reasons = prediction.reasonCodes() != null ? prediction.reasonCodes() : List.of();

        double signalConfidence = extractDouble(summary, "signalConfidence", 0.55);
        double efficiencyRatio = extractDouble(summary, "efficiencyRatio", 0.25);
        double volRegimeRatio = extractDouble(summary, "volRegimeRatio", 1.0);
        String regime = extractString(summary, "regime", "MEAN_REVERSION");

        if (reasons.contains("VOLATILITY_REGIME_UNSAFE") || "HIGH_VOL_STRESS".equals(regime)) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "VOLATILITY_REGIME_UNSAFE");
        }
        if (reasons.contains("CHOPPY_REGIME") && !"HIGH".equals(riskLevel)) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "CHOPPY_MARKET_WAIT");
        }
        if (reasons.contains("LOW_CONFIDENCE") && !"HIGH".equals(riskLevel)) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "LOW_MODEL_CONFIDENCE");
        }

        double minConfidence = switch (riskLevel) {
            case "LOW" -> 0.56;
            case "HIGH" -> 0.22;
            default -> 0.53;
        };
        if (signalConfidence < minConfidence) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "CONFIDENCE_BELOW_RISK_THRESHOLD");
        }

        double predLogRet = prediction.predictedLogReturn();
        double absExpectedReturn = Math.abs(Math.expm1(predLogRet));
        double cost = Math.max(0.0, estimatedRoundTripCost);
        int h = prediction.horizonSeconds();
        double minMove = switch (riskLevel) {
            case "LOW" -> Math.max(cost * 2.10, h <= 60 ? 0.0009 : (h <= 900 ? 0.0014 : 0.0018));
            case "HIGH" -> Math.max(cost * 0.70, h <= 60 ? 0.00025 : 0.00040);
            default -> Math.max(cost * 1.65, h <= 60 ? 0.0007 : (h <= 900 ? 0.0011 : 0.0015));
        };
        if (absExpectedReturn < minMove) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "EDGE_BELOW_COST_HURDLE");
        }

        boolean isLong = predLogRet > 0.0;
        String action = isLong ? "LONG" : "SHORT";

        double entryD = entryPrice.doubleValue();
        double atrFrac = clamp(verifiedAtr.doubleValue() / entryD, 0.0012, 0.12);
        double modelVol = Math.max(0.0, prediction.volatilityEstimate());
        double effVolFrac = Math.max(atrFrac, Math.min(modelVol * 0.65, 0.10));

        double horizonMinSlPct = h <= 60 ? 0.0022
                : (h <= 900 ? 0.0040
                : (h <= 3600 ? 0.0065
                : (h <= 86400 ? 0.0120 : 0.0220)));

        double slMult = switch (riskLevel) {
            case "LOW" -> 1.40;
            case "HIGH" -> 1.85;
            default -> 1.65;
        };
        if (reasons.contains("HTF_TREND_CONFIRMED")) {
            slMult *= 0.92;
        }
        if (volRegimeRatio > 1.80) {
            slMult *= 1.15;
        }
        double stopDistPct = clamp(effVolFrac * slMult, horizonMinSlPct, 0.085);

        double baseRr = switch (riskLevel) {
            case "LOW" -> 2.10;
            case "HIGH" -> 1.85;
            default -> 2.00;
        };
        double rrBoost = 0.45 * clamp((signalConfidence - 0.50) / 0.40, 0.0, 1.0)
                + 0.30 * clamp(efficiencyRatio, 0.0, 1.0);
        if (reasons.contains("HTF_TREND_CONFIRMED")) {
            rrBoost += 0.25;
        }
        if (reasons.contains("MEAN_REVERSION_EXHAUSTION")) {
            rrBoost -= 0.25;
        }
        double rrRatio = clamp(baseRr + rrBoost, 1.60, 3.20);
        double tpDistPct = clamp(Math.max(stopDistPct * rrRatio, absExpectedReturn * 1.15), stopDistPct * 1.60, 0.28);

        int scale = entryPrice.compareTo(BigDecimal.ONE) < 0 ? 5
                : (entryPrice.compareTo(BigDecimal.TEN) < 0 ? 4
                : (entryPrice.compareTo(new BigDecimal("500")) < 0 ? 3 : 2));

        BigDecimal slDelta = BigDecimal.valueOf(stopDistPct);
        BigDecimal tpDelta = BigDecimal.valueOf(tpDistPct);

        BigDecimal stopLoss = isLong
                ? entryPrice.multiply(BigDecimal.ONE.subtract(slDelta)).setScale(scale, RoundingMode.HALF_UP)
                : entryPrice.multiply(BigDecimal.ONE.add(slDelta)).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal takeProfit = isLong
                ? entryPrice.multiply(BigDecimal.ONE.add(tpDelta)).setScale(scale, RoundingMode.HALF_UP)
                : entryPrice.multiply(BigDecimal.ONE.subtract(tpDelta)).setScale(scale, RoundingMode.HALF_UP);

        BigDecimal minTick = BigDecimal.ONE.movePointLeft(scale);
        if (stopLoss.compareTo(BigDecimal.ZERO) <= 0) {
            stopLoss = minTick;
        }
        if (takeProfit.compareTo(BigDecimal.ZERO) <= 0) {
            takeProfit = minTick;
        }
        if (isLong) {
            if (stopLoss.compareTo(entryPrice) >= 0) {
                stopLoss = entryPrice.subtract(minTick).max(minTick);
            }
            if (takeProfit.compareTo(entryPrice) <= 0) {
                takeProfit = entryPrice.add(minTick);
            }
        } else {
            if (stopLoss.compareTo(entryPrice) <= 0) {
                stopLoss = entryPrice.add(minTick);
            }
            if (takeProfit.compareTo(entryPrice) >= 0) {
                takeProfit = entryPrice.subtract(minTick).max(minTick);
            }
        }

        // Optimal leverage: SL must strictly execute before isolated liquidation + buffer
        double safetyFactor = switch (riskLevel) {
            case "LOW" -> 2.00;
            case "HIGH" -> 1.55;
            default -> 1.75;
        };
        int liqSafeMax = (int) Math.floor(1.0 / (stopDistPct * safetyFactor + 0.008));
        int effSessionCap = Math.max(1, sessionMaxLeverage);
        int effInstCap = Math.max(1, instrumentMaxLeverage);
        int hardCap = Math.max(1, Math.min(Math.min(effSessionCap, effInstCap), Math.max(1, liqSafeMax)));

        double baseMarginRisk = switch (riskLevel) {
            case "LOW" -> 0.06;
            case "HIGH" -> 0.34;
            default -> 0.15;
        };
        double convScale = 0.72 + 0.68 * clamp((signalConfidence - 0.50) / 0.40, 0.0, 1.0);
        double volDamper = volRegimeRatio > 1.25 ? (1.0 / (0.85 + 0.25 * volRegimeRatio)) : 1.0;
        double alignMult = 1.0;
        if (reasons.contains("HTF_TREND_CONFIRMED")) {
            alignMult *= 1.12;
        } else if (reasons.contains("HTF_COUNTER_TREND")) {
            alignMult *= 0.72;
        }
        if (reasons.contains("HIGH_FUNDING_CAUTION")) {
            alignMult *= 0.85;
        }

        int rawLev = (int) Math.round((baseMarginRisk / stopDistPct) * convScale * volDamper * alignMult);
        int optimalLeverage = Math.max(1, Math.min(hardCap, rawLev));

        return new NumericPolicy.Outcome(
                action,
                stopLoss,
                takeProfit,
                BigDecimal.valueOf(optimalLeverage),
                "QUANT_ADAPTIVE_POLICY"
        );
    }

    private static double extractDouble(Map<String, Object> summary, String key, double fallback) {
        Object val = summary.get(key);
        if (val instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : fallback;
        }
        if (val instanceof String s) {
            try {
                double d = Double.parseDouble(s.trim());
                return Double.isFinite(d) ? d : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String extractString(Map<String, Object> summary, String key, String fallback) {
        Object val = summary.get(key);
        return val != null ? String.valueOf(val) : fallback;
    }

    /**
     * Generates a concise, user-friendly Turkish market observation summary for WAIT outcomes
     * without creating any OrderProposal or exposing internal system codes.
     */
    public static String buildWaitSummary(
            PredictionResult prediction,
            String policyReason,
            int horizonSeconds,
            BigDecimal referencePrice,
            BigDecimal verifiedAtr,
            String rawRiskLevel
    ) {
        if (prediction == null) {
            return "Mevcut piyasa verisi risk eşiklerini karşılamadığı için sistem koruma amaçlı beklemede kalmayı seçti.";
        }
        Map<String, Object> summary = prediction.observedFeatureSummary() != null
                ? prediction.observedFeatureSummary()
                : Map.of();

        String tfLabel = switch (horizonSeconds) {
            case 60 -> "1 Dakika";
            case 900 -> "15 Dakika";
            case 3600 -> "1 Saat";
            case 86400 -> "4 Saat / Günlük";
            case 604800 -> "Günlük / Haftalık";
            default -> horizonSeconds + " sn";
        };
        String regime = switch (extractString(summary, "regime", "CHOPPY")) {
            case "TREND_UP" -> "Yükseliş Eğilimli";
            case "TREND_DOWN" -> "Düşüş Eğilimli";
            case "HIGH_VOL_STRESS" -> "Aşırı Oynaklık Baskısı";
            case "MEAN_REVERSION" -> "Ortalamaya Dönüş";
            default -> "Yatay / Kararsız Piyasa";
        };

        String leanText = "Nötr / Kararsız";
        Double predRet = prediction.predictedLogReturn();
        if (predRet != null && Math.abs(predRet) > 1e-6) {
            leanText = predRet > 0 ? "Hafif Yükseliş (LONG) Eğilimi" : "Hafif Düşüş (SHORT) Eğilimi";
        }

        double atrPct = extractDouble(summary, "atrPct", -1.0);
        double rsi14 = extractDouble(summary, "rsi14", -1.0);
        double conf = extractDouble(summary, "signalConfidence", -1.0);

        StringBuilder sb = new StringBuilder();
        sb.append("Piyasa Gözlemi (").append(tfLabel).append(") | Rejim: ").append(regime)
                .append(" | Kısa Vadeli Yön Eğilimi: ").append(leanText);
        if (atrPct >= 0) {
            sb.append(" | Oynaklık (ATR): %").append(String.format(Locale.US, "%.2f", atrPct * 100.0));
        }
        if (rsi14 >= 0) {
            sb.append(" | RSI(14): ").append(String.format(Locale.US, "%.1f", rsi14));
        }
        if (conf >= 0) {
            sb.append(" | Sinyal Güveni: %").append(Math.round(conf * 100.0));
        }
        if (referencePrice != null && referencePrice.compareTo(BigDecimal.ZERO) > 0) {
            int scale = referencePrice.compareTo(BigDecimal.ONE) < 0 ? 5
                    : (referencePrice.compareTo(BigDecimal.TEN) < 0 ? 4
                    : (referencePrice.compareTo(new BigDecimal("500")) < 0 ? 3 : 2));
            sb.append(". Referans Fiyat: ").append(referencePrice.setScale(scale, RoundingMode.HALF_UP).toPlainString());
            if (verifiedAtr != null && verifiedAtr.compareTo(BigDecimal.ZERO) > 0 && predRet != null && Math.abs(predRet) > 1e-6) {
                BigDecimal slBand = verifiedAtr.multiply(new BigDecimal("1.5"));
                BigDecimal tpBand = verifiedAtr.multiply(new BigDecimal("2.6"));
                boolean leanLong = predRet > 0;
                BigDecimal refSl = (leanLong ? referencePrice.subtract(slBand) : referencePrice.add(slBand))
                        .max(BigDecimal.ONE.movePointLeft(scale))
                        .setScale(scale, RoundingMode.HALF_UP);
                BigDecimal refTp = (leanLong ? referencePrice.add(tpBand) : referencePrice.subtract(tpBand))
                        .max(BigDecimal.ONE.movePointLeft(scale))
                        .setScale(scale, RoundingMode.HALF_UP);
                sb.append(" | İzleme Referans Bandı -> SL: ").append(refSl.toPlainString())
                        .append(" / TP: ").append(refTp.toPlainString());
            }
        }
        String waitCause = switch (policyReason != null ? policyReason : "") {
            case "VOLATILITY_REGIME_UNSAFE" -> "Ani ve yüksek oynaklık riski nedeniyle sermaye koruması önceliklendirildi.";
            case "CHOPPY_MARKET_WAIT" -> "Piyasa yatay seyrettiği için seçili risk seviyesinde güvenli giriş eşiği oluşmadı.";
            case "EDGE_BELOW_COST_HURDLE" -> "Beklenen fiyat hareketi işlem komisyonu ve kayma maliyetini yeterince aşmadığı için bekleme önerildi.";
            default -> "Sinyal güveni seçili risk koruma eşiğinin altında kaldığı için bekleme önerildi.";
        };
        sb.append(". ").append(waitCause);
        if (!"HIGH".equals(normalizeRiskLevel(rawRiskLevel)) && !"VOLATILITY_REGIME_UNSAFE".equals(policyReason)) {
            sb.append(" Daha aktif fırsat ve işlem önerisi görmek için risk seviyesini 'Yüksek (Maks. Kâr)' olarak seçip yeniden analiz başlatabilirsiniz.");
        }
        return sb.toString();
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}

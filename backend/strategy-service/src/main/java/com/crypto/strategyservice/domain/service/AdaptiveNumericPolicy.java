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
        boolean isV4 = reasons.contains("QUANT_MODEL_V4");
        if (reasons.contains("CHOPPY_REGIME") && (isV4 || !"HIGH".equals(riskLevel))) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "CHOPPY_MARKET_WAIT");
        }
        if (reasons.contains("LOW_CONFIDENCE") && (isV4 || !"HIGH".equals(riskLevel))) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "LOW_MODEL_CONFIDENCE");
        }
        if (reasons.contains("MEAN_REVERSION_EXHAUSTION")
                && (isV4 ? (!"HIGH".equals(riskLevel) || signalConfidence < 0.72 || !reasons.contains("ORDER_FLOW_CONFIRMED"))
                         : (!"HIGH".equals(riskLevel) || signalConfidence < 0.58))) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "EXHAUSTION_REVERSAL_RISK");
        }
        if (reasons.contains("HTF_COUNTER_TREND") && reasons.contains("ORDER_FLOW_DIVERGENCE")) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "COUNTER_TREND_FLOW_CONFLICT");
        }
        if (isV4 && reasons.contains("HTF_COUNTER_TREND")
                && !(reasons.contains("TRIPLE_CONFLUENCE_CONFIRMED") && signalConfidence >= 0.66)) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "HTF_COUNTER_TREND_WAIT");
        }
        if (reasons.contains("HTF_COUNTER_TREND") && !"HIGH".equals(riskLevel) && signalConfidence < 0.65) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "HTF_COUNTER_TREND_WAIT");
        }

        double minConfidence = isV4
                ? switch (riskLevel) {
                    case "LOW" -> 0.64;
                    case "HIGH" -> 0.56;
                    default -> 0.60;
                }
                : switch (riskLevel) {
                    case "LOW" -> 0.58;
                    case "HIGH" -> 0.38;
                    default -> 0.54;
                };
        if (signalConfidence < minConfidence) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "CONFIDENCE_BELOW_RISK_THRESHOLD");
        }
        if (isV4 && summary.containsKey("barrierEvR")) {
            double barrierEvR = extractDouble(summary, "barrierEvR", 0.0);
            if (barrierEvR < 0.02) {
                return new NumericPolicy.Outcome("WAIT", null, null, null, "NEGATIVE_NET_EXPECTANCY");
            }
        }

        double predLogRet = prediction.predictedLogReturn();
        double absExpectedReturn = Math.abs(Math.expm1(predLogRet));
        double cost = Math.max(0.0, estimatedRoundTripCost);
        int h = prediction.horizonSeconds();
        double minMove = isV4
                ? switch (riskLevel) {
                    case "LOW" -> Math.max(cost * 3.20, h <= 60 ? 0.0028 : (h <= 900 ? 0.0038 : 0.0050));
                    case "HIGH" -> Math.max(cost * 2.10, h <= 60 ? 0.0018 : (h <= 900 ? 0.0024 : 0.0032));
                    default -> Math.max(cost * 2.60, h <= 60 ? 0.0022 : (h <= 900 ? 0.0030 : 0.0040));
                }
                : switch (riskLevel) {
                    case "LOW" -> Math.max(cost * 2.10, h <= 60 ? 0.0009 : (h <= 900 ? 0.0014 : 0.0018));
                    case "HIGH" -> Math.max(cost * 0.85, h <= 60 ? 0.00040 : 0.00060);
                    default -> Math.max(cost * 1.65, h <= 60 ? 0.0007 : (h <= 900 ? 0.0011 : 0.0015));
                };
        if (absExpectedReturn < minMove) {
            return new NumericPolicy.Outcome("WAIT", null, null, null, "EDGE_BELOW_COST_HURDLE");
        }

        boolean isLong = predLogRet > 0.0;
        String action = isLong ? "LONG" : "SHORT";
        CandidateBracket bracket = computeCandidateBracket(
                prediction,
                isLong,
                entryPrice,
                verifiedAtr,
                riskLevel,
                sessionMaxLeverage,
                instrumentMaxLeverage,
                cost
        );

        return new NumericPolicy.Outcome(
                action,
                bracket.stopLoss(),
                bracket.takeProfit(),
                bracket.suggestedLeverage(),
                "QUANT_ADAPTIVE_POLICY"
        );
    }

    private record CandidateBracket(
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            BigDecimal suggestedLeverage
    ) {
    }

    private static CandidateBracket computeCandidateBracket(
            PredictionResult prediction,
            boolean isLong,
            BigDecimal entryPrice,
            BigDecimal verifiedAtr,
            String riskLevel,
            int sessionMaxLeverage,
            int instrumentMaxLeverage,
            double estimatedRoundTripCost
    ) {
        Map<String, Object> summary = prediction.observedFeatureSummary() != null
                ? prediction.observedFeatureSummary()
                : Map.of();
        List<String> reasons = prediction.reasonCodes() != null ? prediction.reasonCodes() : List.of();
        double signalConfidence = extractDouble(summary, "signalConfidence", 0.55);
        double efficiencyRatio = extractDouble(summary, "efficiencyRatio", 0.25);
        double volRegimeRatio = extractDouble(summary, "volRegimeRatio", 1.0);
        boolean isV4 = reasons.contains("QUANT_MODEL_V4");
        int h = prediction.horizonSeconds();
        double cost = Math.max(0.0, estimatedRoundTripCost);

        double entryD = entryPrice.doubleValue();
        double atrFrac = clamp(verifiedAtr.doubleValue() / entryD, 0.0012, 0.12);
        double modelVol = prediction.volatilityEstimate() != null
                ? Math.max(0.0, prediction.volatilityEstimate())
                : atrFrac;
        double effVolFrac = Math.max(atrFrac, Math.min(modelVol * 0.65, 0.10));

        double horizonMinSlPct = isV4
                ? (h <= 60 ? 0.0032
                : (h <= 900 ? 0.0048
                : (h <= 3600 ? 0.0072
                : (h <= 86400 ? 0.0125 : 0.0220))))
                : (h <= 60 ? 0.0032
                : (h <= 900 ? 0.0050
                : (h <= 3600 ? 0.0080
                : (h <= 86400 ? 0.0140 : 0.0240))));

        double slMult = isV4
                ? switch (riskLevel) {
                    case "LOW" -> 1.55;
                    case "HIGH" -> 1.85;
                    default -> 1.70;
                }
                : switch (riskLevel) {
                    case "LOW" -> 2.00;
                    case "HIGH" -> 2.30;
                    default -> 2.15;
                };
        if (volRegimeRatio > 1.80) {
            slMult *= 1.15;
        }
        double stopDistPct = clamp(effVolFrac * slMult, horizonMinSlPct, 0.085);

        double tpDistPct;
        if (isV4) {
            // Pozitif beklenti: komisyon maliyetini rahatca asan minimum TP + TP > SL (R:R >= 1.45..2.25)
            double minNetTp = Math.max(
                    cost * 3.80,
                    h <= 60 ? 0.0042 : (h <= 900 ? 0.0056 : (h <= 3600 ? 0.0090 : (h <= 86400 ? 0.0170 : 0.0320)))
            );
            double baseRr = switch (riskLevel) {
                case "LOW" -> 1.65;
                case "HIGH" -> 1.80;
                default -> 1.70;
            };
            double rrBoost = 0.22 * clamp((signalConfidence - 0.56) / 0.34, 0.0, 1.0)
                    + 0.14 * clamp(efficiencyRatio, 0.0, 1.0);
            if (reasons.contains("TRIPLE_CONFLUENCE_CONFIRMED") || reasons.contains("HTF_TREND_CONFIRMED")) {
                rrBoost += 0.15;
            }
            if (reasons.contains("MEAN_REVERSION_EXHAUSTION")) {
                rrBoost -= 0.15;
            }
            double rrRatio = clamp(baseRr + rrBoost, 1.45, 2.25);
            double targetByRr = stopDistPct * rrRatio;
            tpDistPct = clamp(Math.max(targetByRr, minNetTp), minNetTp, 0.22);
        } else {
            double baseRr = switch (riskLevel) {
                case "LOW" -> 1.35;
                case "HIGH" -> 1.55;
                default -> 1.45;
            };
            double rrBoost = 0.18 * clamp((signalConfidence - 0.50) / 0.40, 0.0, 1.0)
                    + 0.12 * clamp(efficiencyRatio, 0.0, 1.0);
            if (reasons.contains("TRIPLE_CONFLUENCE_CONFIRMED") || reasons.contains("HTF_TREND_CONFIRMED")) {
                rrBoost += 0.12;
            }
            if (reasons.contains("MEAN_REVERSION_EXHAUSTION")) {
                rrBoost -= 0.15;
            }
            double rrRatio = clamp(baseRr + rrBoost, 1.25, 1.95);
            double minProfitableTp = Math.max(stopDistPct * 1.25, cost * 3.50);
            tpDistPct = clamp(Math.max(stopDistPct * rrRatio, minProfitableTp), minProfitableTp, 0.22);
        }

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
            case "LOW" -> 3.20;
            case "HIGH" -> 2.40;
            default -> 2.75;
        };
        int liqSafeMax = (int) Math.floor(1.0 / (stopDistPct * safetyFactor + 0.012));
        int horizonLevCap = switch (riskLevel) {
            case "LOW" -> h <= 60 ? 3 : (h <= 3600 ? 5 : 4);
            case "HIGH" -> h <= 60 ? 8 : (h <= 900 ? 10 : (h <= 3600 ? 12 : (h <= 86400 ? 10 : 7)));
            default -> h <= 60 ? 5 : (h <= 900 ? 7 : (h <= 3600 ? 8 : (h <= 86400 ? 6 : 5)));
        };
        int effSessionCap = Math.max(1, sessionMaxLeverage);
        int effInstCap = Math.max(1, instrumentMaxLeverage);
        int hardCap = Math.max(1, Math.min(Math.min(effSessionCap, effInstCap), Math.min(horizonLevCap, Math.max(1, liqSafeMax))));

        double baseMarginRisk = switch (riskLevel) {
            case "LOW" -> 0.045;
            case "HIGH" -> 0.115;
            default -> 0.075;
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

        return new CandidateBracket(stopLoss, takeProfit, BigDecimal.valueOf(optimalLeverage));
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
        return buildWaitSummary(
                prediction,
                policyReason,
                horizonSeconds,
                referencePrice,
                verifiedAtr,
                rawRiskLevel,
                20,
                125,
                0.0012
        );
    }

    public static String buildWaitSummary(
            PredictionResult prediction,
            String policyReason,
            int horizonSeconds,
            BigDecimal referencePrice,
            BigDecimal verifiedAtr,
            String rawRiskLevel,
            int sessionMaxLeverage,
            int instrumentMaxLeverage,
            double estimatedRoundTripCost
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

        Double predRet = prediction.predictedLogReturn();
        double rsi14 = extractDouble(summary, "rsi14", -1.0);
        boolean leanLong = (predRet != null && Math.abs(predRet) > 1e-6)
                ? (predRet > 0)
                : (rsi14 < 0 || rsi14 >= 50.0);
        String leanText = leanLong
                ? "Hafif Yükseliş (LONG) Eğilimi"
                : "Hafif Düşüş (SHORT) Eğilimi";

        double atrPct = extractDouble(summary, "atrPct", -1.0);
        double conf = extractDouble(summary, "signalConfidence", -1.0);
        String riskLevel = normalizeRiskLevel(rawRiskLevel);
        if (riskLevel == null) {
            riskLevel = "MEDIUM";
        }

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
            if (verifiedAtr != null && verifiedAtr.compareTo(BigDecimal.ZERO) > 0) {
                CandidateBracket bracket = computeCandidateBracket(
                        prediction,
                        leanLong,
                        referencePrice,
                        verifiedAtr,
                        riskLevel,
                        sessionMaxLeverage,
                        instrumentMaxLeverage,
                        estimatedRoundTripCost
                );
                sb.append(" | ATR: ").append(verifiedAtr.setScale(scale, RoundingMode.HALF_UP).toPlainString())
                        .append(" | Önerilen Kaldıraç: ").append(bracket.suggestedLeverage().toPlainString()).append("x")
                        .append(" | İzleme Referans Bandı -> SL: ").append(bracket.stopLoss().toPlainString())
                        .append(" / TP: ").append(bracket.takeProfit().toPlainString());
            }
        }
        String waitCause = switch (policyReason != null ? policyReason : "") {
            case "VOLATILITY_REGIME_UNSAFE" -> "Ani ve yüksek oynaklık riski nedeniyle sermaye koruması önceliklendirildi.";
            case "CHOPPY_MARKET_WAIT" -> "Piyasa yatay ve yönsüz seyrettiği için düşük kaliteli işlem açılmadı.";
            case "NEGATIVE_NET_EXPECTANCY" -> "Komisyon ve stop-loss oranı sonrası net beklenen getiri pozitif eşiği geçmediği için bekleme önerildi.";
            case "HTF_COUNTER_TREND_WAIT" -> "Ana üst zaman dilimi trendine ters yön riski nedeniyle bekleme önerildi.";
            case "EDGE_BELOW_COST_HURDLE" -> "Beklenen fiyat hareketi işlem komisyonu ve kayma maliyetini yeterince aşmadığı için bekleme önerildi.";
            default -> "Sinyal güveni seçili risk koruma eşiğinin altında kaldığı için bekleme önerildi.";
        };
        sb.append(". ").append(waitCause);
        if (!"HIGH".equals(riskLevel) && !"VOLATILITY_REGIME_UNSAFE".equals(policyReason)) {
            sb.append(" Daha aktif fırsat ve işlem önerisi görmek için risk seviyesini 'Yüksek (Maks. Kâr)' olarak seçip yeniden analiz başlatabilirsiniz.");
        }
        return sb.toString();
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}

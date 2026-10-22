package com.crypto.strategyservice.domain.service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Sorumluluk: Açıklama servisi zaman aşımına uğradığında veya erişilemez olduğunda
 * yalnız doğrulanmış reasonCodes ve observedFeatureSummary alanlarından deterministik
 * şablon açıklama üretir. Uydurma kaynak/sayfa veya yeni fiyat seviyesi üretmez.
 */
public final class ExplanationTemplate {

    private ExplanationTemplate() {
    }

    public static String render(
            String action,
            String policyReason,
            List<String> reasonCodes,
            Map<String, Object> observedFeatureSummary
    ) {
        if (action == null || action.isBlank() || policyReason == null || policyReason.isBlank()) {
            throw new IllegalArgumentException("INVALID_TEMPLATE_INPUT");
        }
        String reasons = (reasonCodes == null || reasonCodes.isEmpty())
                ? "NONE"
                : String.join(",", reasonCodes);

        String features;
        if (observedFeatureSummary == null || observedFeatureSummary.isEmpty()) {
            features = "NONE";
        } else {
            Map<String, Object> sorted = new TreeMap<>(observedFeatureSummary);
            features = sorted.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(","));
        }

        return "TEMPLATE[action=" + action
                + ";policy=" + policyReason
                + ";reasons=" + reasons
                + ";features=" + features + "]";
    }
}


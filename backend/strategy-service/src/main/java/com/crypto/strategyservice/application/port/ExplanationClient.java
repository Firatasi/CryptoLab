package com.crypto.strategyservice.application.port;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sorumluluk: Doğrulanmış sayısal karar ve özellik özetinden açıklama üretme portudur.
 * Fiyat seviyesi veya işlem yönü belirleme yetkisi yoktur.
 */
public interface ExplanationClient {

    record Request(
            UUID analysisId,
            String action,
            String policyReason,
            List<String> reasonCodes,
            Map<String, Object> observedFeatureSummary
    ) {
        public Request {
            reasonCodes = List.copyOf(reasonCodes);
            observedFeatureSummary = Map.copyOf(observedFeatureSummary);
        }
    }

    String explain(Request request);
}


package com.crypto.strategyservice.application.port;

import com.crypto.strategyservice.domain.model.PredictionResult;
import com.crypto.strategyservice.domain.service.NumericPolicy;

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
            Map<String, Object> observedFeatureSummary,
            UUID modelBundleId,
            PredictionResult predictionResult,
            NumericPolicy.Outcome policyOutcome
    ) {
        public Request(
                UUID analysisId,
                String action,
                String policyReason,
                List<String> reasonCodes,
                Map<String, Object> observedFeatureSummary
        ) {
            this(analysisId, action, policyReason, reasonCodes, observedFeatureSummary, null, null, null);
        }

        public Request {
            reasonCodes = List.copyOf(reasonCodes);
            observedFeatureSummary = Map.copyOf(observedFeatureSummary);
        }
    }

    String explain(Request request);
}


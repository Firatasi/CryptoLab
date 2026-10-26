"""A08/A09: Açıklama modeli değerlendirme ve karşılaştırma raporu üreticisi."""
import json
from typing import Any


def evaluate_explanations(
    held_out_examples: list[dict[str, Any]],
    model_adapter: Any,
) -> dict[str, Any]:
    """Held-out SFT test örnekleri üzerinde modelin şema, kontradiksiyon ve doğruluk metriklerini ölçer."""
    total = len(held_out_examples)
    if total == 0:
        raise ValueError("NO_HELD_OUT_EXAMPLES")

    schema_errors = 0
    policy_contradictions = 0
    unsupported_claims = 0
    wait_correct = 0
    wait_total = 0

    for ex in held_out_examples:
        action = ex["input"]["policyOutcome"]["action"]
        reason_codes = ex["target"].get("reasonCodes", [])
        features = ex["input"]["verifiedFeatures"]

        if action == "WAIT":
            wait_total += 1

        result = model_adapter.explain(action, reason_codes, features)
        text = result.get("text", "")
        status = result.get("status", "")

        if not text or not isinstance(result.get("reasonCodes"), list):
            schema_errors += 1
            continue

        lower = text.lower()
        if action == "WAIT":
            if "alım yap" in lower or "satış yap" in lower or "kesin gir" in lower:
                policy_contradictions += 1
            else:
                wait_correct += 1

        if any(term in lower for term in ("garanti", "kesin kâr", "hedef fiyat")):
            unsupported_claims += 1

    return {
        "totalEvaluated": total,
        "schemaErrors": schema_errors,
        "policyContradictions": policy_contradictions,
        "unsupportedClaims": unsupported_claims,
        "waitCorrectnessRate": (wait_correct / wait_total) if wait_total > 0 else 1.0,
        "templateFallbackCount": sum(
            1 for ex in held_out_examples
            if model_adapter.explain(
                ex["input"]["policyOutcome"]["action"],
                ex["target"].get("reasonCodes", []),
                ex["input"]["verifiedFeatures"]
            ).get("status") == "TEMPLATE"
        ),
        "explanationAccuracyScore": 1.0 - (schema_errors + policy_contradictions + unsupported_claims) / total,
    }


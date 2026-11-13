"""A07.3: Kural tabanlı veya teacher adapter ile SFT taslak (draft) üretici portu.
Kural: Üretilen örnekler daima DRAFT durumundadır; insan incelemesi olmadan ACCEPTED yapılamaz.
"""
from typing import Any
from uuid import uuid4


class DraftGenerator:
    """Doğrulanmış özellik ve politika çıktılarından aday açıklama taslakları üretir."""

    def __init__(self, mode: str = "rule-based"):
        self.mode = mode

    def generate_draft(
        self,
        task: str,
        verified_features: dict[str, Any],
        policy_outcome: dict[str, Any],
        source_spans: list[dict[str, Any]],
        split_group: str,
        language: str = "tr",
    ) -> dict[str, Any]:
        """Aday bir eğitim örneği taslağı oluşturur."""
        action = policy_outcome.get("action", "WAIT")
        policy_reason = policy_outcome.get("policyReason", "HOLD")

        if action == "WAIT":
            explanation = (
                f"Sistem bekleme ({action}) durumundadır. "
                f"Gerekçe: {policy_reason}. Doğrulanmış veriler işlem açmak için yeterli güven eşiğini sağlamamaktadır."
            )
            reason_codes = [policy_reason, "WAIT_OBSERVED"]
        elif action in ("LONG", "SHORT"):
            direction = "yükseliş (LONG)" if action == "LONG" else "düşüş (SHORT)"
            explanation = (
                f"Doğrulanmış piyasa özellikleri {direction} yönlü sinyali desteklemektedir. "
                f"Gerekçe: {policy_reason}. Risk ve sermaye tahsisi motoru parametreleri esastır."
            )
            reason_codes = [policy_reason, f"{action}_SIGNAL"]
        else:
            explanation = f"Politika durumu: {action} ({policy_reason})."
            reason_codes = [policy_reason]

        return {
            "exampleId": str(uuid4()),
            "task": task,
            "input": {
                "verifiedFeatures": verified_features,
                "policyOutcome": policy_outcome,
                "language": language,
            },
            "target": {
                "explanation": explanation,
                "reasonCodes": reason_codes,
            },
            "reviewStatus": "DRAFT",
            "reviewerId": None,
            "sourceSpans": source_spans,
            "splitGroup": split_group,
        }


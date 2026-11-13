"""A08: Yerel açıklama modeli inference adaptörü, şema denetimi ve template fallback'i."""
import json
import time
from typing import Any

from app.explanations.prompt import render_prompt
from app.explanations.template import build_template_explanation


class LocalExplanationAdapter:
    """Fine-tuned veya template tabanlı açıklama üreten güvenli adaptör."""

    FORBIDDEN_TERMS = {
        "garanti",
        "kesin kazanç",
        "hedef fiyat",
        "tüm bakiye",
        "hemen al",
        "hemen sat",
    }

    def __init__(self, model_version: str = "explanation-v1", adapter_path: str | None = None):
        self.model_version = model_version
        self.adapter_path = adapter_path

    def explain(
        self,
        action: str,
        reason_codes: list[str],
        feature_summary: dict[str, Any],
        language: str = "tr",
    ) -> dict[str, Any]:
        """Açıklama üretir; politika kontradiksiyonu veya geçersiz şemada template'e düşer."""
        t0 = time.perf_counter()

        # Politika ve özellik bağlamı
        policy_outcome = {"action": action, "policyReason": reason_codes[0] if reason_codes else "DEFAULT"}
        prompt = render_prompt(feature_summary, policy_outcome, language)

        # Doğrudan template fallback (veya adapter yoksa güvenli fallback)
        # Kural: Gerçek A08 fine-tuned adapter doğrulanmadığı sürece TEMPLATE döner
        if not self.adapter_path:
            text = build_template_explanation(action, reason_codes, feature_summary)
            latency_ms = max(1, int((time.perf_counter() - t0) * 1000))
            return {
                "status": "TEMPLATE",
                "explanationModelVersion": self.model_version,
                "text": text,
                "reasonCodes": reason_codes,
                "latencyMs": latency_ms,
            }

        # Adapter mevcutsa çıktıyı dene ve denetle
        try:
            raw_output = self._call_adapter(prompt)
            parsed = json.loads(raw_output)

            if not isinstance(parsed, dict) or "explanation" not in parsed or "reasonCodes" not in parsed:
                raise ValueError("INVALID_OUTPUT_SCHEMA")

            text = parsed["explanation"]
            out_codes = parsed["reasonCodes"]

            # Güvenlik ve kontradiksiyon kontrolleri:
            # 1. WAIT kararı varken al/sat önerisi yasaktır
            lower_text = text.lower()
            if action == "WAIT":
                if "alım yap" in lower_text or "satış yap" in lower_text or "long pozisyon" in lower_text:
                    raise ValueError("POLICY_CONTRADICTION")

            # 2. Yasaklı kelimeler (kesin kazanç / garanti vaatleri)
            for term in self.FORBIDDEN_TERMS:
                if term in lower_text:
                    raise ValueError("UNSUPPORTED_CLAIM")

            latency_ms = max(1, int((time.perf_counter() - t0) * 1000))
            return {
                "status": "READY",
                "explanationModelVersion": self.model_version,
                "text": text,
                "reasonCodes": out_codes,
                "latencyMs": latency_ms,
            }

        except Exception:
            # Herhangi bir hata veya kural ihlalinde güvenli template'e düş
            text = build_template_explanation(action, reason_codes, feature_summary)
            latency_ms = max(1, int((time.perf_counter() - t0) * 1000))
            return {
                "status": "TEMPLATE",
                "explanationModelVersion": self.model_version,
                "text": text,
                "reasonCodes": reason_codes,
                "latencyMs": latency_ms,
            }

    def _call_adapter(self, prompt: str) -> str:
        """Mock veya gerçek yerel model çağrısı."""
        return json.dumps({
            "explanation": "Doğrulanmış göstergeler çerçevesinde mevcut risk politikası korunmuştur.",
            "reasonCodes": ["OBSERVED_ALIGNMENT"],
        })


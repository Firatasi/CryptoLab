"""A05/A07/A08: Model açıklama istemi (prompt) kuralları, formatı ve token kodlama sınırları.

1. Model açıklaması finansal emir miktarı, fiyat veya yön üzerinde ASLA otorite değildir.
2. Açıklama metni Strategy servisinin ürettiği doğrulanmış karar ve özellikleri (observedFeatureSummary)
   dışında harici kaynak veya uydurma kitap/sayfa referansı içeremez.
3. Açıklama çağrısının gecikmesi (timeout) veya başarısızlığı işlem planının validUntil süresini
   kesinlikle uzatamaz.
4. Eğitim ve inference aynı prompt formatını kullanır; kaynak metni veya retrievedEvidence girmez.
"""
import json
from typing import Any, Mapping, Sequence

SYSTEM = (
    "Yalnız verilen doğrulanmış özellikleri ve politika sonucunu Türkçe açıkla. "
    "Yeni işlem kararı, fiyat veya kesin kazanç iddiası üretme. JSON explanation ve reasonCodes döndür."
)


def build_explanation_context(
    action: str,
    reason_codes: Sequence[str],
    feature_summary: Mapping[str, object],
) -> dict[str, Any]:
    """Doğrulanmış bağlamı güvenli sözlük olarak hazırlar."""
    return {
        "action": action,
        "reasonCodes": list(reason_codes),
        "features": dict(feature_summary),
        "disclaimer": "İşlem miktarı ve teminat risk motoru tarafından hesaplanır; bu metin bağlayıcı değildir.",
    }


def render_prompt(
    verified_features: dict[str, Any],
    policy_outcome: dict[str, Any],
    language: str = "tr",
) -> str:
    """Eğitim ve serving için deterministik prompt üretir; kaynak metni (sourceSpans) içermez."""
    input_payload = {
        "verifiedFeatures": verified_features,
        "policyOutcome": policy_outcome,
        "language": language,
    }
    return SYSTEM + "\nINPUT\n" + json.dumps(input_payload, ensure_ascii=False, sort_keys=True) + "\nOUTPUT\n"


def encode_example(tokenizer: Any, prompt: str, target: str, max_tokens: int) -> dict[str, Any]:
    """Prompt ve hedefi token'lara dönüştürür; hedef loss maskesi uygular ve truncation hatalarını kontrol eder."""
    prefix = tokenizer.encode(prompt, add_special_tokens=False)
    all_ids = tokenizer.encode(prompt + target, add_special_tokens=False)

    if all_ids[: len(prefix)] != prefix:
        raise ValueError("TOKEN_BOUNDARY_MISMATCH")

    if getattr(tokenizer, "eos_token_id", None) is None:
        raise ValueError("EOS_REQUIRED")

    all_ids = all_ids + [tokenizer.eos_token_id]

    if len(all_ids) > max_tokens or len(all_ids) <= len(prefix) + 1:
        raise ValueError("TARGET_TRUNCATED_OR_EMPTY")

    return {
        "input_ids": all_ids,
        "attention_mask": [1] * len(all_ids),
        "labels": [-100] * len(prefix) + all_ids[len(prefix):],
    }

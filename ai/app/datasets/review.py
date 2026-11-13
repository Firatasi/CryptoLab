"""A07: Kontrollü eğitim örnekleri inceleme ve freeze kuralları.
Yalnız ACCEPTED örnekler freeze edilir; belge ailesi sızıntıyı önlemek için split'ten önce gruplanır.
"""
import hashlib
import json
from typing import Any

TASKS = {
    "CONCEPT_EXPLANATION",
    "RISK_REASONING",
    "ABSTENTION",
    "FAILURE_ANALYSIS",
    "OUTPUT_SCHEMA",
}


def freeze_examples(
    examples: list[dict[str, Any]],
    split_by_family: dict[str, str],
) -> list[dict[str, Any]]:
    """İncelenmiş ve onaylanmış SFT örneklerini sabitler, kopyaları eler ve split'lere dağıtır."""
    selected = []
    seen: dict[str, str] = {}

    for e in examples:
        if e.get("reviewStatus") != "ACCEPTED":
            continue

        if not e.get("reviewerId") or not e.get("sourceSpans"):
            raise ValueError("REVIEW_PROVENANCE_REQUIRED")

        if e.get("task") not in TASKS:
            raise ValueError("TASK_NOT_FOR_SFT")

        family = e.get("splitGroup")
        if not family or family not in split_by_family:
            raise ValueError("INVALID_SPLIT")

        split = split_by_family[family]
        if split not in ("train", "validation", "test"):
            raise ValueError("INVALID_SPLIT")

        input_keys = set(e.get("input", {}).keys())
        if input_keys - {"verifiedFeatures", "policyOutcome", "language"}:
            raise ValueError("TRAIN_SERVE_SKEW")

        # Exact duplicate kontrolü
        payload = json.dumps(
            {"input": e["input"], "target": e["target"]},
            sort_keys=True,
            ensure_ascii=False,
        )
        h = hashlib.sha256(payload.encode("utf-8")).hexdigest()

        if h in seen:
            if seen[h] != split:
                raise ValueError("CROSS_SPLIT_DUPLICATE")
            continue

        seen[h] = split
        item = dict(e)
        item["split"] = split
        item["contentHash"] = f"sha256:{h}"
        selected.append(item)

    if not selected:
        raise ValueError("NO_ACCEPTED_EXAMPLES")

    return sorted(selected, key=lambda x: str(x["exampleId"]))


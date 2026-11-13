"""A07.6: Onaylanmış örneklerin dondurulması ve değişmez (immutable) veri seti manifesti servisi."""
import hashlib
import json
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

from app.datasets.review import freeze_examples


class FreezeService:
    """ACCEPTED örnekleri sıralı JSONL dosyasına yazar, SHA256 doğrular ve manifest üretir."""

    def __init__(self, artifact_store: Any):
        self.artifacts = artifact_store

    def freeze_dataset(
        self,
        dataset_id: UUID,
        owner_id: UUID,
        examples: list[dict[str, Any]],
        split_by_family: dict[str, str],
        dataset_name: str = "sft-explanation-v1",
    ) -> dict[str, Any]:
        """Örnekleri filtreler, JSONL içeriğini üretir ve artifact store'a yazar."""
        frozen_examples = freeze_examples(examples, split_by_family)

        lines = [
            json.dumps(ex, sort_keys=True, ensure_ascii=False)
            for ex in frozen_examples
        ]
        jsonl_content = "\n".join(lines).encode("utf-8")

        artifact_ref = self.artifacts.put(jsonl_content)
        manifest_payload = {
            "datasetId": str(dataset_id),
            "ownerId": str(owner_id),
            "name": dataset_name,
            "kind": "SFT_EXPLANATION",
            "artifactRef": artifact_ref,
            "totalExamples": len(frozen_examples),
            "splitCounts": {
                "train": sum(1 for e in frozen_examples if e["split"] == "train"),
                "validation": sum(1 for e in frozen_examples if e["split"] == "validation"),
                "test": sum(1 for e in frozen_examples if e["split"] == "test"),
            },
            "taskCounts": {
                task: sum(1 for e in frozen_examples if e["task"] == task)
                for task in {e["task"] for e in frozen_examples}
            },
        }

        manifest_hash = "sha256:" + hashlib.sha256(
            json.dumps(manifest_payload, sort_keys=True).encode("utf-8")
        ).hexdigest()

        manifest_payload["manifestHash"] = manifest_hash
        return manifest_payload


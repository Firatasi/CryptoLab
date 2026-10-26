"""A07: İnceleme örnekleri ve optimistic locking repository implementasyonu."""
from typing import Any
from uuid import UUID


class ExampleRepository:
    """Eğitim örnekleri için optimistic locking ve durum geçişi yönetimi."""

    VALID_TRANSITIONS = {
        "DRAFT": {"IN_REVIEW", "REJECTED"},
        "IN_REVIEW": {"ACCEPTED", "REJECTED", "DRAFT"},
        "ACCEPTED": {"IN_REVIEW", "REJECTED"},
        "REJECTED": {"IN_REVIEW"},
    }

    def __init__(self, db_conn=None):
        self.db = db_conn
        self._examples: dict[UUID, dict[str, Any]] = {}

    def save_example(
        self,
        example_id: UUID,
        owner_id: UUID,
        body: dict[str, Any],
        review_status: str = "DRAFT",
        version: int = 1,
    ) -> dict[str, Any]:
        record = {
            "id": example_id,
            "owner_id": owner_id,
            "body": body,
            "review_status": review_status,
            "version": version,
        }
        self._examples[example_id] = record
        return record

    def get_example(self, example_id: UUID) -> dict[str, Any] | None:
        return self._examples.get(example_id)

    def list_examples(self, owner_id: UUID, is_admin: bool = False) -> list[dict[str, Any]]:
        results = []
        for e in self._examples.values():
            if is_admin or e["owner_id"] == owner_id:
                results.append(e)
        return sorted(results, key=lambda x: str(x["id"]))

    def update_review(
        self,
        example_id: UUID,
        trusted_actor_id: UUID,
        is_admin: bool,
        expected_version: int,
        new_status: str,
        corrected_target: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Optimistic locking ile inceleme durumunu ve düzeltilmiş hedefi günceller."""
        example = self._examples.get(example_id)
        if not example:
            raise ValueError("EXAMPLE_NOT_FOUND")

        if not is_admin and example["owner_id"] != trusted_actor_id:
            raise PermissionError("FORBIDDEN")

        if example["version"] != expected_version:
            raise ValueError("VERSION_CONFLICT")

        current_status = example["review_status"]
        allowed = self.VALID_TRANSITIONS.get(current_status, set())
        if new_status not in allowed:
            raise ValueError(f"INVALID_STATUS_TRANSITION: {current_status} -> {new_status}")

        body = dict(example["body"])
        body["reviewStatus"] = new_status
        body["reviewerId"] = str(trusted_actor_id)
        if corrected_target is not None:
            body["target"] = corrected_target

        example["review_status"] = new_status
        example["body"] = body
        example["version"] += 1
        return example


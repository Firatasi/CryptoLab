"""A06: Eğitim kaynakları ve sayfaları için repository implementasyonu."""
from typing import Any
from uuid import UUID


class SourceRepository:
    """Veritabanı veya bellek-içi kaynak deposu adaptörü."""

    def __init__(self, db_conn=None):
        self.db = db_conn
        self._sources: dict[UUID, dict[str, Any]] = {}
        self._versions: dict[UUID, dict[str, Any]] = {}
        self._pages: dict[tuple[UUID, int], dict[str, Any]] = {}

    def save_source(
        self,
        source_id: UUID,
        owner_id: UUID,
        title: str,
        rights: str,
        state: str = "IN_REVIEW",
        version: int = 1,
    ) -> dict[str, Any]:
        record = {
            "id": source_id,
            "owner_id": owner_id,
            "title": title,
            "rights": rights,
            "state": state,
            "version": version,
            "deleted": False,
        }
        self._sources[source_id] = record
        return record

    def save_version(
        self,
        version_id: UUID,
        source_id: UUID,
        content_hash: str,
        parser_version: str,
        artifact_ref: str,
    ) -> dict[str, Any]:
        record = {
            "id": version_id,
            "source_id": source_id,
            "content_hash": content_hash,
            "parser_version": parser_version,
            "artifact_ref": artifact_ref,
            "deleted": False,
        }
        self._versions[version_id] = record
        return record

    def save_pages(self, version_id: UUID, pages: list[dict[str, Any]]) -> None:
        for p in pages:
            self._pages[(version_id, p["page"])] = p

    def get_source(self, source_id: UUID) -> dict[str, Any] | None:
        s = self._sources.get(source_id)
        if s and not s.get("deleted", False):
            return s
        return None

    def list_sources(self, actor_id: UUID, is_admin: bool = False) -> list[dict[str, Any]]:
        results = []
        for s in self._sources.values():
            if s.get("deleted", False):
                continue
            if is_admin or s["owner_id"] == actor_id:
                results.append(s)
        return sorted(results, key=lambda x: str(x["id"]))

    def get_page(self, source_id: UUID, page_number: int, actor_id: UUID, is_admin: bool = False) -> dict[str, Any] | None:
        source = self.get_source(source_id)
        if not source:
            return None
        if not is_admin and source["owner_id"] != actor_id:
            raise PermissionError("FORBIDDEN")

        # Kaynağa ait en son versiyonu bul
        source_versions = [v for v in self._versions.values() if v["source_id"] == source_id and not v.get("deleted")]
        if not source_versions:
            return None
        latest_version = source_versions[-1]
        return self._pages.get((latest_version["id"], page_number))

    def mark_source_deleted(self, source_id: UUID, expected_version: int) -> dict[str, Any]:
        source = self._sources.get(source_id)
        if not source:
            raise ValueError("SOURCE_NOT_FOUND")
        if source["version"] != expected_version:
            raise ValueError("VERSION_CONFLICT")

        source["deleted"] = True
        source["version"] += 1
        source["state"] = "DELETED"
        return source


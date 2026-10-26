"""A06/A09: Kaynak silme (soft-delete), lineage işaretleme ve unlearning sınırları."""
from typing import Any
from uuid import UUID


class SourceDeleteService:
    """Eğitim kaynağını güvenli biçimde silen ve lineage etkisini işaretleyen servis."""

    def __init__(self, source_repository: Any):
        self.repo = source_repository

    def delete_source(
        self,
        actor_id: UUID,
        is_admin: bool,
        source_id: UUID,
        expected_version: int,
    ) -> dict[str, Any]:
        """Kaynağı siler, versiyonunu artırır ve bağlı model lineage'ını IMPACTED olarak işaretler.
        
        Kural: Eğitilmiş ağırlıktan bilginin anında silindiği (unlearning) iddia edilmez;
        ham erişim kapatılır, dataset ve model soykütüğünde etki kayıt altına alınır.
        """
        source = self.repo.get_source(source_id)
        if not source:
            raise ValueError("SOURCE_NOT_FOUND")

        if not is_admin and source["owner_id"] != actor_id:
            raise PermissionError("FORBIDDEN")

        if source["version"] != expected_version:
            raise ValueError("VERSION_CONFLICT")

        updated = self.repo.mark_source_deleted(source_id, expected_version)
        return {
            "sourceId": str(source_id),
            "status": "DELETED",
            "version": updated["version"],
            "lineageImpact": "SOURCE_MARKED_DELETED_RETRAIN_REQUIRED_FOR_FULL_REMOVAL",
        }


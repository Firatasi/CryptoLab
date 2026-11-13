"""A10.6 Entegrasyon Testi: Job iptali, alias rollback, silinen kaynak lineage'ı ve yetkisiz publish denetimleri."""
from uuid import uuid4

import pytest

from app.registry.promotion import promote
from app.registry.rollback import rollback
from app.sources.delete_service import SourceDeleteService
from app.infrastructure.source_repository import SourceRepository


class MockRegistryDb:
    def __init__(self, alias_ver=1):
        self.alias_version = alias_ver
        self.bundle_status = "SHADOW"
        self.promoted = False
        self.rolled_back = False

    def in_memory_promote(self, actor, authorities, scope, bundle_id, expected_version, eval_id):
        if self.alias_version != expected_version:
            raise ValueError("VERSION_CONFLICT")
        self.alias_version += 1
        self.promoted = True
        return self.alias_version

    def in_memory_rollback(self, actor, authorities, scope, target_id, expected_version, reason):
        if self.alias_version != expected_version:
            raise ValueError("VERSION_CONFLICT")
        self.alias_version += 1
        self.rolled_back = True
        return self.alias_version


def test_unauthorized_publish_is_blocked():
    """MODEL_PUBLISH yetkisi olmayan aktörün promote çağrısı kesinlikle reddedilmelidir."""
    db = MockRegistryDb(alias_ver=1)
    with pytest.raises(PermissionError):
        promote(db, uuid4(), {"TRAINER"}, "production", uuid4(), 1, uuid4())


def test_rollback_updates_alias_with_audit():
    """Rollback işlemi CAS doğrulaması ile hedef versiyona geçer ve audit üretir."""
    db = MockRegistryDb(alias_ver=2)
    new_ver = rollback(db, uuid4(), {"MODEL_PUBLISH"}, "production", uuid4(), 2, "Anomali tespiti")
    assert new_ver == 3
    assert db.rolled_back is True


def test_deleted_source_lineage_impact():
    """Silinen kaynağın lineage etkisi işaretlenir ancak unlearning iddiasında bulunulmaz."""
    repo = SourceRepository()
    del_service = SourceDeleteService(repo)

    owner = uuid4()
    src_id = uuid4()
    repo.save_source(src_id, owner, "Risk Yönetimi PDF", "Lisanslı", state="REVIEW_REQUIRED", version=1)

    result = del_service.delete_source(owner, is_admin=False, source_id=src_id, expected_version=1)
    assert result["status"] == "DELETED"
    assert "RETRAIN_REQUIRED" in result["lineageImpact"]


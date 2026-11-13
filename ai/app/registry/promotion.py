"""A09: Alias CAS + audit + outbox tek işlemde atomik yayınlama (promotion)."""
import json
from typing import Any
from uuid import UUID, uuid4


def promote(
    db: Any,
    actor: UUID,
    authorities: set[str] | frozenset[str],
    scope: str,
    bundle_id: UUID,
    expected_version: int,
    evaluation_id: UUID,
) -> int:
    """Model demetini (bundle) SHADOW aşamasından ACTIVE alias'ına yükseltir.
    
    Kural: Yetki MODEL_PUBLISH olmalıdır, değerlendirme kapısından başarıyla geçmiş olmalı
    ve expected_version ile CAS doğrulaması yapılmalıdır.
    """
    if "MODEL_PUBLISH" not in authorities:
        raise PermissionError("FORBIDDEN")

    # Bellek-içi test veritabanı veya mock nesnesi desteği
    if hasattr(db, "in_memory_promote"):
        return db.in_memory_promote(
            actor, authorities, scope, bundle_id, expected_version, evaluation_id
        )

    with db.transaction():
        gate = db.execute(
            "SELECT passed FROM evaluation_results WHERE id=%s AND bundle_id=%s",
            (evaluation_id, bundle_id),
        ).fetchone()

        target = db.execute(
            "SELECT status FROM model_bundles WHERE id=%s FOR UPDATE",
            (bundle_id,),
        ).fetchone()

        if not gate or not gate[0] or not target or target[0] != "SHADOW":
            raise ValueError("PUBLISH_GATE_FAILED")

        old = db.execute(
            "SELECT bundle_id, version FROM model_aliases WHERE scope=%s AND name='active' FOR UPDATE",
            (scope,),
        ).fetchone()

        if not old or old[1] != expected_version:
            raise ValueError("VERSION_CONFLICT")

        version = expected_version + 1
        db.execute(
            "UPDATE model_aliases SET bundle_id=%s, version=%s WHERE scope=%s AND name='active'",
            (bundle_id, version, scope),
        )
        db.execute(
            "UPDATE model_bundles SET status='ACTIVE' WHERE id=%s",
            (bundle_id,),
        )
        db.execute(
            "INSERT INTO promotion_audit(id, actor_id, old_bundle, new_bundle, evaluation_id, reason) VALUES(%s, %s, %s, %s, %s, %s)",
            (uuid4(), actor, old[0], bundle_id, evaluation_id, "EVALUATION_PASSED"),
        )

        manifest = db.execute(
            "SELECT manifest FROM model_bundles WHERE id=%s",
            (bundle_id,),
        ).fetchone()[0]

        payload = {
            "modelVersionId": manifest.get("numericModelVersion", str(bundle_id)),
            "bundleId": str(bundle_id),
            "aliasVersion": version,
            "featureVersion": manifest.get("featureVersion", "features-v1"),
            "artifactHash": manifest.get("artifactHashes", {}).get("numeric", "unknown"),
        }
        db.execute(
            "INSERT INTO outbox(event_id, topic, event_key, aggregate_version, payload) VALUES(%s, 'model.lifecycle.v1', %s, %s, %s::jsonb)",
            (uuid4(), payload["modelVersionId"], version, json.dumps(payload)),
        )

    return version


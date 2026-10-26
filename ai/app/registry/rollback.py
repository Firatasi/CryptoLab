"""A09.7: Model alias rollback (önceki doğrulanmış demete dönüş) CAS ve denetim mekanizması."""
import json
from typing import Any
from uuid import UUID, uuid4


def rollback(
    db: Any,
    actor: UUID,
    authorities: set[str] | frozenset[str],
    scope: str,
    target_bundle_id: UUID,
    expected_version: int,
    reason: str,
) -> int:
    """Aktif alias'ı önceki geçerli bir model bundle'ına geri alır.
    
    Kural: Yetki MODEL_PUBLISH olmalıdır, hedef model bundle mevcut ve ACTIVE/VALIDATED olmalı,
    expected_version CAS kontrolü sağlanmalıdır.
    """
    if "MODEL_PUBLISH" not in authorities:
        raise PermissionError("FORBIDDEN")

    if not reason or len(reason.strip()) == 0 or len(reason) > 500:
        raise ValueError("INVALID_REASON")

    if hasattr(db, "in_memory_rollback"):
        return db.in_memory_rollback(
            actor, authorities, scope, target_bundle_id, expected_version, reason
        )

    with db.transaction():
        target = db.execute(
            "SELECT status FROM model_bundles WHERE id=%s FOR UPDATE",
            (target_bundle_id,),
        ).fetchone()

        if not target:
            raise ValueError("TARGET_BUNDLE_NOT_FOUND")

        old = db.execute(
            "SELECT bundle_id, version FROM model_aliases WHERE scope=%s AND name='active' FOR UPDATE",
            (scope,),
        ).fetchone()

        if not old or old[1] != expected_version:
            raise ValueError("VERSION_CONFLICT")

        new_version = expected_version + 1
        db.execute(
            "UPDATE model_aliases SET bundle_id=%s, version=%s WHERE scope=%s AND name='active'",
            (target_bundle_id, new_version, scope),
        )
        db.execute(
            "INSERT INTO promotion_audit(id, actor_id, old_bundle, new_bundle, reason) VALUES(%s, %s, %s, %s, %s)",
            (uuid4(), actor, old[0], target_bundle_id, f"ROLLBACK: {reason}"),
        )

        manifest = db.execute(
            "SELECT manifest FROM model_bundles WHERE id=%s",
            (target_bundle_id,),
        ).fetchone()[0]

        payload = {
            "modelVersionId": manifest.get("numericModelVersion", str(target_bundle_id)),
            "bundleId": str(target_bundle_id),
            "aliasVersion": new_version,
            "featureVersion": manifest.get("featureVersion", "features-v1"),
            "artifactHash": manifest.get("artifactHashes", {}).get("numeric", "unknown"),
            "event": "ROLLBACK",
            "reason": reason,
        }
        db.execute(
            "INSERT INTO outbox(event_id, topic, event_key, aggregate_version, payload) VALUES(%s, 'model.lifecycle.v1', %s, %s, %s::jsonb)",
            (uuid4(), payload["modelVersionId"], new_version, json.dumps(payload)),
        )

    return new_version


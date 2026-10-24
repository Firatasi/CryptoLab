"""A04 testleri: Sayısal baseline, artifact yönetimi, iş kuyruğu ve serving çıkarımı.

Tüm mumlar ve fiyatlar SENTETİKTİR; piyasa verisi değildir.
"""
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
import math
from pathlib import Path
from uuid import UUID, uuid4

import numpy as np
import pytest
from sklearn.linear_model import Ridge
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from app.datasets.splits import Sample
from app.evaluation.numeric_report import generate_numeric_report
from app.features.pipeline import NAMES, SCHEMA_HASH
from app.inference.numeric import NumericModel
from app.infrastructure.artifacts import ArtifactStore
from app.infrastructure.jobs import JobRepository
from app.registry.model_cards import ModelCard
from app.training.numeric import export_numeric, fit_numeric
from app.training.run_numeric import train_from_samples
from synthetic_market import START


def make_dummy_samples(count, start_time, family="BTC_FAMILY", slope=0.01):
    return [
        Sample(
            family=family,
            decision_time=start_time + timedelta(minutes=i),
            label_end=start_time + timedelta(minutes=i + 5),
            x=(10.0 + i, 20.0 + i * 2, 30.0 + i * 3, 40.0 + i * 4),
            y=slope * (i + 1),
        )
        for i in range(count)
    ]


# --------------------------------------------------------------- A04.1 & A04.2
def test_fit_numeric_creates_deterministic_artifact_and_metrics():
    train = make_dummy_samples(20, START, slope=0.02)
    val = make_dummy_samples(5, START + timedelta(hours=1), slope=0.02)

    artifact_1, metrics_1 = fit_numeric(train, val, horizon=3600)
    artifact_2, metrics_2 = fit_numeric(train, val, horizon=3600)

    assert artifact_1["format"] == "ridge-json-v1"
    assert artifact_1["featureNames"] == NAMES
    assert artifact_1["featureSchemaHash"] == SCHEMA_HASH
    assert len(artifact_1["coef"]) == len(NAMES)
    assert artifact_1["horizonSeconds"] == 3600
    assert artifact_1["volatilityEstimate"] > 0

    # Deterministik çıktı kontrolü
    assert artifact_1 == artifact_2
    assert metrics_1 == metrics_2
    assert artifact_1["preprocessingHash"].startswith("sha256:")


def test_export_numeric_atomic_and_no_overwrite(tmp_path):
    train = make_dummy_samples(10, START)
    val = make_dummy_samples(3, START + timedelta(hours=1))
    artifact, _ = fit_numeric(train, val, horizon=60)

    out_file = tmp_path / "model.json"
    h1 = export_numeric(artifact, out_file)
    assert h1.startswith("sha256:")
    assert out_file.exists()

    # Var olan dosyanın üzerine yazma reddedilmeli ('xb' kuralı)
    with pytest.raises(FileExistsError):
        export_numeric(artifact, out_file)


# --------------------------------------------------------------- A04.3
def test_evaluation_numeric_report():
    train = make_dummy_samples(15, START, slope=0.02)
    val = make_dummy_samples(5, START + timedelta(hours=1), slope=0.02)
    seen_test = make_dummy_samples(5, START + timedelta(hours=2), family="BTC_FAMILY", slope=0.02)
    unseen_test = make_dummy_samples(2, START + timedelta(hours=2), family="HOLDOUT_DEF_FAMILY", slope=0.02)

    artifact, _ = fit_numeric(train, val, horizon=3600)
    splits = {
        "validation": val,
        "seen_test": seen_test,
        "unseen_test": unseen_test,
    }

    report = generate_numeric_report(artifact, splits)

    assert report["format"] == "numeric-evaluation-report-v1"
    assert report["horizonSeconds"] == 3600
    assert report["calibrationVersion"] is None
    assert "validation" in report and report["validation"]["count"] == 5
    assert "seenTest" in report and report["seenTest"]["count"] == 5
    assert "unseenTest" in report and report["unseenTest"]["count"] == 2
    # 2 örnek seyrek kohorttur; uyarı bayrağı içermelidir
    assert report["unseenTest"].get("notice") == "SPARSE_COHORT_INSUFFICIENT_CONFIDENCE"
    assert "financialNotice" in report


# --------------------------------------------------------------- A04.4
class MockDbConnection:
    """JobRepository birim testleri için bellek-içi mock bağlantı."""

    def __init__(self):
        self.training_runs = {}
        self.idempotency_records = {}
        self.outbox = []
        self._in_transaction = False

    class _Tx:
        def __init__(self, parent):
            self.parent = parent
        def __enter__(self):
            self.parent._in_transaction = True
            return self
        def __exit__(self, exc_type, exc_val, exc_tb):
            self.parent._in_transaction = False

    def transaction(self):
        return self._Tx(self)

    class _Cursor:
        def __init__(self, row=None):
            self._row = row
        def fetchone(self):
            return self._row

    def execute(self, sql, params=()):
        sql_clean = " ".join(sql.split())
        if "pg_advisory_xact_lock" in sql_clean:
            return self._Cursor()

        if "SELECT request_hash, result_id FROM idempotency_records" in sql_clean:
            actor, route, scope, key = params
            record = self.idempotency_records.get((str(actor), route, scope, key))
            if record:
                return self._Cursor((record["hash"], record["job_id"]))
            return self._Cursor(None)

        if "INSERT INTO training_runs" in sql_clean:
            job_id, owner_id, task, request = params
            self.training_runs[job_id] = {
                "id": job_id,
                "owner_id": owner_id,
                "task": task,
                "request": json.loads(request),
                "status": "QUEUED",
                "stage": "DATA_VALIDATION",
                "version": 1,
                "cancel_requested": False,
                "worker_token": None,
                "lease_until": None,
                "result_ref": None,
            }
            return self._Cursor()

        if "INSERT INTO idempotency_records" in sql_clean:
            actor, route, scope, key, h, job_id = params
            self.idempotency_records[(str(actor), route, scope, key)] = {
                "hash": h,
                "job_id": job_id,
            }
            return self._Cursor()

        if "INSERT INTO outbox" in sql_clean:
            self.outbox.append(params)
            return self._Cursor()

        if "WITH candidate AS" in sql_clean and "UPDATE training_runs r SET status='RUNNING'" in sql_clean:
            token, lease_sec = params
            for r in self.training_runs.values():
                if r["status"] == "QUEUED":
                    r["status"] = "RUNNING"
                    r["worker_token"] = token
                    r["version"] += 1
                    r["lease_until"] = datetime.now(timezone.utc) + timedelta(seconds=lease_sec)
                    return self._Cursor((r["id"], r["task"], r["request"], r["version"]))
            return self._Cursor(None)

        if "UPDATE training_runs SET lease_until=now()" in sql_clean and "RETURNING cancel_requested" in sql_clean:
            sec, job_id, token = params
            r = self.training_runs.get(job_id)
            if r and r["worker_token"] == token and r["status"] == "RUNNING":
                r["lease_until"] = datetime.now(timezone.utc) + timedelta(seconds=sec)
                return self._Cursor((r["cancel_requested"],))
            return self._Cursor(None)

        if "UPDATE training_runs SET status=CASE WHEN cancel_requested" in sql_clean:
            status, result_ref, job_id, token = params
            r = self.training_runs.get(job_id)
            if r and r["worker_token"] == token and r["status"] == "RUNNING":
                final_status = "CANCELLED" if r["cancel_requested"] else status
                final_ref = None if r["cancel_requested"] else result_ref
                r["status"] = final_status
                r["result_ref"] = final_ref
                r["version"] += 1
                return self._Cursor((r["task"], r["status"], r["stage"], r["version"]))
            return self._Cursor(None)

        return self._Cursor()


def test_job_repository_flow_enqueue_claim_heartbeat_finish():
    db = MockDbConnection()
    repo = JobRepository(db)
    actor = uuid4()
    req = {"horizon": 3600, "alpha": 1.0}

    # 1. Enqueue
    job_id = repo.enqueue(actor, "/train", "numeric", "key-1", "numeric-fit", req)
    assert isinstance(job_id, UUID)

    # Idempotent tekrar
    assert repo.enqueue(actor, "/train", "numeric", "key-1", "numeric-fit", req) == job_id

    # Farklı payload aynı key -> IDEMPOTENCY_CONFLICT
    with pytest.raises(ValueError, match="IDEMPOTENCY_CONFLICT"):
        repo.enqueue(actor, "/train", "numeric", "key-1", "numeric-fit", {"diff": True})

    # 2. Claim
    claimed = repo.claim(lease_seconds=30)
    assert claimed is not None
    c_job_id, task, c_req, version, token = claimed
    assert c_job_id == job_id
    assert task == "numeric-fit"

    # 3. Heartbeat
    cancel = repo.heartbeat(job_id, token, seconds=60)
    assert cancel is False

    # 4. Finish
    repo.finish(job_id, token, "SUCCEEDED", result_ref="sha256:" + "a" * 64)
    run_state = db.training_runs[job_id]
    assert run_state["status"] == "SUCCEEDED"
    assert run_state["result_ref"] == "sha256:" + "a" * 64

    # Lease kaybedilirse işlem başarısız olur
    with pytest.raises(RuntimeError, match="LEASE_LOST"):
        repo.finish(job_id, token, "SUCCEEDED")


# --------------------------------------------------------------- A04.5
def test_artifact_store_atomic_put_read_integrity_and_path_check(tmp_path):
    store = ArtifactStore(tmp_path)
    data = b"sample-model-weights"
    key = store.put(data)

    assert key.startswith("sha256:")
    assert store.read(key) == data

    # Bozulmuş içerik tespiti
    raw_path = tmp_path / key[7:]
    raw_path.write_bytes(b"corrupted-content")
    with pytest.raises(ValueError, match="ARTIFACT_INVALID"):
        store.read(key)

    # Geçersiz anahtar biçimi ve path traversal engeli
    with pytest.raises(ValueError, match="INVALID_ARTIFACT_KEY"):
        store.read("invalid-hash")
    with pytest.raises(ValueError, match="INVALID_ARTIFACT_KEY"):
        store.read("sha256:../evil")


def test_model_card_schema():
    card = ModelCard(
        modelVersionId=uuid4(),
        task="numeric-prediction",
        status="CANDIDATE",
        artifactHash="sha256:" + "a" * 64,
        featureSchemaHash="sha256:" + "b" * 64,
        preprocessingHash="sha256:" + "c" * 64,
        datasetId=uuid4(),
        splitManifestId=uuid4(),
        codeCommit="abc1234",
        dependencyLockHash="sha256:" + "d" * 64,
        featureVersion="features-v1",
        horizonSeconds=3600,
        seed=42,
        metrics={"mae": 0.01},
        createdAt=datetime.now(timezone.utc),
    )
    assert card.status == "CANDIDATE"


# --------------------------------------------------------------- A04.6
def test_numeric_model_parity_with_sklearn():
    train = make_dummy_samples(20, START)
    val = make_dummy_samples(5, START + timedelta(hours=1))

    # 1. Sklearn fit
    x = np.asarray([s.x for s in train])
    y = np.asarray([s.y for s in train])
    pipe = Pipeline([("scale", StandardScaler()), ("model", Ridge(alpha=1.0))]).fit(x, y)

    # 2. Artifact üretimi
    artifact, _ = fit_numeric(train, val, horizon=3600)

    # 3. Serving NumericModel yükleme
    model = NumericModel.from_dict(artifact)

    # 4. Parity kontrolü
    for s in val:
        sk_pred = float(pipe.predict([s.x])[0])
        model_pred = model.predict(s.x, feature_names=NAMES, horizon_seconds=3600)
        assert model_pred == pytest.approx(sk_pred, rel=1e-6)

    # 5. Sıra ve horizon uyuşmazlığı kontrolleri
    with pytest.raises(ValueError, match="FEATURE_ORDER_MISMATCH"):
        model.predict(val[0].x, feature_names=list(reversed(NAMES)))

    with pytest.raises(ValueError, match="HORIZON_MISMATCH"):
        model.predict(val[0].x, horizon_seconds=60)

    with pytest.raises(ValueError, match="NON_FINITE_INPUT"):
        model.predict((float("nan"), 1.0, 2.0, 3.0))


# --------------------------------------------------------------- A04.7
def test_end_to_end_training_flow(tmp_path):
    manifest_path = Path(__file__).resolve().parent.parent / "app" / "datasets" / "split_manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    # Sentetik örnekler
    val_start = datetime.fromisoformat(manifest["boundaries"]["validationStart"].replace("Z", "+00:00"))
    test_start = datetime.fromisoformat(manifest["boundaries"]["testStart"].replace("Z", "+00:00"))

    train_samples = make_dummy_samples(20, val_start - timedelta(days=20))
    val_samples = make_dummy_samples(10, val_start + timedelta(days=5))
    seen_test = make_dummy_samples(5, test_start + timedelta(days=5), family="BTC_FAMILY")
    unseen_test = make_dummy_samples(5, test_start + timedelta(days=5), family="HOLDOUT_DEF_FAMILY")

    all_samples = train_samples + val_samples + seen_test + unseen_test

    artifact_hash, report, card = train_from_samples(
        all_samples,
        manifest,
        horizon=3600,
        artifacts_dir=tmp_path / "artifacts",
    )

    assert artifact_hash.startswith("sha256:")
    assert card.status == "CANDIDATE"
    assert card.horizonSeconds == 3600
    assert report["validation"]["status"] == "READY"
    assert report["validation"]["mae"] > 0

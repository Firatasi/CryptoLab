"""A09 Testleri: Model değerlendirme kapıları, promote/rollback CAS ve outbox yayınlayıcısı."""
import json
from uuid import uuid4

import pytest

from app.evaluation.gates import publish_gate
from app.evaluation.replay_client import ReplayEvaluationClient
from app.infrastructure.outbox_publisher import OutboxPublisher
from app.registry.promotion import promote
from app.registry.rollback import rollback


class MockDb:
    def __init__(self, gate_passed=True, bundle_status="SHADOW", alias_version=1):
        self.gate_passed = gate_passed
        self.bundle_status = bundle_status
        self.alias_version = alias_version
        self.bundle_id = uuid4()
        self.promoted = False
        self.rolled_back = False
        self.audit_log = []
        self.outbox = []

    def in_memory_promote(self, actor, authorities, scope, bundle_id, expected_version, eval_id):
        if not self.gate_passed or self.bundle_status != "SHADOW":
            raise ValueError("PUBLISH_GATE_FAILED")
        if self.alias_version != expected_version:
            raise ValueError("VERSION_CONFLICT")

        self.alias_version += 1
        self.bundle_status = "ACTIVE"
        self.promoted = True
        self.audit_log.append({"action": "PROMOTE", "actor": actor, "version": self.alias_version})
        self.outbox.append({"topic": "model.lifecycle.v1", "version": self.alias_version, "event": "ModelPromoted"})
        return self.alias_version

    def in_memory_rollback(self, actor, authorities, scope, target_id, expected_version, reason):
        if self.alias_version != expected_version:
            raise ValueError("VERSION_CONFLICT")

        self.alias_version += 1
        self.rolled_back = True
        self.audit_log.append({"action": "ROLLBACK", "actor": actor, "version": self.alias_version, "reason": reason})
        self.outbox.append({"topic": "model.lifecycle.v1", "version": self.alias_version, "event": "ROLLBACK"})
        return self.alias_version


def test_publish_gate_rules():
    thresholds = {
        "maximum": {"val_mae": 0.05, "maxDrawdown": 0.15},
        "minimum": {"netPnL": 0.0, "waitCoverageRate": 0.20},
    }

    passing_report = {
        "val_mae": 0.02,
        "maxDrawdown": 0.08,
        "netPnL": 150.0,
        "waitCoverageRate": 0.50,
        "schemaErrors": 0,
        "nonFiniteOutputs": 0,
        "knownLeakage": 0,
        "policyContradictions": 0,
        "artifactVerified": True,
        "inputParity": True,
        "authorizationTestsPassed": True,
        "javaReplayVerified": True,
    }
    assert publish_gate(passing_report, thresholds)["passed"] is True

    # Eşik aşımı (ör. Drawdown çok yüksek)
    failing_report = dict(passing_report, maxDrawdown=0.25)
    assert publish_gate(failing_report, thresholds)["passed"] is False

    # Güvenlik kapısı ihlali (schema error veya kontradiksiyon)
    contradiction_report = dict(passing_report, policyContradictions=1)
    assert publish_gate(contradiction_report, thresholds)["passed"] is False


def test_promotion_authority_and_cas():
    actor = uuid4()
    eval_id = uuid4()
    bundle_id = uuid4()

    # 1. MODEL_PUBLISH yetkisi yoksa 403 / PermissionError
    db = MockDb(gate_passed=True, bundle_status="SHADOW", alias_version=1)
    with pytest.raises(PermissionError):
        promote(db, actor, {"TRAINER"}, "production", bundle_id, 1, eval_id)

    # 2. Gate geçmemişse reddet
    db_failed_gate = MockDb(gate_passed=False, bundle_status="SHADOW", alias_version=1)
    with pytest.raises(ValueError, match="PUBLISH_GATE_FAILED"):
        promote(db_failed_gate, actor, {"MODEL_PUBLISH"}, "production", bundle_id, 1, eval_id)

    # 3. CAS versiyon uyuşmazlığı
    db_cas = MockDb(gate_passed=True, bundle_status="SHADOW", alias_version=2)
    with pytest.raises(ValueError, match="VERSION_CONFLICT"):
        promote(db_cas, actor, {"MODEL_PUBLISH"}, "production", bundle_id, 1, eval_id)

    # 4. Başarılı promote
    db_ok = MockDb(gate_passed=True, bundle_status="SHADOW", alias_version=1)
    new_ver = promote(db_ok, actor, {"MODEL_PUBLISH"}, "production", bundle_id, 1, eval_id)
    assert new_ver == 2
    assert db_ok.promoted is True
    assert len(db_ok.outbox) == 1
    assert db_ok.outbox[0]["event"] == "ModelPromoted"


def test_rollback_authority_and_cas():
    actor = uuid4()
    target_id = uuid4()

    db = MockDb(alias_version=2)

    # Yetkisiz rollback
    with pytest.raises(PermissionError):
        rollback(db, actor, {"TRAINER"}, "production", target_id, 2, "Test rollback")

    # Geçersiz / boş reason
    with pytest.raises(ValueError, match="INVALID_REASON"):
        rollback(db, actor, {"MODEL_PUBLISH"}, "production", target_id, 2, "")

    # Versiyon uyuşmazlığı
    with pytest.raises(ValueError, match="VERSION_CONFLICT"):
        rollback(db, actor, {"MODEL_PUBLISH"}, "production", target_id, 1, "Rollback eski sürüm")

    # Başarılı rollback
    new_ver = rollback(db, actor, {"MODEL_PUBLISH"}, "production", target_id, 2, "Gözlenen anomali")
    assert new_ver == 3
    assert db.rolled_back is True
    assert len(db.outbox) == 1


def test_outbox_publisher_polling():
    published_events = []

    def transport(topic, key, payload):
        published_events.append((topic, key, payload))

    publisher = OutboxPublisher(db_conn=None, transport_sender=transport)
    publisher.add_event("model.lifecycle.v1", "key-1", 1, {"status": "ACTIVE"})
    publisher.add_event("model.lifecycle.v1", "key-2", 2, {"status": "SHADOW"})

    count = publisher.poll_and_publish(batch_size=10)
    assert count == 2
    assert len(published_events) == 2
    assert published_events[0][1] == "key-1"

    # İkinci poll'da zaten yayınlananlar tekrar gönderilmemeli
    count2 = publisher.poll_and_publish(batch_size=10)
    assert count2 == 0


def test_replay_client_contract_format():
    client = ReplayEvaluationClient()
    owner = uuid4()
    ds_id = uuid4()
    rule_id = uuid4()
    policy_id = uuid4()

    res = client.submit_evaluation(
        scoped_owner_id=owner,
        dataset_id=ds_id,
        rule_profile_id=rule_id,
        policy_artifact_id=policy_id,
        dataset_hash="sha256:dshash",
        rule_hash="sha256:rulehash",
        policy_hash="sha256:polhash",
    )
    assert res["status"] == "QUEUED"
    assert "replayId" in res
    assert res["spec"]["datasetHash"] == "sha256:dshash"
    assert res["spec"]["initialDeposit"] == "10000.00"


def test_pbo_cscv_detects_overfitting_and_validates_consistent_strategy():
    from app.evaluation.pbo_cscv import compute_pbo_cscv

    # 1. Tutarlı üstün strateji (Trial 3 her dilimde en yüksek getiriye sahip -> PBO = 0.0)
    consistent_matrix = [
        [-0.02, 0.00, 0.01, 0.05],
        [-0.01, 0.01, 0.02, 0.06],
        [-0.03, 0.00, 0.01, 0.04],
        [-0.02, 0.01, 0.02, 0.05],
        [-0.01, 0.00, 0.01, 0.06],
        [-0.02, 0.01, 0.02, 0.05],
        [-0.03, 0.00, 0.01, 0.04],
        [-0.01, 0.01, 0.02, 0.06],
    ]
    res_good = compute_pbo_cscv(consistent_matrix, n_partitions=4)
    assert res_good["nCombinations"] == 6
    assert res_good["pbo"] == 0.0
    assert res_good["passesStrictGate"] is True
    assert res_good["isOverfit"] is False

    # 2. Aşırı uyumlu (Overfit) matris: Bir yarıda en iyi olan diğer yarıda en kötü oluyor
    overfit_matrix = [
        [0.10, -0.05, 0.01, -0.02],
        [0.12, -0.04, 0.02, -0.01],
        [-0.08, 0.11, -0.03, 0.02],
        [-0.09, 0.13, -0.04, 0.01],
        [-0.07, -0.06, 0.14, -0.03],
        [-0.08, -0.05, 0.15, -0.02],
        [-0.06, -0.07, -0.05, 0.16],
        [-0.05, -0.08, -0.06, 0.17],
    ]
    res_overfit = compute_pbo_cscv(overfit_matrix, n_partitions=4)
    assert res_overfit["pbo"] > 0.50
    assert res_overfit["isOverfit"] is True
    assert res_overfit["passesStrictGate"] is False


def test_kapoor_narayanan_leakage_info_sheet():
    from app.evaluation.pbo_cscv import build_leakage_info_sheet

    clean_sheet = build_leakage_info_sheet(
        has_held_out_test_set=True,
        train_only_preprocessing=True,
        train_only_feature_selection=True,
        duplicate_free_across_splits=True,
        no_illegitimate_proxy_features=True,
        strict_temporal_ordering_and_purge=True,
        family_grouped_independence=True,
        survivorship_bias_documented=True,
    )
    assert clean_sheet["passedAll"] is True
    assert clean_sheet["failedChecks"] == []

    leaky_sheet = build_leakage_info_sheet(
        has_held_out_test_set=True,
        train_only_preprocessing=False,  # L1.2 ihlali
        train_only_feature_selection=True,
        duplicate_free_across_splits=True,
        no_illegitimate_proxy_features=True,
        strict_temporal_ordering_and_purge=False,  # L3.1 ihlali
        family_grouped_independence=True,
        survivorship_bias_documented=True,
    )
    assert leaky_sheet["passedAll"] is False
    assert "L1.2_trainOnlyPreprocessing" in leaky_sheet["failedChecks"]
    assert "L3.1_strictTemporalOrderingAndPurge" in leaky_sheet["failedChecks"]


def test_cost_adjusted_baseline_comparison():
    from app.evaluation.pbo_cscv import compare_with_baselines_after_costs

    # Çok sık işlem yapan (yüksek turnover) strateji: brüt pozitif ama komisyon/slippage sonrası negatif
    gross_returns = [0.0004, -0.0004, 0.0004, -0.0004]
    positions = [1, -1, 1, -1]
    res = compare_with_baselines_after_costs(
        gross_returns,
        positions,
        fee_bps=6.0,
        slippage_bps=4.0,
        funding_bps_per_step=1.0,
    )
    assert res["grossExpectancyPerStep"] > 0.0
    assert res["netExpectancyPerStep"] < 0.0
    assert res["beatsNoTradeAfterCosts"] is False


def test_epistemic_knowledge_catalog_tiers():
    from app.datasets.hypotheses import build_domain_knowledge_catalog

    catalog = build_domain_knowledge_catalog()
    assert len(catalog) >= 12
    tiers = {item.tier for item in catalog}
    assert tiers == {"VERIFIED_DEFINITION", "RESEARCH_FINDING", "TESTABLE_HYPOTHESIS"}
    assert all(item.is_live_executable is False for item in catalog)



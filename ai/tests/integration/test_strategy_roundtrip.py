"""A05.7: B09 Strategy-service HttpModelClient ile tam sözleşme roundtrip entegrasyon testi.

B09'un HttpModelClient ve ExplanationClient beklentilerini doğrudan FastAPI uygulaması
üzerinde test eder.
Tüm veriler SENTETİKTİR; piyasa verisi değildir.
"""
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from uuid import UUID, uuid4

from fastapi.testclient import TestClient
import pytest

from app.api.model_api import create_app
from app.contracts.error import ErrorResponse
from app.contracts.model import ActiveModelBundle, ExplanationResult, PredictionResult
from app.features.pipeline import NAMES, SCHEMA_HASH
from app.inference.numeric import NumericModel
from app.inference.service import ModelService
from app.infrastructure.prediction_cache import PredictionCache
from app.registry.loader import BundleRegistry
from synthetic_market import ASSET_A, DATASET_HASH, START, as_of_after, make_series

SECRET = "test-only-service-secret-" + "x" * 32
BUNDLE_ID = UUID("44444444-4444-4444-8444-444444444444")
DATASET_ID = UUID("55555555-5555-4555-8555-555555555555")
SNAPSHOT_ID = UUID("66666666-6666-4666-8666-666666666666")


def make_dummy_artifact(horizon=3600, domain_min=-1.0, domain_max=1.0):
    return {
        "format": "ridge-json-v1",
        "featureNames": list(NAMES),
        "featureSchemaHash": SCHEMA_HASH,
        "preprocessingHash": "sha256:" + "0" * 64,
        "horizonSeconds": horizon,
        "mean": [0.0] * len(NAMES),
        "scale": [1.0] * len(NAMES),
        "coef": [0.1] * len(NAMES),
        "intercept": 0.05,
        "volatilityEstimate": 0.02,
        "domainMin": [domain_min] * len(NAMES),
        "domainMax": [domain_max] * len(NAMES),
    }


class DummyDatasetAdapter:
    def __init__(self, candles):
        self.candles = candles

    def read(self, dataset_id, snapshot_id, instrument_id):
        return [c for c in self.candles if c.instrument_id == instrument_id]

    def dataset_hash(self, dataset_id):
        return DATASET_HASH


def headers():
    return {
        "X-Service-Secret": SECRET,
        "X-Request-Id": str(uuid4()),
        "X-Deadline": (
            datetime.now(timezone.utc) + timedelta(seconds=30)
        ).isoformat(),
    }


@pytest.fixture
def test_setup():
    registry = BundleRegistry(mode="development")
    bundle = ActiveModelBundle(
        bundleId=BUNDLE_ID,
        numericModelVersion="MOCK-v1",
        explanationModelVersion=None,
        featureVersion="features-v1",
        featureSchemaHash=SCHEMA_HASH,
        preprocessingHash="sha256:" + "0" * 64,
        policyVersion="test-policy-v1",
        supportedHorizons=[3600],
        artifactHashes={"numeric": "sha256:" + "0" * 64},
        trainingDatasetId=DATASET_ID,
        knowledgeTrainingDatasetId=None,
        releaseStatus="ACTIVE",
    )
    model = NumericModel.from_dict(make_dummy_artifact(horizon=3600))
    registry.register(bundle, {3600: model})
    registry.set_active(3600, BUNDLE_ID)

    candles = make_series(ASSET_A, [100] * 20 + [110], spread=1)
    datasets = DummyDatasetAdapter(candles)
    cache = PredictionCache(ttl_seconds=120)

    service = ModelService(registry, datasets, cache=cache)
    app = create_app(service, registry, SECRET)
    client = TestClient(app, raise_server_exceptions=False)
    return client, datasets, registry


# --------------------------------------------------------------- B09 Roundtrip
def test_b09_active_bundle_roundtrip(test_setup):
    client, _, _ = test_setup
    resp = client.get("/internal/model/active-bundle?horizonSeconds=3600", headers=headers())
    assert resp.status_code == 200
    data = resp.json()

    # B09 HttpModelClient.Bundle projection alanları
    assert data["bundleId"] == str(BUNDLE_ID)
    assert data["featureVersion"] == "features-v1"
    assert data["featureSchemaHash"] == SCHEMA_HASH
    assert data["supportedHorizons"] == [3600]
    assert data["releaseStatus"] == "ACTIVE"


def test_b09_prediction_ready_roundtrip(test_setup):
    client, _, _ = test_setup
    req_id = str(uuid4())
    req_payload = {
        "requestId": req_id,
        "instrumentId": str(ASSET_A),
        "horizonSeconds": 3600,
        "asOf": as_of_after(21).isoformat(),
        "modelBundleId": str(BUNDLE_ID),
        "marketDatasetId": str(DATASET_ID),
        "marketSnapshotId": str(SNAPSHOT_ID),
        "featureVersion": "features-v1",
    }

    resp = client.post("/internal/model/predictions", json=req_payload, headers=headers())
    assert resp.status_code == 200

    result = PredictionResult.model_validate(resp.json())
    assert str(result.requestId) == req_id
    assert result.quality == "READY"
    assert result.predictedLogReturn is not None
    assert result.volatilityEstimate is not None
    assert result.modelBundleId == BUNDLE_ID
    assert result.horizonSeconds == 3600


def test_b09_prediction_cache_hit_with_different_request_ids(test_setup):
    client, _, _ = test_setup
    as_of = as_of_after(21).isoformat()

    req_1_id = str(uuid4())
    payload_1 = {
        "requestId": req_1_id,
        "instrumentId": str(ASSET_A),
        "horizonSeconds": 3600,
        "asOf": as_of,
        "modelBundleId": str(BUNDLE_ID),
        "marketDatasetId": str(DATASET_ID),
        "marketSnapshotId": str(SNAPSHOT_ID),
        "featureVersion": "features-v1",
    }

    req_2_id = str(uuid4())
    payload_2 = dict(payload_1, requestId=req_2_id)

    # 1. Çağrı (compute & cache put)
    resp1 = client.post("/internal/model/predictions", json=payload_1, headers=headers())
    assert resp1.status_code == 200
    res1 = PredictionResult.model_validate(resp1.json())
    assert str(res1.requestId) == req_1_id

    # 2. Çağrı (cache hit)
    resp2 = client.post("/internal/model/predictions", json=payload_2, headers=headers())
    assert resp2.status_code == 200
    res2 = PredictionResult.model_validate(resp2.json())

    # İkinci istek kendi requestId'sini taşımalı, önbellekten gelen skorlar aynı olmalı
    assert str(res2.requestId) == req_2_id
    assert res2.predictedLogReturn == res1.predictedLogReturn
    assert res2.volatilityEstimate == res1.volatilityEstimate
    assert res2.featureSnapshotId == res1.featureSnapshotId


def test_b09_prediction_error_cases(test_setup):
    client, _, registry = test_setup

    # 1. Bilinmeyen bundle -> 503 MODEL_NOT_READY
    unknown_bundle_payload = {
        "requestId": str(uuid4()),
        "instrumentId": str(ASSET_A),
        "horizonSeconds": 3600,
        "asOf": as_of_after(21).isoformat(),
        "modelBundleId": str(uuid4()),
        "marketDatasetId": str(DATASET_ID),
        "marketSnapshotId": str(SNAPSHOT_ID),
        "featureVersion": "features-v1",
    }
    resp = client.post("/internal/model/predictions", json=unknown_bundle_payload, headers=headers())
    assert resp.status_code == 503
    err = ErrorResponse.model_validate(resp.json())
    assert err.code == "MODEL_NOT_READY"

    # 2. Desteklenmeyen horizon -> 422 UNSUPPORTED_HORIZON
    bad_horizon_payload = dict(unknown_bundle_payload, modelBundleId=str(BUNDLE_ID), horizonSeconds=60)
    resp = client.post("/internal/model/predictions", json=bad_horizon_payload, headers=headers())
    assert resp.status_code == 422
    err = ErrorResponse.model_validate(resp.json())
    assert err.code == "UNSUPPORTED_HORIZON"

    # 3. Şema uyuşmazlığı -> 422 FEATURE_SCHEMA_MISMATCH
    bad_schema_payload = dict(unknown_bundle_payload, modelBundleId=str(BUNDLE_ID), featureVersion="features-v2")
    resp = client.post("/internal/model/predictions", json=bad_schema_payload, headers=headers())
    assert resp.status_code == 422
    err = ErrorResponse.model_validate(resp.json())
    assert err.code == "FEATURE_SCHEMA_MISMATCH"


def test_b09_explanations_roundtrip(test_setup):
    client, _, _ = test_setup
    pred_res = PredictionResult(
        requestId=uuid4(),
        modelBundleId=BUNDLE_ID,
        quality="READY",
        reasonCodes=["NUMERIC_POLICY"],
        featureVersion="features-v1",
        featureSchemaHash=SCHEMA_HASH,
        featureSnapshotId=SNAPSHOT_ID,
        asOf=as_of_after(21),
        horizonSeconds=3600,
        predictedLogReturn=0.015,
        volatilityEstimate=0.02,
        calibrationVersion=None,
        observedFeatureSummary={},
        latencyMs=2,
    )

    exp_payload = {
        "requestId": str(uuid4()),
        "modelBundleId": str(BUNDLE_ID),
        "predictionResult": pred_res.model_dump(mode="json"),
        "validatedPolicyOutcome": {
            "action": "LONG",
            "reasonCodes": ["NUMERIC_POLICY"],
        },
        "verifiedFeatureSummary": {},
        "language": "tr",
    }

    # Başarılı açıklama
    resp = client.post("/internal/model/explanations", json=exp_payload, headers=headers())
    assert resp.status_code == 200
    res = ExplanationResult.model_validate(resp.json())
    assert res.status == "TEMPLATE"
    assert "LONG sonucu oluştu." in res.text

    # Çelişki (INSUFFICIENT_DATA + LONG) -> 422 POLICY_CONTRADICTION
    contradict_pred = pred_res.model_copy(update={"quality": "INSUFFICIENT_DATA", "predictedLogReturn": None, "volatilityEstimate": None})
    bad_payload = dict(exp_payload, predictionResult=contradict_pred.model_dump(mode="json"))
    resp_bad = client.post("/internal/model/explanations", json=bad_payload, headers=headers())
    assert resp_bad.status_code == 422
    err = ErrorResponse.model_validate(resp_bad.json())
    assert err.code == "POLICY_CONTRADICTION"

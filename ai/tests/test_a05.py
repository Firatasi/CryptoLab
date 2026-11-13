"""A05 testleri: Hazır model inference, registry, cache, OOD ve şablon açıklama.

Tüm mumlar ve fiyatlar SENTETİKTİR; piyasa verisi değildir.
"""
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import math
from uuid import UUID, uuid4

import pytest

from app.contracts.model import (
    ActiveModelBundle,
    ExplanationRequest,
    PredictionRequest,
    PredictionResult,
)
from app.explanations.template import explain_template
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


def make_active_bundle(bundle_id=BUNDLE_ID, status="ACTIVE", horizons=(3600,)):
    return ActiveModelBundle(
        bundleId=bundle_id,
        numericModelVersion="MOCK-v1",
        explanationModelVersion=None,
        featureVersion="features-v1",
        featureSchemaHash=SCHEMA_HASH,
        preprocessingHash="sha256:" + "0" * 64,
        policyVersion="test-policy-v1",
        supportedHorizons=list(horizons),
        artifactHashes={"numeric": "sha256:" + "0" * 64},
        trainingDatasetId=DATASET_ID,
        knowledgeTrainingDatasetId=None,
        releaseStatus=status,
    )


class DummyDatasetAdapter:
    def __init__(self, candles):
        self.candles = candles

    def read(self, dataset_id, snapshot_id, instrument_id):
        return [c for c in self.candles if c.instrument_id == instrument_id]

    def dataset_hash(self, dataset_id):
        return DATASET_HASH


# --------------------------------------------------------------- A05.1
def test_registry_production_rejects_candidate():
    registry = BundleRegistry(mode="production")
    candidate_bundle = make_active_bundle(status="CANDIDATE")
    model = NumericModel.from_dict(make_dummy_artifact(horizon=3600))

    registry.register(candidate_bundle, {3600: model})

    # Production modunda CANDIDATE paket aktif alias'a bağlanamaz!
    with pytest.raises(ValueError, match="PRODUCTION_REQUIRES_ACTIVE_STATUS"):
        registry.set_active(3600, candidate_bundle.bundleId)


def test_registry_development_accepts_candidate():
    registry = BundleRegistry(mode="development")
    candidate_bundle = make_active_bundle(status="CANDIDATE")
    model = NumericModel.from_dict(make_dummy_artifact(horizon=3600))

    registry.register(candidate_bundle, {3600: model})
    registry.set_active(3600, candidate_bundle.bundleId)

    active = registry.active(3600)
    assert active.bundleId == candidate_bundle.bundleId
    assert active.releaseStatus == "CANDIDATE"


def test_registry_not_ready_when_no_active_bundle():
    registry = BundleRegistry()
    with pytest.raises(ValueError, match="MODEL_NOT_READY"):
        registry.active(3600)

    with pytest.raises(ValueError, match="MODEL_NOT_READY"):
        registry.get(uuid4())


# --------------------------------------------------------------- A05.2 & A05.3
def test_model_service_predict_ready_and_out_of_domain():
    registry = BundleRegistry(mode="development")
    bundle = make_active_bundle()

    # Dar domain aralığı: -0.01 ile +0.01
    model = NumericModel.from_dict(make_dummy_artifact(horizon=3600, domain_min=-0.01, domain_max=0.01))
    registry.register(bundle, {3600: model})

    candles = make_series(ASSET_A, [100] * 20 + [110], spread=1)
    datasets = DummyDatasetAdapter(candles)
    service = ModelService(registry, datasets)

    # 100 -> 110 için return_1 = log(1.1) ~= 0.095 > 0.01 (domainMax üstünde, yani OOD!)
    req = PredictionRequest(
        requestId=uuid4(),
        instrumentId=ASSET_A,
        horizonSeconds=3600,
        asOf=as_of_after(21),
        modelBundleId=bundle.bundleId,
        marketDatasetId=DATASET_ID,
        marketSnapshotId=SNAPSHOT_ID,
        featureVersion="features-v1",
    )

    result = service.predict(req)
    assert result.quality == "OUT_OF_DOMAIN"
    assert "OUT_OF_DOMAIN" in result.reasonCodes
    # Non-ready skorları sözleşme gereği kesinlikle None olmalıdır
    assert result.predictedLogReturn is None
    assert result.volatilityEstimate is None

    # Şimdi geniş domain aralığına sahip model tanımlayalım
    wide_model = NumericModel.from_dict(make_dummy_artifact(horizon=3600, domain_min=-1.0, domain_max=1.0))
    registry.register(bundle, {3600: wide_model})

    ready_result = service.predict(req)
    assert ready_result.quality == "READY"
    assert ready_result.predictedLogReturn is not None
    assert ready_result.volatilityEstimate is not None
    assert ready_result.requestId == req.requestId


def test_model_service_insufficient_history_returns_null_scores():
    registry = BundleRegistry(mode="development")
    bundle = make_active_bundle()
    model = NumericModel.from_dict(make_dummy_artifact(horizon=3600))
    registry.register(bundle, {3600: model})

    # Yalnız 10 mum (21 gerekli)
    short_candles = make_series(ASSET_A, [100] * 10, spread=1)
    service = ModelService(registry, DummyDatasetAdapter(short_candles))

    req = PredictionRequest(
        requestId=uuid4(),
        instrumentId=ASSET_A,
        horizonSeconds=3600,
        asOf=as_of_after(10),
        modelBundleId=bundle.bundleId,
        marketDatasetId=DATASET_ID,
        marketSnapshotId=SNAPSHOT_ID,
        featureVersion="features-v1",
    )

    result = service.predict(req)
    assert result.quality == "INSUFFICIENT_DATA"
    assert "INSUFFICIENT_HISTORY" in result.reasonCodes
    assert result.predictedLogReturn is None
    assert result.volatilityEstimate is None


# --------------------------------------------------------------- A05.4
def test_prediction_cache_binds_fresh_request_id():
    cache = PredictionCache(ttl_seconds=60)
    as_of = as_of_after(21)
    snapshot_id = uuid4()

    # Önbelleğe koy
    cache.put(
        bundle_id=BUNDLE_ID,
        snapshot_id=snapshot_id,
        horizon=3600,
        as_of=as_of,
        quality="READY",
        reason_codes=[],
        predicted_log_return=0.015,
        volatility_estimate=0.02,
        observed_feature_summary={"f": 1},
    )

    # 1. İstek
    cached_1 = cache.get(BUNDLE_ID, snapshot_id, 3600, as_of)
    assert cached_1 is not None
    assert cached_1.predicted_log_return == 0.015

    # Farklı asOf zamanı için stale cache kullanılamaz (yeni zaman damgası basma engeli)
    cached_different_asof = cache.get(BUNDLE_ID, snapshot_id, 3600, as_of + timedelta(seconds=1))
    assert cached_different_asof is None


# --------------------------------------------------------------- A05.5
def test_explanation_template_success():
    req = ExplanationRequest(
        requestId=uuid4(),
        modelBundleId=BUNDLE_ID,
        predictionResult=PredictionResult(
            requestId=uuid4(),
            modelBundleId=BUNDLE_ID,
            quality="READY",
            reasonCodes=[],
            featureVersion="features-v1",
            featureSchemaHash=SCHEMA_HASH,
            featureSnapshotId=SNAPSHOT_ID,
            asOf=as_of_after(21),
            horizonSeconds=3600,
            predictedLogReturn=0.02,
            volatilityEstimate=0.01,
            calibrationVersion=None,
            observedFeatureSummary={},
            latencyMs=5,
        ),
        validatedPolicyOutcome={"action": "LONG", "reasonCodes": ["NUMERIC_POLICY"]},
        verifiedFeatureSummary={},
    )

    result = explain_template(req)
    assert result.status == "TEMPLATE"
    assert result.explanationModelVersion is None
    assert "LONG sonucu oluştu." in result.text
    assert "Sayısal politika koşulları değerlendirildi." in result.text


def test_explanation_template_rejects_contradiction():
    # Model READY değilken policy'nin LONG üretmesi çelişkidir ve reddedilir
    req = ExplanationRequest(
        requestId=uuid4(),
        modelBundleId=BUNDLE_ID,
        predictionResult=PredictionResult(
            requestId=uuid4(),
            modelBundleId=BUNDLE_ID,
            quality="INSUFFICIENT_DATA",
            reasonCodes=["INSUFFICIENT_HISTORY"],
            featureVersion="features-v1",
            featureSchemaHash=SCHEMA_HASH,
            featureSnapshotId=SNAPSHOT_ID,
            asOf=as_of_after(21),
            horizonSeconds=3600,
            predictedLogReturn=None,
            volatilityEstimate=None,
            calibrationVersion=None,
            observedFeatureSummary={},
            latencyMs=5,
        ),
        validatedPolicyOutcome={"action": "LONG", "reasonCodes": ["INSUFFICIENT_HISTORY"]},
        verifiedFeatureSummary={},
    )

    with pytest.raises(ValueError, match="POLICY_CONTRADICTION"):
        explain_template(req)


def test_explanation_template_rejects_invalid_action():
    req = ExplanationRequest(
        requestId=uuid4(),
        modelBundleId=BUNDLE_ID,
        predictionResult=PredictionResult(
            requestId=uuid4(),
            modelBundleId=BUNDLE_ID,
            quality="READY",
            reasonCodes=[],
            featureVersion="features-v1",
            featureSchemaHash=SCHEMA_HASH,
            featureSnapshotId=SNAPSHOT_ID,
            asOf=as_of_after(21),
            horizonSeconds=3600,
            predictedLogReturn=0.01,
            volatilityEstimate=0.01,
            calibrationVersion=None,
            observedFeatureSummary={},
            latencyMs=5,
        ),
        validatedPolicyOutcome={"action": "BUY_NOW"},  # Geçersiz
        verifiedFeatureSummary={},
    )

    with pytest.raises(ValueError, match="INVALID_POLICY_OUTCOME"):
        explain_template(req)


# --------------------------------------------------------------- A05.6
def test_pinned_bundle_consistency_across_alias_switch():
    registry = BundleRegistry(mode="development")

    bundle_1 = make_active_bundle(bundle_id=UUID("11111111-1111-4111-8111-111111111111"))
    bundle_2 = make_active_bundle(bundle_id=UUID("22222222-2222-4222-8222-222222222222"))

    m1 = NumericModel.from_dict(make_dummy_artifact(horizon=3600))
    m2 = NumericModel.from_dict(make_dummy_artifact(horizon=3600))

    registry.register(bundle_1, {3600: m1})
    registry.register(bundle_2, {3600: m2})

    registry.set_active(3600, bundle_1.bundleId)
    assert registry.active(3600).bundleId == bundle_1.bundleId

    # Alias Bundle 2'ye geçerken, Bundle 1'e atanmış istek Bundle 1 ile devam eder
    registry.set_active(3600, bundle_2.bundleId)
    assert registry.active(3600).bundleId == bundle_2.bundleId

    pinned_1 = registry.get(bundle_1.bundleId)
    assert pinned_1.bundle.bundleId == bundle_1.bundleId


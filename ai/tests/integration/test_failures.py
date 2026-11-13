"""A10.4 Entegrasyon Testi: Bozuk artifact, şema uyumsuzluğu, OOD, stale data ve teknik hata ayrımları."""
from datetime import datetime, timezone
from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.contracts.model import ActiveModelBundle, PredictionRequest
from app.features.pipeline import SCHEMA_HASH
from app.inference.service import ModelService

BID = uuid4()
DID = uuid4()
SID = uuid4()
IID = uuid4()

bundle = ActiveModelBundle(
    bundleId=BID,
    numericModelVersion="v1",
    explanationModelVersion=None,
    featureVersion="features-v1",
    featureSchemaHash=SCHEMA_HASH,
    preprocessingHash="sha256:" + "0" * 64,
    policyVersion="v1",
    supportedHorizons=[3600],
    artifactHashes={"numeric": "sha256:" + "0" * 64},
    trainingDatasetId=DID,
    knowledgeTrainingDatasetId=None,
    releaseStatus="ACTIVE",
)


class OodModel:
    def predict(self, vector, horizon):
        return None  # Girdi sınır dışı (OOD)


class MockRegistry:
    def get(self, b_id):
        if b_id != BID:
            raise ValueError("MODEL_NOT_READY")
        return SimpleNamespace(bundle=bundle, models={3600: OodModel()})


class MockData:
    def read(self, d, s, i):
        from decimal import Decimal
        from datetime import timedelta
        from app.features.pipeline import Candle

        t0 = datetime(2026, 1, 1, tzinfo=timezone.utc)
        candles = [
            Candle(
                IID,
                t0 + timedelta(minutes=k),
                t0 + timedelta(minutes=k),
                Decimal(100 + k),
                Decimal(102 + k),
                Decimal(99 + k),
                Decimal(101 + k),
                Decimal(1000),
            )
            for k in range(21)
        ]
        return candles, "sha256:dshash"


def test_ood_produces_nonready_and_null_scores():
    """Girdi eğitim kapsamı dışındaysa OUT_OF_DOMAIN döner ve skorlar kesinlikle None olur."""
    service = ModelService(MockRegistry(), MockData())

    req = PredictionRequest(
        requestId=uuid4(),
        instrumentId=IID,
        horizonSeconds=3600,
        asOf=datetime(2026, 1, 1, 0, 20, tzinfo=timezone.utc),
        modelBundleId=BID,
        marketDatasetId=DID,
        marketSnapshotId=SID,
        featureVersion="features-v1",
    )

    res = service.predict(req)
    assert res.quality == "OUT_OF_DOMAIN"
    assert res.predictedLogReturn is None
    assert res.volatilityEstimate is None
    assert "OUT_OF_DOMAIN" in res.reasonCodes


def test_schema_mismatch_raises_technical_error():
    """İstek şeması aktif model şeması ile uyuşmazsa FEATURE_SCHEMA_MISMATCH fırlatılır."""
    service = ModelService(MockRegistry(), MockData())

    req = PredictionRequest(
        requestId=uuid4(),
        instrumentId=IID,
        horizonSeconds=3600,
        asOf=datetime(2026, 1, 1, 0, 20, tzinfo=timezone.utc),
        modelBundleId=BID,
        marketDatasetId=DID,
        marketSnapshotId=SID,
        featureVersion="features-v2",  # Uyuşmazlık
    )

    with pytest.raises(ValueError, match="FEATURE_SCHEMA_MISMATCH"):
        service.predict(req)


def test_missing_bundle_raises_model_not_ready():
    """Mevcut olmayan model bundle ID istendiğinde MODEL_NOT_READY fırlatılır."""
    service = ModelService(MockRegistry(), MockData())

    req = PredictionRequest(
        requestId=uuid4(),
        instrumentId=IID,
        horizonSeconds=3600,
        asOf=datetime(2026, 1, 1, 0, 20, tzinfo=timezone.utc),
        modelBundleId=uuid4(),  # Tanımsız bundle
        marketDatasetId=DID,
        marketSnapshotId=SID,
        featureVersion="features-v1",
    )

    with pytest.raises(ValueError, match="MODEL_NOT_READY"):
        service.predict(req)


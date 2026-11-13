"""A10.3 Entegrasyon Testi: Training worker ve PDF deposu kapalıyken serving ve tahmin akışı sürer."""
from datetime import datetime, timezone
from types import SimpleNamespace
from uuid import uuid4

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


class MockModel:
    def predict(self, vector, horizon):
        return (0.0125, 0.025)


class MockRegistry:
    def get(self, b_id):
        return SimpleNamespace(bundle=bundle, models={3600: MockModel()})


class MockData:
    def read(self, d, s, i):
        # 21 mumluk geçerli sentetik veri
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


def test_offline_training_serving_continues():
    """Training API veya PDF deposu çevrimdışıyken ModelService kesintisiz çalışmalıdır."""
    # Training worker ve PDF ingestion hiç başlatılmamıştır
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
    assert res.quality == "READY"
    assert res.predictedLogReturn == 0.0125
    assert res.volatilityEstimate == 0.025
    assert res.requestId == req.requestId


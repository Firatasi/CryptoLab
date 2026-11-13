"""A05/A10: HTTP API sözleşme, yetkilendirme ve hata uç noktası testleri."""
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.api.model_api import create_app
from app.contracts.model import ActiveModelBundle
from app.features.pipeline import SCHEMA_HASH
from app.inference.service import ModelService

SECRET = "x" * 40
BID = uuid4()
DID = uuid4()
SID = uuid4()
IID = uuid4()

B = ActiveModelBundle(
    bundleId=BID,
    numericModelVersion="fixture",
    explanationModelVersion=None,
    featureVersion="features-v1",
    featureSchemaHash=SCHEMA_HASH,
    preprocessingHash="sha256:" + "a" * 64,
    policyVersion="fixture",
    supportedHorizons=[3600],
    artifactHashes={"numeric": "sha256:" + "a" * 64},
    trainingDatasetId=DID,
    knowledgeTrainingDatasetId=None,
    releaseStatus="DEVELOPMENT_CANDIDATE",
)


class Registry:
    def active(self, h):
        return B

    def get(self, b_id):
        if b_id != BID:
            raise ValueError("MODEL_NOT_READY")
        return SimpleNamespace(bundle=B, models={})


class Data:
    def read(self, d, s, i):
        if (d, s, i) != (DID, SID, IID):
            raise ValueError("SNAPSHOT_BINDING_MISMATCH")
        return [], "sha256:fixture"


def client():
    r = Registry()
    return TestClient(create_app(ModelService(r, Data()), r, SECRET))


def headers():
    return {
        "X-Service-Secret": SECRET,
        "X-Request-Id": str(uuid4()),
        "X-Deadline": (datetime.now(timezone.utc) + timedelta(seconds=30)).isoformat(),
    }


def body():
    return {
        "requestId": str(uuid4()),
        "instrumentId": str(IID),
        "horizonSeconds": 3600,
        "asOf": "2026-01-01T00:00:00Z",
        "modelBundleId": str(BID),
        "marketDatasetId": str(DID),
        "marketSnapshotId": str(SID),
        "featureVersion": "features-v1",
    }


def test_auth_required():
    assert client().post("/internal/model/predictions", json=body()).status_code == 401


def test_nonready_and_echo():
    b = body()
    r = client().post("/internal/model/predictions", json=b, headers=headers())
    assert r.status_code == 200 and r.json()["quality"] == "INSUFFICIENT_DATA"
    assert r.json()["predictedLogReturn"] is None and r.json()["requestId"] == b["requestId"]


def test_invalid_body_400():
    assert client().post(
        "/internal/model/predictions",
        json=body() | {"quantity": "100"},
        headers=headers(),
    ).status_code == 400


def test_schema_mismatch_422():
    assert client().post(
        "/internal/model/predictions",
        json=body() | {"featureVersion": "other"},
        headers=headers(),
    ).status_code == 422


def test_missing_model_technical_failure():
    assert client().post(
        "/internal/model/predictions",
        json=body() | {"modelBundleId": str(uuid4())},
        headers=headers(),
    ).status_code == 503


def test_dataset_binding_failure():
    assert client().post(
        "/internal/model/predictions",
        json=body() | {"marketSnapshotId": str(uuid4())},
        headers=headers(),
    ).status_code == 503


def test_bundle_fields():
    r = client().get("/internal/model/active-bundle?horizonSeconds=3600", headers=headers())
    assert r.status_code == 200 and r.json()["releaseStatus"] == "DEVELOPMENT_CANDIDATE"


import asyncio
from datetime import datetime, timedelta, timezone
from uuid import UUID

import httpx
import pytest
from pydantic import ValidationError

from app.api.model_api import create_app
from app.contracts.error import ErrorResponse
from app.contracts.model import (
    ActiveModelBundle,
    PredictionRequest,
    PredictionResult,
)


# Tamami sentetik test kimlikleri; gercek model veya secret degil.
SECRET = "test-only-service-secret-" + "x" * 32
BUNDLE_ID = UUID("44444444-4444-4444-8444-444444444444")
DATASET_ID = UUID("55555555-5555-4555-8555-555555555555")
HASH = "sha256:" + "a" * 64


def request_payload():
    return {
        "requestId": "22222222-2222-4222-8222-222222222222",
        "instrumentId": "33333333-3333-4333-8333-333333333333",
        "horizonSeconds": 3600,
        "asOf": "2026-10-23T12:00:00Z",
        "modelBundleId": str(BUNDLE_ID),
        "marketDatasetId": str(DATASET_ID),
        "marketSnapshotId": "66666666-6666-4666-8666-666666666666",
        "featureVersion": "features-v1",
    }


def result_payload():
    request = request_payload()
    return {
        "requestId": request["requestId"],
        "modelBundleId": request["modelBundleId"],
        "quality": "READY",
        "reasonCodes": [],
        "featureVersion": request["featureVersion"],
        "featureSchemaHash": HASH,
        "featureSnapshotId": "77777777-7777-4777-8777-777777777777",
        "asOf": request["asOf"],
        "horizonSeconds": request["horizonSeconds"],
        "predictedLogReturn": 0.01,
        "volatilityEstimate": 0.02,
        "calibrationVersion": None,
        "observedFeatureSummary": {},
        "latencyMs": 1,
    }


# Fake'ler yalniz test dosyasinda bulunur.
class FakeRegistry:
    def active(self, horizon):
        return ActiveModelBundle(
            bundleId=BUNDLE_ID,
            numericModelVersion="MOCK",
            explanationModelVersion=None,
            featureVersion="features-v1",
            featureSchemaHash=HASH,
            preprocessingHash=HASH,
            policyVersion="test-policy",
            supportedHorizons=[3600],
            artifactHashes={"numeric": HASH},
            trainingDatasetId=DATASET_ID,
            knowledgeTrainingDatasetId=None,
            releaseStatus="DEVELOPMENT_CANDIDATE",
        )


class FakePredictionService:
    def __init__(self, error=None):
        self.error = error
        self.calls = 0

    def predict(self, request):
        self.calls += 1

        if self.error is not None:
            raise self.error

        # Istekteki kimlik ve zaman sonucuna aynen baglanir.
        return PredictionResult.model_validate({
            **result_payload(),
            "requestId": request.requestId,
            "modelBundleId": request.modelBundleId,
            "asOf": request.asOf,
            "horizonSeconds": request.horizonSeconds,
            "featureVersion": request.featureVersion,
        })


def headers():
    return {
        "X-Service-Secret": SECRET,
        "X-Request-Id": "88888888-8888-4888-8888-888888888888",
        "X-Deadline": (
            datetime.now(timezone.utc) + timedelta(seconds=30)
        ).isoformat(),
    }


def call(app, method, path, **kwargs):
    # HTTP sunucusu/port acmadan uygulamaya bellek icinden istek yapar.
    async def execute():
        transport = httpx.ASGITransport(
            app=app,
            raise_app_exceptions=False,
        )
        async with httpx.AsyncClient(
            transport=transport,
            base_url="http://test",
        ) as client:
            return await client.request(method, path, **kwargs)

    return asyncio.run(execute())


@pytest.fixture
def setup_api():
    service = FakePredictionService()
    app = create_app(service, FakeRegistry(), SECRET)
    return app, service


def test_request_json_roundtrip():
    request = PredictionRequest.model_validate(request_payload())
    restored = PredictionRequest.model_validate_json(
        request.model_dump_json()
    )
    assert restored == request


@pytest.mark.parametrize("changes", [
    {"horizonSeconds": 300},
    {"instrumentId": "hatali"},
    {"asOf": "2026-10-23T12:00:00"},
    {"asOf": "2026-10-23T15:00:00+03:00"},
    {"quantity": "100"},
])
def test_invalid_request_rejected(changes):
    with pytest.raises(ValidationError):
        PredictionRequest.model_validate({
            **request_payload(),
            **changes,
        })


def test_ready_result_json_roundtrip():
    result = PredictionResult.model_validate(result_payload())
    assert PredictionResult.model_validate_json(
        result.model_dump_json()
    ) == result


@pytest.mark.parametrize("quality", [
    "INSUFFICIENT_DATA",
    "OUT_OF_DOMAIN",
    "STALE_DATA",
])
def test_nonready_requires_null_scores(quality):
    result = PredictionResult.model_validate({
        **result_payload(),
        "quality": quality,
        "predictedLogReturn": None,
        "volatilityEstimate": None,
    })
    assert result.predictedLogReturn is None
    assert result.volatilityEstimate is None

    with pytest.raises(ValidationError):
        PredictionResult.model_validate({
            **result_payload(),
            "quality": quality,
        })


@pytest.mark.parametrize("changes", [
    {"predictedLogReturn": None},
    {"predictedLogReturn": float("nan")},
    {"predictedLogReturn": float("inf")},
    {"volatilityEstimate": -0.1},
    {"latencyMs": -1},
    {"featureSchemaHash": "hatali"},
])
def test_invalid_result_rejected(changes):
    with pytest.raises(ValidationError):
        PredictionResult.model_validate({
            **result_payload(),
            **changes,
        })


def test_prediction_endpoint(setup_api):
    app, service = setup_api
    response = call(
        app, "POST", "/internal/model/predictions",
        headers=headers(),
        json=request_payload(),
    )

    assert response.status_code == 200
    result = PredictionResult.model_validate(response.json())
    assert str(result.requestId) == request_payload()["requestId"]
    assert result.modelBundleId == BUNDLE_ID
    assert result.quality == "READY"
    assert service.calls == 1


@pytest.mark.parametrize("changes, expected_status", [
    ({"X-Service-Secret": ""}, 401),
    ({"X-Request-Id": "hatali"}, 400),
    ({"X-Deadline": "2000-01-01T00:00:00Z"}, 400),
    ({"X-Deadline": "2026-10-23T12:00:00"}, 400),
])
def test_security_rejects_before_prediction(
    setup_api, changes, expected_status
):
    app, service = setup_api
    response = call(
        app, "POST", "/internal/model/predictions",
        headers={**headers(), **changes},
        json=request_payload(),
    )

    assert response.status_code == expected_status
    ErrorResponse.model_validate(response.json())
    assert service.calls == 0


def test_unknown_body_field_rejected(setup_api):
    app, service = setup_api
    response = call(
        app, "POST", "/internal/model/predictions",
        headers=headers(),
        json={**request_payload(), "pdf": "forbidden"},
    )
    assert response.status_code == 400
    assert service.calls == 0


def test_active_bundle(setup_api):
    app, _ = setup_api
    response = call(
        app, "GET",
        "/internal/model/active-bundle?horizonSeconds=3600",
        headers=headers(),
    )
    assert response.status_code == 200
    bundle = ActiveModelBundle.model_validate(response.json())
    assert bundle.bundleId == BUNDLE_ID


def test_unsupported_horizon(setup_api):
    app, _ = setup_api
    response = call(
        app, "GET",
        "/internal/model/active-bundle?horizonSeconds=300",
        headers=headers(),
    )
    assert response.status_code == 422


@pytest.mark.parametrize("error, status, code", [
    (ValueError("MODEL_NOT_READY"), 503, "MODEL_NOT_READY"),
    (ValueError("FEATURE_SCHEMA_MISMATCH"), 422, "FEATURE_SCHEMA_MISMATCH"),
    (RuntimeError("private-internal-detail"), 503, "MODEL_UNAVAILABLE"),
])
def test_model_errors_are_safe(error, status, code):
    app = create_app(
        FakePredictionService(error=error),
        FakeRegistry(),
        SECRET,
    )
    response = call(
        app, "POST", "/internal/model/predictions",
        headers=headers(),
        json=request_payload(),
    )

    assert response.status_code == status
    result = ErrorResponse.model_validate(response.json())
    assert result.code == code
    assert result.retryable == (status == 503)
    assert "private-internal-detail" not in response.text
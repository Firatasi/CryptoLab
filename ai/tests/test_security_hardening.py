"""Security hardening regression tests for Training API."""
from uuid import uuid4

from fastapi.testclient import TestClient

from app.api.training_api import create_training_app


def _headers(secret: str = "a" * 32) -> dict[str, str]:
    return {
        "X-Service-Secret": secret,
        "X-Actor-Id": str(uuid4()),
        "X-Actor-Roles": "TRAINER",
        "X-Actor-Authorities": "",
        "Idempotency-Key": "sec-test-1",
    }


def test_training_api_disables_swagger_and_enforces_constant_time_secret():
    app = create_training_app(service_secret="a" * 32)
    client = TestClient(app)

    assert client.get("/docs").status_code == 404
    assert client.get("/redoc").status_code == 404
    assert client.get("/openapi.json").status_code == 404

    assert client.get("/internal/training/sources", headers=_headers("b" * 32)).status_code == 401
    assert client.get("/internal/training/sources", headers=_headers("a" * 32)).status_code == 200


def test_training_api_rejects_ssrf_urls_on_source_import():
    app = create_training_app(service_secret="a" * 32)
    client = TestClient(app)

    for bad_url in [
        "http://research.cryptolab.org/paper.pdf",
        "https://localhost/paper.pdf",
        "https://127.0.0.1/paper.pdf",
        "https://169.254.169.254/latest/meta-data",
        "https://user:pass@research.cryptolab.org/paper.pdf",
        "https://metadata.internal/secret.pdf",
    ]:
        resp = client.post(
            "/internal/training/source-imports",
            headers=_headers("a" * 32),
            json={"url": bad_url},
        )
        assert resp.status_code == 400, f"Expected 400 for {bad_url}, got {resp.status_code}"

    ok = client.post(
        "/internal/training/source-imports",
        headers=_headers("a" * 32),
        json={"url": "https://research.cryptolab.org/paper.pdf"},
    )
    assert ok.status_code == 202


def test_training_api_rejects_oversized_upload_before_buffering():
    app = create_training_app(service_secret="a" * 32)
    client = TestClient(app)

    resp = client.post(
        "/internal/training/sources",
        headers=_headers("a" * 32) | {"Content-Length": str(25 * 1024 * 1024)},
        content=b"%PDF-1.4\n",
    )
    assert resp.status_code == 413

"""A06 Testleri: PDF metin çıkarma, URL güvenlik politikası, kaynak repo ve Training API kaynak rotaları."""
from io import BytesIO
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from pypdf import PdfWriter

from app.api.training_api import create_training_app
from app.infrastructure.source_repository import SourceRepository
from app.sources.delete_service import SourceDeleteService
from app.sources.ocr_adapter import OcrAdapter
from app.sources.pdf_extract import extract_pages
from app.sources.url_policy import validate_target


def create_minimal_pdf(pages_text: list[str]) -> bytes:
    writer = PdfWriter()
    for text in pages_text:
        writer.add_blank_page(width=72, height=72)
    stream = BytesIO()
    writer.write(stream)
    return stream.getvalue()


def test_pdf_extract_pages_and_ocr_flag():
    raw = create_minimal_pdf(["sayfa 1"])
    pages = extract_pages(raw)
    assert len(pages) == 1
    assert pages[0]["page"] == 1
    assert "OCR_REQUIRED" in pages[0]["qualityIssues"]
    assert pages[0]["reviewStatus"] == "IN_REVIEW"


def test_pdf_invalid_bytes_and_header():
    with pytest.raises(ValueError, match="TRAINING_SOURCE_INVALID"):
        extract_pages(b"NOT_A_PDF_STREAM")


def test_url_policy_validation():
    # Geçerli durum
    host = validate_target("https://research.cryptolab.org/paper.pdf", ["93.184.216.34"], {"research.cryptolab.org"})
    assert host == "research.cryptolab.org"

    # HTTP reddi
    with pytest.raises(ValueError, match="IMPORT_URL_REJECTED"):
        validate_target("http://research.cryptolab.org/paper.pdf", ["93.184.216.34"], {"research.cryptolab.org"})

    # Host whitelist reddi
    with pytest.raises(ValueError, match="IMPORT_HOST_REJECTED"):
        validate_target("https://evil.com/a.pdf", ["93.184.216.34"], {"research.cryptolab.org"})

    # Private IP reddi (SSRF)
    for bad_ip in ["127.0.0.1", "10.0.0.5", "192.168.1.1", "169.254.169.254", "::1"]:
        with pytest.raises(ValueError, match="IMPORT_IP_REJECTED"):
            validate_target("https://research.cryptolab.org/paper.pdf", [bad_ip], {"research.cryptolab.org"})


def test_ocr_adapter_quality_eval():
    adapter = OcrAdapter()
    empty_res = adapter.process_page(1, "")
    assert "OCR_REQUIRED" in empty_res["qualityIssues"]
    assert empty_res["reviewStatus"] == "REVIEW_REQUIRED"

    good_res = adapter.process_page(2, "Bu sayfa Smart Money Concepts ve Likidite temizleme kurallarını anlatır.")
    assert not good_res["qualityIssues"]
    assert good_res["reviewStatus"] == "IN_REVIEW"


def test_source_repository_and_delete_service():
    repo = SourceRepository()
    del_service = SourceDeleteService(repo)

    owner = uuid4()
    source_id = uuid4()
    repo.save_source(source_id, owner, "Kitap 01", "Telif İzinli", "REVIEW_REQUIRED", version=1)

    # Başka actor silemez
    other_actor = uuid4()
    with pytest.raises(PermissionError):
        del_service.delete_source(other_actor, is_admin=False, source_id=source_id, expected_version=1)

    # Version mismatch
    with pytest.raises(ValueError, match="VERSION_CONFLICT"):
        del_service.delete_source(owner, is_admin=False, source_id=source_id, expected_version=2)

    # Başarılı silme
    res = del_service.delete_source(owner, is_admin=False, source_id=source_id, expected_version=1)
    assert res["status"] == "DELETED"
    assert res["version"] == 2
    assert "RETRAIN_REQUIRED" in res["lineageImpact"]


def test_training_api_sources_endpoint():
    app = create_training_app(service_secret="test-secret")
    client = TestClient(app)

    actor_id = str(uuid4())
    headers = {
        "X-Service-Secret": "test-secret",
        "X-Actor-Id": actor_id,
        "X-Actor-Roles": "TRAINER",
        "X-Actor-Authorities": "",
        "Idempotency-Key": "key-001",
    }

    # 401 Unauthorized
    assert client.get("/internal/training/sources", headers={"X-Service-Secret": "bad"}).status_code == 401

    # 403 Forbidden (USER role)
    assert client.get(
        "/internal/training/sources",
        headers=headers | {"X-Actor-Roles": "USER"},
    ).status_code == 403

    # 200 List
    r = client.get("/internal/training/sources", headers=headers)
    assert r.status_code == 200
    assert "sources" in r.json()


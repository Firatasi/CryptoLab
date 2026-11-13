"""A06: Yalnız izole training/parser process'inde import edilir.
PDF metin çıkarma, sayfa sınırları ve OCR ihtiyaç tespiti.
"""
from io import BytesIO
from typing import Any
from pypdf import PdfReader


def extract_pages(
    raw: bytes,
    max_bytes: int = 20 * 1024 * 1024,
    max_pages: int = 300,
) -> list[dict[str, Any]]:
    """B10 sözleşmesine göre 20 MiB ve 300 sayfa sınırıyla PDF metinlerini çıkarır."""
    if not raw or len(raw) > max_bytes or not raw.startswith(b"%PDF-"):
        raise ValueError("TRAINING_SOURCE_INVALID")
    
    try:
        pdf = PdfReader(BytesIO(raw), strict=True)
    except Exception as e:
        raise ValueError("TRAINING_SOURCE_INVALID") from e

    if pdf.is_encrypted:
        raise ValueError("ENCRYPTED_SOURCE")

    if len(pdf.pages) > max_pages:
        raise ValueError("PAGE_LIMIT")

    pages: list[dict[str, Any]] = []
    for number, page in enumerate(pdf.pages, 1):
        try:
            text = page.extract_text() or ""
        except Exception:
            text = ""

        # Metin yoksa veya çok azsa OCR_REQUIRED kalite bayrağı eklenir;
        # taranmış sayfa doğrudan READY_FOR_EXAMPLES yapılmaz.
        quality_issues = [] if text.strip() else ["OCR_REQUIRED"]
        pages.append({
            "page": number,
            "text": text,
            "qualityIssues": quality_issues,
            "reviewStatus": "IN_REVIEW",
        })

    return pages


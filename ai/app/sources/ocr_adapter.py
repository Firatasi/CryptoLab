"""A06: Scanned sayfalar, OCR kalite kontrolü ve review işaretleme adaptörü."""
from typing import Any


class OcrAdapter:
    """Taranmış veya metinsiz sayfalar için OCR değerlendirme adaptörü."""

    def __init__(self, tool_name: str = "tesseract-reference", version: str = "5.3.0", language: str = "tur+eng"):
        self.tool_name = tool_name
        self.version = version
        self.language = language

    def process_page(self, page_number: int, raw_page_text: str, image_bytes: bytes | None = None) -> dict[str, Any]:
        """Sayfa metnini inceler; boş veya okunamayan sayfalara OCR_REQUIRED bayrağı ve review durumu atar."""
        stripped = raw_page_text.strip()
        if not stripped:
            return {
                "page": page_number,
                "text": "",
                "qualityIssues": ["OCR_REQUIRED"],
                "reviewStatus": "REVIEW_REQUIRED",
                "ocrMetadata": {
                    "tool": self.tool_name,
                    "version": self.version,
                    "language": self.language,
                    "status": "PENDING_OCR_OR_HUMAN_REVIEW",
                },
            }

        # Metin varsa temel kalite kontrolleri
        issues = []
        if len(stripped) < 20:
            issues.append("LOW_CHARACTER_COUNT")

        return {
            "page": page_number,
            "text": raw_page_text,
            "qualityIssues": issues,
            "reviewStatus": "IN_REVIEW" if not issues else "REVIEW_REQUIRED",
            "ocrMetadata": None,
        }


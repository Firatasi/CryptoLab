"""A06: Dış kütüphane bağımlılığı (python-multipart) olmaksızın çalışan standart multipart/form-data ayrıştırıcısı."""
import re
from typing import Any


def parse_multipart(content_type: str, body: bytes) -> tuple[dict[str, str], bytes | None]:
    """Content-Type başlığındaki boundary'yi kullanarak form alanlarını ve PDF dosya byte'larını ayrıştırır."""
    match = re.search(r"boundary=([^\s;]+)", content_type, re.IGNORECASE)
    if not match:
        raise ValueError("MISSING_BOUNDARY")

    boundary = match.group(1).strip('"\'').encode("utf-8")
    delimiter = b"--" + boundary
    parts = body.split(delimiter)

    fields: dict[str, str] = {}
    file_bytes: bytes | None = None

    for part in parts:
        part = part.strip()
        if not part or part == b"--":
            continue

        if b"\r\n\r\n" in part:
            header_bytes, payload = part.split(b"\r\n\r\n", 1)
        elif b"\n\n" in part:
            header_bytes, payload = part.split(b"\n\n", 1)
        else:
            continue

        # Trailing CRLF kaldır
        if payload.endswith(b"\r\n"):
            payload = payload[:-2]
        elif payload.endswith(b"\n"):
            payload = payload[:-1]

        header_str = header_bytes.decode("utf-8", errors="replace")

        # name="..." ara
        name_match = re.search(r'name=["\']([^"\']+)["\']', header_str)
        if not name_match:
            continue
        field_name = name_match.group(1)

        # filename="..." kontrolü
        filename_match = re.search(r'filename=["\']([^"\']+)["\']', header_str)
        if filename_match or field_name == "file":
            file_bytes = payload
        else:
            fields[field_name] = payload.decode("utf-8", errors="replace")

    return fields, file_bytes


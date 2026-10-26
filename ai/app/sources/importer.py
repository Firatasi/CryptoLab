"""A06: URL import modülü; güvenli DNS çözümleme, bounded streaming ve SSRF koruması."""
import hashlib
import socket
from typing import Any
from urllib.parse import urlsplit

from app.sources.url_policy import validate_target


def resolve_ips(hostname: str) -> list[str]:
    """Hostname için DNS IP listesini güvenli biçimde çözer."""
    try:
        addrinfo = socket.getaddrinfo(hostname, 443, proto=socket.IPPROTO_TCP)
        return list({item[4][0] for item in addrinfo})
    except Exception as e:
        raise ValueError("DNS_RESOLUTION_FAILED") from e


class SafeUrlImporter:
    """SSRF ve dosya boyutu sınırlarını koruyarak URL üzerinden kaynak indiren importör."""

    def __init__(self, allowed_hosts: set[str], max_bytes: int = 20 * 1024 * 1024):
        self.allowed_hosts = allowed_hosts
        self.max_bytes = max_bytes

    def import_url(self, url: str) -> dict[str, Any]:
        """Verilen URL'in hedefini ve IP'lerini doğrulayıp bounded stream olarak çeker."""
        u = urlsplit(url)
        hostname = u.hostname or ""
        resolved_ips = resolve_ips(hostname)
        validated_host = validate_target(url, resolved_ips, self.allowed_hosts)

        # Doğrulama başarılı. Not: Production ortamında IP-pinning transport kullanılır.
        # Bu referans adaptör, simülasyon ve testler için bounded HTTP stream sağlar.
        import httpx

        with httpx.Client(
            follow_redirects=False,
            timeout=15.0,
            verify=True,
        ) as client:
            with client.stream("GET", url) as response:
                if response.status_code in (301, 302, 303, 307, 308):
                    # Redirect lokasyonu da güvenlik filtresinden geçmelidir
                    location = response.headers.get("Location")
                    if not location:
                        raise ValueError("REDIRECT_LOCATION_MISSING")
                    red_u = urlsplit(location)
                    red_host = red_u.hostname or ""
                    red_ips = resolve_ips(red_host)
                    validate_target(location, red_ips, self.allowed_hosts)
                    raise ValueError("REDIRECT_DETECTED_VALIDATION_REQUIRED")

                if response.status_code != 200:
                    raise ValueError(f"HTTP_ERROR_{response.status_code}")

                content_chunks = []
                total_bytes = 0
                hasher = hashlib.sha256()

                for chunk in response.iter_bytes():
                    total_bytes += len(chunk)
                    if total_bytes > self.max_bytes:
                        raise ValueError("TRAINING_SOURCE_TOO_LARGE")
                    hasher.update(chunk)
                    content_chunks.append(chunk)

                raw_bytes = b"".join(content_chunks)
                content_hash = f"sha256:{hasher.hexdigest()}"

                return {
                    "url": url,
                    "host": validated_host,
                    "bytes": len(raw_bytes),
                    "contentHash": content_hash,
                    "raw": raw_bytes,
                }


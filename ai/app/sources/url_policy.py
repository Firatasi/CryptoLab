"""A06: Network importer'ın saf ön kontrolü; tek başına SSRF çözümü değildir.
HTTPS, allowed hosts ve global IP doğrulama kuralları.
"""
from ipaddress import ip_address
from urllib.parse import urlsplit


def validate_target(url: str, resolved_ips: list[str], allowed_hosts: set[str]) -> str:
    """Import edilecek hedef URL ve çözülmüş IP'lerin güvenlik politikasını doğrular.
    
    Yalnız HTTPS, varsayılan/443 portu, yetkili hostlar ve küresel (public) IP'lere izin verilir.
    Loopback, link-local, private veya multicast IP'ler reddedilir.
    """
    try:
        u = urlsplit(url)
    except Exception as e:
        raise ValueError("IMPORT_URL_REJECTED") from e

    if (
        u.scheme != "https"
        or not u.hostname
        or u.username
        or u.password
        or u.port not in (None, 443)
    ):
        raise ValueError("IMPORT_URL_REJECTED")

    if u.hostname.lower() not in {h.lower() for h in allowed_hosts}:
        raise ValueError("IMPORT_HOST_REJECTED")

    if not resolved_ips:
        raise ValueError("IMPORT_IP_REJECTED")

    for ip in resolved_ips:
        try:
            addr = ip_address(ip)
            if not addr.is_global or addr.is_private or addr.is_loopback or addr.is_link_local:
                raise ValueError("IMPORT_IP_REJECTED")
        except ValueError:
            raise ValueError("IMPORT_IP_REJECTED")

    return u.hostname.lower()


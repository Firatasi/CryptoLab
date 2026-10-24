"""A02.2: B06 dataset export'unu okuyan yetkili artifact reader.

Sinir kurallari:
- Market DB'ye veya market tablolarina baglanilmaz; yalniz B06 internal API.
- POST dataset-exports yalniz job kabul eder; READY'ye kadar sinirli backoff.
- Artifact, yalniz artifactId ile ve YAPILANDIRILMIS internal download
  route'undan indirilir. Payload'daki URL calistirilmaz.
- SHA256 gercek byte'lardan dogrulanir, content-address cache'e konur.
- Yanlis checksum = teknik hata (exception). Coverage gap = veri kalite
  sonucu (LoadedDataset.quality), exception degil.

VARSAYIM (A02.7'de gercek B06 ciktisiyla dogrulanacak): READY yanitindaki
alan adlari README 8.1'den gelir; `snapshotIds` ve job yanitindaki
`jobId`/`status` adlari bu adapter'da normalize edilir. Artifact download
route'u repoda yoktur; uydurulmaz, `download_path_template` ile verilir.
"""
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from hashlib import sha256
from hmac import compare_digest
import json
import os
from pathlib import Path
import re
import tempfile
import time
from typing import Any, Protocol
from uuid import UUID, uuid4

import httpx
from pydantic import AwareDatetime, ConfigDict, Field, ValidationError
from pydantic import field_validator

from app.contracts.model import Hash, StrictModel
from app.features.pipeline import INTERVAL_SECONDS, Candle

EXPORT_COLLECTION_PATH = "/internal/market/dataset-exports"
DEFAULT_MAX_ARTIFACT_BYTES = 64 * 1024 * 1024
FAILED_STATES = {"FAILED", "CANCELLED", "REJECTED", "EXPIRED"}
_HEX64 = re.compile(r"^[0-9a-f]{64}$")


class MarketDatasetError(Exception):
    """Teknik hata. `code` programatik, `retryable` tekrar denenebilirlik."""

    def __init__(self, code: str, retryable: bool = False, **details: Any):
        super().__init__(code)
        self.code = code
        self.retryable = retryable
        self.details = details


# --------------------------------------------------------------------------
# Istek ve manifest modelleri
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class ExportSpec:
    instrument_ids: tuple[UUID, ...]
    start: datetime
    end: datetime
    intervals: tuple[str, ...]
    price_kinds: tuple[str, ...]
    as_of_cutoff: datetime

    def __post_init__(self) -> None:
        if not self.instrument_ids or not self.intervals or not self.price_kinds:
            raise ValueError("EXPORT_SPEC_EMPTY")
        for value in (self.start, self.end, self.as_of_cutoff):
            if value.tzinfo is None or value.utcoffset() != timedelta(0):
                raise ValueError("UTC_REQUIRED")
        if self.start >= self.end:
            raise ValueError("EXPORT_RANGE_INVALID")

    def to_payload(self) -> dict:
        def iso(value: datetime) -> str:
            return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

        return {
            "instrumentIds": [str(i) for i in self.instrument_ids],
            "from": iso(self.start),
            "to": iso(self.end),
            "intervals": list(self.intervals),
            "priceKinds": list(self.price_kinds),
            "asOfCutoff": iso(self.as_of_cutoff),
        }


class MissingRange(StrictModel):
    model_config = ConfigDict(
        extra="forbid", allow_inf_nan=False, populate_by_name=True,
    )

    instrumentId: UUID | None = None
    interval: str | None = None
    priceKind: str | None = None
    from_: AwareDatetime = Field(alias="from")
    to: AwareDatetime


class MarketDatasetManifest(StrictModel):
    datasetId: UUID
    artifactId: UUID
    sha256: Hash
    coverage: dict
    missingRanges: list[MissingRange]
    availableAtPolicy: str = Field(min_length=1)

    # Istek ve READY yanitindan birlestirilen bagler.
    instrumentIds: list[UUID]
    snapshotIds: list[UUID]
    asOfCutoff: AwareDatetime

    @field_validator("sha256", mode="before")
    @classmethod
    def normalize_sha(cls, value):
        # B06 ham hex ya da "sha256:" onekli dondurebilir; tek bicime cekilir.
        if isinstance(value, str):
            value = value.lower()
            if _HEX64.match(value):
                return "sha256:" + value
        return value


def normalize_manifest(raw: dict, spec: ExportSpec) -> MarketDatasetManifest:
    """B06 READY yanitini tek bicime cevirir. Bilinmeyen alanlar yok sayilir."""
    try:
        return MarketDatasetManifest.model_validate({
            "datasetId": raw["datasetId"],
            "artifactId": raw["artifactId"],
            "sha256": raw["sha256"],
            "coverage": raw["coverage"],
            "missingRanges": [
                {k: item[k] for k in
                 ("instrumentId", "interval", "priceKind", "from", "to")
                 if k in item}
                for item in raw["missingRanges"]
            ],
            "availableAtPolicy": raw["availableAtPolicy"],
            "snapshotIds": raw.get("snapshotIds", []),
            "instrumentIds": list(spec.instrument_ids),
            "asOfCutoff": spec.as_of_cutoff,
        })
    except (KeyError, TypeError, ValidationError) as exc:
        raise MarketDatasetError("MANIFEST_INVALID") from exc


@dataclass(frozen=True)
class LoadedDataset:
    manifest: MarketDatasetManifest
    candles: tuple[Candle, ...]

    # READY | COVERAGE_GAP: veri kalite sonucu; teknik hata degildir.
    quality: str
    reason_codes: tuple[str, ...]


# --------------------------------------------------------------------------
# Taşıma portu ve HTTP adapter'ı
# --------------------------------------------------------------------------

class MarketExportTransport(Protocol):
    def submit_export(self, spec: ExportSpec) -> str: ...

    def get_export(self, job_id: str) -> dict: ...

    def download_artifact(self, artifact_id: UUID, max_bytes: int) -> bytes: ...


class HttpMarketExportTransport:
    """B06 internal API istemcisi. Yalniz sabit base_url'e gider."""

    def __init__(
        self,
        base_url: str,
        secret: str,
        download_path_template: str | None = None,
        timeout_seconds: float = 10.0,
        client: httpx.Client | None = None,
    ) -> None:
        if not secret or len(secret) < 32:
            raise ValueError("SERVICE_SECRET_REQUIRED")

        if download_path_template is not None:
            # Yalniz goreli yol; sablonda tek {artifactId} yer tutucusu olmali.
            if (
                not download_path_template.startswith("/")
                or "//" in download_path_template
                or "://" in download_path_template
                or download_path_template.count("{artifactId}") != 1
            ):
                raise ValueError("DOWNLOAD_PATH_TEMPLATE_INVALID")

        self._secret = secret
        self._download_path_template = download_path_template
        self._timeout = timeout_seconds
        # Redirect takip edilmez: baska host'a sapma olmaz.
        self._client = client or httpx.Client(
            base_url=base_url,
            timeout=timeout_seconds,
            follow_redirects=False,
        )

    def _headers(self) -> dict:
        return {
            "X-Service-Secret": self._secret,
            "X-Request-Id": str(uuid4()),
            "X-Deadline": (
                datetime.now(timezone.utc) + timedelta(seconds=self._timeout)
            ).isoformat(),
        }

    @staticmethod
    def _fail(response: httpx.Response) -> MarketDatasetError:
        status = response.status_code
        return MarketDatasetError(
            "MARKET_EXPORT_HTTP_ERROR",
            retryable=status == 429 or status >= 500,
            status=status,
        )

    def submit_export(self, spec: ExportSpec) -> str:
        try:
            response = self._client.post(
                EXPORT_COLLECTION_PATH,
                json=spec.to_payload(),
                headers=self._headers(),
            )
        except httpx.TransportError as exc:
            raise MarketDatasetError("MARKET_EXPORT_UNREACHABLE", True) from exc

        if response.status_code != 202:
            raise self._fail(response)

        body = response.json()
        job_id = body.get("jobId") or body.get("id")
        if not isinstance(job_id, str) or not job_id:
            raise MarketDatasetError("EXPORT_JOB_ID_MISSING")

        # Yol parcasi olarak kullanilacagi icin UUID biciminde olmali.
        try:
            return str(UUID(job_id))
        except ValueError as exc:
            raise MarketDatasetError("EXPORT_JOB_ID_INVALID") from exc

    def get_export(self, job_id: str) -> dict:
        try:
            response = self._client.get(
                f"{EXPORT_COLLECTION_PATH}/{UUID(job_id)}",
                headers=self._headers(),
            )
        except httpx.TransportError as exc:
            raise MarketDatasetError("MARKET_EXPORT_UNREACHABLE", True) from exc

        if response.status_code != 200:
            raise self._fail(response)

        return response.json()

    def download_artifact(self, artifact_id: UUID, max_bytes: int) -> bytes:
        if self._download_path_template is None:
            # Gercek B06 controller/OpenAPI yolu verilmeden endpoint uydurulmaz.
            raise MarketDatasetError("ARTIFACT_ROUTE_NOT_CONFIGURED")

        path = self._download_path_template.format(artifactId=str(artifact_id))
        data = bytearray()

        try:
            with self._client.stream(
                "GET", path, headers=self._headers(),
            ) as response:
                if response.status_code != 200:
                    raise self._fail(response)

                for chunk in response.iter_bytes():
                    data.extend(chunk)
                    if len(data) > max_bytes:
                        raise MarketDatasetError("ARTIFACT_TOO_LARGE")
        except httpx.TransportError as exc:
            raise MarketDatasetError("MARKET_EXPORT_UNREACHABLE", True) from exc

        return bytes(data)


# --------------------------------------------------------------------------
# Content-address cache
# --------------------------------------------------------------------------

def sha256_ref(data: bytes) -> str:
    return "sha256:" + sha256(data).hexdigest()


class ContentAddressedCache:
    """Anahtar = byte'larin SHA-256'si. Okurken hash yeniden dogrulanir."""

    def __init__(self, root: Path) -> None:
        self._root = Path(root)

    def _path(self, ref: str) -> Path:
        hex_part = ref.removeprefix("sha256:")
        if not _HEX64.match(hex_part):
            raise ValueError("CACHE_KEY_INVALID")
        return self._root / hex_part

    def get(self, ref: str) -> bytes | None:
        path = self._path(ref)
        try:
            data = path.read_bytes()
        except FileNotFoundError:
            return None

        if sha256_ref(data) != ref:
            # Bozulmus cache girdisi kullanilmaz.
            path.unlink(missing_ok=True)
            return None
        return data

    def put(self, ref: str, data: bytes) -> None:
        if sha256_ref(data) != ref:
            raise ValueError("CACHE_CONTENT_MISMATCH")

        path = self._path(ref)
        self._root.mkdir(parents=True, exist_ok=True)

        # Atomik yazim: yarim dosya cache anahtariyla gorunmez.
        fd, tmp = tempfile.mkstemp(dir=self._root)
        try:
            with os.fdopen(fd, "wb") as handle:
                handle.write(data)
            os.replace(tmp, path)
        finally:
            if os.path.exists(tmp):
                os.unlink(tmp)


# --------------------------------------------------------------------------
# JSONL -> Candle normalizasyonu
# --------------------------------------------------------------------------

def _parse_time(value: Any) -> datetime:
    if not isinstance(value, str):
        raise ValueError("TIME_NOT_STRING")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() != timedelta(0):
        raise ValueError("TIME_NOT_UTC")
    return parsed


def _parse_decimal(value: Any) -> Decimal:
    # Finansal degerler JSON string olarak gelir; number kabul edilmez.
    if not isinstance(value, str):
        raise ValueError("DECIMAL_NOT_STRING")
    try:
        parsed = Decimal(value)
    except InvalidOperation as exc:
        raise ValueError("DECIMAL_INVALID") from exc
    if not parsed.is_finite():
        raise ValueError("DECIMAL_NOT_FINITE")
    return parsed


def parse_candle_record(record: dict, spec: ExportSpec) -> Candle:
    """B06 mum alanlarini (README 4) Candle'a cevirir; kurallari dogrular."""
    instrument_id = UUID(record["instrumentId"])
    interval = record["interval"]
    price_kind = record["priceKind"]

    if instrument_id not in spec.instrument_ids:
        raise ValueError("INSTRUMENT_NOT_REQUESTED")
    if interval not in spec.intervals:
        raise ValueError("INTERVAL_NOT_REQUESTED")
    if price_kind not in spec.price_kinds:
        raise ValueError("PRICE_KIND_NOT_REQUESTED")
    if not isinstance(record["finalized"], bool):
        raise ValueError("FINALIZED_NOT_BOOL")

    open_time = _parse_time(record["openTime"])
    close_time = _parse_time(record["closeTime"])
    available_at = _parse_time(record["availableAt"])

    expected = INTERVAL_SECONDS.get(interval)
    if expected is not None and (close_time - open_time).total_seconds() != expected:
        raise ValueError("INTERVAL_LENGTH_MISMATCH")

    if available_at > spec.as_of_cutoff:
        # Cutoff'tan sonra yayimlanan veri gecmise tasinamaz.
        raise ValueError("AVAILABLE_AT_AFTER_CUTOFF")

    volume = record.get("volume")
    return Candle(
        instrument_id=instrument_id,
        close_time=close_time,
        available_at=available_at,
        open=_parse_decimal(record["open"]),
        high=_parse_decimal(record["high"]),
        low=_parse_decimal(record["low"]),
        close=_parse_decimal(record["close"]),
        finalized=record["finalized"],
        interval=interval,
        price_kind=price_kind,
        volume=None if volume is None else _parse_decimal(volume),
    )


def parse_jsonl(data: bytes, spec: ExportSpec) -> tuple[Candle, ...]:
    candles = []
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise MarketDatasetError("ARTIFACT_RECORD_INVALID", line=0) from exc

    for number, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue
        try:
            candles.append(parse_candle_record(json.loads(line), spec))
        except (ValueError, KeyError, TypeError) as exc:
            # Satir icerigi hataya konmaz; yalniz satir no ve kod.
            raise MarketDatasetError(
                "ARTIFACT_RECORD_INVALID", line=number, reason=str(exc),
            ) from exc

    return tuple(candles)


# --------------------------------------------------------------------------
# Reader
# --------------------------------------------------------------------------

class MarketDatasetReader:
    def __init__(
        self,
        transport: MarketExportTransport,
        cache: ContentAddressedCache,
        sleep=time.sleep,
        poll_attempts: int = 8,
        initial_delay_seconds: float = 0.5,
        max_delay_seconds: float = 8.0,
        max_artifact_bytes: int = DEFAULT_MAX_ARTIFACT_BYTES,
    ) -> None:
        self._transport = transport
        self._cache = cache
        self._sleep = sleep
        self._poll_attempts = poll_attempts
        self._initial_delay = initial_delay_seconds
        self._max_delay = max_delay_seconds
        self._max_bytes = max_artifact_bytes
        self._datasets: dict[UUID, LoadedDataset] = {}

    def _await_ready(self, job_id: str) -> dict:
        delay = self._initial_delay
        for attempt in range(self._poll_attempts):
            raw = self._transport.get_export(job_id)
            status = str(raw.get("status", "")).upper()

            if status == "READY":
                return raw
            if status in FAILED_STATES:
                raise MarketDatasetError("EXPORT_JOB_FAILED", status=status)

            if attempt + 1 < self._poll_attempts:
                self._sleep(delay)
                # Sinirli ussel backoff.
                delay = min(delay * 2, self._max_delay)

        raise MarketDatasetError("EXPORT_TIMEOUT", retryable=True)

    def load(self, spec: ExportSpec) -> LoadedDataset:
        job_id = self._transport.submit_export(spec)
        manifest = normalize_manifest(self._await_ready(job_id), spec)

        data = self._cache.get(manifest.sha256)
        if data is None:
            data = self._transport.download_artifact(
                manifest.artifactId, self._max_bytes,
            )
            # Hash, B06'nin soyledigi degerden degil gercek byte'lardan hesaplanir.
            if not compare_digest(
                sha256_ref(data).encode(), manifest.sha256.encode(),
            ):
                raise MarketDatasetError("ARTIFACT_CHECKSUM_MISMATCH")
            self._cache.put(manifest.sha256, data)

        candles = parse_jsonl(data, spec)

        reasons = []
        if manifest.missingRanges:
            reasons.append("COVERAGE_GAP")
        present = {c.instrument_id for c in candles}
        if any(i not in present for i in spec.instrument_ids):
            reasons.append("INSTRUMENT_WITHOUT_CANDLES")

        loaded = LoadedDataset(
            manifest=manifest,
            candles=candles,
            quality="COVERAGE_GAP" if reasons else "READY",
            reason_codes=tuple(reasons),
        )
        self._datasets[manifest.datasetId] = loaded
        return loaded

    def _dataset(self, dataset_id: UUID) -> LoadedDataset:
        loaded = self._datasets.get(dataset_id)
        if loaded is None:
            raise MarketDatasetError("DATASET_NOT_LOADED")
        return loaded

    def dataset_hash(self, dataset_id: UUID) -> str:
        return self._dataset(dataset_id).manifest.sha256

    def read(
        self, dataset_id: UUID, snapshot_id: UUID, instrument_id: UUID,
    ) -> list[Candle]:
        """dataset-snapshot-instrument baginı manifest uzerinden dogrular."""
        manifest = self._dataset(dataset_id).manifest

        if snapshot_id not in manifest.snapshotIds:
            # Baska dataset'e ait ya da bilinmeyen snapshot.
            raise MarketDatasetError("SNAPSHOT_DATASET_MISMATCH")
        if instrument_id not in manifest.instrumentIds:
            raise MarketDatasetError("INSTRUMENT_NOT_IN_DATASET")

        return [
            c for c in self._dataset(dataset_id).candles
            if c.instrument_id == instrument_id
        ]


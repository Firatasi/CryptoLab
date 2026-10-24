"""A02.2 testleri: B06 export reader, checksum, coverage ve baglar.

Tum veri SENTETIKTIR; B06 gercek export'u degildir (bkz. A02.7).
"""
from datetime import timedelta
from hashlib import sha256
import json
from uuid import UUID, uuid4

import httpx
import pytest

from app.infrastructure.market_dataset import (
    ContentAddressedCache,
    ExportSpec,
    HttpMarketExportTransport,
    MarketDatasetError,
    MarketDatasetReader,
    sha256_ref,
)
from synthetic_market import (
    ASSET_A,
    ASSET_B,
    ASSET_C,
    START,
    candle_record,
    make_series,
)

SECRET = "test-only-service-secret-" + "x" * 32
JOB_ID = UUID("99999999-9999-4999-8999-999999999999")
DATASET_ID = UUID("55555555-5555-4555-8555-555555555555")
OTHER_DATASET_ID = UUID("55555555-5555-4555-8555-555555555556")
ARTIFACT_ID = UUID("77777777-7777-4777-8777-777777777777")
SNAPSHOT_ID = UUID("66666666-6666-4666-8666-666666666666")
FOREIGN_SNAPSHOT_ID = UUID("66666666-6666-4666-8666-666666666667")
DOWNLOAD_TEMPLATE = "/internal/test-only/artifacts/{artifactId}"


def spec(**changes):
    values = dict(
        instrument_ids=(ASSET_A, ASSET_B),
        start=START,
        end=START + timedelta(hours=1),
        intervals=("1m",),
        price_kinds=("TRADE",),
        as_of_cutoff=START + timedelta(hours=1),
    )
    values.update(changes)
    return ExportSpec(**values)


def artifact_bytes(records=None):
    if records is None:
        candles = (
            make_series(ASSET_A, [100, 101, 102])
            + make_series(ASSET_B, [50, 51, 52])
        )
        records = [candle_record(c) for c in candles]
    return "\n".join(json.dumps(r) for r in records).encode()


def ready_payload(data, **changes):
    payload = {
        "status": "READY",
        "datasetId": str(DATASET_ID),
        "artifactId": str(ARTIFACT_ID),
        "sha256": sha256(data).hexdigest(),
        "coverage": {"fixture": True},
        "missingRanges": [],
        "availableAtPolicy": "AVAILABLE_AT_LTE_ASOF",
        "snapshotIds": [str(SNAPSHOT_ID)],
        # Payload'daki URL asla cagrilmamali.
        "downloadUrl": "https://evil.example/steal",
    }
    payload.update(changes)
    return payload


class FakeTransport:
    def __init__(self, data, statuses=("READY",), **payload_changes):
        self.data = data
        self.statuses = list(statuses)
        self.payload_changes = payload_changes
        self.polls = 0
        self.downloads = 0

    def submit_export(self, export_spec):
        return str(JOB_ID)

    def get_export(self, job_id):
        status = self.statuses[min(self.polls, len(self.statuses) - 1)]
        self.polls += 1
        if status != "READY":
            return {"status": status}
        return ready_payload(self.data, **self.payload_changes)

    def download_artifact(self, artifact_id, max_bytes):
        assert artifact_id == ARTIFACT_ID
        self.downloads += 1
        return self.data


def reader(transport, tmp_path, sleeps=None, **kwargs):
    sleeps = sleeps if sleeps is not None else []
    return MarketDatasetReader(
        transport,
        ContentAddressedCache(tmp_path / "cache"),
        sleep=sleeps.append,
        **kwargs,
    )


def error_code(call):
    with pytest.raises(MarketDatasetError) as caught:
        call()
    return caught.value


# ---------------------------------------------------------- reader akisi

def test_loads_ready_dataset_and_reads_by_manifest(tmp_path):
    data = artifact_bytes()
    r = reader(FakeTransport(data), tmp_path)

    loaded = r.load(spec())

    assert loaded.quality == "READY"
    assert loaded.reason_codes == ()
    assert loaded.manifest.sha256 == sha256_ref(data)
    assert len(loaded.candles) == 6

    candles = r.read(DATASET_ID, SNAPSHOT_ID, ASSET_A)
    assert {c.instrument_id for c in candles} == {ASSET_A}
    assert len(candles) == 3
    assert r.dataset_hash(DATASET_ID) == sha256_ref(data)


def test_polling_uses_bounded_backoff(tmp_path):
    transport = FakeTransport(
        artifact_bytes(), ["QUEUED", "RUNNING", "RUNNING", "RUNNING", "READY"],
    )
    sleeps = []
    reader(
        transport, tmp_path, sleeps,
        initial_delay_seconds=1, max_delay_seconds=3,
    ).load(spec())

    assert sleeps == [1, 2, 3, 3]


def test_polling_gives_up_with_retryable_timeout(tmp_path):
    transport = FakeTransport(artifact_bytes(), ["RUNNING"])
    sleeps = []
    err = error_code(lambda: reader(
        transport, tmp_path, sleeps, poll_attempts=3,
    ).load(spec()))

    assert err.code == "EXPORT_TIMEOUT" and err.retryable
    assert transport.polls == 3
    assert len(sleeps) == 2
    assert transport.downloads == 0


def test_failed_job_is_technical_error(tmp_path):
    err = error_code(lambda: reader(
        FakeTransport(artifact_bytes(), ["RUNNING", "FAILED"]), tmp_path,
    ).load(spec()))
    assert err.code == "EXPORT_JOB_FAILED" and not err.retryable


def test_wrong_checksum_is_technical_error_and_not_cached(tmp_path):
    data = artifact_bytes()
    transport = FakeTransport(data, sha256=sha256(b"baska").hexdigest())
    r = reader(transport, tmp_path)

    err = error_code(lambda: r.load(spec()))
    assert err.code == "ARTIFACT_CHECKSUM_MISMATCH"
    assert not (tmp_path / "cache").exists() or not list(
        (tmp_path / "cache").iterdir()
    )
    with pytest.raises(MarketDatasetError):
        r.read(DATASET_ID, SNAPSHOT_ID, ASSET_A)


def test_coverage_gap_is_quality_result_not_exception(tmp_path):
    missing = [{
        "instrumentId": str(ASSET_A),
        "from": "2026-10-23T12:03:00Z",
        "to": "2026-10-23T12:04:00Z",
    }]
    r = reader(FakeTransport(artifact_bytes(), missingRanges=missing), tmp_path)

    loaded = r.load(spec())
    assert loaded.quality == "COVERAGE_GAP"
    assert "COVERAGE_GAP" in loaded.reason_codes
    assert len(loaded.manifest.missingRanges) == 1


def test_requested_instrument_without_candles_is_quality_result(tmp_path):
    r = reader(FakeTransport(artifact_bytes()), tmp_path)
    loaded = r.load(spec(instrument_ids=(ASSET_A, ASSET_B, ASSET_C)))
    assert "INSTRUMENT_WITHOUT_CANDLES" in loaded.reason_codes


def test_content_address_cache_avoids_second_download(tmp_path):
    data = artifact_bytes()
    transport = FakeTransport(data)
    r = reader(transport, tmp_path)

    r.load(spec())
    r.load(spec())
    assert transport.downloads == 1

    # Bozulmus cache girdisi kullanilmaz; yeniden indirilir ve hash'lenir.
    cached = tmp_path / "cache" / sha256(data).hexdigest()
    cached.write_bytes(b"bozuk")
    r.load(spec())
    assert transport.downloads == 2
    assert cached.read_bytes() == data


# -------------------------------------------------------------- bag kurallari

def test_snapshot_of_other_dataset_is_rejected(tmp_path):
    r = reader(FakeTransport(artifact_bytes()), tmp_path)
    r.load(spec())

    err = error_code(lambda: r.read(DATASET_ID, FOREIGN_SNAPSHOT_ID, ASSET_A))
    assert err.code == "SNAPSHOT_DATASET_MISMATCH"

    err = error_code(lambda: r.read(OTHER_DATASET_ID, SNAPSHOT_ID, ASSET_A))
    assert err.code == "DATASET_NOT_LOADED"

    err = error_code(lambda: r.read(DATASET_ID, SNAPSHOT_ID, ASSET_C))
    assert err.code == "INSTRUMENT_NOT_IN_DATASET"


def test_manifest_without_required_fields_is_rejected(tmp_path):
    class Broken(FakeTransport):
        def get_export(self, job_id):
            payload = ready_payload(self.data)
            del payload["availableAtPolicy"]
            return payload

    err = error_code(lambda: reader(Broken(artifact_bytes()), tmp_path).load(spec()))
    assert err.code == "MANIFEST_INVALID"


def mutate_first(**changes):
    records = [
        candle_record(c) for c in make_series(ASSET_A, [100, 101, 102])
    ]
    records[0].update(changes)
    return artifact_bytes(records)


@pytest.mark.parametrize("changes", [
    {"priceKind": "MARK"},
    {"interval": "5m"},
    {"instrumentId": str(uuid4())},
    {"availableAt": "2026-10-23T14:00:00Z"},
    {"availableAt": "2026-10-23T11:00:00Z"},
    {"open": 100.5},
    {"high": "NaN"},
    {"closeTime": "2026-10-23T12:01:00+03:00"},
    {"finalized": "true"},
])
def test_invalid_records_are_rejected(tmp_path, changes):
    err = error_code(lambda: reader(
        FakeTransport(mutate_first(**changes)), tmp_path,
    ).load(spec(instrument_ids=(ASSET_A,))))
    assert err.code == "ARTIFACT_RECORD_INVALID"
    assert err.details["line"] == 1


def test_missing_volume_stays_none(tmp_path):
    r = reader(FakeTransport(artifact_bytes()), tmp_path)
    assert all(c.volume is None for c in r.load(spec()).candles)


# --------------------------------------------------------------- HTTP adapter

def http_transport(handler, template=DOWNLOAD_TEMPLATE, **kwargs):
    client = httpx.Client(
        base_url="http://market.test",
        transport=httpx.MockTransport(handler),
        follow_redirects=False,
    )
    return HttpMarketExportTransport(
        "http://market.test", SECRET, template, client=client, **kwargs,
    )


def test_http_end_to_end_uses_configured_route_only(tmp_path):
    data = artifact_bytes()
    seen = []

    def handler(request):
        seen.append((request.method, request.url.path))
        assert request.headers["X-Service-Secret"] == SECRET
        UUID(request.headers["X-Request-Id"])
        assert request.headers["X-Deadline"]

        if request.method == "POST":
            body = json.loads(request.content)
            assert set(body) == {
                "instrumentIds", "from", "to", "intervals",
                "priceKinds", "asOfCutoff",
            }
            return httpx.Response(202, json={"jobId": str(JOB_ID)})
        if request.url.path.endswith(str(JOB_ID)):
            return httpx.Response(200, json=ready_payload(data))
        return httpx.Response(200, content=data)

    r = reader(http_transport(handler), tmp_path)
    loaded = r.load(spec())

    assert loaded.quality == "READY"
    assert seen == [
        ("POST", "/internal/market/dataset-exports"),
        ("GET", f"/internal/market/dataset-exports/{JOB_ID}"),
        ("GET", f"/internal/test-only/artifacts/{ARTIFACT_ID}"),
    ]


def test_http_download_route_is_not_invented(tmp_path):
    transport = http_transport(lambda request: httpx.Response(500), template=None)
    err = error_code(lambda: transport.download_artifact(ARTIFACT_ID, 1000))
    assert err.code == "ARTIFACT_ROUTE_NOT_CONFIGURED"


@pytest.mark.parametrize("template", [
    "https://evil.example/{artifactId}",
    "//evil.example/{artifactId}",
    "relative/{artifactId}",
    "/internal/no-placeholder",
    "/internal/{artifactId}/{artifactId}",
])
def test_http_download_template_must_be_safe(template):
    with pytest.raises(ValueError, match="DOWNLOAD_PATH_TEMPLATE_INVALID"):
        HttpMarketExportTransport("http://market.test", SECRET, template)


def test_http_redirect_is_not_followed():
    def handler(request):
        return httpx.Response(
            302, headers={"Location": "https://evil.example/x"},
        )

    err = error_code(lambda: http_transport(handler).download_artifact(
        ARTIFACT_ID, 1000,
    ))
    assert err.code == "MARKET_EXPORT_HTTP_ERROR"
    assert err.details["status"] == 302


def test_http_size_limit_and_retryable_status():
    big = http_transport(lambda r: httpx.Response(200, content=b"x" * 100))
    assert error_code(
        lambda: big.download_artifact(ARTIFACT_ID, 10)
    ).code == "ARTIFACT_TOO_LARGE"

    unavailable = http_transport(lambda r: httpx.Response(503))
    err = error_code(lambda: unavailable.get_export(str(JOB_ID)))
    assert err.retryable

    denied = http_transport(lambda r: httpx.Response(403))
    assert not error_code(lambda: denied.get_export(str(JOB_ID))).retryable


def test_http_submit_requires_202_and_uuid_job_id():
    wrong_status = http_transport(lambda r: httpx.Response(200, json={}))
    assert error_code(
        lambda: wrong_status.submit_export(spec())
    ).code == "MARKET_EXPORT_HTTP_ERROR"

    bad_id = http_transport(
        lambda r: httpx.Response(202, json={"jobId": "../../etc"}),
    )
    assert error_code(
        lambda: bad_id.submit_export(spec())
    ).code == "EXPORT_JOB_ID_INVALID"


def test_http_secret_is_required():
    with pytest.raises(ValueError, match="SERVICE_SECRET_REQUIRED"):
        HttpMarketExportTransport("http://market.test", "kisa")


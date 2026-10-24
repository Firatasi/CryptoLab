"""A02 testleri: feature pipeline, multiframe ve train/serve parity.

Tum mumlar SENTETIKTIR (bkz. synthetic_market.py); piyasa verisi degildir.
"""
from dataclasses import replace
from datetime import timedelta
from decimal import Decimal
from uuid import UUID, uuid4
import math

import pytest

from app.contracts.model import ActiveModelBundle, PredictionRequest
from app.features.multiframe import asof_available, latest_asof, resample_utc
from app.features.pipeline import (
    FEATURES_V1,
    FEATURES_V2,
    NAMES,
    SCHEMA_HASH,
    Candle,
    build_features,
    make_feature_set,
)
from app.inference.service import FeatureService, prediction_cache_key
from app.infrastructure.market_dataset import ExportSpec, parse_jsonl
from synthetic_market import (
    ASSET_A,
    ASSET_B,
    ASSET_C,
    DATASET_HASH,
    START,
    SYNTHETIC_DATA_NOTICE,
    as_of_after,
    candle_record,
    make_candle,
    make_series,
)

FLAT_100 = [100] * 21


def v1(candles, instrument=ASSET_A, count=21, **kwargs):
    return build_features(
        candles, instrument, as_of_after(count), DATASET_HASH, **kwargs,
    )


# --------------------------------------------------------------- A02.1

def test_fixture_is_marked_synthetic():
    assert "SENTETIK" in SYNTHETIC_DATA_NOTICE


def test_three_synthetic_assets_20_vs_21_candles():
    candles = (
        make_series(ASSET_A, FLAT_100[:20], spread=1)
        + make_series(ASSET_B, FLAT_100, spread=1)
        + make_series(ASSET_C, FLAT_100, spread=1)
    )

    short = build_features(candles, ASSET_A, as_of_after(21), DATASET_HASH)
    assert short.quality == "INSUFFICIENT_DATA"
    assert short.reason_codes == ("INSUFFICIENT_HISTORY",)
    assert short.vector == ()

    for ready_asset in (ASSET_B, ASSET_C):
        ready = build_features(
            candles, ready_asset, as_of_after(21), DATASET_HASH,
        )
        assert ready.quality == "READY"
        assert len(ready.vector) == len(NAMES) == 4

    # Varliklar ayni zaman cizelgesinde birbirine karismaz.
    ids = {
        build_features(candles, a, as_of_after(21), DATASET_HASH).id
        for a in (ASSET_A, ASSET_B, ASSET_C)
    }
    assert len(ids) == 3


def test_gap_stale_and_invalid_candle():
    series = make_series(ASSET_A, FLAT_100, spread=1)

    # 22 mumdan biri eksik: 21 satir kalir ama aralarinda bosluk vardir.
    long_series = make_series(ASSET_A, [100] * 22, spread=1)
    with_gap = long_series[:10] + long_series[11:]
    assert v1(with_gap, count=22).reason_codes == ("CANDLE_GAP",)

    stale = build_features(
        series, ASSET_A, as_of_after(21, extra_seconds=600), DATASET_HASH,
    )
    assert stale.quality == "STALE_DATA"

    broken = series[:-1] + [replace(series[-1], low=Decimal(200))]
    with pytest.raises(ValueError, match="INVALID_CANDLE"):
        v1(broken)

    duplicate = series + [series[-1]]
    with pytest.raises(ValueError, match="DUPLICATE_CANDLE"):
        v1(duplicate)


def test_naive_times_rejected():
    with pytest.raises(ValueError, match="CANDLE_TIME_UTC_REQUIRED"):
        Candle(
            ASSET_A, START.replace(tzinfo=None), START,
            Decimal(1), Decimal(1), Decimal(1), Decimal(1),
        )

    with pytest.raises(ValueError, match="ASOF_UTC_REQUIRED"):
        build_features(
            [], ASSET_A, START.replace(tzinfo=None), DATASET_HASH,
        )


# --------------------------------------------------------------- A02.3

def test_returns_hand_computed():
    closes = [100] * 20 + [110]
    snap = v1(make_series(ASSET_A, closes, spread=1))
    features = snap.summary["features"]

    assert features["return_1"] == math.log(1.1)
    # 5 fark icin 6 fiyat: t-5 kapanisi 100'dur.
    assert features["return_5"] == math.log(1.1)


def test_return_5_needs_six_prices_not_five():
    # Genel gereksinim 21; return_5 penceresinin 6 gozlem oldugu spec'te yazili.
    spec = {s.name: s for s in FEATURES_V1.specs}
    assert spec["return_1"].window == 2
    assert spec["return_5"].window == 6


def test_realized_volatility_hand_computed():
    closes = [100] * 20 + [110]
    snap = v1(make_series(ASSET_A, closes, spread=1))
    x = math.log(1.1)
    # 20 getiriden biri x, 19'u 0: pstdev = x * sqrt(19) / 20
    assert snap.summary["features"]["realized_volatility_20"] == (
        pytest.approx(x * math.sqrt(19) / 20)
    )


def test_future_prices_do_not_change_decision_features():
    base = make_series(ASSET_A, [100] * 20 + [110], spread=1)
    as_of = as_of_after(21)
    before = build_features(base, ASSET_A, as_of, DATASET_HASH)

    future = [
        make_candle(ASSET_A, 21 + i, 100000 * (i + 1), spread=1)
        for i in range(5)
    ]
    after = build_features(base + future, ASSET_A, as_of, DATASET_HASH)

    assert after.vector == before.vector
    assert after.id == before.id


def test_build_features_does_not_mutate_input():
    series = make_series(ASSET_A, [100] * 20 + [110], spread=1)
    shuffled = list(reversed(series))
    snapshot = list(shuffled)

    v1(shuffled)
    assert shuffled == snapshot


def test_unfinalized_candle_is_not_used():
    series = make_series(ASSET_A, FLAT_100, spread=1)
    series[-1] = replace(series[-1], finalized=False)
    assert v1(series).quality == "INSUFFICIENT_DATA"


def test_available_at_after_as_of_is_not_used():
    series = make_series(ASSET_A, FLAT_100, spread=1)
    series[-1] = make_candle(ASSET_A, 20, 100, spread=1, delay_seconds=30)
    # as_of kapanistan 1 sn sonra; availableAt 30 sn sonra.
    assert v1(series).quality == "INSUFFICIENT_DATA"
    late = build_features(
        series, ASSET_A, as_of_after(21, extra_seconds=30), DATASET_HASH,
    )
    assert late.quality == "READY"


# --------------------------------------------------------------- A02.4

def test_atr_is_sma_true_range_not_wilder():
    snap = v1(make_series(ASSET_A, FLAT_100, spread=1))
    # Her mumda TR = 2, 14'luk SMA = 2, oran = 2/100.
    assert snap.summary["atrPrice"] == "2"
    assert snap.summary["features"]["atr_ratio_14"] == pytest.approx(0.02)
    assert snap.summary["conversionVersion"] == "decimal-atr-sma14-v1"

    spec = {s.name: s for s in FEATURES_V1.specs}["atr_ratio_14"]
    assert "sma" in spec.method and "wilder" not in spec.method.lower()


def test_feature_spec_records_source_requirements():
    for spec in FEATURES_V2.specs:
        assert spec.sourcePriceKind == "TRADE"
        assert spec.interval == "1m"
        assert spec.minObservations == spec.window
        assert spec.missingPolicy == "REJECT"
        assert spec.availableAtRule == (
            "finalized_and_available_at_lte_asof"
        )

    assert {
        s.name for s in FEATURES_V2.specs if s.source == "MARKET_CONTEXT"
    } == {"market_return_5"}


def v2_candles(target_closes, target_volumes, context_closes):
    return (
        make_series(ASSET_A, target_closes, target_volumes, spread=1)
        + make_series(ASSET_B, context_closes, spread=1)
    )


def v2(candles, count=40, **kwargs):
    return build_features(
        candles, ASSET_A, as_of_after(count), DATASET_HASH,
        feature_set=FEATURES_V2, context_instrument=ASSET_B, **kwargs,
    )


def test_relative_volume_hand_computed():
    closes = [100] * 40
    volumes = [10] * 39 + [30]
    snap = v2(v2_candles(closes, volumes, closes))

    # Son hacim 30, onceki 20 mumun ortalamasi 10.
    assert snap.summary["features"]["relative_volume_20"] == 3.0


def test_missing_volume_is_rejected_not_zero_filled():
    closes = [100] * 40
    volumes = [10] * 40
    volumes[30] = None
    snap = v2(v2_candles(closes, volumes, closes))

    assert snap.quality == "INSUFFICIENT_DATA"
    assert snap.reason_codes == ("MISSING_VOLUME",)
    assert snap.vector == ()


def test_zero_volume_baseline_is_rejected():
    closes = [100] * 40
    volumes = [0] * 39 + [5]
    snap = v2(v2_candles(closes, volumes, closes))
    assert snap.reason_codes == ("ZERO_VOLUME_BASELINE",)


def test_ema_gap_ratio_hand_computed():
    closes = [100] * 39 + [110]
    snap = v2(v2_candles(closes, [1] * 40, [100] * 40))

    # Tohum SMA=100; son adimda ema = 100 + (2/21)*10.
    ema = 100 + (2 / 21) * 10
    assert snap.summary["features"]["ema_gap_ratio"] == pytest.approx(
        (110 - ema) / ema
    )


def test_market_context_comes_from_other_uuid_same_as_of():
    context_closes = [100] * 39 + [110]
    snap = v2(v2_candles([100] * 40, [1] * 40, context_closes))

    assert snap.summary["features"]["market_return_5"] == math.log(1.1)
    # Hedef varligin kendi getirisi etkilenmez.
    assert snap.summary["features"]["return_5"] == 0.0


def test_market_context_missing_is_unavailable():
    candles = make_series(ASSET_A, [100] * 40, [1] * 40, spread=1)
    snap = v2(candles)
    assert snap.quality == "INSUFFICIENT_DATA"
    assert snap.reason_codes == ("MARKET_CONTEXT_INSUFFICIENT_HISTORY",)


def test_context_instrument_rules():
    candles = v2_candles([100] * 40, [1] * 40, [100] * 40)
    with pytest.raises(ValueError, match="CONTEXT_INSTRUMENT_MUST_DIFFER"):
        build_features(
            candles, ASSET_A, as_of_after(40), DATASET_HASH,
            feature_set=FEATURES_V2, context_instrument=ASSET_A,
        )
    with pytest.raises(ValueError, match="CONTEXT_INSTRUMENT_REQUIRED"):
        build_features(
            candles, ASSET_A, as_of_after(40), DATASET_HASH,
            feature_set=FEATURES_V2,
        )
    with pytest.raises(ValueError, match="CONTEXT_NOT_USED_BY_FEATURE_SET"):
        build_features(
            candles, ASSET_A, as_of_after(40), DATASET_HASH,
            context_instrument=ASSET_B,
        )


def test_snapshot_identity_includes_context_instrument():
    candles = v2_candles([100] * 40, [1] * 40, [100] * 40) + make_series(
        ASSET_C, [100] * 40, spread=1,
    )
    with_b = v2(candles)
    with_c = build_features(
        candles, ASSET_A, as_of_after(40), DATASET_HASH,
        feature_set=FEATURES_V2, context_instrument=ASSET_C,
    )
    assert with_b.id != with_c.id


def test_schema_hash_covers_order_source_and_window():
    assert FEATURES_V2.schema_hash != FEATURES_V1.schema_hash
    assert len(FEATURES_V2.names) == len(FEATURES_V1.names) + 3
    assert list(FEATURES_V2.names) == sorted(FEATURES_V2.names)

    widened = [
        replace(s, window=s.window + 1, minObservations=s.minObservations + 1)
        if s.name == "return_5" else s
        for s in FEATURES_V1.specs
    ]
    assert make_feature_set("x", widened).schema_hash != SCHEMA_HASH

    other_kind = [
        replace(s, sourcePriceKind="MARK") for s in FEATURES_V1.specs
    ]
    assert make_feature_set("x", other_kind).schema_hash != SCHEMA_HASH

    # Sira girdi listesine bagli degildir; isimle dondurulur.
    reversed_set = make_feature_set("features-v1", list(reversed(FEATURES_V1.specs)))
    assert reversed_set.schema_hash == SCHEMA_HASH
    assert reversed_set.names == FEATURES_V1.names


def test_mixed_sources_rejected():
    mixed = [
        replace(s, interval="5m") if s.name == "return_1" else s
        for s in FEATURES_V1.specs
    ]
    with pytest.raises(ValueError, match="MIXED_SOURCE_NOT_SUPPORTED"):
        make_feature_set("x", mixed)


# --------------------------------------------------------------- A02.5

def minute_series(count, delay_by_index=None, skip=()):
    delay_by_index = delay_by_index or {}
    return [
        make_candle(
            ASSET_A, i, 100 + i, spread=1, volume=10,
            delay_seconds=delay_by_index.get(i, 1),
        )
        for i in range(count) if i not in skip
    ]


def test_resample_5m_hand_computed():
    bars = resample_utc(minute_series(5), "5m")
    assert len(bars) == 1

    bar = bars[0]
    assert bar.interval == "5m"
    assert bar.close_time == START + timedelta(minutes=5)
    assert bar.open == Decimal(100)
    assert bar.close == Decimal(104)
    assert bar.high == Decimal(105)
    assert bar.low == Decimal(99)
    assert bar.volume == Decimal(50)
    assert bar.available_at == START + timedelta(minutes=5, seconds=1)


def test_resample_missing_child_produces_no_bar():
    assert resample_utc(minute_series(5, skip={2}), "5m") == []
    assert resample_utc(minute_series(4), "5m") == []


def test_resample_unfinalized_child_produces_no_bar():
    series = minute_series(5)
    series[4] = replace(series[4], finalized=False)
    assert resample_utc(series, "5m") == []


def test_resample_unknown_volume_makes_bar_volume_none():
    series = minute_series(5)
    series[1] = replace(series[1], volume=None)
    assert resample_utc(series, "5m")[0].volume is None


def test_resample_two_aligned_buckets():
    bars = resample_utc(minute_series(10), "5m")
    assert [b.close_time for b in bars] == [
        START + timedelta(minutes=5), START + timedelta(minutes=10),
    ]


def test_unsupported_intervals():
    for bad in ("1m", "1w", "7m"):
        with pytest.raises(ValueError, match="UNSUPPORTED_INTERVAL"):
            resample_utc(minute_series(5), bad)


def test_asof_does_not_use_open_bar():
    bars = resample_utc(minute_series(5), "5m")
    # 12:03'te 12:00-12:05 bari henuz kapanmadi.
    at_1203 = START + timedelta(minutes=3)
    assert asof_available(bars, at_1203) == []
    assert latest_asof(bars, ASSET_A, "TRADE", "5m", at_1203) is None


def test_asof_requires_available_at_too():
    bars = resample_utc(minute_series(5, delay_by_index={4: 2}), "5m")
    assert bars[0].available_at == START + timedelta(minutes=5, seconds=2)

    at_12_05_01 = START + timedelta(minutes=5, seconds=1)
    assert latest_asof(bars, ASSET_A, "TRADE", "5m", at_12_05_01) is None

    at_12_05_02 = START + timedelta(minutes=5, seconds=2)
    assert latest_asof(bars, ASSET_A, "TRADE", "5m", at_12_05_02) is bars[0]


def test_available_at_is_max_of_children():
    # 2. cocuk 12:02'de kapanir, 400 sn gec yayimlanir: bar bunu bekler.
    bars = resample_utc(minute_series(5, delay_by_index={1: 400}), "5m")
    assert bars[0].available_at == START + timedelta(minutes=2, seconds=400)


# --------------------------------------------------------------- A02.6

BUNDLE_ID = UUID("44444444-4444-4444-8444-444444444444")
DATASET_ID = UUID("55555555-5555-4555-8555-555555555555")
SNAPSHOT_ID = UUID("66666666-6666-4666-8666-666666666666")


class FakeSource:
    """Test-only CandleSource; gercek B06 adapter'i degildir."""

    def __init__(self, candles):
        self.candles = candles

    def read(self, dataset_id, snapshot_id, instrument_id):
        assert dataset_id == DATASET_ID and snapshot_id == SNAPSHOT_ID
        return [c for c in self.candles if c.instrument_id == instrument_id]

    def dataset_hash(self, dataset_id):
        return DATASET_HASH


def request(as_of, feature_version="features-v1", **changes):
    return PredictionRequest.model_validate({
        "requestId": str(uuid4()),
        "instrumentId": str(ASSET_A),
        "horizonSeconds": 3600,
        "asOf": as_of.isoformat(),
        "modelBundleId": str(BUNDLE_ID),
        "marketDatasetId": str(DATASET_ID),
        "marketSnapshotId": str(SNAPSHOT_ID),
        "featureVersion": feature_version,
        **changes,
    })


def serialized_roundtrip(candles, instruments):
    import json
    spec = ExportSpec(
        instrument_ids=tuple(instruments),
        start=START,
        end=START + timedelta(hours=2),
        intervals=("1m",),
        price_kinds=("TRADE",),
        as_of_cutoff=START + timedelta(hours=2),
    )
    data = "\n".join(
        json.dumps(candle_record(c)) for c in candles
    ).encode()
    return list(parse_jsonl(data, spec))


def test_training_and_serving_use_same_vector():
    closes = [100 + (i % 7) * 0.5 for i in range(40)]
    volumes = [10 + i for i in range(40)]
    original = (
        make_series(ASSET_A, closes, volumes, spread=1)
        + make_series(ASSET_B, [50 + i * 0.1 for i in range(40)], spread=1)
    )
    # Ayni serialized girdi: JSONL'den okunan mumlar iki yola da girer.
    parsed = serialized_roundtrip(original, (ASSET_A, ASSET_B))
    as_of = as_of_after(40)

    offline = build_features(
        parsed, ASSET_A, as_of, DATASET_HASH,
        feature_set=FEATURES_V2, context_instrument=ASSET_B,
    )

    served = FeatureService(
        FakeSource(parsed), FEATURES_V2, context_instrument=ASSET_B,
    ).features_for(request(as_of, "features-v2"))

    assert offline.quality == served.quality == "READY"
    assert served.vector == offline.vector
    assert served.id == offline.id
    assert served.schema_hash == FEATURES_V2.schema_hash

    # Sira sabit: vektor, spec'in isim sirasidir.
    assert list(served.summary["features"]) == list(FEATURES_V2.names)
    assert list(served.vector) == [
        served.summary["features"][n] for n in FEATURES_V2.names
    ]

    # Ham (Decimal) mumlarla da ayni sonuc: serialization sonucu degistirmez.
    direct = build_features(
        original, ASSET_A, as_of, DATASET_HASH,
        feature_set=FEATURES_V2, context_instrument=ASSET_B,
    )
    assert direct.vector == offline.vector


def test_snapshot_id_is_deterministic_and_depends_on_inputs():
    candles = make_series(ASSET_A, FLAT_100, spread=1)
    base = build_features(candles, ASSET_A, as_of_after(21), DATASET_HASH)
    again = build_features(candles, ASSET_A, as_of_after(21), DATASET_HASH)
    assert base.id == again.id

    other_hash = build_features(
        candles, ASSET_A, as_of_after(21), "sha256:" + "e" * 64,
    )
    other_time = build_features(
        candles, ASSET_A, as_of_after(21, extra_seconds=2), DATASET_HASH,
    )
    other_schema = build_features(
        candles, ASSET_A, as_of_after(21), DATASET_HASH,
        feature_set=make_feature_set("x", list(FEATURES_V1.specs[:2])),
    )
    assert len({base.id, other_hash.id, other_time.id, other_schema.id}) == 4


def test_service_rejects_feature_version_mismatch():
    service = FeatureService(
        FakeSource(make_series(ASSET_A, FLAT_100, spread=1)),
    )
    with pytest.raises(ValueError, match="FEATURE_SCHEMA_MISMATCH"):
        service.features_for(request(as_of_after(21), "features-v2"))


def bundle(**changes):
    values = dict(
        bundleId=BUNDLE_ID,
        numericModelVersion="MOCK",
        explanationModelVersion=None,
        featureVersion="features-v1",
        featureSchemaHash=SCHEMA_HASH,
        preprocessingHash="sha256:" + "a" * 64,
        policyVersion="test-policy",
        supportedHorizons=[3600],
        artifactHashes={"numeric": "sha256:" + "a" * 64},
        trainingDatasetId=DATASET_ID,
        knowledgeTrainingDatasetId=None,
        releaseStatus="DEVELOPMENT_CANDIDATE",
    )
    values.update(changes)
    return ActiveModelBundle(**values)


def test_bundle_must_match_feature_schema():
    service = FeatureService(FakeSource([]))
    service.ensure_bundle(bundle())

    with pytest.raises(ValueError, match="FEATURE_SCHEMA_MISMATCH"):
        service.ensure_bundle(
            bundle(featureSchemaHash="sha256:" + "0" * 64),
        )
    with pytest.raises(ValueError, match="FEATURE_SCHEMA_MISMATCH"):
        service.ensure_bundle(bundle(featureVersion="features-v2"))


def test_cache_key_has_no_request_id():
    key = prediction_cache_key(BUNDLE_ID, SNAPSHOT_ID, 3600)
    assert key == f"{BUNDLE_ID}:{SNAPSHOT_ID}:3600"

    first = request(as_of_after(21))
    second = request(as_of_after(21))
    assert first.requestId != second.requestId
    assert str(first.requestId) not in key

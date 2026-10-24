"""A03 testleri: Hedefler, zaman ayrımı ve veri sızıntısı (leakage) önleme testleri.

Tüm mumlar ve fiyatlar SENTETİKTİR; piyasa verisi değildir.
"""
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
import math
from pathlib import Path
from uuid import UUID

import pytest

from app.datasets.build_numeric import build_numeric_samples
from app.datasets.splits import (
    Sample,
    forward_label,
    split_panel,
    validate_no_leakage,
)
from app.features.pipeline import FEATURES_V1, Candle
from app.training.numeric import fit_numeric
from synthetic_market import (
    ASSET_A,
    ASSET_B,
    ASSET_C,
    DATASET_HASH,
    START,
    as_of_after,
    make_candle,
    make_series,
)

FAMILY_MAP = {
    ASSET_A: "BTC_FAMILY",
    ASSET_B: "ETH_FAMILY",
    ASSET_C: "HOLDOUT_DEF_FAMILY",
}


# --------------------------------------------------------------- A03.1
def test_forward_label_calculation():
    # 100 -> 110 için log(1.1)
    t = START
    horizon = 3600
    prices = {
        t: Decimal("100"),
        t + timedelta(seconds=horizon): Decimal("110"),
    }
    res = forward_label(prices, t, horizon)
    assert res is not None
    y, end = res
    assert y == pytest.approx(math.log(1.1))
    assert end == t + timedelta(seconds=horizon)


def test_forward_label_missing_future_returns_none():
    t = START
    horizon = 604800  # 1 hafta
    prices = {
        t: Decimal("100"),
        # t+horizon anında fiyat yok
    }
    # Eksik gelecek fiyatı kesinlikle 0.0 veya forward-fill yapılmaz; None döner!
    res = forward_label(prices, t, horizon)
    assert res is None


def test_forward_label_validates_prices_and_timezone():
    t = START
    horizon = 60
    # Sıfır veya negatif fiyat
    with pytest.raises(ValueError, match="NON_POSITIVE_PRICE"):
        forward_label({t: Decimal("0"), t + timedelta(seconds=60): Decimal("100")}, t, horizon)

    # Saat dilimsiz (naive) zaman
    with pytest.raises(ValueError, match="UTC_REQUIRED"):
        forward_label({t: Decimal("100")}, t.replace(tzinfo=None), horizon)

    # Geçersiz horizon
    with pytest.raises(ValueError, match="INVALID_HORIZON"):
        forward_label({t: Decimal("100")}, t, 0)


# --------------------------------------------------------------- A03.2
def test_five_horizons_produce_distinct_targets():
    # 45 mumluk seri üretelim (60s ve 900s horizonları için yeterli uzunluk)
    closes = [100 + i for i in range(45)]
    candles = make_series(ASSET_A, closes, spread=1)

    datasets, counts = build_numeric_samples(
        candles,
        {ASSET_A: "BTC_FAMILY"},
        DATASET_HASH,
        horizons=(60, 900, 3600),
    )

    # 60 saniye (1 mum sonra) ile 900 saniye (15 mum sonra) etiketleri farklı olmalıdır
    assert counts[60] > 0
    assert counts[900] > 0
    # 3600 saniye (60 dk) için yeterli gelecek veri olmadığından missing=DROP ile 0 olmalıdır
    assert counts[3600] == 0

    s_60 = datasets[60][0]
    s_900 = datasets[900][0]
    assert s_60.horizon == 60
    assert s_900.horizon == 900
    assert s_60.y != s_900.y


# --------------------------------------------------------------- A03.3
def test_global_utc_boundaries_applied_to_all_assets():
    val_start = START + timedelta(days=30)
    test_start = START + timedelta(days=60)
    test_end = START + timedelta(days=90)

    # İki varlık için aynı zaman diliminde örnekler
    samples = [
        Sample("BTC_FAMILY", START + timedelta(days=10), START + timedelta(days=10, hours=1), (1.0, 2.0), 0.01),
        Sample("ETH_FAMILY", START + timedelta(days=10), START + timedelta(days=10, hours=1), (1.5, 2.5), 0.02),
        Sample("BTC_FAMILY", START + timedelta(days=40), START + timedelta(days=40, hours=1), (1.1, 2.1), -0.01),
        Sample("ETH_FAMILY", START + timedelta(days=40), START + timedelta(days=40, hours=1), (1.6, 2.6), -0.02),
    ]

    splits = split_panel(samples, val_start, test_start, test_end, unseen_families=["HOLDOUT_DEF_FAMILY"])

    # Hem BTC hem ETH için boundary aynıdır; Ocak train'de, Şubat rastgele testte olamaz
    assert len(splits["train"]) == 2
    assert {s.family for s in splits["train"]} == {"BTC_FAMILY", "ETH_FAMILY"}
    assert len(splits["validation"]) == 2
    assert {s.family for s in splits["validation"]} == {"BTC_FAMILY", "ETH_FAMILY"}


# --------------------------------------------------------------- A03.4
def test_purge_removes_boundary_overlapping_train_samples():
    val_start = START + timedelta(days=30)
    test_start = START + timedelta(days=60)
    test_end = START + timedelta(days=90)

    # 1 gün öncesinde (29. gün):
    # 1. Kısa horizon (1m): label_end = 29. gün + 1m < val_start -> TRAIN kalmalı
    sample_short = Sample(
        "BTC_FAMILY",
        val_start - timedelta(days=1),
        val_start - timedelta(days=1) + timedelta(minutes=1),
        (1.0, 2.0),
        0.01,
    )
    # 2. Uzun horizon (1w): label_end = 29. gün + 7 gün > val_start -> PURGED olmalı!
    sample_long = Sample(
        "BTC_FAMILY",
        val_start - timedelta(days=1),
        val_start - timedelta(days=1) + timedelta(days=7),
        (1.0, 2.0),
        0.05,
    )

    splits = split_panel(
        [sample_short, sample_long],
        val_start,
        test_start,
        test_end,
        unseen_families=[],
    )

    assert sample_short in splits["train"]
    assert sample_long in splits["purged"]
    assert sample_long not in splits["train"]


# --------------------------------------------------------------- A03.5
def test_unseen_asset_family_holdout():
    val_start = START + timedelta(days=30)
    test_start = START + timedelta(days=60)
    test_end = START + timedelta(days=90)

    # Erken dönemde holdout ailesine ait bir örnek
    early_unseen = Sample(
        "HOLDOUT_DEF_FAMILY",
        START + timedelta(days=5),
        START + timedelta(days=5, hours=1),
        (1.0, 2.0),
        0.01,
    )
    # Test döneminde holdout ailesine ait bir örnek
    test_unseen = Sample(
        "HOLDOUT_DEF_FAMILY",
        test_start + timedelta(days=5),
        test_start + timedelta(days=5, hours=1),
        (1.0, 2.0),
        0.02,
    )
    # Test döneminde bilinen aileye ait bir örnek
    test_seen = Sample(
        "BTC_FAMILY",
        test_start + timedelta(days=5),
        test_start + timedelta(days=5, hours=1),
        (1.0, 2.0),
        0.03,
    )

    splits = split_panel(
        [early_unseen, test_unseen, test_seen],
        val_start,
        test_start,
        test_end,
        unseen_families=["HOLDOUT_DEF_FAMILY"],
    )

    # Erken dönem unseen örneği train veya val'e ASLA giremez, elenir
    assert early_unseen not in splits["train"]
    assert early_unseen not in splits["validation"]
    # Test döneminde ayrılır
    assert test_seen in splits["seen_test"]
    assert test_unseen in splits["unseen_test"]

    # Sızıntı kontrolü
    validate_no_leakage(splits, val_start, test_start, test_end, ["HOLDOUT_DEF_FAMILY"])


def test_asset_families_policy_file_exists():
    path = Path(__file__).resolve().parent.parent / "app" / "datasets" / "asset_families.json"
    assert path.exists()
    data = json.loads(path.read_text(encoding="utf-8"))
    assert "HOLDOUT_DEF_FAMILY" in data["unseenFamilies"]
    assert "rules" in data and len(data["rules"]) > 0


# --------------------------------------------------------------- A03.6
def test_preprocessing_fit_only_on_train_no_test_leakage():
    # 10 train, 4 validation örneği
    train = [
        Sample("BTC_FAMILY", START + timedelta(minutes=i), START + timedelta(minutes=i + 1), (float(i), float(i * 2)), 0.01 * i)
        for i in range(10)
    ]
    val = [
        Sample("BTC_FAMILY", START + timedelta(minutes=20 + i), START + timedelta(minutes=21 + i), (float(20 + i), float((20 + i) * 2)), 0.05)
        for i in range(4)
    ]

    artifact_1, _ = fit_numeric(train, val, horizon=60, feature_names=["f1", "f2"], feature_schema_hash="sha256:" + "0" * 64)

    # Test/validation verilerine 1000 kat uç değer ekleyelim
    val_extreme = [
        Sample("BTC_FAMILY", s.decision_time, s.label_end, (s.x[0] * 1000.0, s.x[1] * 1000.0), s.y)
        for s in val
    ]

    artifact_2, _ = fit_numeric(train, val_extreme, horizon=60, feature_names=["f1", "f2"], feature_schema_hash="sha256:" + "0" * 64)

    # Train scaler parametreleri (mean ve scale) ve katsayılar tamamen aynı kalmalıdır!
    assert artifact_1["mean"] == artifact_2["mean"]
    assert artifact_1["scale"] == artifact_2["scale"]
    assert artifact_1["preprocessingHash"] == artifact_2["preprocessingHash"]
    assert artifact_1["coef"] == pytest.approx(artifact_2["coef"])
    assert artifact_1["intercept"] == pytest.approx(artifact_2["intercept"])


# --------------------------------------------------------------- A03.7
def test_split_manifest_freeze_and_survivorship_notice():
    manifest_path = Path(__file__).resolve().parent.parent / "app" / "datasets" / "split_manifest.json"
    assert manifest_path.exists()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    assert "splitManifestId" in manifest
    assert "datasetHash" in manifest
    assert "purgePolicy" in manifest
    assert "survivorshipBiasNotice" in manifest
    assert manifest["survivorshipBiasNotice"]["hasSurvivorshipBias"] is True

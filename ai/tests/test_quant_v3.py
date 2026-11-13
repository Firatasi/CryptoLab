"""Unit, tree-parity & serving tests for features-quant-v4 & quant-horizon-v4 (+ v3 compat)."""
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import math
from pathlib import Path
from uuid import uuid4

from app.features.pipeline import Candle
from app.features.quant import (
    HORIZON_TIMEFRAMES,
    QUANT_FEATURE_NAMES,
    QUANT_FEATURE_NAMES_V3,
    QUANT_FEATURE_VERSION,
    QUANT_FEATURE_VERSION_V3,
    QUANT_SCHEMA_HASH,
    build_quant_features,
)
from app.inference.quant_model import QuantHorizonModel, eval_gbdt_prob, eval_gbdt_raw
from app.main import registry


def _make_series(
    instrument_id,
    interval: str,
    step_seconds: int,
    count: int,
    end_time: datetime,
    trend_per_bar: float = 0.002,
    vol_base: float = 1000.0,
    taker_buy_ratio: float | None = 0.55,
) -> list[Candle]:
    bars: list[Candle] = []
    start = end_time - timedelta(seconds=step_seconds * (count - 1))
    price = 100.0
    for i in range(count):
        t = start + timedelta(seconds=step_seconds * i)
        osc = 0.0008 * (1 if i % 3 == 0 else -0.5)
        price = max(1.0, price * (1.0 + trend_per_bar + osc))
        hi = price * 1.003
        lo = price * 0.997
        vol = vol_base * (1.0 + (i % 5) * 0.15)
        tbv = Decimal(f"{vol * taker_buy_ratio:.2f}") if taker_buy_ratio is not None else None
        bars.append(
            Candle(
                instrument_id=instrument_id,
                close_time=t,
                available_at=t,
                open=Decimal(f"{price * 0.999:.6f}"),
                high=Decimal(f"{hi:.6f}"),
                low=Decimal(f"{lo:.6f}"),
                close=Decimal(f"{price:.6f}"),
                finalized=True,
                interval=interval,
                price_kind="TRADE",
                volume=Decimal(f"{vol:.2f}"),
                taker_buy_volume=tbv,
            )
        )
    return bars


def test_quant_features_causal_filtering_and_horizon_alignment() -> None:
    inst_id = uuid4()
    as_of = datetime(2026, 11, 13, 12, 0, tzinfo=timezone.utc)

    for horizon, cfg in HORIZON_TIMEFRAMES.items():
        p_secs = cfg["primary_seconds"]
        c_secs = cfg["context_seconds"]
        primary = _make_series(inst_id, cfg["primary_interval"], p_secs, 90, as_of, trend_per_bar=0.003)
        # Append a future unclosed / post-asOf bar that MUST be filtered out strictly
        future_bar = Candle(
            instrument_id=inst_id,
            close_time=as_of + timedelta(seconds=p_secs),
            available_at=as_of + timedelta(seconds=p_secs),
            open=Decimal("9999"),
            high=Decimal("9999"),
            low=Decimal("9999"),
            close=Decimal("9999"),
            finalized=True,
            interval=cfg["primary_interval"],
            volume=Decimal("99999"),
        )
        context = _make_series(inst_id, cfg["context_interval"], c_secs, 60, as_of, trend_per_bar=0.004)

        snap_clean = build_quant_features(
            primary_bars=primary,
            instrument_id=inst_id,
            horizon_seconds=horizon,
            as_of=as_of,
            dataset_hash="sha256:test",
            context_bars=context,
            funding_rate=0.0001,
        )
        snap_with_future = build_quant_features(
            primary_bars=[*primary, future_bar],
            instrument_id=inst_id,
            horizon_seconds=horizon,
            as_of=as_of,
            dataset_hash="sha256:test",
            context_bars=context,
            funding_rate=0.0001,
        )

        assert snap_clean.quality == "READY"
        assert len(snap_clean.vector) == len(QUANT_FEATURE_NAMES) == 20
        assert snap_clean.summary["interval"] == cfg["primary_interval"]
        assert snap_clean.summary["contextInterval"] == cfg["context_interval"]
        assert snap_clean.vector == snap_with_future.vector
        assert snap_clean.id == snap_with_future.id

        snap_v3 = build_quant_features(
            primary_bars=primary,
            instrument_id=inst_id,
            horizon_seconds=horizon,
            as_of=as_of,
            dataset_hash="sha256:test",
            context_bars=context,
            funding_rate=0.0001,
            feature_version=QUANT_FEATURE_VERSION_V3,
        )
        assert snap_v3.quality == "READY"
        assert len(snap_v3.vector) == len(QUANT_FEATURE_NAMES_V3) == 14
        assert snap_v3.vector == snap_clean.vector[:14]


def test_quant_v4_taker_buy_volume_and_fallback_behavior() -> None:
    inst_id = uuid4()
    as_of = datetime(2026, 11, 13, 12, 0, tzinfo=timezone.utc)
    bull_flow = _make_series(inst_id, "1h", 3600, 90, as_of, trend_per_bar=0.001, taker_buy_ratio=0.72)
    bear_flow = _make_series(inst_id, "1h", 3600, 90, as_of, trend_per_bar=0.001, taker_buy_ratio=0.28)
    clv_fallback = _make_series(inst_id, "1h", 3600, 90, as_of, trend_per_bar=0.001, taker_buy_ratio=None)

    snap_bull = build_quant_features(bull_flow, inst_id, 3600, as_of, "sha256:bull")
    snap_bear = build_quant_features(bear_flow, inst_id, 3600, as_of, "sha256:bear")
    snap_clv = build_quant_features(clv_fallback, inst_id, 3600, as_of, "sha256:clv")

    idx_delta = QUANT_FEATURE_NAMES.index("cum_delta_z")
    assert snap_bull.vector[idx_delta] > snap_bear.vector[idx_delta]
    assert all(math.isfinite(v) for v in snap_clv.vector)


def test_quant_features_rejects_stale_and_insufficient_bars() -> None:
    inst_id = uuid4()
    as_of = datetime(2026, 11, 13, 12, 0, tzinfo=timezone.utc)

    short_bars = _make_series(inst_id, "1h", 3600, 30, as_of)
    snap_short = build_quant_features(short_bars, inst_id, 3600, as_of, "sha256:s")
    assert snap_short.quality == "INSUFFICIENT_DATA"

    stale_end = as_of - timedelta(hours=5)
    stale_bars = _make_series(inst_id, "1h", 3600, 80, stale_end)
    snap_stale = build_quant_features(stale_bars, inst_id, 3600, as_of, "sha256:st")
    assert snap_stale.quality == "STALE_DATA"


def test_trained_active_bundle_loaded_and_predicts_all_horizons() -> None:
    manifest_path = Path("ai/artifacts/bundles/active_bundles.json")
    if not manifest_path.exists():
        manifest_path = Path("artifacts/bundles/active_bundles.json")
    assert manifest_path.exists()

    for h in (60, 900, 3600, 86400, 604800):
        bundle = registry.active(h)
        assert bundle.featureVersion == QUANT_FEATURE_VERSION
        assert bundle.featureSchemaHash == QUANT_SCHEMA_HASH
        assert bundle.releaseStatus == "ACTIVE"
        pinned = registry.get(bundle.bundleId)
        model = pinned.models[h]
        assert isinstance(model, QuantHorizonModel)

        inst_id = uuid4()
        cfg = HORIZON_TIMEFRAMES[h]
        as_of = datetime(2026, 11, 13, 12, 0, tzinfo=timezone.utc)
        p_bars = _make_series(inst_id, cfg["primary_interval"], cfg["primary_seconds"], 90, as_of, trend_per_bar=0.0025, taker_buy_ratio=0.68)
        c_bars = _make_series(inst_id, cfg["context_interval"], cfg["context_seconds"], 60, as_of, trend_per_bar=0.003)
        snap = build_quant_features(p_bars, inst_id, h, as_of, "sha256:t", context_bars=c_bars)
        assert snap.quality == "READY"
        out = model.predict_quant(snap.vector, h, snap.horizon_vol, snap.summary)
        assert out is not None
        assert "QUANT_MODEL_V4" in out["reasonCodes"]
        assert out["volatilityEstimate"] > 0
        extras = out["summaryExtras"]
        assert 0.0 < extras["pLongWin"] < 1.0
        assert 0.0 < extras["pShortWin"] < 1.0

        # OOD extreme spike must cleanly trigger None -> OUT_OF_DOMAIN
        ood_vec = (5.0,) + snap.vector[1:]
        assert model.predict_quant(ood_vec, h, snap.horizon_vol, snap.summary) is None


def test_mild_choppy_signal_retains_calibrated_direction_for_high_risk() -> None:
    bundle = registry.active(3600)
    model = registry.get(bundle.bundleId).models[3600]
    assert isinstance(model, QuantHorizonModel)

    inst_id = uuid4()
    as_of = datetime(2026, 11, 13, 12, 0, tzinfo=timezone.utc)
    p_bars = _make_series(inst_id, "1h", 3600, 90, as_of, trend_per_bar=0.0001)
    c_bars = _make_series(inst_id, "4h", 14400, 60, as_of, trend_per_bar=0.0008)
    snap = build_quant_features(p_bars, inst_id, 3600, as_of, "sha256:chop", context_bars=c_bars)
    assert snap.quality == "READY"
    out = model.predict_quant(snap.vector, 3600, snap.horizon_vol, snap.summary)
    assert out is not None
    assert abs(out["predictedLogReturn"]) > 0.00025
    assert out["summaryExtras"]["signalConfidence"] >= 0.24


def test_pure_python_gbdt_parity_with_sklearn() -> None:
    import numpy as np
    from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor
    from app.training.train_live_market import _export_gbdt

    rng = np.random.default_rng(13)
    x = rng.normal(size=(300, 20))
    y = 0.6 * np.tanh(x[:, 0]) + 0.3 * x[:, 14] - 0.2 * x[:, 16] + rng.normal(scale=0.1, size=300)
    reg = HistGradientBoostingRegressor(max_iter=25, max_depth=3, random_state=13).fit(x, y)
    clf = HistGradientBoostingClassifier(max_iter=25, max_depth=3, random_state=13).fit(x, (y > 0).astype(int))

    exp_reg = _export_gbdt(reg)
    exp_clf = _export_gbdt(clf)
    test_x = x[:40].tolist()

    sk_reg = reg.predict(x[:40])
    py_reg = [eval_gbdt_raw(exp_reg, r) for r in test_x]
    assert max(abs(a - b) for a, b in zip(sk_reg.tolist(), py_reg)) < 1e-6

    sk_prob = clf.predict_proba(x[:40])[:, 1]
    py_prob = [eval_gbdt_prob(exp_clf, r) for r in test_x]
    assert max(abs(a - b) for a, b in zip(sk_prob.tolist(), py_prob)) < 1e-6

"""Causal multi-horizon quantitative feature pipeline (features-quant-v4 + v3 compat).

Serving-safe: uses only Python stdlib (no sklearn/torch/pickle).
Strictly causal: consumes only finalized bars with close_time <= as_of and available_at <= as_of.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from hashlib import sha256
import json
import math
from typing import Any, Sequence
from uuid import UUID

from app.features.pipeline import Candle

QUANT_FEATURE_VERSION_V3 = "features-quant-v3"
QUANT_FEATURE_VERSION = "features-quant-v4"

QUANT_FEATURE_NAMES_V3: tuple[str, ...] = (
    "ret_1",
    "ret_3",
    "ret_8",
    "ret_21",
    "ema_trend_z",
    "ema_macro_z",
    "rsi_14_centered",
    "macd_hist_atr",
    "bb_zscore_20",
    "efficiency_ratio_14",
    "vol_regime_ratio",
    "volume_z_20",
    "htf_trend_z",
    "funding_rate_bps",
)

QUANT_FEATURE_NAMES: tuple[str, ...] = QUANT_FEATURE_NAMES_V3 + (
    "taker_imbalance_z",
    "cum_delta_z",
    "vwap_dist_atr",
    "liquidity_sweep_score",
    "range_compression_z",
    "body_wick_imbalance",
)

HORIZON_TIMEFRAMES: dict[int, dict[str, Any]] = {
    60: {
        "primary_interval": "1m",
        "primary_seconds": 60,
        "context_interval": "15m",
        "context_seconds": 900,
        "target_bars": 5,
        "min_bars": 60,
    },
    900: {
        "primary_interval": "15m",
        "primary_seconds": 900,
        "context_interval": "1h",
        "context_seconds": 3600,
        "target_bars": 3,
        "min_bars": 60,
    },
    3600: {
        "primary_interval": "1h",
        "primary_seconds": 3600,
        "context_interval": "4h",
        "context_seconds": 14400,
        "target_bars": 3,
        "min_bars": 60,
    },
    86400: {
        "primary_interval": "4h",
        "primary_seconds": 14400,
        "context_interval": "1d",
        "context_seconds": 86400,
        "target_bars": 6,
        "min_bars": 60,
    },
    604800: {
        "primary_interval": "1d",
        "primary_seconds": 86400,
        "context_interval": "1d",
        "context_seconds": 86400,
        "target_bars": 7,
        "min_bars": 60,
    },
}


def compute_quant_schema_hash(
    version: str = QUANT_FEATURE_VERSION,
    feature_names: Sequence[str] = QUANT_FEATURE_NAMES,
) -> str:
    payload = json.dumps(
        {
            "version": version,
            "features": list(feature_names),
            "horizons": {str(k): HORIZON_TIMEFRAMES[k] for k in sorted(HORIZON_TIMEFRAMES)},
        },
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return "sha256:" + sha256(payload).hexdigest()


QUANT_SCHEMA_HASH = compute_quant_schema_hash(QUANT_FEATURE_VERSION, QUANT_FEATURE_NAMES)
QUANT_SCHEMA_HASH_V3 = compute_quant_schema_hash(QUANT_FEATURE_VERSION_V3, QUANT_FEATURE_NAMES_V3)


@dataclass(frozen=True)
class QuantFeatureSnapshot:
    id: UUID
    quality: str
    reason_codes: tuple[str, ...]
    vector: tuple[float, ...]
    summary: dict[str, Any]
    horizon_vol: float


def _clip(val: float, low: float, high: float) -> float:
    return max(low, min(high, val))


def _require_utc(dt: datetime, code: str) -> None:
    if dt.tzinfo is None or dt.utcoffset() != timedelta(0):
        raise ValueError(code)


def _ema_series(values: Sequence[float], period: int) -> list[float]:
    k = 2.0 / (period + 1.0)
    out = [values[0]]
    for v in values[1:]:
        out.append(v * k + out[-1] * (1.0 - k))
    return out


def _rsi(closes: Sequence[float], period: int = 14) -> float:
    if len(closes) < period + 1:
        return 50.0
    deltas = [closes[i] - closes[i - 1] for i in range(1, len(closes))]
    gains = [max(d, 0.0) for d in deltas[:period]]
    losses = [max(-d, 0.0) for d in deltas[:period]]
    avg_gain = sum(gains) / period
    avg_loss = sum(losses) / period
    for d in deltas[period:]:
        avg_gain = (avg_gain * (period - 1) + max(d, 0.0)) / period
        avg_loss = (avg_loss * (period - 1) + max(-d, 0.0)) / period
    if avg_loss <= 1e-12:
        return 100.0 if avg_gain > 0 else 50.0
    rs = avg_gain / avg_loss
    return 100.0 - (100.0 / (1.0 + rs))


def _sma_atr(bars: Sequence[Candle], period: int = 14) -> float:
    if len(bars) < period + 1:
        return 0.0
    trs: list[float] = []
    start_idx = len(bars) - period
    for i in range(start_idx, len(bars)):
        hi = float(bars[i].high)
        lo = float(bars[i].low)
        prev_close = float(bars[i - 1].close)
        tr = max(hi - lo, abs(hi - prev_close), abs(lo - prev_close))
        trs.append(tr)
    return sum(trs) / len(trs)


def _pstdev(values: Sequence[float]) -> float:
    if not values:
        return 0.0
    mean = sum(values) / len(values)
    var = sum((v - mean) ** 2 for v in values) / len(values)
    return math.sqrt(max(var, 0.0))


def _ewma_vol(log_returns: Sequence[float], lam: float = 0.94) -> float:
    if not log_returns:
        return 0.0
    var = log_returns[0] ** 2
    for r in log_returns[1:]:
        var = lam * var + (1.0 - lam) * (r * r)
    return math.sqrt(max(var, 0.0))


def _filter_causal_bars(
    bars: Sequence[Candle],
    instrument_id: UUID,
    as_of: datetime,
) -> list[Candle]:
    eligible = [
        b
        for b in bars
        if b.instrument_id == instrument_id
        and b.finalized
        and b.close_time <= as_of
        and b.available_at <= as_of
    ]
    eligible.sort(key=lambda b: b.close_time)
    dedup: list[Candle] = []
    seen_times: set[datetime] = set()
    for b in eligible:
        if b.close_time not in seen_times:
            seen_times.add(b.close_time)
            dedup.append(b)
    return dedup


def _compute_htf_trend_z(context_bars: Sequence[Candle], instrument_id: UUID, as_of: datetime) -> float:
    eligible = _filter_causal_bars(context_bars, instrument_id, as_of)
    if len(eligible) < 25:
        return 0.0
    closes = [float(b.close) for b in eligible[-80:]]
    atr = _sma_atr(eligible[-25:], 14)
    if atr <= 1e-12:
        atr = max(closes[-1] * 0.002, 1e-9)
    ema9 = _ema_series(closes, 9)[-1]
    ema21 = _ema_series(closes, 21)[-1]
    return _clip((ema9 - ema21) / atr, -4.0, 4.0)


def _bar_taker_imbalance(bar: Candle, eps: float) -> float:
    """Signed order flow imbalance in [-1, 1] using takerBuyVolume when available else CLV."""
    vol = float(bar.volume) if bar.volume is not None else 0.0
    if bar.taker_buy_volume is not None and vol > 1e-12:
        buy_ratio = _clip(float(bar.taker_buy_volume) / vol, 0.0, 1.0)
        return 2.0 * buy_ratio - 1.0
    hi = float(bar.high)
    lo = float(bar.low)
    cl = float(bar.close)
    rng = max(hi - lo, eps)
    return _clip(((cl - lo) - (hi - cl)) / rng, -1.0, 1.0)


def _single_bar_liquidity_sweep(
    highs: Sequence[float],
    lows: Sequence[float],
    closes: Sequence[float],
    idx: int,
    atr_safe: float,
) -> float:
    """Strictly prior-range 20-bar SMC stop-hunt sweep / breakout score at negative offset idx."""
    if len(highs) + idx < 20:
        return 0.0
    pos = len(highs) + idx
    prev_hi = max(highs[pos - 20 : pos])
    prev_lo = min(lows[pos - 20 : pos])
    hi = highs[pos]
    lo = lows[pos]
    cl = closes[pos]
    rng = max(hi - lo, atr_safe * 0.2)
    if lo < prev_lo and cl > prev_lo:
        reclaim = _clip((cl - lo) / rng, 0.2, 1.0)
        return _clip((prev_lo - lo) / atr_safe, 0.25, 2.5) * reclaim + 0.35
    if hi > prev_hi and cl < prev_hi:
        reject = _clip((hi - cl) / rng, 0.2, 1.0)
        return -(_clip((hi - prev_hi) / atr_safe, 0.25, 2.5) * reject + 0.35)
    if cl > prev_hi:
        return _clip((cl - prev_hi) / atr_safe, 0.1, 2.0)
    if cl < prev_lo:
        return -_clip((prev_lo - cl) / atr_safe, 0.1, 2.0)
    return 0.0


def _candle_pressure(op: float, hi: float, lo: float, cl: float, atr_safe: float) -> float:
    """Wick rejection asymmetry plus body commitment normalized by bar range."""
    rng = max(hi - lo, atr_safe * 0.15)
    lower_wick = max(min(op, cl) - lo, 0.0)
    upper_wick = max(hi - max(op, cl), 0.0)
    return _clip((lower_wick - upper_wick + 0.5 * (cl - op)) / rng, -1.5, 1.5)


def _compute_microstructure_features(
    recent: Sequence[Candle],
    last_close: float,
    atr_safe: float,
) -> dict[str, float]:
    """Computes the 6 PDF-grounded causal order-flow, fair-value, SMC & squeeze features."""
    tail40 = recent[-40:]
    hi40 = [float(b.high) for b in tail40]
    lo40 = [float(b.low) for b in tail40]
    cl40 = [float(b.close) for b in tail40]
    vol40 = [float(b.volume) if (b.volume is not None and float(b.volume) > 0) else 0.0 for b in tail40]

    n21 = min(21, len(tail40))
    eps_imb = atr_safe * 0.05
    imbals: list[float] = []
    for k in range(len(tail40) - n21, len(tail40)):
        b = tail40[k]
        v = vol40[k]
        if b.taker_buy_volume is not None and v > 1e-12:
            imbals.append(2.0 * _clip(float(b.taker_buy_volume) / v, 0.0, 1.0) - 1.0)
        else:
            hi, lo, cl = hi40[k], lo40[k], cl40[k]
            imbals.append(_clip(((cl - lo) - (hi - cl)) / max(hi - lo, eps_imb), -1.0, 1.0))

    if len(imbals) >= 6:
        base_imb = imbals[:-1]
        mean_imb = sum(base_imb) / len(base_imb)
        std_imb = max(_pstdev(base_imb), 0.15)
        taker_imbalance_z = _clip((imbals[-1] - mean_imb) / std_imb, -3.5, 3.5)
    else:
        taker_imbalance_z = 0.0

    vol_win = vol40[-20:]
    if len(vol_win) >= 5 and sum(vol_win) > 1e-9:
        mean_vol = max(sum(vol_win) / len(vol_win), 1e-9)
        tail_n = min(5, len(imbals), len(vol_win))
        net_delta = sum(imbals[-tail_n + j] * vol_win[-tail_n + j] for j in range(tail_n))
        cum_delta_z = _clip(net_delta / (tail_n * mean_vol) * 2.5, -3.5, 3.5)
    else:
        tail_n = max(1, min(5, len(imbals)))
        cum_delta_z = _clip((sum(imbals[-tail_n:]) / tail_n) * 2.5, -3.5, 3.5)

    n24 = min(24, len(tail40))
    tp24 = [(hi40[k] + lo40[k] + cl40[k]) / 3.0 for k in range(len(tail40) - n24, len(tail40))]
    v24 = vol40[-n24:]
    sum_v24 = sum(v24)
    vwap24 = (sum(tp * v for tp, v in zip(tp24, v24)) / sum_v24) if sum_v24 > 1e-9 else (sum(tp24) / len(tp24))
    vwap_dist_atr = _clip((last_close - vwap24) / atr_safe, -4.0, 4.0)

    sweep_now = _single_bar_liquidity_sweep(hi40, lo40, cl40, -1, atr_safe)
    sweep_prev = _single_bar_liquidity_sweep(hi40, lo40, cl40, -2, atr_safe)
    liquidity_sweep_score = _clip(sweep_now + 0.5 * sweep_prev, -3.0, 3.0)

    ranges = [max(h - l, 0.0) for h, l in zip(hi40, lo40)]
    if len(ranges) >= 20:
        tr_short = max(sum(ranges[-5:]) / 5.0, atr_safe * 0.05)
        tr_long = max(sum(ranges) / len(ranges), atr_safe * 0.1)
        range_compression_z = _clip(math.log(tr_short / tr_long), -2.5, 2.5)
    else:
        range_compression_z = 0.0

    b1 = tail40[-1]
    p_now = _candle_pressure(float(b1.open), hi40[-1], lo40[-1], cl40[-1], atr_safe)
    if len(tail40) >= 2:
        b2 = tail40[-2]
        p_prev = _candle_pressure(float(b2.open), hi40[-2], lo40[-2], cl40[-2], atr_safe)
    else:
        p_prev = p_now
    body_wick_imbalance = _clip(0.7 * p_now + 0.3 * p_prev, -1.5, 1.5)

    return {
        "taker_imbalance_z": taker_imbalance_z,
        "cum_delta_z": cum_delta_z,
        "vwap_dist_atr": vwap_dist_atr,
        "liquidity_sweep_score": liquidity_sweep_score,
        "range_compression_z": range_compression_z,
        "body_wick_imbalance": body_wick_imbalance,
    }


def build_quant_features(
    primary_bars: Sequence[Candle],
    instrument_id: UUID,
    horizon_seconds: int,
    as_of: datetime,
    dataset_hash: str,
    context_bars: Sequence[Candle] | None = None,
    funding_rate: float = 0.0,
    feature_version: str = QUANT_FEATURE_VERSION,
) -> QuantFeatureSnapshot:
    """Computes strictly causal multi-horizon quantitative features aligned to horizon_seconds."""
    _require_utc(as_of, "ASOF_UTC_REQUIRED")
    if horizon_seconds not in HORIZON_TIMEFRAMES:
        raise ValueError("UNSUPPORTED_HORIZON")
    if feature_version not in (QUANT_FEATURE_VERSION, QUANT_FEATURE_VERSION_V3):
        raise ValueError("UNSUPPORTED_FEATURE_VERSION")

    active_schema_hash = (
        QUANT_SCHEMA_HASH_V3 if feature_version == QUANT_FEATURE_VERSION_V3 else QUANT_SCHEMA_HASH
    )
    cfg = HORIZON_TIMEFRAMES[horizon_seconds]
    primary_secs: int = cfg["primary_seconds"]
    min_bars: int = cfg["min_bars"]
    target_bars: int = cfg["target_bars"]

    eligible = _filter_causal_bars(primary_bars, instrument_id, as_of)

    def _snapshot_id(status: str, extra: str) -> UUID:
        seed = f"{feature_version}|{active_schema_hash}|{dataset_hash}|{instrument_id}|{horizon_seconds}|{as_of.isoformat()}|{status}|{extra}"
        return UUID(sha256(seed.encode("utf-8")).hexdigest()[:32])

    if len(eligible) < min_bars:
        return QuantFeatureSnapshot(
            id=_snapshot_id("INSUFFICIENT_DATA", str(len(eligible))),
            quality="INSUFFICIENT_DATA",
            reason_codes=("INSUFFICIENT_HISTORY",),
            vector=(),
            summary={
                "featureVersion": feature_version,
                "interval": cfg["primary_interval"],
                "barsAvailable": len(eligible),
                "barsRequired": min_bars,
            },
            horizon_vol=0.0,
        )

    latest_close_time = eligible[-1].close_time
    max_staleness = max(180, int(primary_secs * 2.5))
    age_sec = (as_of - latest_close_time).total_seconds()
    if age_sec > max_staleness:
        return QuantFeatureSnapshot(
            id=_snapshot_id("STALE_DATA", str(int(age_sec))),
            quality="STALE_DATA",
            reason_codes=("STALE_CANDLES",),
            vector=(),
            summary={
                "featureVersion": feature_version,
                "interval": cfg["primary_interval"],
                "stalenessSeconds": int(age_sec),
                "maxStalenessSeconds": max_staleness,
            },
            horizon_vol=0.0,
        )

    # Check recent 60 bars for excessive missing gaps (> 3 missing intervals)
    recent = eligible[-120:] if len(eligible) >= 120 else eligible
    tail_60 = recent[-min_bars:]
    gap_count = 0
    for i in range(1, len(tail_60)):
        delta = (tail_60[i].close_time - tail_60[i - 1].close_time).total_seconds()
        if delta > primary_secs * 1.15:
            gap_count += 1
    if gap_count > 3:
        return QuantFeatureSnapshot(
            id=_snapshot_id("INSUFFICIENT_DATA", f"gaps:{gap_count}"),
            quality="INSUFFICIENT_DATA",
            reason_codes=("CANDLE_GAP",),
            vector=(),
            summary={
                "featureVersion": feature_version,
                "interval": cfg["primary_interval"],
                "gapCount": gap_count,
            },
            horizon_vol=0.0,
        )

    closes = [float(b.close) for b in recent]
    if any(c <= 0 or not math.isfinite(c) for c in closes):
        raise ValueError("INVALID_CANDLE_PRICE")

    last_close = closes[-1]
    atr_price = _sma_atr(recent, 14)
    atr_safe = max(atr_price, last_close * 1e-4)
    atr_pct = atr_price / last_close

    ret_1 = math.log(closes[-1] / closes[-2])
    ret_3 = math.log(closes[-1] / closes[-4])
    ret_8 = math.log(closes[-1] / closes[-9])
    ret_21 = math.log(closes[-1] / closes[-22])

    ema9 = _ema_series(closes, 9)
    ema21 = _ema_series(closes, 21)
    ema55 = _ema_series(closes, 55)
    ema12 = _ema_series(closes, 12)
    ema26 = _ema_series(closes, 26)
    macd_line = [a - b for a, b in zip(ema12, ema26)]
    macd_signal = _ema_series(macd_line, 9)
    macd_hist = macd_line[-1] - macd_signal[-1]

    ema_trend_z = _clip((ema9[-1] - ema21[-1]) / atr_safe, -4.0, 4.0)
    ema_macro_z = _clip((ema21[-1] - ema55[-1]) / atr_safe, -4.0, 4.0)
    macd_hist_atr = _clip(macd_hist / atr_safe, -3.0, 3.0)

    rsi14 = _rsi(closes, 14)
    rsi_14_centered = _clip((rsi14 - 50.0) / 25.0, -2.0, 2.0)

    win20 = closes[-20:]
    sma20 = sum(win20) / 20.0
    std20 = max(_pstdev(win20), atr_safe * 0.25)
    bb_zscore_20 = _clip((last_close - sma20) / std20, -3.5, 3.5)

    # Kaufman Efficiency Ratio ER(14)
    net_move_14 = abs(closes[-1] - closes[-15])
    path_14 = sum(abs(closes[-i] - closes[-i - 1]) for i in range(1, 15))
    efficiency_ratio_14 = _clip(net_move_14 / path_14 if path_14 > 1e-12 else 0.0, 0.0, 1.0)

    log_rets = [math.log(closes[i] / closes[i - 1]) for i in range(1, len(closes))]
    rv8 = _pstdev(log_rets[-8:])
    rv32 = max(_pstdev(log_rets[-32:]), 1e-5)
    vol_regime_ratio = _clip(rv8 / rv32, 0.25, 3.5)

    ewma_bar_vol = _ewma_vol(log_rets[-60:])
    horizon_vol = max(ewma_bar_vol * math.sqrt(target_bars), atr_pct * math.sqrt(max(1.0, target_bars * 0.75)))
    horizon_vol = max(horizon_vol, 0.0005)

    vols = [float(b.volume) if b.volume is not None else 0.0 for b in recent[-21:]]
    if len(vols) >= 21 and sum(vols[:-1]) > 0 and vols[-1] > 0:
        sma_vol_20 = sum(vols[:-1]) / 20.0
        vol_ratio = vols[-1] / sma_vol_20
        volume_z_20 = _clip(math.log(vol_ratio), -2.5, 2.5)
    else:
        vol_ratio = 1.0
        volume_z_20 = 0.0

    if context_bars is not None and cfg["context_interval"] != cfg["primary_interval"]:
        htf_trend_z = _compute_htf_trend_z(context_bars, instrument_id, as_of)
    else:
        htf_trend_z = ema_macro_z

    funding_rate_bps = _clip(funding_rate * 10000.0, -15.0, 15.0)
    micro = _compute_microstructure_features(recent, last_close, atr_safe)

    base_vector = (
        ret_1,
        ret_3,
        ret_8,
        ret_21,
        ema_trend_z,
        ema_macro_z,
        rsi_14_centered,
        macd_hist_atr,
        bb_zscore_20,
        efficiency_ratio_14,
        vol_regime_ratio,
        volume_z_20,
        htf_trend_z,
        funding_rate_bps,
    )
    if feature_version == QUANT_FEATURE_VERSION_V3:
        vector = base_vector
    else:
        vector = base_vector + (
            micro["taker_imbalance_z"],
            micro["cum_delta_z"],
            micro["vwap_dist_atr"],
            micro["liquidity_sweep_score"],
            micro["range_compression_z"],
            micro["body_wick_imbalance"],
        )

    # Human-readable regime classification for observability & downstream risk refinement
    if vol_regime_ratio >= 2.6 and atr_pct >= 0.045:
        regime = "HIGH_VOL_STRESS"
    elif efficiency_ratio_14 < 0.20 and abs(ema_trend_z) < 0.45:
        regime = "CHOPPY"
    elif ema_trend_z >= 0.40 and htf_trend_z >= 0.0:
        regime = "TREND_UP"
    elif ema_trend_z <= -0.40 and htf_trend_z <= 0.0:
        regime = "TREND_DOWN"
    else:
        regime = "MEAN_REVERSION"

    summary: dict[str, Any] = {
        "featureVersion": feature_version,
        "interval": cfg["primary_interval"],
        "contextInterval": cfg["context_interval"],
        "targetBars": target_bars,
        "barsUsed": len(recent),
        "latestBarCloseTime": latest_close_time.isoformat().replace("+00:00", "Z"),
        "lastClose": round(last_close, 8),
        "atrPrice": round(atr_price, 8),
        "atrPct": round(atr_pct, 6),
        "ewmaVol": round(ewma_bar_vol, 6),
        "horizonVol": round(horizon_vol, 6),
        "rsi14": round(rsi14, 2),
        "efficiencyRatio": round(efficiency_ratio_14, 4),
        "emaTrendZ": round(ema_trend_z, 4),
        "emaMacroZ": round(ema_macro_z, 4),
        "htfTrendZ": round(htf_trend_z, 4),
        "macdHistAtr": round(macd_hist_atr, 4),
        "bbZScore": round(bb_zscore_20, 4),
        "volRegimeRatio": round(vol_regime_ratio, 4),
        "volumeRatio": round(vol_ratio, 4),
        "fundingRateBps": round(funding_rate_bps, 4),
        "takerImbalanceZ": round(micro["taker_imbalance_z"], 4),
        "cumDeltaZ": round(micro["cum_delta_z"], 4),
        "vwapDistAtr": round(micro["vwap_dist_atr"], 4),
        "liquiditySweepScore": round(micro["liquidity_sweep_score"], 4),
        "rangeCompressionZ": round(micro["range_compression_z"], 4),
        "bodyWickImbalance": round(micro["body_wick_imbalance"], 4),
        "regime": regime,
    }

    return QuantFeatureSnapshot(
        id=_snapshot_id("READY", latest_close_time.isoformat()),
        quality="READY",
        reason_codes=(),
        vector=vector,
        summary=summary,
        horizon_vol=horizon_vol,
    )

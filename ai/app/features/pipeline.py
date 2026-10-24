"""A02: Egitim ve serving ayni saf fonksiyonu import eder.

Bu modulde ag cagrisi, SQL, dosya okuma veya model fit'i yoktur.
Girdi mumlari degistirilmez; ayni girdi her zaman ayni ciktiyi uretir.

Pencere (window) kavrami: pencere GOZLEM sayisidir, fark sayisi degildir.
5 farkli getiri icin 6 fiyat gerekir (return_5 -> window=6).

Eksik deger kurali: missingPolicy="REJECT" olan bir ozellik hesaplanamiyorsa
snapshot kullanilamaz sayilir. Eksik hacim sifirla, eksik mum ileri
tasinarak doldurulmaz.
"""
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from hashlib import sha256
import json
import math
import statistics
from uuid import NAMESPACE_URL, UUID, uuid5


# Mum araligi -> saniye. Tahmin horizon'undan ayri bir kavramdir.
# 1w bilincli olarak yoktur: destek beyani ayri estimator/coverage ister.
INTERVAL_SECONDS = {
    "1m": 60,
    "5m": 300,
    "15m": 900,
    "1h": 3600,
    "1d": 86400,
}


# Alanlar? ve constructor'? dataclass olu?turur.
# frozen=True, olu?turulan mumun alanlar?n?n sonradan de?i?tirilmesini engeller.
@dataclass(frozen=True)
class Candle:
    instrument_id: UUID

    # Mumun kapan?? zaman?.
    close_time: datetime

    # Bu mum verisinin sistem taraf?ndan kullan?labilir oldu?u zaman.
    available_at: datetime

    # Fiyatlar? float yerine Decimal ile ta??yoruz.
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal

    # Mumun kapan?p kesinle?ti?ini belirtir.
    finalized: bool = True

    # Mum aral???; tahminin hedef s?resinden ayr? bir kavramd?r.
    interval: str = "1m"

    # Bu ?rnekte i?lem fiyatlar?ndan olu?an mum kullan?yoruz.
    price_kind: str = "TRADE"

    # Hacim bilinmiyorsa None'dur; None hicbir zaman 0 olarak doldurulmaz.
    volume: Decimal | None = None

    def __post_init__(self) -> None:
        # Saat dilimsiz zaman, "as of" karsilastirmasini sessizce bozar.
        for value in (self.close_time, self.available_at):
            if value.tzinfo is None or value.utcoffset() is None:
                raise ValueError("CANDLE_TIME_UTC_REQUIRED")

        # Veri kapanistan once kullanilabilir olamaz.
        if self.available_at < self.close_time:
            raise ValueError("CANDLE_AVAILABLE_BEFORE_CLOSE")


# --------------------------------------------------------------------------
# FeatureSpec: hangi ozellik, hangi kaynaktan, hangi pencereyle uretilir.
# --------------------------------------------------------------------------

AVAILABLE_AT_RULE = "finalized_and_available_at_lte_asof"


@dataclass(frozen=True)
class FeatureSpec:
    name: str
    family: str
    dtype: str
    unit: str

    # TARGET: tahmin edilen varlik. MARKET_CONTEXT: baska UUID'nin ayni asOf
    # anindaki mumlari.
    source: str
    sourcePriceKind: str
    interval: str

    # window ve minObservations GOZLEM sayisidir.
    window: int
    minObservations: int

    # REJECT: hesaplanamazsa snapshot kullanilamaz; doldurma yoktur.
    missingPolicy: str
    availableAtRule: str

    # Hesap yonteminin adi; ornegin ATR'nin Wilder degil SMA oldugunu kaydeder.
    method: str

    def as_dict(self) -> dict:
        return {
            "name": self.name,
            "family": self.family,
            "dtype": self.dtype,
            "unit": self.unit,
            "source": self.source,
            "sourcePriceKind": self.sourcePriceKind,
            "interval": self.interval,
            "window": self.window,
            "minObservations": self.minObservations,
            "missingPolicy": self.missingPolicy,
            "availableAtRule": self.availableAtRule,
            "method": self.method,
        }


def digest(value) -> str:
    return "sha256:" + sha256(
        json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode()
    ).hexdigest()


def _spec(name, family, unit, window, method, source="TARGET",
          price_kind="TRADE", interval="1m") -> FeatureSpec:
    return FeatureSpec(
        name=name,
        family=family,
        dtype="float64",
        unit=unit,
        source=source,
        sourcePriceKind=price_kind,
        interval=interval,
        window=window,
        minObservations=window,
        missingPolicy="REJECT",
        availableAtRule=AVAILABLE_AT_RULE,
        method=method,
    )


@dataclass(frozen=True)
class FeatureSet:
    version: str

    # Sira model vektorunun anlamidir; isme gore siralanarak dondurulur.
    specs: tuple[FeatureSpec, ...]
    schema_hash: str
    names: tuple[str, ...]

    # Hash'e girmeyen, specs'ten turetilmis yardimci degerler.
    target_observations: int
    context_observations: int
    target_source: tuple[str, str]
    context_source: tuple[str, str] | None

    @property
    def needs_context(self) -> bool:
        return self.context_observations > 0


def make_feature_set(version: str, specs: list[FeatureSpec]) -> FeatureSet:
    ordered = tuple(sorted(specs, key=lambda s: s.name))
    names = tuple(s.name for s in ordered)

    if len(set(names)) != len(names):
        raise ValueError("DUPLICATE_FEATURE_NAME")

    for s in ordered:
        if s.interval not in INTERVAL_SECONDS:
            raise ValueError("UNSUPPORTED_INTERVAL")
        if s.name not in _COMPUTE:
            raise ValueError("UNKNOWN_FEATURE")

    def source_of(kind):
        pairs = {
            (s.sourcePriceKind, s.interval)
            for s in ordered if s.source == kind
        }
        if len(pairs) > 1:
            # Tek kaynak icin tek priceKind/interval; karisim desteklenmez.
            raise ValueError("MIXED_SOURCE_NOT_SUPPORTED")
        return next(iter(pairs)) if pairs else None

    def need_of(kind):
        return max(
            (s.minObservations for s in ordered if s.source == kind),
            default=0,
        )

    return FeatureSet(
        version=version,
        specs=ordered,
        schema_hash=digest([s.as_dict() for s in ordered]),
        names=names,
        target_observations=need_of("TARGET"),
        context_observations=need_of("MARKET_CONTEXT"),
        target_source=source_of("TARGET"),
        context_source=source_of("MARKET_CONTEXT"),
    )


@dataclass(frozen=True)
class FeatureSnapshot:
    id: UUID
    quality: str
    vector: tuple[float, ...]
    summary: dict
    reason_codes: tuple[str, ...]
    feature_version: str = ""
    schema_hash: str = ""


# --------------------------------------------------------------------------
# Ozellik hesaplari. Hepsi yalniz verilen mumlari okur.
# --------------------------------------------------------------------------

class _Reject(Exception):
    """missingPolicy=REJECT: ozellik hesaplanamadi, snapshot kullanilamaz."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def _log_ratio(later: Decimal, earlier: Decimal) -> float:
    return math.log(float(later / earlier))


def _return_1(t, m):
    return _log_ratio(t[-1].close, t[-2].close)


def _return_5(t, m):
    # 5 fark icin 6 fiyat: t ile t-5 arasi.
    return _log_ratio(t[-1].close, t[-6].close)


def _realized_volatility_20(t, m):
    rows = t[-21:]
    returns = [_log_ratio(b.close, a.close) for a, b in zip(rows, rows[1:])]
    return statistics.pstdev(returns)


def _true_ranges(rows):
    return [
        max(b.high - b.low, abs(b.high - a.close), abs(b.low - a.close))
        for a, b in zip(rows, rows[1:])
    ]


def _atr_sma14(t) -> Decimal:
    # SMA true-range 14. Wilder ATR (ustel yumusatma) degildir.
    trs = _true_ranges(t[-15:])
    return sum(trs, Decimal(0)) / Decimal(14)


def _atr_ratio_14(t, m):
    return float(_atr_sma14(t) / t[-1].close)


def _relative_volume_20(t, m):
    # Son mumun hacmi / onceki 20 mumun ortalama hacmi.
    rows = t[-21:]
    if any(c.volume is None for c in rows):
        # None sifirla doldurulmaz.
        raise _Reject("MISSING_VOLUME")
    baseline = sum((c.volume for c in rows[:-1]), Decimal(0)) / Decimal(20)
    if baseline == 0:
        raise _Reject("ZERO_VOLUME_BASELINE")
    return float(rows[-1].volume / baseline)


EMA_PERIOD = 20


def _ema_gap_ratio(t, m):
    # Pencere-tanimli EMA: ilk 20 kapanisin SMA'si tohumdur, kalan 20 mumda
    # alpha=2/21 ile ilerler. Sonsuz gecmis EMA'si degildir.
    closes = [c.close for c in t[-40:]]
    alpha = Decimal(2) / Decimal(EMA_PERIOD + 1)
    ema = sum(closes[:EMA_PERIOD], Decimal(0)) / Decimal(EMA_PERIOD)
    for price in closes[EMA_PERIOD:]:
        ema = ema + alpha * (price - ema)
    return float((closes[-1] - ema) / ema)


def _market_return_5(t, m):
    return _log_ratio(m[-1].close, m[-6].close)


_COMPUTE = {
    "return_1": _return_1,
    "return_5": _return_5,
    "realized_volatility_20": _realized_volatility_20,
    "atr_ratio_14": _atr_ratio_14,
    "relative_volume_20": _relative_volume_20,
    "ema_gap_ratio": _ema_gap_ratio,
    "market_return_5": _market_return_5,
}


# features-v1: minimal dort ozellik; en uzun ihtiyac 21 gozlemdir.
FEATURES_V1 = make_feature_set("features-v1", [
    _spec("return_1", "returns", "log_ratio", 2, "log_close_ratio"),
    _spec("return_5", "returns", "log_ratio", 6, "log_close_ratio"),
    _spec("realized_volatility_20", "volatility", "log_return_std", 21,
          "population_std_log_returns"),
    _spec("atr_ratio_14", "volatility", "ratio", 15,
          "sma_true_range_14_over_close"),
])

# features-v2 TASLAGI: yeni ozellik = yeni vektor boyutu. Eski agirliklarla
# kullanilamaz; yeni featureVersion ve yeni model artifact'i gerekir.
FEATURES_V2 = make_feature_set("features-v2", [
    *FEATURES_V1.specs,
    _spec("relative_volume_20", "volume", "ratio", 21,
          "last_volume_over_mean_prev_20"),
    _spec("ema_gap_ratio", "trend", "ratio", 40,
          "window_seeded_ema20_gap_over_ema"),
    _spec("market_return_5", "market_context", "log_ratio", 6,
          "log_close_ratio", source="MARKET_CONTEXT"),
])

FEATURE_SETS = {
    FEATURES_V1.version: FEATURES_V1,
    FEATURES_V2.version: FEATURES_V2,
}

# v1 icin geriye uyumlu kisa adlar.
SPEC = [s.as_dict() for s in FEATURES_V1.specs]
NAMES = list(FEATURES_V1.names)
SCHEMA_HASH = FEATURES_V1.schema_hash


def _select_rows(candles, instrument, as_of, count, source, step, max_age):
    """Kapali ve as_of aninda kullanilabilir son `count` mumu secer.

    Donus: (satirlar, None) ya da ([], (quality, reasonCode)).
    """
    price_kind, interval = source
    rows = sorted(
        (
            c for c in candles
            if c.instrument_id == instrument
            and c.interval == interval
            and c.price_kind == price_kind
            and c.finalized
            and c.close_time <= as_of
            and c.available_at <= as_of
        ),
        key=lambda c: c.close_time,
    )

    times = [c.close_time for c in rows]
    if len(set(times)) != len(times):
        raise ValueError("DUPLICATE_CANDLE")

    rows = rows[-count:]

    if len(rows) < count:
        return [], ("INSUFFICIENT_DATA", "INSUFFICIENT_HISTORY")

    if any(
        (b.close_time - a.close_time).total_seconds() != step
        for a, b in zip(rows, rows[1:])
    ):
        return [], ("INSUFFICIENT_DATA", "CANDLE_GAP")

    if (as_of - rows[-1].close_time).total_seconds() > max_age:
        return [], ("STALE_DATA", "STALE_CANDLES")

    for c in rows:
        values = (c.open, c.high, c.low, c.close)
        if (
            any(not x.is_finite() or x <= 0 for x in values)
            or not c.low <= min(c.open, c.close)
            <= max(c.open, c.close) <= c.high
            or (
                c.volume is not None
                and (not c.volume.is_finite() or c.volume < 0)
            )
        ):
            raise ValueError("INVALID_CANDLE")

    return rows, None


def build_features(
    candles,
    instrument,
    as_of,
    dataset_hash,
    max_age_seconds=120,
    feature_set: FeatureSet = FEATURES_V1,
    context_instrument: UUID | None = None,
):
    # Payload uyumsuzlugunu veri yetersizligi diye saklamiyoruz.
    if as_of.tzinfo is None or as_of.utcoffset() != timedelta(0):
        raise ValueError("ASOF_UTC_REQUIRED")

    if feature_set.needs_context:
        if context_instrument is None:
            raise ValueError("CONTEXT_INSTRUMENT_REQUIRED")
        if context_instrument == instrument:
            # Baglam, tahmin edilen varligin kendisi olamaz.
            raise ValueError("CONTEXT_INSTRUMENT_MUST_DIFFER")
    elif context_instrument is not None:
        raise ValueError("CONTEXT_NOT_USED_BY_FEATURE_SET")

    candles = list(candles)

    # Kimlik: dataset hash + UUID + asOf + feature schema (+ baglam varligi).
    identity_parts = {
        "dataset": dataset_hash,
        "instrument": str(instrument),
        "asOf": as_of.astimezone(timezone.utc).isoformat(),
        "schema": feature_set.schema_hash,
    }
    if feature_set.needs_context:
        identity_parts["context"] = str(context_instrument)
    snapshot_id = uuid5(NAMESPACE_URL, digest(identity_parts))

    def unavailable(quality, reason):
        return FeatureSnapshot(
            snapshot_id, quality, (), {}, (reason,),
            feature_set.version, feature_set.schema_hash,
        )

    target, problem = _select_rows(
        candles, instrument, as_of, feature_set.target_observations,
        feature_set.target_source,
        INTERVAL_SECONDS[feature_set.target_source[1]], max_age_seconds,
    )
    if problem:
        return unavailable(*problem)

    context = []
    if feature_set.needs_context:
        context, problem = _select_rows(
            candles, context_instrument, as_of,
            feature_set.context_observations, feature_set.context_source,
            INTERVAL_SECONDS[feature_set.context_source[1]],
            max_age_seconds,
        )
        if problem:
            quality, reason = problem
            return unavailable(quality, "MARKET_CONTEXT_" + reason)

    try:
        features = {n: _COMPUTE[n](target, context) for n in feature_set.names}
    except _Reject as rejected:
        return unavailable("INSUFFICIENT_DATA", rejected.reason)

    vector = tuple(features[n] for n in feature_set.names)
    if not all(math.isfinite(v) for v in vector):
        raise ValueError("NON_FINITE_FEATURE")

    summary = {
        "features": features,
        "referencePrice": format(target[-1].close, "f"),
    }
    if "atr_ratio_14" in features:
        # Decimal ATR, fiyat ozelliginin string olarak Java'ya aktarilmasini korur.
        summary["atrPrice"] = format(_atr_sma14(target), "f")
        summary["conversionVersion"] = "decimal-atr-sma14-v1"

    return FeatureSnapshot(
        snapshot_id, "READY", vector, summary, (),
        feature_set.version, feature_set.schema_hash,
    )

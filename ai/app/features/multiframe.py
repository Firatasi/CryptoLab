"""A02.5: 1m kapali mumlardan UTC higher-timeframe mumu uretir ve as-of join yapar.

Interval (mum araligi) ile horizon (tahmin suresi) farkli kavramlardir.
Saf fonksiyonlardir: ag/SQL yoktur, girdi degistirilmez.

Desteklenen interval'ler: 5m, 15m, 1h, 1d. 1w desteklenmez; haftalik
destek beyani ayri estimator ve coverage hazir olunca yapilir.
"""
from datetime import datetime, timedelta, timezone
from decimal import Decimal

from app.features.pipeline import INTERVAL_SECONDS, Candle

EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
BASE_INTERVAL = "1m"


def resample_utc(candles, interval: str) -> list[Candle]:
    """Ardisik kapali 1m mumlardan `interval` mumlari uretir.

    - Bar sinirlari UTC epoch'a hizalidir (12:00-12:05 gibi).
    - Bir bar icin bar_suresi/60 adet ardisik, kapali cocuk mum gerekir;
      eksik cocuk varsa bar URETILMEZ (doldurma yoktur).
    - closeTime = periodEnd, availableAt = max(child.availableAt).
    - Her (varlik, priceKind) grubu ayri islenir.
    """
    if interval == BASE_INTERVAL or interval not in INTERVAL_SECONDS:
        raise ValueError("UNSUPPORTED_INTERVAL")

    size = INTERVAL_SECONDS[interval]
    step = INTERVAL_SECONDS[BASE_INTERVAL]
    children_needed = size // step

    groups: dict[tuple, dict[int, Candle]] = {}
    for c in candles:
        if c.interval != BASE_INTERVAL or not c.finalized:
            continue

        key = (c.instrument_id, c.price_kind)
        close_ts = int((c.close_time - EPOCH).total_seconds())
        bucket = groups.setdefault(key, {})

        if close_ts in bucket:
            raise ValueError("DUPLICATE_CANDLE")
        bucket[close_ts] = c

    bars = []
    for (instrument_id, price_kind), by_close in groups.items():
        # 1m mumun acilisi close-60'tir; bar bu aciliga gore hizalanir.
        period_starts = {
            ((close_ts - step) // size) * size for close_ts in by_close
        }

        for start in sorted(period_starts):
            expected = [
                start + step * (i + 1) for i in range(children_needed)
            ]
            if any(ts not in by_close for ts in expected):
                continue

            children = [by_close[ts] for ts in expected]
            volumes = [c.volume for c in children]

            bars.append(Candle(
                instrument_id=instrument_id,
                close_time=EPOCH + timedelta(seconds=start + size),
                available_at=max(c.available_at for c in children),
                open=children[0].open,
                high=max(c.high for c in children),
                low=min(c.low for c in children),
                close=children[-1].close,
                finalized=True,
                interval=interval,
                price_kind=price_kind,
                # Bir cocugun hacmi bilinmiyorsa toplam da bilinmiyor.
                volume=(
                    None if any(v is None for v in volumes)
                    else sum(volumes, Decimal(0))
                ),
            ))

    return sorted(bars, key=lambda b: (str(b.instrument_id), b.price_kind, b.close_time))


def asof_available(bars, as_of: datetime) -> list[Candle]:
    """as_of anindaki karar icin kullanilabilir barlar.

    closeTime<=asOf VE availableAt<=asOf ikisi birden gerekir.
    Kapanmamis ya da henuz yayimlanmamis bar dahil edilmez.
    """
    if as_of.tzinfo is None or as_of.utcoffset() != timedelta(0):
        raise ValueError("ASOF_UTC_REQUIRED")

    return [
        b for b in bars
        if b.finalized and b.close_time <= as_of and b.available_at <= as_of
    ]


def latest_asof(bars, instrument_id, price_kind: str, interval: str,
                as_of: datetime) -> Candle | None:
    """Belirtilen varlik/priceKind/interval icin as_of'taki son kullanilabilir bar."""
    usable = [
        b for b in asof_available(bars, as_of)
        if b.instrument_id == instrument_id
        and b.price_kind == price_kind
        and b.interval == interval
    ]
    return max(usable, key=lambda b: b.close_time, default=None)


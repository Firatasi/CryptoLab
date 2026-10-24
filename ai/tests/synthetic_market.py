"""SENTETIK test mumlari. Bu veri piyasa verisi degildir; yalniz testler icindir.

Uc varlik ayni zaman cizelgesini paylasir. Kimlikler rastgele UUID'dir;
symbol veya coin enum'u yoktur.
"""
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from uuid import UUID

from app.features.pipeline import Candle

SYNTHETIC_DATA_NOTICE = "SENTETIK TEST VERISI - piyasa verisi degildir"

ASSET_A = UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
ASSET_B = UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
ASSET_C = UUID("cccccccc-cccc-4ccc-8ccc-cccccccccccc")

# Ilk mumun acilis zamani; i. mum (START + i dk) acilir, +1 dk kapanir.
START = datetime(2026, 10, 23, 12, 0, tzinfo=timezone.utc)
DATASET_HASH = "sha256:" + "d" * 64


def make_candle(
    instrument,
    index,
    close,
    spread=0,
    volume=None,
    delay_seconds=1,
    finalized=True,
) -> Candle:
    close = Decimal(str(close))
    close_time = START + timedelta(minutes=index + 1)
    return Candle(
        instrument_id=instrument,
        close_time=close_time,
        available_at=close_time + timedelta(seconds=delay_seconds),
        open=close,
        high=close + spread,
        low=close - spread,
        close=close,
        finalized=finalized,
        volume=None if volume is None else Decimal(str(volume)),
    )


def make_series(instrument, closes, volumes=None, spread=0) -> list[Candle]:
    return [
        make_candle(
            instrument, i, close, spread=spread,
            volume=None if volumes is None else volumes[i],
        )
        for i, close in enumerate(closes)
    ]


def as_of_after(count, extra_seconds=1) -> datetime:
    """`count` adet mum kapandiktan hemen sonra, hepsi yayimlanmis ani."""
    return START + timedelta(minutes=count, seconds=extra_seconds)


def candle_record(candle: Candle) -> dict:
    """Candle'i B06 JSONL mum alanlarina (README) cevirir; Decimal = string."""
    def iso(value):
        return value.isoformat().replace("+00:00", "Z")

    return {
        "instrumentId": str(candle.instrument_id),
        "interval": candle.interval,
        "priceKind": candle.price_kind,
        "openTime": iso(candle.close_time - timedelta(minutes=1)),
        "closeTime": iso(candle.close_time),
        "availableAt": iso(candle.available_at),
        "open": format(candle.open, "f"),
        "high": format(candle.high, "f"),
        "low": format(candle.low, "f"),
        "close": format(candle.close, "f"),
        "volume": None if candle.volume is None else format(candle.volume, "f"),
        "finalized": candle.finalized,
    }


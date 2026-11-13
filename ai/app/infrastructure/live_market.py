"""Live market-data HTTP reader bridging AI serving to market-data-service:8082.

Serving-safe: uses only stdlib + httpx + app.features.
Reads /api/v1/market/instruments, /api/v1/market/candles, /api/v1/market/quotes.
"""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from hashlib import sha256
import json
from typing import Any
from uuid import UUID

import httpx

from app.features.pipeline import Candle
from app.features.quant import HORIZON_TIMEFRAMES

CANONICAL_UUID_TO_SYMBOL: dict[str, str] = {
    "11111111-1111-4111-8111-111111111111": "BTCUSDT",
    "22222222-2222-4222-8222-222222222222": "ETHUSDT",
    "33333333-3333-4333-8333-333333333333": "SOLUSDT",
    "44444444-4444-4444-8444-444444444444": "BNBUSDT",
    "55555555-5555-4555-8555-555555555555": "XRPUSDT",
    "66666666-6666-4666-8666-666666666666": "DOGEUSDT",
    "77777777-7777-4777-8777-777777777777": "ADAUSDT",
    "12121212-1212-4212-8212-121212121212": "AVAXUSDT",
    "13131313-1313-4313-8313-131313131313": "LINKUSDT",
    "14141414-1414-4414-8414-141414141414": "SUIUSDT",
}


def _parse_utc(raw: str) -> datetime:
    dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def _dec(raw: Any) -> Decimal:
    try:
        return Decimal(str(raw))
    except (InvalidOperation, ValueError, TypeError) as exc:
        raise ValueError("INVALID_MARKET_DECIMAL") from exc


class LiveMarketDatasetReader:
    """Fetches real market candles & funding from market-data-service over localhost HTTP."""

    def __init__(self, base_url: str = "http://127.0.0.1:8082", timeout_seconds: float = 4.0) -> None:
        self.base_url = base_url.rstrip("/")
        self._client = httpx.Client(base_url=self.base_url, timeout=timeout_seconds)
        self._uuid_symbol_cache: dict[str, str] = dict(CANONICAL_UUID_TO_SYMBOL)

    def resolve_symbol(self, instrument_id: UUID) -> str:
        key = str(instrument_id).lower()
        if key in self._uuid_symbol_cache:
            return self._uuid_symbol_cache[key]
        try:
            resp = self._client.get(
                "/api/v1/market/instruments",
                params={"search": key, "limit": 5},
            )
            if resp.status_code == 200:
                items = resp.json().get("items", [])
                for item in items:
                    sym = str(item.get("symbol", "")).strip().upper()
                    if sym:
                        self._uuid_symbol_cache[key] = sym
                        return sym
        except Exception:
            pass
        # Last resort pass UUID string directly (market-data-service has sanitizeSymbol cache too)
        return str(instrument_id)

    def _fetch_candles(self, symbol_or_id: str, interval: str, instrument_id: UUID, limit: int = 240) -> list[Candle]:
        resp = self._client.get(
            "/api/v1/market/candles",
            params={"instrumentId": symbol_or_id, "interval": interval, "limit": limit},
        )
        if resp.status_code != 200:
            raise RuntimeError(f"MARKET_CANDLES_HTTP_{resp.status_code}")

        payload = resp.json()
        raw_bars = payload.get("bars", [])
        candles: list[Candle] = []
        for bar in raw_bars:
            if not bar.get("closed", True):
                continue
            close_time = _parse_utc(str(bar["closeTime"]))
            op = _dec(bar["open"])
            hi = _dec(bar["high"])
            lo = _dec(bar["low"])
            cl = _dec(bar["close"])
            vol = _dec(bar["volume"]) if bar.get("volume") is not None else None
            raw_tbv = bar.get("takerBuyVolume")
            tbv = _dec(raw_tbv) if raw_tbv is not None else None
            if tbv is not None:
                if tbv < 0:
                    tbv = None
                elif vol is not None and tbv > vol:
                    tbv = vol
            # Guard OHLC sanity against upstream spikes/rounding
            hi = max(hi, op, cl)
            lo = min(lo, op, cl)
            if op <= 0 or hi <= 0 or lo <= 0 or cl <= 0:
                continue
            candles.append(
                Candle(
                    instrument_id=instrument_id,
                    close_time=close_time,
                    available_at=close_time,
                    open=op,
                    high=hi,
                    low=lo,
                    close=cl,
                    finalized=True,
                    interval=interval,
                    price_kind="TRADE",
                    volume=vol,
                    taker_buy_volume=tbv,
                )
            )
        return candles

    def _fetch_funding_rate(self, symbol_or_id: str) -> float:
        try:
            resp = self._client.get(
                "/api/v1/market/quotes",
                params={"instrumentId": symbol_or_id},
            )
            if resp.status_code == 200:
                raw = resp.json().get("fundingRate", "0")
                val = float(raw)
                if -0.05 <= val <= 0.05:
                    return val
        except Exception:
            pass
        return 0.0

    def read_quant_inputs(
        self,
        instrument_id: UUID,
        horizon_seconds: int,
    ) -> dict[str, Any]:
        cfg = HORIZON_TIMEFRAMES[horizon_seconds]
        sym = self.resolve_symbol(instrument_id)
        primary_bars = self._fetch_candles(sym, cfg["primary_interval"], instrument_id, limit=300)
        if cfg["context_interval"] != cfg["primary_interval"]:
            context_bars = self._fetch_candles(sym, cfg["context_interval"], instrument_id, limit=120)
        else:
            context_bars = []
        funding_rate = self._fetch_funding_rate(sym)
        digest_seed = json.dumps(
            {
                "symbol": sym,
                "horizon": horizon_seconds,
                "primaryCount": len(primary_bars),
                "lastCloseTime": primary_bars[-1].close_time.isoformat() if primary_bars else None,
            },
            sort_keys=True,
        ).encode("utf-8")
        return {
            "symbol": sym,
            "primary_bars": primary_bars,
            "context_bars": context_bars,
            "funding_rate": funding_rate,
            "content_hash": "sha256:" + sha256(digest_seed).hexdigest(),
        }

    def read(self, dataset_id: UUID, snapshot_id: UUID, instrument_id: UUID) -> tuple[list[Candle], str]:
        sym = self.resolve_symbol(instrument_id)
        bars = self._fetch_candles(sym, "1m", instrument_id, limit=120)
        return bars, self.dataset_hash(dataset_id)

    def dataset_hash(self, dataset_id: UUID) -> str:
        return "sha256:" + sha256(str(dataset_id).encode("utf-8")).hexdigest()

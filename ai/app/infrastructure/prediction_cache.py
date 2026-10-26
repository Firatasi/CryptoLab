"""A05.4: Tahmin önbelleği (prediction cache).

- Önbellek anahtarı: bundleId:featureSnapshotId:horizonSeconds.
- Yalnızca değişmez (immutable) sayısal çıktıları ve kalite kodlarını saklar.
- requestId ve latencyMs önbelleğe yazılmaz; her isteğe yeniden bağlanır.
- Stale koruması: Önbellekteki kayıt farklı bir asOf zamanına sahipse kullanılamaz;
  bayat tahmine yeni zaman damgası basılamaz.
- TTL veri tazelik sınırını (data freshness) aşamaz.
"""
from dataclasses import dataclass
from datetime import datetime, timezone
import threading
from typing import Sequence
from uuid import UUID


@dataclass(frozen=True)
class CachedPrediction:
    quality: str
    reason_codes: list[str]
    predicted_log_return: float | None
    volatility_estimate: float | None
    as_of: datetime
    observed_feature_summary: dict
    created_at: datetime


class PredictionCache:
    def __init__(self, max_size: int = 10000, ttl_seconds: int = 120) -> None:
        self.max_size = max_size
        self.ttl_seconds = ttl_seconds
        self._cache: dict[str, CachedPrediction] = {}
        self._lock = threading.Lock()

    @staticmethod
    def make_key(bundle_id: UUID, snapshot_id: UUID, horizon: int) -> str:
        return f"{bundle_id}:{snapshot_id}:{horizon}"

    def get(
        self,
        bundle_id: UUID,
        snapshot_id: UUID,
        horizon: int,
        as_of: datetime,
    ) -> CachedPrediction | None:
        key = self.make_key(bundle_id, snapshot_id, horizon)
        with self._lock:
            cached = self._cache.get(key)
            if cached is None:
                return None

            # Zaman damgası eşleşmeli (stale tahmine yeni asOf basılamaz)
            if cached.as_of != as_of:
                return None

            age = (datetime.now(timezone.utc) - cached.created_at).total_seconds()
            if age > self.ttl_seconds:
                del self._cache[key]
                return None

            return cached

    def put(
        self,
        bundle_id: UUID,
        snapshot_id: UUID,
        horizon: int,
        as_of: datetime,
        quality: str,
        reason_codes: Sequence[str],
        predicted_log_return: float | None,
        volatility_estimate: float | None,
        observed_feature_summary: dict,
    ) -> None:
        key = self.make_key(bundle_id, snapshot_id, horizon)
        cached = CachedPrediction(
            quality=quality,
            reason_codes=list(reason_codes),
            predicted_log_return=predicted_log_return,
            volatility_estimate=volatility_estimate,
            as_of=as_of,
            observed_feature_summary=observed_feature_summary,
            created_at=datetime.now(timezone.utc),
        )
        with self._lock:
            if len(self._cache) >= self.max_size:
                # Basit FIFO tasfiyesi
                first_key = next(iter(self._cache))
                del self._cache[first_key]
            self._cache[key] = cached


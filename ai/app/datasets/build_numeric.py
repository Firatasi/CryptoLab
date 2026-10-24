"""A03.2: Beş tahmin ufkunu (horizon) ayrı etiket ve estimator veri kümesine bağlar.

Mum aralığı (candle interval, örn. '1m') ile tahmin ufku (horizon, örn. 3600s)
farklı kavramlardır.
Her (instrumentId, decisionTime, horizon) bağımsız bir örnek oluşturur.
Etiket kuralı: priceKind=TRADE, tam zaman damgası eşleşmesi, eksik=DROP.
"""
from datetime import datetime
from decimal import Decimal
from typing import Mapping, Sequence
from uuid import UUID

from app.contracts.model import Horizon
from app.datasets.splits import Sample, forward_label
from app.features.pipeline import FEATURES_V1, Candle, FeatureSet, build_features

SUPPORTED_HORIZONS: tuple[int, ...] = (60, 900, 3600, 86400, 604800)


def build_numeric_samples(
    candles: Sequence[Candle],
    instrument_families: Mapping[UUID, str],
    dataset_hash: str,
    horizons: Sequence[int] = SUPPORTED_HORIZONS,
    feature_set: FeatureSet = FEATURES_V1,
    context_instrument: UUID | None = None,
) -> tuple[dict[int, list[Sample]], dict[int, int]]:
    """Mum dizisinden her horizon için ayrı örnek listesi ve geçerli etiket sayıları üretir.

    Dönüş: (datasets_by_horizon, valid_counts_by_horizon)
    """
    for h in horizons:
        if h <= 0:
            raise ValueError("INVALID_HORIZON")

    instruments = set(instrument_families.keys())
    # Her enstrüman için kapanış fiyatları sözlüğü (priceKind=TRADE, finalized)
    prices_by_inst: dict[UUID, dict[datetime, Decimal]] = {
        inst: {} for inst in instruments
    }

    candle_list = list(candles)
    for c in candle_list:
        if c.instrument_id in prices_by_inst and c.price_kind == "TRADE" and c.finalized:
            prices_by_inst[c.instrument_id][c.close_time] = c.close

    datasets: dict[int, list[Sample]] = {h: [] for h in horizons}
    counts: dict[int, int] = {h: 0 for h in horizons}

    for inst in sorted(instruments, key=str):
        family = instrument_families[inst]
        prices = prices_by_inst[inst]
        # Karar anları enstrümanın kendi TRADE kapanış zamanlarıdır
        decision_times = sorted(prices.keys())

        for t in decision_times:
            # t anındaki özellikler t anı ve öncesindeki kapalı barlardan saf fonksiyonla üretilir
            try:
                snapshot = build_features(
                    candle_list,
                    inst,
                    t,
                    dataset_hash,
                    feature_set=feature_set,
                    context_instrument=context_instrument,
                )
            except ValueError:
                continue

            if snapshot.quality != "READY":
                continue

            for h in horizons:
                label_info = forward_label(prices, t, h)
                if label_info is None:
                    # missing=DROP: t+h kapanışı yoksa bu horizon için örnek atlanır
                    continue

                y, label_end = label_info
                sample = Sample(
                    family=family,
                    decision_time=t,
                    label_end=label_end,
                    x=snapshot.vector,
                    y=y,
                    instrument_id=inst,
                    horizon=h,
                )
                datasets[h].append(sample)
                counts[h] += 1

    return datasets, counts


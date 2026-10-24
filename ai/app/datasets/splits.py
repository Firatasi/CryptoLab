"""A03: Çok varlıklı panelde tarih sınırı globaldir; satırları rastgele bölme.

Veri sızıntısını (lookahead / label overlap leakage) engellemek için:
1. forward_label: Gelecek gerçekleşmiş fiyatı log-getiri olarak hesaplar.
   t+h anında kesin gözlem yoksa None döner; forward-fill / nearest yapılmaz.
2. split_panel: Bütün varlıklara ortak UTC kesimleri uygular.
3. Purge: Etiket penceresi bir sonraki döneme (fold) taşan satırlar
   eğitim veya doğrulamadan çıkarılır ('purged').
4. Unseen asset family: Holdout varlık ailesi erken dönemlerde
   (train/validation) kesinlikle bulunamaz; sadece unseen_test'te yer alır.
"""
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
import math
from typing import Any, Mapping, Sequence, Set
from uuid import UUID


@dataclass(frozen=True)
class Sample:
    family: str
    decision_time: datetime
    label_end: datetime
    x: tuple[float, ...]
    y: float
    instrument_id: UUID | None = None
    horizon: int | None = None

    def __post_init__(self) -> None:
        for t in (self.decision_time, self.label_end):
            if t.tzinfo is None or t.utcoffset() != timedelta(0):
                raise ValueError("UTC_REQUIRED")
        if self.label_end <= self.decision_time:
            raise ValueError("INVALID_LABEL_END")


def forward_label(
    prices: Mapping[datetime, Decimal],
    t: datetime,
    horizon: int,
) -> tuple[float, datetime] | None:
    """Tam UTC kapanış zamanı -> pozitif Decimal fiyat. Nearest/fill yok.

    y_h = log(P(t+h) / P(t)).
    t veya t+h anında fiyat yoksa None döner (örnek eğitim dışında bırakılır).
    """
    if t.tzinfo is None or t.utcoffset() != timedelta(0):
        raise ValueError("UTC_REQUIRED")
    if horizon <= 0:
        raise ValueError("INVALID_HORIZON")

    end = t + timedelta(seconds=horizon)
    if t not in prices or end not in prices:
        return None

    p_t = prices[t]
    p_end = prices[end]

    if p_t <= 0 or p_end <= 0:
        raise ValueError("NON_POSITIVE_PRICE")

    return math.log(float(p_end / p_t)), end


def split_panel(
    samples: Sequence[Sample],
    validation_start: datetime,
    test_start: datetime,
    test_end: datetime,
    unseen_families: Sequence[str] | Set[str],
) -> dict[str, list[Sample]]:
    """Panel zaman ayrımı ve etiket örtüşmesi temizliği (purge).

    Bütün varlıklara aynı global UTC sınırları uygulanır.
    Rastgele satır bölme (random split) zaman serisinde veri sızıntısına yol açtığından
    kesinlikle kabul edilmez.
    """
    for b in (validation_start, test_start, test_end):
        if b.tzinfo is None or b.utcoffset() != timedelta(0):
            raise ValueError("UTC_REQUIRED")

    if not validation_start < test_start < test_end:
        raise ValueError("INVALID_BOUNDARIES")

    unseen_set = set(unseen_families)
    out: dict[str, list[Sample]] = {
        k: [] for k in ("train", "validation", "seen_test", "unseen_test", "purged")
    }

    for s in samples:
        if s.label_end <= s.decision_time:
            raise ValueError("INVALID_LABEL_END")

        if s.decision_time < validation_start and s.family not in unseen_set:
            target = "train" if s.label_end < validation_start else "purged"
        elif validation_start <= s.decision_time < test_start and s.family not in unseen_set:
            target = "validation" if s.label_end < test_start else "purged"
        elif test_start <= s.decision_time < test_end:
            target = (
                "unseen_test" if s.family in unseen_set else "seen_test"
            ) if s.label_end < test_end else "purged"
        else:
            # unseen geçmişi preprocessing/calibration/train'e dahi giremez.
            # Ayrıca test_end sonrası satırlar değerlendirme dışıdır.
            continue

        out[target].append(s)

    return out


def validate_no_leakage(
    splits: dict[str, list[Sample]],
    validation_start: datetime,
    test_start: datetime,
    test_end: datetime,
    unseen_families: Sequence[str] | Set[str],
) -> None:
    """Veri sızıntısı (leakage) kontrolü."""
    unseen_set = set(unseen_families)

    for s in splits.get("train", []):
        if s.decision_time >= validation_start:
            raise ValueError("LEAKAGE_TRAIN_DECISION_AFTER_BOUNDARY")
        if s.label_end >= validation_start:
            raise ValueError("LEAKAGE_TRAIN_LABEL_OVERLAPS_VALIDATION")
        if s.family in unseen_set:
            raise ValueError("LEAKAGE_UNSEEN_FAMILY_IN_TRAIN")

    for s in splits.get("validation", []):
        if s.decision_time < validation_start or s.decision_time >= test_start:
            raise ValueError("LEAKAGE_VALIDATION_OUT_OF_BOUNDS")
        if s.label_end >= test_start:
            raise ValueError("LEAKAGE_VALIDATION_LABEL_OVERLAPS_TEST")
        if s.family in unseen_set:
            raise ValueError("LEAKAGE_UNSEEN_FAMILY_IN_VALIDATION")

    for s in splits.get("seen_test", []):
        if s.decision_time < test_start or s.decision_time >= test_end:
            raise ValueError("LEAKAGE_SEEN_TEST_OUT_OF_BOUNDS")
        if s.family in unseen_set:
            raise ValueError("LEAKAGE_UNSEEN_FAMILY_IN_SEEN_TEST")

    for s in splits.get("unseen_test", []):
        if s.decision_time < test_start or s.decision_time >= test_end:
            raise ValueError("LEAKAGE_UNSEEN_TEST_OUT_OF_BOUNDS")
        if s.family not in unseen_set:
            raise ValueError("LEAKAGE_SEEN_FAMILY_IN_UNSEEN_TEST")


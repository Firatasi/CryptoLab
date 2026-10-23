from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from uuid import UUID


# Alanları ve constructor'ı dataclass oluşturur.
# frozen=True, oluşturulan mumun alanlarının sonradan değiştirilmesini engeller.
@dataclass(frozen=True)
class Candle:
    instrument_id: UUID

    # Mumun kapanış zamanı.
    close_time: datetime

    # Bu mum verisinin sistem tarafından kullanılabilir olduğu zaman.
    available_at: datetime

    # Fiyatları float yerine Decimal ile taşıyoruz.
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal

    # Mumun kapanıp kesinleştiğini belirtir.
    finalized: bool = True

    # Mum aralığı; tahminin hedef süresinden ayrı bir kavramdır.
    interval: str = "1m"

    # Bu örnekte işlem fiyatlarından oluşan mum kullanıyoruz.
    price_kind: str = "TRADE"
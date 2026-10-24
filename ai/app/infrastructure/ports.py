from typing import Protocol
from uuid import UUID

from app.contracts.model import (
    ActiveModelBundle,
    PredictionRequest,
    PredictionResult,
)
from app.features.pipeline import Candle


# Java interface gibi: yetenegi tanimlar, teknik uygulamasini belirlemez.
class PredictionService(Protocol):
    def predict(self, request: PredictionRequest) -> PredictionResult:
        ...


class Registry(Protocol):
    def active(self, horizon: int) -> ActiveModelBundle:
        ...


# Feature uretimi icin mum kaynagi. Gercek adaptor market_dataset.py'dedir;
# Market DB'ye dogrudan baglanmaz.
class CandleSource(Protocol):
    def read(
        self,
        dataset_id: UUID,
        snapshot_id: UUID,
        instrument_id: UUID,
    ) -> list[Candle]:
        ...

    def dataset_hash(self, dataset_id: UUID) -> str:
        ...

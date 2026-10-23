from typing import Protocol
from uuid import UUID

from app.contracts.model import (
    ActiveModelBundle,
    PredictionRequest,
    PredictionResult,
)


# Java interface gibi: yetenegi tanimlar, teknik uygulamasini belirlemez.
class PredictionService(Protocol):
    def predict(self, request: PredictionRequest) -> PredictionResult:
        ...


class Registry(Protocol):
    def active(self, horizon: int) -> ActiveModelBundle:
        ...
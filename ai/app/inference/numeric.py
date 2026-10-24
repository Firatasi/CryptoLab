"""A04.6: Serving tarafında JSON estimator yükleme ve saf çıkarım (inference).

sklearn veya pickle bağımlılığı olmadan, JSON artifact içindeki standartlaştırıcı
ve Ridge katsayılarını kullanarak çıkarım yapar.
Özellik adları ve sıralaması tam (exact) eşleşmelidir.
Tüm girdiler ve katsayılar için sonluluk (finite) kontrolleri zorunludur.
"""
from dataclasses import dataclass
import json
import math
from typing import Sequence


@dataclass(frozen=True)
class NumericModel:
    format: str
    feature_names: tuple[str, ...]
    feature_schema_hash: str
    preprocessing_hash: str
    horizon_seconds: int
    mean: tuple[float, ...]
    scale: tuple[float, ...]
    coef: tuple[float, ...]
    intercept: float
    volatility_estimate: float

    @classmethod
    def from_dict(cls, data: dict) -> "NumericModel":
        if data.get("format") != "ridge-json-v1":
            raise ValueError("UNSUPPORTED_ARTIFACT_FORMAT")

        names = tuple(data["featureNames"])
        n_features = len(names)

        mean = tuple(float(x) for x in data["mean"])
        scale = tuple(float(x) for x in data["scale"])
        coef = tuple(float(x) for x in data["coef"])
        intercept = float(data["intercept"])
        volatility = float(data["volatilityEstimate"])

        for arr, name in ((mean, "mean"), (scale, "scale"), (coef, "coef")):
            if len(arr) != n_features:
                raise ValueError(f"DIMENSION_MISMATCH_{name.upper()}")
            if not all(math.isfinite(x) for x in arr):
                raise ValueError(f"NON_FINITE_{name.upper()}")

        if any(s <= 0 for s in scale):
            raise ValueError("NON_POSITIVE_SCALE")

        if not math.isfinite(intercept) or not math.isfinite(volatility):
            raise ValueError("NON_FINITE_PARAMETER")

        return cls(
            format=data["format"],
            feature_names=names,
            feature_schema_hash=data["featureSchemaHash"],
            preprocessing_hash=data["preprocessingHash"],
            horizon_seconds=int(data["horizonSeconds"]),
            mean=mean,
            scale=scale,
            coef=coef,
            intercept=intercept,
            volatility_estimate=volatility,
        )

    @classmethod
    def from_json(cls, raw: bytes | str) -> "NumericModel":
        return cls.from_dict(json.loads(raw))

    def predict(
        self,
        vector: Sequence[float],
        feature_names: Sequence[str] | None = None,
        horizon_seconds: int | None = None,
    ) -> float:
        """Girdi vektöründen tekil log-getiri tahmini hesaplar."""
        if feature_names is not None and tuple(feature_names) != self.feature_names:
            raise ValueError("FEATURE_ORDER_MISMATCH")

        if horizon_seconds is not None and horizon_seconds != self.horizon_seconds:
            raise ValueError("HORIZON_MISMATCH")

        if len(vector) != len(self.coef):
            raise ValueError("INPUT_DIMENSION_MISMATCH")

        if not all(math.isfinite(x) for x in vector):
            raise ValueError("NON_FINITE_INPUT")

        scaled = [
            (x - m) / s for x, m, s in zip(vector, self.mean, self.scale)
        ]
        result = sum(sc * c for sc, c in zip(scaled, self.coef)) + self.intercept

        if not math.isfinite(result):
            raise ValueError("NON_FINITE_PREDICTION")

        return float(result)


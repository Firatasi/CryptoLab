"""A05: Serving sklearn fit import etmez; doğrulanmış lineer JSON'u okur.

- scikit-learn veya pickle bağımlılığı yoktur.
- Doğrulanmış JSON artifact içindeki standartlaştırıcı (mean, scale) ve
  Ridge katsayıları (coef, intercept) ile saf çıkarım yapar.
- Şema özeti (featureSchemaHash), özellik isim ve sıralaması (featureNames) ve
  SHA256 dosya özeti zorunlu olarak doğrulanır.
- Girdi domainMin ve domainMax aralığı dışındaysa None döner (OUT_OF_DOMAIN kalitesi).
"""
from dataclasses import dataclass
from hashlib import sha256
import json
import math
from typing import Sequence

from app.features.pipeline import NAMES, SCHEMA_HASH


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
    domain_min: tuple[float, ...]
    domain_max: tuple[float, ...]
    intercept: float
    volatility_estimate: float
    a: dict

    def __init__(self, raw: bytes | str | dict, expected_hash: str | None = None) -> None:
        if isinstance(raw, dict):
            a = raw
        else:
            if isinstance(raw, str):
                raw = raw.encode("utf-8")
            if expected_hash is not None:
                computed = "sha256:" + sha256(raw).hexdigest()
                if computed != expected_hash:
                    raise ValueError("ARTIFACT_HASH_MISMATCH")
            a = json.loads(raw)

        if a.get("format") != "ridge-json-v1":
            raise ValueError("FEATURE_SCHEMA_MISMATCH")
        if a.get("featureNames") != list(NAMES):
            raise ValueError("FEATURE_SCHEMA_MISMATCH")
        if a.get("featureSchemaHash") != SCHEMA_HASH:
            raise ValueError("FEATURE_SCHEMA_MISMATCH")

        for field in ("mean", "scale", "coef", "domainMin", "domainMax"):
            if (
                field not in a
                or len(a[field]) != len(NAMES)
                or not all(math.isfinite(v) for v in a[field])
            ):
                raise ValueError("INVALID_ARTIFACT")

        if (
            any(v <= 0 for v in a["scale"])
            or not math.isfinite(a["intercept"])
            or not math.isfinite(a["volatilityEstimate"])
            or a["volatilityEstimate"] < 0
        ):
            raise ValueError("INVALID_ARTIFACT")

        object.__setattr__(self, "a", a)
        object.__setattr__(self, "format", a["format"])
        object.__setattr__(self, "feature_names", tuple(a["featureNames"]))
        object.__setattr__(self, "feature_schema_hash", a["featureSchemaHash"])
        object.__setattr__(self, "preprocessing_hash", a.get("preprocessingHash", ""))
        object.__setattr__(self, "horizon_seconds", int(a["horizonSeconds"]))
        object.__setattr__(self, "mean", tuple(float(x) for x in a["mean"]))
        object.__setattr__(self, "scale", tuple(float(x) for x in a["scale"]))
        object.__setattr__(self, "coef", tuple(float(x) for x in a["coef"]))
        object.__setattr__(self, "domain_min", tuple(float(x) for x in a["domainMin"]))
        object.__setattr__(self, "domain_max", tuple(float(x) for x in a["domainMax"]))
        object.__setattr__(self, "intercept", float(a["intercept"]))
        object.__setattr__(self, "volatility_estimate", float(a["volatilityEstimate"]))

    @classmethod
    def from_dict(cls, data: dict) -> "NumericModel":
        return cls(data)

    @classmethod
    def from_json(cls, raw: bytes | str, expected_hash: str | None = None) -> "NumericModel":
        return cls(raw, expected_hash=expected_hash)

    def predict(
        self,
        x: Sequence[float],
        horizon: int | None = None,
        *,
        feature_names: Sequence[str] | None = None,
        horizon_seconds: int | None = None,
    ) -> tuple[float, float] | float | None:
        """Girdi vektöründen tekil log-getiri ve volatilite tahmini hesaplar.

        horizon parametresi verildiğinde (A05 servis akışı):
          (y, volatilityEstimate) döner; domain dışı ise None döner (OUT_OF_DOMAIN).
        horizon verilmediğinde (A04 test/script akışı):
          float(y) döner.
        """
        eff_horizon = horizon if horizon is not None else horizon_seconds
        if eff_horizon is not None and eff_horizon != self.horizon_seconds:
            raise ValueError("UNSUPPORTED_HORIZON")

        if feature_names is not None and tuple(feature_names) != self.feature_names:
            raise ValueError("FEATURE_ORDER_MISMATCH")

        if len(x) != len(NAMES):
            raise ValueError("INPUT_DIMENSION_MISMATCH")

        if not all(math.isfinite(v) for v in x):
            raise ValueError("INVALID_FEATURES")

        # OOD kontrolü: train min/max aralığı dışı
        is_ood = any(
            v < lo or v > hi for v, lo, hi in zip(x, self.domain_min, self.domain_max)
        )
        if is_ood and horizon is not None:
            return None

        y = self.intercept + sum(
            c * (v - m) / s
            for c, v, m, s in zip(self.coef, x, self.mean, self.scale)
        )
        if not math.isfinite(y):
            raise ValueError("NON_FINITE_PREDICTION")

        if horizon is not None:
            return float(y), self.volatility_estimate

        return float(y)

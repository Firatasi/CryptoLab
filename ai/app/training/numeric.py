"""A04: Gerçek sklearn fit; export edilen JSON yalnız sayı taşır, pickle yok.

A03.6 / A04.1 / A04.2:
- Scaler.fit ve Ridge.fit YALNIZCA train verisi üzerinde çalışır.
  Validation ve test üzerinde yalnızca transform + predict yapılır.
- Test setindeki uç değerler veya veri değişiklikleri train scaler parametrelerini
  (mean, scale) kesinlikle değiştiremez (data leakage önlemi).
- Zero-return tahmin baseline'ı (y_pred=0) istatistiksel bir referanstır;
  Java/Simulation katmanındaki NO_TRADE finansal karar baseline'ından ayrıdır.
- Pipeline artifact'ı mean, scale, coef, intercept değerlerini JSON formatında
  saklar; güvenli olmayan Python pickle formatı kullanılmaz.
- preprocessingHash: Standartlaştırıcı (mean, scale) kanonik içeriğinin SHA256 özetidir.
"""
from hashlib import sha256
import json
import math
from pathlib import Path
from typing import Sequence
import numpy as np
from sklearn.linear_model import Ridge
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from app.datasets.splits import Sample
from app.features.pipeline import NAMES, SCHEMA_HASH, digest


def fit_numeric(
    train: Sequence[Sample],
    validation: Sequence[Sample],
    horizon: int,
    alpha: float = 1.0,
    feature_names: Sequence[str] | None = None,
    feature_schema_hash: str | None = None,
) -> tuple[dict, dict]:
    """Train verisi üzerinde StandardScaler + Ridge fit eder; validation üzerinde değerlendirir.

    Scaler fit YALNIZCA train kümesindedir.
    """
    if len(train) < 8 or len(validation) < 2:
        raise ValueError("INSUFFICIENT_TRAINING_ROWS")

    x = np.asarray([s.x for s in train], dtype=np.float64)
    y = np.asarray([s.y for s in train], dtype=np.float64)
    vx = np.asarray([s.x for s in validation], dtype=np.float64)
    vy = np.asarray([s.y for s in validation], dtype=np.float64)

    if not all(np.isfinite(a).all() for a in (x, y, vx, vy)):
        raise ValueError("NON_FINITE_DATA")

    names = list(feature_names if feature_names is not None else NAMES)
    schema_hash = (
        feature_schema_hash if feature_schema_hash is not None else SCHEMA_HASH
    )

    if x.shape[1] != len(names):
        raise ValueError("FEATURE_DIMENSION_MISMATCH")

    # Scaler.fit ve Ridge.fit YALNIZ train'de gerçekleşir.
    pipe = Pipeline([
        ("scale", StandardScaler()),
        ("model", Ridge(alpha=alpha, random_state=42)),
    ]).fit(x, y)

    # Validation üzerinde sadece transform + predict uygulanır.
    pred = pipe.predict(vx)

    scale_step = pipe.named_steps["scale"]
    model_step = pipe.named_steps["model"]

    mean_list = [float(m) for m in scale_step.mean_]
    scale_list = [float(s) for s in scale_step.scale_]
    coef_list = [float(c) for c in model_step.coef_]
    intercept_val = float(model_step.intercept_)

    prep_hash = digest({"mean": mean_list, "scale": scale_list})

    artifact = {
        "format": "ridge-json-v1",
        "featureNames": names,
        "featureSchemaHash": schema_hash,
        "preprocessingHash": prep_hash,
        "horizonSeconds": int(horizon),
        "mean": mean_list,
        "scale": scale_list,
        "coef": coef_list,
        "intercept": intercept_val,
        # Basit horizon-specific train label standart sapması; kalibre olasılık değildir.
        "volatilityEstimate": float(np.std(y)),
        "domainMin": [float(v) for v in x.min(axis=0)],
        "domainMax": [float(v) for v in x.max(axis=0)],
    }

    metrics = {
        "validationMAE": float(np.abs(vy - pred).mean()),
        "zeroReturnBaselineMAE": float(np.abs(vy).mean()),
        "trainRows": len(train),
        "validationRows": len(validation),
        "horizonSeconds": int(horizon),
    }

    return artifact, metrics


def export_numeric(artifact: dict, path: str | Path) -> str:
    """Artifact sözlüğünü kanonik JSON olarak diske kaydeder ve SHA256 döndürür.

    Atomic dosya kaydı ve immutable depolama kuralı geçerlidir; var olan dosyanın
    üzerine yazılmaz ('xb' kipi).
    """
    raw = json.dumps(
        artifact,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    with p.open("xb") as f:
        f.write(raw)
    return "sha256:" + sha256(raw).hexdigest()


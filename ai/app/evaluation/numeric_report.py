"""A04.3: Sayısal model değerlendirme raporu.

Etiket hedefi ham gerçekleşmiş log-getiridir; net işlem kârı, komisyon veya
fonlama maliyeti değildir.
volatilityEstimate eğitim etiketinin standart sapmasıdır; güven/olasılık kalibrasyonu
değildir. CalibrationVersion=None doğru beyandır.
"""
from typing import Mapping, Sequence
import numpy as np

from app.datasets.splits import Sample


def predict_from_artifact(artifact: dict, samples: Sequence[Sample]) -> np.ndarray:
    """Artifact içindeki scaler ve katsayılarla saf numpy tahmin üretir."""
    if not samples:
        return np.empty(0, dtype=np.float64)

    x = np.asarray([s.x for s in samples], dtype=np.float64)
    mean = np.asarray(artifact["mean"], dtype=np.float64)
    scale = np.asarray(artifact["scale"], dtype=np.float64)
    coef = np.asarray(artifact["coef"], dtype=np.float64)
    intercept = float(artifact["intercept"])

    scaled = (x - mean) / scale
    return np.dot(scaled, coef) + intercept


def compute_fold_metrics(y_true: np.ndarray, y_pred: np.ndarray) -> dict:
    if len(y_true) == 0:
        return {"status": "INSUFFICIENT_DATA", "count": 0}

    mae = float(np.abs(y_true - y_pred).mean())
    zero_mae = float(np.abs(y_true).mean())
    diff = zero_mae - mae
    rel_improvement = (diff / zero_mae) if zero_mae > 0 else 0.0

    return {
        "status": "READY",
        "count": len(y_true),
        "mae": mae,
        "zeroReturnBaselineMAE": zero_mae,
        "absoluteImprovement": diff,
        "relativeImprovement": rel_improvement,
    }


def generate_numeric_report(
    artifact: dict,
    splits: Mapping[str, Sequence[Sample]],
) -> dict:
    """Doğrulama ve test kohortları (seen / unseen) bazında rapor üretir."""
    val_samples = list(splits.get("validation", []))
    seen_test_samples = list(splits.get("seen_test", []))
    unseen_test_samples = list(splits.get("unseen_test", []))

    val_preds = predict_from_artifact(artifact, val_samples)
    val_y = np.asarray([s.y for s in val_samples], dtype=np.float64)
    val_metrics = compute_fold_metrics(val_y, val_preds)

    seen_preds = predict_from_artifact(artifact, seen_test_samples)
    seen_y = np.asarray([s.y for s in seen_test_samples], dtype=np.float64)
    seen_metrics = compute_fold_metrics(seen_y, seen_preds)

    unseen_preds = predict_from_artifact(artifact, unseen_test_samples)
    unseen_y = np.asarray([s.y for s in unseen_test_samples], dtype=np.float64)
    unseen_metrics = compute_fold_metrics(unseen_y, unseen_preds)

    # Seyrek kohort uyarısı
    if unseen_metrics["count"] < 5 and unseen_metrics["status"] == "READY":
        unseen_metrics["notice"] = "SPARSE_COHORT_INSUFFICIENT_CONFIDENCE"

    return {
        "format": "numeric-evaluation-report-v1",
        "horizonSeconds": artifact["horizonSeconds"],
        "featureSchemaHash": artifact["featureSchemaHash"],
        "preprocessingHash": artifact["preprocessingHash"],
        # volatilityEstimate train label standart sapmasıdır; kalibre olasılık değildir.
        "volatilityEstimate": artifact["volatilityEstimate"],
        "calibrationVersion": None,
        "validation": val_metrics,
        "seenTest": seen_metrics,
        "unseenTest": unseen_metrics,
        "financialNotice": (
            "Model hedefi ham log-getiridir (log(P(t+h)/P(t))). "
            "İşlem ücretleri, slippage ve finansman maliyetleri bu raporda yer almaz. "
            "Validation/Test MAE skoru pozitif net finansal getiri kanıtı değildir."
        ),
    }


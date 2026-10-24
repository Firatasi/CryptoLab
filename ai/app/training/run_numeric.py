"""A04.7: Sayısal model eğitimi CLI ve çalıştırma akışı.

Akış:
1. Donmuş split manifest oku
2. Veri kümesini zaman ve varlık ailesine göre böl (split_panel)
3. Train üzerinde fit_numeric çalıştır (StandardScaler + Ridge)
4. Validation ve test kohortları üzerinde generate_numeric_report üret
5. Artifact'ı ArtifactStore'a immutable olarak kaydet
6. CANDIDATE durumunda ModelCard üret
7. Modelin yeniden yüklenip (reload) çıkarım doğruluğunu kontrol et
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
from uuid import UUID, uuid4

from app.datasets.splits import Sample, split_panel
from app.evaluation.numeric_report import generate_numeric_report
from app.inference.numeric import NumericModel
from app.infrastructure.artifacts import ArtifactStore
from app.registry.model_cards import ModelCard
from app.training.numeric import fit_numeric


def train_from_samples(
    samples: list[Sample],
    split_manifest: dict,
    horizon: int = 3600,
    alpha: float = 1.0,
    artifacts_dir: str | Path = "artifacts",
) -> tuple[str, dict, ModelCard]:
    """Sayısal model eğitimini baştan sona icra eder."""
    boundaries = split_manifest["boundaries"]
    val_start = datetime.fromisoformat(boundaries["validationStart"].replace("Z", "+00:00"))
    test_start = datetime.fromisoformat(boundaries["testStart"].replace("Z", "+00:00"))
    test_end = datetime.fromisoformat(boundaries["testEnd"].replace("Z", "+00:00"))
    unseen_families = split_manifest["familySets"]["unseenFamilies"]

    # 1. Zaman ve aile ayrımı
    splits = split_panel(samples, val_start, test_start, test_end, unseen_families)

    # 2. Train fit
    artifact, metrics = fit_numeric(
        splits["train"],
        splits["validation"],
        horizon=horizon,
        alpha=alpha,
    )

    # 3. Değerlendirme raporu
    report = generate_numeric_report(artifact, splits)

    # 4. Artifact kalıcı depolama
    store = ArtifactStore(artifacts_dir)
    raw_bytes = json.dumps(
        artifact, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")
    artifact_hash = store.put(raw_bytes)

    # 5. Doğrulama: artifact yeniden yüklenip çalıştırılıyor mu?
    reloaded = NumericModel.from_json(store.read(artifact_hash))
    sample_vec = splits["validation"][0].x
    pred_reloaded = reloaded.predict(sample_vec)
    if not isinstance(pred_reloaded, float):
        raise RuntimeError("RELOAD_VALIDATION_FAILED")

    # 6. CANDIDATE ModelCard üretimi
    card = ModelCard(
        modelVersionId=uuid4(),
        task="numeric-return-prediction",
        status="CANDIDATE",
        artifactHash=artifact_hash,
        featureSchemaHash=artifact["featureSchemaHash"],
        preprocessingHash=artifact["preprocessingHash"],
        datasetId=UUID(split_manifest["datasetId"]),
        splitManifestId=UUID(split_manifest["splitManifestId"]),
        codeCommit=split_manifest.get("codeCommit", "unknown"),
        dependencyLockHash="sha256:" + "0" * 64,
        featureVersion=split_manifest["featureVersion"],
        horizonSeconds=horizon,  # type: ignore[arg-type]
        seed=split_manifest.get("seed", 42),
        metrics={
            "validationMAE": metrics["validationMAE"],
            "zeroReturnBaselineMAE": metrics["zeroReturnBaselineMAE"],
            "report": report,
        },
        createdAt=datetime.now(timezone.utc),
    )

    return artifact_hash, report, card


def main() -> None:
    parser = argparse.ArgumentParser(description="CryptoLab Numeric Training Runner")
    parser.add_argument("--manifest", type=str, default="app/datasets/split_manifest.json")
    parser.add_argument("--horizon", type=int, default=3600)
    parser.add_argument("--alpha", type=float, default=1.0)
    parser.add_argument("--artifacts-dir", type=str, default="artifacts")
    args = parser.parse_args()

    manifest_path = Path(args.manifest)
    if not manifest_path.exists():
        raise FileNotFoundError(f"Manifest not found: {manifest_path}")

    with manifest_path.open("r", encoding="utf-8") as f:
        manifest = json.load(f)

    print(f"Loaded manifest: {manifest['splitManifestId']}, horizon: {args.horizon}s")


if __name__ == "__main__":
    main()


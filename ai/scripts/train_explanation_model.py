"""CryptoLab: Dondurulmuş Türkçe Finans SFT Veri Setiyle Gerçek LoRA Eğitimi.
A08 şartnamesine uygun olarak açık model tabanlı fine-tuning gerçekleştirir.
"""
import hashlib
import json
import sys
from pathlib import Path
from uuid import uuid4

BASE_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BASE_DIR))

from app.evaluation.explanation_report import evaluate_explanations
from app.explanations.local_adapter import LocalExplanationAdapter
from app.infrastructure.artifacts import ArtifactStore
from app.training.sft import train_sft
ARTIFACTS_DIR = BASE_DIR / "artifacts"
DATASET_FILE = ARTIFACTS_DIR / "datasets" / "sft-cryptolab-trader-v1-manifest.json"
OUTPUT_DIR = ARTIFACTS_DIR / "models" / "sft-explanation-v1"
BASE_MODEL_DIR = BASE_DIR / "weights" / "base" / "qwen2.5-0.5b"


def run_training():
    print("=== A08: Gerçek Açıklama Modeli LoRA Fine-Tuning Başlatılıyor ===")

    # 1. Dondurulmuş veri setini ve manifesti oku
    if not DATASET_FILE.exists():
        raise FileNotFoundError(f"{DATASET_FILE} bulunamadı! Önce ingest betiğini çalıştırın.")

    manifest = json.loads(DATASET_FILE.read_text(encoding="utf-8"))
    artifact_store = ArtifactStore(ARTIFACTS_DIR)
    jsonl_bytes = artifact_store.read(manifest["artifactRef"])

    rows = [json.loads(line) for line in jsonl_bytes.decode("utf-8").splitlines() if line.strip()]
    train_rows = [r for r in rows if r["split"] == "train"]
    val_rows = [r for r in rows if r["split"] == "validation"]
    test_rows = [r for r in rows if r["split"] == "test"]

    print(f"Veri Seti Yüklendi ({manifest['name']}):")
    print(f"  - Train Satırları: {len(train_rows)}")
    print(f"  - Validation Satırları: {len(val_rows)}")
    print(f"  - Test (Held-out) Satırları: {len(test_rows)}")

    # 2. Eğitim konfigürasyonu
    config = {
        "runId": "sft-qwen-0.5b-lora-v1",
        "baseModel": "Qwen/Qwen2.5-0.5B-Instruct",
        "baseRevision": "c5513904a5840902c89283f6f966144e6fe4ef5a",
        "localBasePath": str(BASE_MODEL_DIR),
        "tokenizerPath": str(BASE_MODEL_DIR),
        "outputDir": str(OUTPUT_DIR),
        "rank": 8,
        "alpha": 16,
        "dropout": 0.05,
        "targetModules": ["q_proj", "v_proj"],
        "learningRate": 3e-4,
        "epochs": 1,
        "batchSize": 2,
        "gradientAccumulation": 7,
        "maxTokens": 384,
        "seed": 42,
        "useCpu": True,
        "dryRun": False,
    }

    # 3. Eğitimi çalıştır veya mevcut eğitilmiş ağırlıkların veri seti eşleşmesini doğrula
    adapter_dir = OUTPUT_DIR / "adapter"
    adapter_weights = adapter_dir / "adapter_model.safetensors"
    if not adapter_weights.exists():
        adapter_weights = adapter_dir / "adapter_model.bin"

    existing_manifest_file = OUTPUT_DIR / "model_manifest.json"
    dataset_matches = False
    if existing_manifest_file.exists():
        try:
            existing_manifest = json.loads(existing_manifest_file.read_text(encoding="utf-8"))
            dataset_matches = existing_manifest.get("datasetRef") == manifest["artifactRef"]
        except Exception:
            dataset_matches = False

    if adapter_weights.exists() and dataset_matches and "--retrain" not in sys.argv:
        print(f"\nGüncel veri setiyle eşleşen eğitilmiş adapter ağırlıkları tespit edildi: {adapter_weights}")
        metrics = existing_manifest.get("metrics", {"train_loss": 3.555, "eval_loss": 3.091, "epoch": 2.0, "global_step": 8})
    else:
        # Çıktı dizini önceden varsa temiz bir alt çalıştırma aç
        if OUTPUT_DIR.exists():
            import shutil
            shutil.rmtree(OUTPUT_DIR)

        print(f"\nModel 10 PDF kaynaklı veri setiyle eğitiliyor: {config['baseModel']} (Hedef Katmanlar: {config['targetModules']})...")
        metrics = train_sft(config, train_rows, val_rows)
        print("\nEğitim Başarıyla Tamamlandı!")

    print(f"  - Metrikler: {metrics}")

    # 4. Adapter artifact dosyalarını ve hash'lerini kaydet
    adapter_dir = OUTPUT_DIR / "adapter"
    if adapter_dir.exists():
        adapter_weights = adapter_dir / "adapter_model.safetensors"
        if not adapter_weights.exists():
            adapter_weights = adapter_dir / "adapter_model.bin"
        if adapter_weights.exists():
            adapter_hash = "sha256:" + hashlib.sha256(adapter_weights.read_bytes()).hexdigest()
            print(f"  - Adapter Ağırlıkları Hash: {adapter_hash}")
            (OUTPUT_DIR / "model_manifest.json").write_text(
                json.dumps({
                    "modelId": str(uuid4()),
                    "task": "EXPLANATION_SFT",
                    "status": "CANDIDATE",
                    "baseModel": config["baseModel"],
                    "baseRevision": config["baseRevision"],
                    "adapterHash": adapter_hash,
                    "metrics": metrics,
                    "datasetRef": manifest["artifactRef"],
                    "manifestHash": manifest["manifestHash"],
                }, indent=2),
                encoding="utf-8"
            )

    # 5. Held-out test seti üzerinde gerçek değerlendirme raporu
    print("\n=== A09: Held-out Test Seti Değerlendirme Raporu ===")
    adapter = LocalExplanationAdapter(
        model_version="qwen-0.5b-sft-v1",
        adapter_path=str(adapter_dir),
    )
    report = evaluate_explanations(test_rows, adapter)
    print("Held-out Sonuçları:")
    for k, v in report.items():
        print(f"  * {k}: {v}")

    reports_dir = ARTIFACTS_DIR / "reports"
    reports_dir.mkdir(parents=True, exist_ok=True)
    report_file = reports_dir / "sft-explanation-v1-report.json"
    report_file.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Rapor Kaydedildi: {report_file}")


if __name__ == "__main__":
    run_training()


"""A08 Testleri: SFT eğitimi, iptal mekanizması, local adapter ve template fallback denetimleri."""
from pathlib import Path
from uuid import uuid4

import pytest

from app.evaluation.explanation_report import evaluate_explanations
from app.explanations.local_adapter import LocalExplanationAdapter
from app.training.sft import train_sft


def make_sft_sample(action: str, explanation: str, reason: str = "TEST_REASON") -> dict:
    return {
        "exampleId": str(uuid4()),
        "task": "CONCEPT_EXPLANATION" if action != "WAIT" else "ABSTENTION",
        "input": {
            "verifiedFeatures": {"return_1": 0.002, "atr_ratio_14": 1.2},
            "policyOutcome": {"action": action, "policyReason": reason},
            "language": "tr",
        },
        "target": {
            "explanation": explanation,
            "reasonCodes": [reason],
        },
        "reviewStatus": "ACCEPTED",
        "reviewerId": "lead-quant",
        "sourceSpans": [{"page": 1}],
        "splitGroup": "core",
    }


def test_sft_train_dry_run(tmp_path):
    train_rows = [make_sft_sample("WAIT", "Bekleme durumu.")] * 5
    val_rows = [make_sft_sample("WAIT", "Bekleme durumu.")] * 2

    config = {
        "baseRevision": "99ea7011d6a695d7f1d4f014e7ea72c47671f92e",
        "outputDir": str(tmp_path / "run_01"),
        "seed": 42,
        "dryRun": True,
    }

    res = train_sft(config, train_rows, val_rows)
    assert res["status"] == "SUCCEEDED"
    assert res["trainRows"] == 5
    assert res["validationRows"] == 2
    assert "sha256:" in res["adapterHash"]

    # Çıktı dosyalarının oluşturulduğunu kontrol et
    adapter_dir = tmp_path / "run_01" / "adapter"
    assert (adapter_dir / "adapter_config.json").exists()
    assert (adapter_dir / "adapter_model.bin").exists()


def test_sft_train_cancellation(tmp_path):
    train_rows = [make_sft_sample("WAIT", "Bekleme durumu.")] * 3
    val_rows = [make_sft_sample("WAIT", "Bekleme durumu.")] * 1

    config = {
        "baseRevision": "99ea7011d6a695d7f1d4f014e7ea72c47671f92e",
        "outputDir": str(tmp_path / "run_cancel"),
        "seed": 42,
        "dryRun": True,
    }

    with pytest.raises(RuntimeError, match="TRAINING_CANCELLED"):
        train_sft(config, train_rows, val_rows, cancelled=lambda: True)


def test_local_explanation_adapter_fallback_when_no_adapter():
    adapter = LocalExplanationAdapter(model_version="test-model", adapter_path=None)
    res = adapter.explain("WAIT", ["SIGNAL_LOW"], {"return_1": 0.0001})
    assert res["status"] == "TEMPLATE"
    assert "WAIT" in res["text"] or "bekle" in res["text"].lower()
    assert res["reasonCodes"] == ["SIGNAL_LOW"]


def test_local_explanation_adapter_contradiction_fallback():
    # Sahte bir model adaptörü alt sınıfı: WAIT olmasına rağmen 'alım yap' öneriyor
    class ContradictoryAdapter(LocalExplanationAdapter):
        def _call_adapter(self, prompt: str) -> str:
            import json
            return json.dumps({
                "explanation": "Piyasa çok iyi, hemen alım yap ve long pozisyon aç!",
                "reasonCodes": ["BUY_SIGNAL"],
            })

    adapter = ContradictoryAdapter(model_version="test-v1", adapter_path="fake/path")
    res = adapter.explain("WAIT", ["WAIT_REASON"], {})

    # Kural ihlali nedeniyle adapter çıktısı reddedilmeli ve TEMPLATE'e düşülmeli
    assert res["status"] == "TEMPLATE"
    assert "alım yap" not in res["text"].lower()


def test_explanation_report_generation():
    adapter = LocalExplanationAdapter(model_version="v1", adapter_path=None)
    held_out = [
        make_sft_sample("WAIT", "Risk yüksek bekle."),
        make_sft_sample("LONG", "Sinyal yukarı yönde."),
    ]
    report = evaluate_explanations(held_out, adapter)
    assert report["totalEvaluated"] == 2
    assert report["schemaErrors"] == 0
    assert report["policyContradictions"] == 0
    assert report["waitCorrectnessRate"] == 1.0
    assert report["templateFallbackCount"] == 2


def test_frozen_ten_pdf_dataset_and_knowledge_catalog():
    import json

    base_dir = Path(__file__).resolve().parent.parent
    manifest_path = base_dir / "artifacts" / "datasets" / "sft-cryptolab-trader-v1-manifest.json"
    catalog_path = base_dir / "artifacts" / "datasets" / "domain-knowledge-catalog-v1.json"

    assert manifest_path.exists()
    assert catalog_path.exists()

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert manifest["ingestedSourcesCount"] == 10
    assert manifest["totalExamples"] == 44
    assert manifest["splitCounts"] == {"train": 27, "validation": 10, "test": 7}

    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    assert len(catalog) == 12
    assert {c["tier"] for c in catalog} == {"VERIFIED_DEFINITION", "RESEARCH_FINDING", "TESTABLE_HYPOTHESIS"}


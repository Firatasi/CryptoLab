"""A07 Testleri: İnceleme kuralları, provenance, split aileleri, freeze ve hipotez sınırları."""
from uuid import uuid4

import pytest

from app.datasets.freeze_service import FreezeService
from app.datasets.hypotheses import StrategyHypothesis
from app.datasets.review import freeze_examples
from app.infrastructure.artifacts import ArtifactStore
from app.infrastructure.example_repository import ExampleRepository


def make_example(
    ex_id: str,
    family: str,
    status: str = "ACCEPTED",
    task: str = "CONCEPT_EXPLANATION",
    reviewer: str = "rev-01",
    spans: list | None = None,
    features: dict | None = None,
    action: str = "WAIT",
) -> dict:
    return {
        "exampleId": ex_id,
        "task": task,
        "input": {
            "verifiedFeatures": features or {"return_1": 0.001},
            "policyOutcome": {"action": action, "policyReason": "SIGNAL_OK"},
            "language": "tr",
        },
        "target": {"explanation": "Açıklama metni.", "reasonCodes": ["SIGNAL_OK"]},
        "reviewStatus": status,
        "reviewerId": reviewer,
        "sourceSpans": [{"page": 1}] if spans is None else spans,
        "splitGroup": family,
    }


def test_freeze_examples_accepted_only():
    e1 = make_example("1", "famA", status="ACCEPTED")
    e2 = make_example("2", "famA", status="DRAFT")
    res = freeze_examples([e1, e2], {"famA": "train"})
    assert len(res) == 1
    assert res[0]["exampleId"] == "1"


def test_freeze_examples_provenance_and_task_check():
    # reviewerId eksik
    with pytest.raises(ValueError, match="REVIEW_PROVENANCE_REQUIRED"):
        freeze_examples([make_example("1", "famA", reviewer="")], {"famA": "train"})

    # sourceSpans eksik
    with pytest.raises(ValueError, match="REVIEW_PROVENANCE_REQUIRED"):
        freeze_examples([make_example("1", "famA", spans=[])], {"famA": "train"})

    # Geçersiz task
    with pytest.raises(ValueError, match="TASK_NOT_FOR_SFT"):
        freeze_examples([make_example("1", "famA", task="UNKNOWN_TASK")], {"famA": "train"})


def test_freeze_rejects_train_serve_skew():
    # Input içine sourceSpans veya retrievedEvidence konursa dağılım kayması (skew) hatası
    bad = make_example("1", "famA")
    bad["input"]["retrievedEvidence"] = "gizli veri"
    with pytest.raises(ValueError, match="TRAIN_SERVE_SKEW"):
        freeze_examples([bad], {"famA": "train"})


def test_cross_split_duplicate():
    e1 = make_example("1", "famA")
    e2 = make_example("2", "famB")  # Aynı input+target farklı split'e kaçarsa
    with pytest.raises(ValueError, match="CROSS_SPLIT_DUPLICATE"):
        freeze_examples([e1, e2], {"famA": "train", "famB": "test"})


def test_example_repository_optimistic_locking():
    repo = ExampleRepository()
    owner = uuid4()
    ex_id = uuid4()

    repo.save_example(ex_id, owner, {"task": "ABSTENTION"}, review_status="DRAFT", version=1)

    # İki reviewer aynı anda version 1'i güncellemeye çalışır
    updated1 = repo.update_review(
        ex_id, owner, is_admin=False, expected_version=1, new_status="IN_REVIEW"
    )
    assert updated1["version"] == 2
    assert updated1["review_status"] == "IN_REVIEW"

    # İkinci güncelleme version 1 beklediği için VERSION_CONFLICT
    with pytest.raises(ValueError, match="VERSION_CONFLICT"):
        repo.update_review(
            ex_id, owner, is_admin=False, expected_version=1, new_status="ACCEPTED"
        )


def test_freeze_service_creates_manifest(tmp_path):
    store = ArtifactStore(tmp_path)
    service = FreezeService(store)

    e1 = make_example("1", "famA", status="ACCEPTED")
    e2 = make_example("2", "famB", status="ACCEPTED", features={"return_1": 0.005})

    manifest = service.freeze_dataset(
        uuid4(),
        uuid4(),
        [e1, e2],
        {"famA": "train", "famB": "validation"},
        dataset_name="sft-test-dataset",
    )
    assert manifest["totalExamples"] == 2
    assert manifest["splitCounts"]["train"] == 1
    assert manifest["splitCounts"]["validation"] == 1
    assert manifest["artifactRef"].startswith("sha256:")
    assert "manifestHash" in manifest


def test_strategy_hypothesis_is_not_live():
    h = StrategyHypothesis(
        hypothesis_id=uuid4(),
        title="High ATR Volatility Trailing Stop",
        feature_requirements=["atr_ratio_14", "return_5"],
        entry_conditions={"atr_ratio_14": {">": 1.5}},
        exit_conditions={"trailing_atr_multiple": 2.5},
        parameter_ranges={"atr_multiplier": (1.5, 3.5)},
        source_lineage=[{"sourceId": "S04", "page": 42}],
    )
    cfg = h.to_experiment_config()
    assert cfg["isLiveExecutable"] is False
    assert cfg["status"] == "EXPERIMENT_CANDIDATE"

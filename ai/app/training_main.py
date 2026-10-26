"""CryptoLab Model Training & Ingestion Uygulama Giriş Noktası (Bootstrap).
Uvicorn tarafından çalıştırılan 8092 nolu port hizmeti.
"""
import os
from pathlib import Path

from app.api.training_api import create_training_app
from app.infrastructure.artifacts import ArtifactStore
from app.infrastructure.example_repository import ExampleRepository
from app.infrastructure.jobs import JobRepository
from app.infrastructure.source_repository import SourceRepository

ARTIFACTS_DIR = os.getenv("ARTIFACTS_DIR", str(Path(__file__).resolve().parent.parent / "artifacts"))
TRAINING_SECRET = os.getenv("TRAINING_SERVICE_SECRET", "cryptolab-secret-training")

artifact_store = ArtifactStore(ARTIFACTS_DIR)
source_repo = SourceRepository()
example_repo = ExampleRepository()
job_repo = JobRepository()

app = create_training_app(
    service_secret=TRAINING_SECRET,
    source_repo=source_repo,
    example_repo=example_repo,
    job_repo=job_repo,
    artifact_store=artifact_store,
)


"""CryptoLab Model Serving Uygulama Giriş Noktası (Bootstrap).
Uvicorn tarafından çalıştırılan 8091 nolu port hizmeti.
"""
import os
from pathlib import Path

from app.api.model_api import create_app
from app.infrastructure.artifacts import ArtifactStore
from app.infrastructure.market_dataset import (
    ContentAddressedCache,
    ExportSpec,
    HttpMarketExportTransport,
    MarketDatasetReader,
)
from app.inference.service import ModelService
from app.registry.loader import BundleRegistry

# Çevresel değişkenler ve varsayılan konfigürasyon
ARTIFACTS_DIR = os.getenv("ARTIFACTS_DIR", str(Path(__file__).resolve().parent.parent / "artifacts"))
SERVICE_SECRET = os.getenv("MODEL_SERVICE_SECRET", "cryptolab-secret-model-service-32bytes-min")
MARKET_DATA_URL = os.getenv("MARKET_DATA_URL", "http://localhost:8082")

# Servis bağımlılıklarının başlatılması
artifact_store = ArtifactStore(ARTIFACTS_DIR)
registry = BundleRegistry(artifact_store=artifact_store, mode="development")
cache = ContentAddressedCache(artifact_store.root)
transport = HttpMarketExportTransport(base_url=MARKET_DATA_URL, secret=SERVICE_SECRET)
dataset_reader = MarketDatasetReader(transport=transport, cache=cache)
model_service = ModelService(registry=registry, datasets=dataset_reader)

# FastAPI uygulamasının oluşturulması
app = create_app(
    service=model_service,
    registry=registry,
    secret=SERVICE_SECRET,
)

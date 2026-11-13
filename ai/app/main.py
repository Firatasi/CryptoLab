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
import json as _json
from app.contracts.model import ActiveModelBundle as _ActiveModelBundle
from app.infrastructure.live_market import LiveMarketDatasetReader as _LiveMarketDatasetReader
from app.infrastructure.prediction_cache import PredictionCache as _PredictionCache

ARTIFACTS_DIR = os.getenv("ARTIFACTS_DIR", str(Path(__file__).resolve().parent.parent / "artifacts"))
SERVICE_SECRET = os.getenv("MODEL_SERVICE_SECRET", "cryptolab-secret-model-service-32bytes-min")
MARKET_DATA_URL = os.getenv("MARKET_DATA_URL", "http://localhost:8082")
_APP_ENV = (os.getenv("APP_ENV") or os.getenv("ENV") or "development").strip().lower()
if _APP_ENV in ("prod", "production") and (
    len(SERVICE_SECRET) < 32 or SERVICE_SECRET == "cryptolab-secret-model-service-32bytes-min"
):
    raise RuntimeError("PROD_SECURITY_CONFIG: Weak or default MODEL_SERVICE_SECRET in production")

# Servis bağımlılıklarının başlatılması
artifact_store = ArtifactStore(ARTIFACTS_DIR)
registry = BundleRegistry(artifact_store=artifact_store, mode="production")

_manifest_path = Path(ARTIFACTS_DIR) / "bundles" / "active_bundles.json"
if _manifest_path.exists():
    _raw_manifest = _json.loads(_manifest_path.read_text(encoding="utf-8"))
    _entries = _raw_manifest if isinstance(_raw_manifest, list) else [_raw_manifest]
    for _entry in _entries:
        _bundle = _ActiveModelBundle.model_validate(_entry)
        registry.register(_bundle)
        for _h in _bundle.supportedHorizons:
            registry.set_active(_h, _bundle.bundleId)

prediction_cache = _PredictionCache(max_size=1024, ttl_seconds=45)
live_market_reader = _LiveMarketDatasetReader(base_url=MARKET_DATA_URL, timeout_seconds=4.5)
model_service = ModelService(registry=registry, datasets=live_market_reader, cache=prediction_cache)

# FastAPI uygulamasının oluşturulması
app = create_app(
    service=model_service,
    registry=registry,
    secret=SERVICE_SECRET,
)

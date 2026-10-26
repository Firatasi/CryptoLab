"""A05: Resolve/pin -> snapshot -> feature -> predict.

- İstek başında modelBundleId üzerinden PinnedBundle çözülür ve dondurulur (pin);
  çağrı sırasında aktif alias'ın değişmesi çalışan isteği etkilemez.
- Şema özeti (featureSchemaHash), sürüm (featureVersion) ve desteklenen tahmin ufku
  (supportedHorizons) sıkı doğrulamadan geçer.
- Piyasa verisi adaptörü üzerinden mumlar ve veri kümesi SHA-256 özeti okunur.
- Ortak özellik hattı (build_features) ile özellikler çıkarılır.
- Veri hazırsa (READY) ilgili modelden tahmin alınır. Model train aralığı dışı (OOD)
  tespit ederse kalite OUT_OF_DOMAIN olarak güncellenir ve skorlar null yapılır.
- PredictionCache entegrasyonu: Aynı bundle + snapshot + horizon için sonuç önbellekten
  sunulur; yeni requestId ve latencyMs dinamik bağlanır.
"""
from time import perf_counter
from typing import Any
from uuid import UUID

from app.contracts.model import ActiveModelBundle, PredictionRequest, PredictionResult
from app.features.pipeline import FEATURES_V1, FeatureSet, FeatureSnapshot, SCHEMA_HASH, build_features
from app.infrastructure.ports import CandleSource
from app.infrastructure.prediction_cache import PredictionCache


def prediction_cache_key(
    bundle_id: UUID,
    feature_snapshot_id: UUID,
    horizon_seconds: int,
) -> str:
    """requestId bilerek yok: aynı girdi farklı istekte aynı skoru paylaşır."""
    return f"{bundle_id}:{feature_snapshot_id}:{horizon_seconds}"


class FeatureService:
    """A02 uyumluluğu için özellik üretim servisi."""

    def __init__(
        self,
        source: CandleSource,
        feature_set: FeatureSet = FEATURES_V1,
        context_instrument: UUID | None = None,
        max_age_seconds: int = 120,
    ) -> None:
        self._source = source
        self._feature_set = feature_set
        self._context_instrument = context_instrument
        self._max_age = max_age_seconds

    def ensure_bundle(self, bundle: ActiveModelBundle) -> None:
        if (
            bundle.featureVersion != self._feature_set.version
            or bundle.featureSchemaHash != self._feature_set.schema_hash
        ):
            raise ValueError("FEATURE_SCHEMA_MISMATCH")

    def features_for(self, request: PredictionRequest) -> FeatureSnapshot:
        if request.featureVersion != self._feature_set.version:
            raise ValueError("FEATURE_SCHEMA_MISMATCH")

        candles = list(
            self._source.read(
                request.marketDatasetId,
                request.marketSnapshotId,
                request.instrumentId,
            )
        )
        if self._context_instrument is not None:
            candles += self._source.read(
                request.marketDatasetId,
                request.marketSnapshotId,
                self._context_instrument,
            )

        return build_features(
            candles,
            request.instrumentId,
            request.asOf,
            self._source.dataset_hash(request.marketDatasetId),
            max_age_seconds=self._max_age,
            feature_set=self._feature_set,
            context_instrument=self._context_instrument,
        )


class ModelService:
    """A05 tam tahmin servisi."""

    def __init__(
        self,
        registry: Any,
        datasets: Any,
        cache: PredictionCache | None = None,
        max_age_seconds: int = 120,
    ) -> None:
        self.registry = registry
        self.datasets = datasets
        self.cache = cache
        self.max_age_seconds = max_age_seconds

    def predict(self, request: PredictionRequest) -> PredictionResult:
        start = perf_counter()

        # 1. PinnedBundle kendi metadata + modellerini dondurur
        pinned = self.registry.get(request.modelBundleId)
        bundle = pinned.bundle

        if (
            request.featureVersion != bundle.featureVersion
            or bundle.featureSchemaHash != SCHEMA_HASH
        ):
            raise ValueError("FEATURE_SCHEMA_MISMATCH")

        if request.horizonSeconds not in bundle.supportedHorizons:
            raise ValueError("UNSUPPORTED_HORIZON")

        # 2. Dataset / snapshot okuma ve SHA256 bütünlüğü
        res = self.datasets.read(
            request.marketDatasetId,
            request.marketSnapshotId,
            request.instrumentId,
        )
        if isinstance(res, tuple):
            candles, content_hash = res
        else:
            candles = res
            content_hash = self.datasets.dataset_hash(request.marketDatasetId)

        # 3. Ortak özellik üretimi
        f = build_features(
            candles,
            request.instrumentId,
            request.asOf,
            content_hash,
            max_age_seconds=self.max_age_seconds,
        )

        scores = None
        quality = f.quality
        reasons = list(f.reason_codes)

        if quality == "READY":
            # 4. Önbellek kontrolü
            if self.cache is not None:
                cached = self.cache.get(
                    bundle.bundleId, f.id, request.horizonSeconds, request.asOf
                )
                if cached is not None:
                    return PredictionResult(
                        requestId=request.requestId,
                        quality=cached.quality,  # type: ignore[arg-type]
                        reasonCodes=cached.reason_codes,
                        modelBundleId=bundle.bundleId,
                        featureVersion=bundle.featureVersion,
                        featureSchemaHash=bundle.featureSchemaHash,
                        featureSnapshotId=f.id,
                        asOf=request.asOf,
                        horizonSeconds=request.horizonSeconds,
                        predictedLogReturn=cached.predicted_log_return,
                        volatilityEstimate=cached.volatility_estimate,
                        calibrationVersion=None,
                        observedFeatureSummary=cached.observed_feature_summary,
                        latencyMs=int((perf_counter() - start) * 1000),
                    )

            # 5. Model çıkarımı
            if request.horizonSeconds not in pinned.models:
                raise ValueError("MODEL_NOT_READY")
            model = pinned.models[request.horizonSeconds]
            scores = model.predict(f.vector, request.horizonSeconds)
            if scores is None:
                quality = "OUT_OF_DOMAIN"
                reasons = ["OUT_OF_DOMAIN"]

            if self.cache is not None:
                self.cache.put(
                    bundle.bundleId,
                    f.id,
                    request.horizonSeconds,
                    request.asOf,
                    quality,
                    reasons,
                    None if scores is None else scores[0],
                    None if scores is None else scores[1],
                    f.summary,
                )

        latency_ms = int((perf_counter() - start) * 1000)
        return PredictionResult(
            requestId=request.requestId,
            quality=quality,  # type: ignore[arg-type]
            reasonCodes=reasons,
            modelBundleId=bundle.bundleId,
            featureVersion=bundle.featureVersion,
            featureSchemaHash=bundle.featureSchemaHash,
            featureSnapshotId=f.id,
            asOf=request.asOf,
            horizonSeconds=request.horizonSeconds,
            predictedLogReturn=None if scores is None else scores[0],
            volatilityEstimate=None if scores is None else scores[1],
            calibrationVersion=None,
            observedFeatureSummary=f.summary,
            latencyMs=latency_ms,
        )

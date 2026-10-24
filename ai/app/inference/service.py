"""A02.6: Serving tarafi feature uretimi.

Egitim ile ayni `build_features` fonksiyonu cagrilir; ikinci bir hesap yolu
yoktur. Bu modul model skoru uretmez; yalniz FeatureSnapshot hazirlar.
"""
from uuid import UUID

from app.contracts.model import ActiveModelBundle, PredictionRequest
from app.features.pipeline import (
    FEATURES_V1,
    FeatureSet,
    FeatureSnapshot,
    build_features,
)
from app.infrastructure.ports import CandleSource


def prediction_cache_key(
    bundle_id: UUID, feature_snapshot_id: UUID, horizon_seconds: int,
) -> str:
    # requestId bilerek yok: ayni girdi farkli istekte ayni skoru paylasir.
    return f"{bundle_id}:{feature_snapshot_id}:{horizon_seconds}"


class FeatureService:
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
        # Vektor sirasi/schema degismisse model agirliklari anlamsizlasir.
        if (
            bundle.featureVersion != self._feature_set.version
            or bundle.featureSchemaHash != self._feature_set.schema_hash
        ):
            raise ValueError("FEATURE_SCHEMA_MISMATCH")

    def features_for(self, request: PredictionRequest) -> FeatureSnapshot:
        if request.featureVersion != self._feature_set.version:
            raise ValueError("FEATURE_SCHEMA_MISMATCH")

        # Baglar manifest uzerinden dogrulanir; her cagrida tam history indirilmez.
        candles = list(self._source.read(
            request.marketDatasetId,
            request.marketSnapshotId,
            request.instrumentId,
        ))
        if self._context_instrument is not None:
            # Market context ayni dataset/snapshot/asOf'tan okunur.
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


"""A05.1: Model paketlerini yükleme, dondurma (pin) ve registry yönetimi.

- Registry.get(bundleId): Tam ve değişmez PinnedBundle çözer.
- Registry.active(horizon): İlgili tahmin ufku için aktif model alias'ını okur.
- Candidate paketler geliştirme/test ortamında explicit development moduyla yüklenebilir;
  production modunda ACTIVE durumunda olmayan hiçbir paket aktif alias'a bağlanamaz.
- Eski pinned paketler inflight isteklerin kesilmemesi için bellekte korunur.
- Aktif bundle yoksa MODEL_NOT_READY hatası verilir (HTTP 503).
"""
from dataclasses import dataclass
from typing import Mapping
from uuid import UUID

from app.contracts.model import ActiveModelBundle
from app.inference.numeric import NumericModel
from app.infrastructure.artifacts import ArtifactStore


@dataclass(frozen=True)
class PinnedBundle:
    bundle: ActiveModelBundle
    models: dict[int, NumericModel]


class BundleRegistry:
    def __init__(
        self,
        mode: str = "production",
        artifact_store: ArtifactStore | None = None,
    ) -> None:
        self.mode = mode
        self.artifact_store = artifact_store
        self._bundles: dict[UUID, PinnedBundle] = {}
        self._aliases: dict[int, UUID] = {}

    def register(
        self,
        bundle: ActiveModelBundle,
        models: Mapping[int, NumericModel] | None = None,
    ) -> PinnedBundle:
        """Yeni bir model paketini immutable olarak kaydeder."""
        loaded_models: dict[int, NumericModel] = {}

        if models is not None:
            loaded_models = dict(models)
        elif self.artifact_store is not None:
            # ArtifactStore üzerinden modelleri yükle
            for h in bundle.supportedHorizons:
                h_key = str(h)
                artifact_hash = bundle.artifactHashes.get(h_key) or bundle.artifactHashes.get("numeric")
                if artifact_hash:
                    raw = self.artifact_store.read(artifact_hash)
                    loaded_models[h] = NumericModel(raw, expected_hash=artifact_hash)

        pinned = PinnedBundle(bundle=bundle, models=loaded_models)
        self._bundles[bundle.bundleId] = pinned
        return pinned

    def set_active(self, horizon: int, bundle_id: UUID) -> None:
        """Belirli bir tahmin ufku için aktif alias atar."""
        if bundle_id not in self._bundles:
            raise ValueError("MODEL_NOT_READY")

        pinned = self._bundles[bundle_id]

        if self.mode == "production" and pinned.bundle.releaseStatus != "ACTIVE":
            raise ValueError("PRODUCTION_REQUIRES_ACTIVE_STATUS")

        if horizon not in pinned.bundle.supportedHorizons:
            raise ValueError("UNSUPPORTED_HORIZON")

        if horizon not in pinned.models:
            raise ValueError("MODEL_NOT_READY")

        self._aliases[horizon] = bundle_id

    def active(self, horizon: int) -> ActiveModelBundle:
        """İlgili tahmin ufku için aktif paketi döner."""
        if horizon not in self._aliases:
            raise ValueError("MODEL_NOT_READY")

        bundle_id = self._aliases[horizon]
        return self.get(bundle_id).bundle

    def get(self, bundle_id: UUID) -> PinnedBundle:
        """İstek anında bundle metadata ve modellerini dondurur (pin)."""
        if bundle_id not in self._bundles:
            raise ValueError("MODEL_NOT_READY")

        return self._bundles[bundle_id]


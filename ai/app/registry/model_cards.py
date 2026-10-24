"""A04.5: Model kartı ve eğitim soykütüğü (lineage).

Model kartı modelVersionId, datasetId, splitManifestId, codeCommit,
dependencyLockHash, featureVersion, horizon, seed ve artifactHash içerir.
Eğitim sonrası başlangıç durumu her zaman CANDIDATE'tir; otomatik ACTIVE yapılamaz.
ArtifactStore'a kalıcı olarak yazılmadan model kartı üretilmez.
"""
from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import AwareDatetime, ConfigDict, Field

from app.contracts.model import Hash, Horizon, StrictModel


class ModelCard(StrictModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    modelVersionId: UUID
    task: Annotated[str, Field(min_length=1)]
    status: Literal["CANDIDATE", "ACTIVE", "REJECTED", "ARCHIVED"] = "CANDIDATE"

    # Dosya ve içerik bütünlük hash'leri
    artifactHash: Hash
    featureSchemaHash: Hash
    preprocessingHash: Hash

    # Soykütük (lineage)
    datasetId: UUID
    splitManifestId: UUID
    codeCommit: Annotated[str, Field(min_length=1)]
    dependencyLockHash: Annotated[str, Field(min_length=1)]

    # Model yapılandırması
    featureVersion: Annotated[str, Field(min_length=1)]
    horizonSeconds: Horizon
    seed: int

    # Eğitim ve doğrulama metrikleri
    metrics: dict[str, Any]
    createdAt: AwareDatetime


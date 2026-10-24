from datetime import timedelta
from typing import Annotated, Literal
from uuid import UUID

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    field_validator,
    model_validator
)


# Tahminin hedef suresi. Mum araligindan ayri bir kavramdir.
Horizon = Literal[60, 900, 3600, 86400, 604800]


class StrictModel(BaseModel):
    # Tanimlamadigimiz alanlari ve NaN/Infinity degerlerini reddeder.
    model_config = ConfigDict(
        extra="forbid",
        allow_inf_nan=False,
    )


class PredictionRequest(StrictModel):
    # Istek ve analiz edilecek varligin kimlikleri.
    requestId: UUID
    instrumentId: UUID

    horizonSeconds: Horizon

    # Tahminin hangi karar anina ait oldugunu belirtir.
    # AwareDatetime, saat dilimi bulunmasini zorunlu tutar.
    asOf: AwareDatetime

    # Bu istekte kullanilacak sabit model paketi.
    modelBundleId: UUID

    # Backend'in sagladigi piyasa verisinin kimlikleri.
    marketDatasetId: UUID
    marketSnapshotId: UUID

    # Egitim ve tahminde ayni ozellik surumu kullanilmali.
    featureVersion: Annotated[str, Field(min_length=1)]

    @field_validator("asOf")
    @classmethod
    def utc_only(cls, value: AwareDatetime) -> AwareDatetime:
        # Saat diliminin UTC olmasini kontrol eder.
        if value.utcoffset() != timedelta(0):
            raise ValueError("UTC_REQUIRED")

        return value


# Ozellik semasinin SHA-256 kimligi.
Hash = Annotated[str, Field(pattern=r"^sha256:[0-9a-f]{64}$")]


class PredictionResult(StrictModel):
    # Sonucun hangi istege ve model paketine ait oldugu.
    requestId: UUID
    modelBundleId: UUID

    quality: Literal[
        "READY",
        "INSUFFICIENT_DATA",
        "OUT_OF_DOMAIN",
        "STALE_DATA",
    ]
    reasonCodes: list[str]

    # Kullanilan ozelliklerin surumu, semasi ve veri anlik goruntusu.
    featureVersion: str
    featureSchemaHash: Hash
    featureSnapshotId: UUID

    asOf: AwareDatetime
    horizonSeconds: Horizon

    # Bunlar model skorlaridir; fiyat, miktar veya kar hesabi degildir.
    predictedLogReturn: float | None
    volatilityEstimate: Annotated[float, Field(ge=0)] | None

    calibrationVersion: str | None
    observedFeatureSummary: dict

    # Tahmin isleminin gecikmesi, milisaniye.
    latencyMs: Annotated[int, Field(ge=0)]

    @field_validator("asOf")
    @classmethod
    def utc_only(cls, value: AwareDatetime) -> AwareDatetime:
        if value.utcoffset() != timedelta(0):
            raise ValueError("UTC_REQUIRED")
        return value

    @model_validator(mode="after")
    def quality_matches_scores(self) -> "PredictionResult":
        # after: Alanlar dogrulandiktan sonra alanlar arasi kurali kontrol eder.
        scores = (self.predictedLogReturn, self.volatilityEstimate)

        if self.quality == "READY":
            if any(value is None for value in scores):
                raise ValueError("READY_REQUIRES_SCORES")
        elif any(value is not None for value in scores):
            raise ValueError("NON_READY_REQUIRES_NULL")

        return self

class ActiveModelBundle(StrictModel):
    bundleId: UUID

    numericModelVersion: str
    explanationModelVersion: str | None

    featureVersion: str
    featureSchemaHash: Hash
    preprocessingHash: Hash
    policyVersion: str

    # Yalniz gercekten desteklenen tahmin sureleri.
    supportedHorizons: list[Horizon]

    # Model dosyalarinin butunluk kontrolu icin.
    artifactHashes: dict[str, Hash]

    trainingDatasetId: UUID
    knowledgeTrainingDatasetId: UUID | None

    # Yayin durumu registry tarafinda kontrol edilecek.
    releaseStatus: str
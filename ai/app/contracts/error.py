from typing import Annotated, Any

from pydantic import Field

from app.contracts.model import StrictModel


class ErrorResponse(StrictModel):
    # Istemcinin programatik olarak isleyecegi hata kodu.
    code: Annotated[str, Field(min_length=1)]

    # Disariya gosterilebilecek guvenli aciklama.
    message: Annotated[str, Field(min_length=1)]

    # Hatayi ilgili istek ve log kaydiyla iliskilendirir.
    traceId: Annotated[str, Field(min_length=1)]

    # Istegin tekrar denenebilir olup olmadigini belirtir.
    retryable: bool

    # Yalniz guvenli, JSON uyumlu ek bilgiler tasir.
    details: dict[str, Any]
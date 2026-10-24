from datetime import datetime, timezone
from secrets import compare_digest
from uuid import UUID

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.contracts.error import ErrorResponse
from app.contracts.model import (
    ActiveModelBundle,
    PredictionRequest,
    PredictionResult,
)
from app.infrastructure.ports import PredictionService, Registry


def create_app(
    service: PredictionService,
    registry: Registry,
    secret: str,
) -> FastAPI:
    # Secret kaynak koda yazilmaz; uygulama kurulurken disaridan verilir.
    if not secret or len(secret) < 32:
        raise ValueError("SERVICE_SECRET_REQUIRED")

    # App factory: testte ve gercek calismada farkli adapter baglayabiliriz.
    app = FastAPI(
        title="CryptoLab model-api",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )

    def internal_auth(request: Request) -> None:
        received_secret = request.headers.get("X-Service-Secret", "")

        # Sabit zamanli karsilastirma ile servis kimligini kontrol eder.
        if not compare_digest(
            received_secret.encode("utf-8"),
            secret.encode("utf-8"),
        ):
            raise HTTPException(401, "UNAUTHORIZED")

        # Header kimligi HTTP cagrisini, body.requestId analiz istegini izler.
        try:
            trace_id = UUID(request.headers.get("X-Request-Id", ""))
        except ValueError:
            raise HTTPException(400, "INVALID_REQUEST_ID")

        request.state.trace_id = str(trace_id)

        # Suresi gecmis istekleri calistirmadan reddeder.
        try:
            deadline = datetime.fromisoformat(
                request.headers.get("X-Deadline", "").replace("Z", "+00:00")
            )
        except ValueError:
            raise HTTPException(400, "INVALID_DEADLINE")

        if deadline.tzinfo is None:
            raise HTTPException(400, "INVALID_DEADLINE")

        if deadline <= datetime.now(timezone.utc):
            raise HTTPException(400, "DEADLINE_EXCEEDED")

    def error_response(
        request: Request,
        status: int,
        code: str,
        message: str,
    ) -> JSONResponse:
        body = ErrorResponse(
            code=code,
            message=message,
            traceId=getattr(request.state, "trace_id", "unknown"),
            retryable=status == 503,
            details={},
        )
        return JSONResponse(
            status_code=status,
            content=body.model_dump(mode="json"),
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error(
        request: Request,
        exc: RequestValidationError,
    ) -> JSONResponse:
        # Hatali body veya secret degerlerini cevaba koymayiz.
        return error_response(
            request, 400, "INVALID_REQUEST",
            "Istek alanlari gecersiz.",
        )

    @app.exception_handler(HTTPException)
    async def http_error(
        request: Request,
        exc: HTTPException,
    ) -> JSONResponse:
        return error_response(
            request, exc.status_code, str(exc.detail),
            "Istek kabul edilmedi.",
        )

    @app.exception_handler(ValueError)
    async def model_error(
        request: Request,
        exc: ValueError,
    ) -> JSONResponse:
        code = str(exc)

        # Yalniz bildigimiz guvenli hata kodlarini disariya aktaririz.
        if code in {"FEATURE_SCHEMA_MISMATCH", "UNSUPPORTED_HORIZON"}:
            return error_response(
                request, 422, code,
                "Istek model sozlesmesiyle uyusmuyor.",
            )

        if code == "MODEL_NOT_READY":
            return error_response(
                request, 503, code,
                "Kullanilabilir model henuz hazir degil.",
            )

        return error_response(
            request, 503, "MODEL_UNAVAILABLE",
            "Tahmin islemi tamamlanamadi.",
        )

    @app.exception_handler(Exception)
    async def unexpected_error(
        request: Request,
        exc: Exception,
    ) -> JSONResponse:
        # Ham exception mesaji veya stack trace istemciye verilmez.
        return error_response(
            request, 503, "MODEL_UNAVAILABLE",
            "Tahmin islemi tamamlanamadi.",
        )

    @app.get(
        "/internal/model/active-bundle",
        response_model=ActiveModelBundle,
        dependencies=[Depends(internal_auth)],
    )
    def active_bundle(horizonSeconds: int) -> ActiveModelBundle:
        if horizonSeconds not in (60, 900, 3600, 86400, 604800):
            raise HTTPException(422, "UNSUPPORTED_HORIZON")

        return registry.active(horizonSeconds)

    @app.post(
        "/internal/model/predictions",
        response_model=PredictionResult,
        dependencies=[Depends(internal_auth)],
    )
    def predictions(body: PredictionRequest) -> PredictionResult:
        # HTTP katmani model egitmez; baglanan servisi cagirir.
        return service.predict(body)

    return app
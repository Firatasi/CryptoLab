"""A01/A05: App factory, bağımlılıkları dışarıdan alır. Training paketi import edilmez."""
from datetime import datetime, timezone
from secrets import compare_digest
from uuid import UUID

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.contracts.error import ErrorResponse
from app.contracts.model import (
    ActiveModelBundle,
    ExplanationRequest,
    ExplanationResult,
    PredictionRequest,
    PredictionResult,
)
from app.explanations.template import explain_template
from app.infrastructure.ports import PredictionService, Registry


def create_app(
    service: PredictionService,
    registry: Registry,
    secret: str,
) -> FastAPI:
    if not secret or len(secret) < 32:
        raise ValueError("SERVICE_SECRET_REQUIRED")

    app = FastAPI(
        title="CryptoLab model-api",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )

    def internal_auth(request: Request) -> None:
        received_secret = request.headers.get("X-Service-Secret", "")
        if not compare_digest(received_secret.encode("utf-8"), secret.encode("utf-8")):
            raise HTTPException(401, "UNAUTHORIZED")

        try:
            trace_id = UUID(request.headers.get("X-Request-Id", ""))
        except (ValueError, TypeError):
            raise HTTPException(400, "INVALID_REQUEST_ID")

        request.state.trace_id = str(trace_id)

        try:
            deadline_str = request.headers.get("X-Deadline", "")
            deadline = datetime.fromisoformat(deadline_str.replace("Z", "+00:00"))
        except (ValueError, TypeError):
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
        trace_id = getattr(request.state, "trace_id", None) or request.headers.get(
            "X-Request-Id", "unknown"
        )
        body = ErrorResponse(
            code=code,
            message=message or code,
            traceId=str(trace_id),
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
        return error_response(
            request, 400, "INVALID_REQUEST", "Istek alanlari gecersiz."
        )

    @app.exception_handler(HTTPException)
    async def http_error(
        request: Request,
        exc: HTTPException,
    ) -> JSONResponse:
        return error_response(
            request, exc.status_code, str(exc.detail), "Istek kabul edilmedi."
        )

    @app.exception_handler(ValueError)
    async def domain_error(
        request: Request,
        exc: ValueError,
    ) -> JSONResponse:
        code = str(exc)
        safe_domain_errors = {
            "FEATURE_SCHEMA_MISMATCH",
            "UNSUPPORTED_HORIZON",
            "INVALID_POLICY_OUTCOME",
            "POLICY_CONTRADICTION",
            "INVALID_REASON_CODES",
        }
        if code in safe_domain_errors:
            return error_response(
                request, 422, code, "Istek model sozlesmesiyle uyusmuyor."
            )

        if code == "MODEL_NOT_READY":
            return error_response(
                request, 503, code, "Kullanilabilir model henuz hazir degil."
            )

        return error_response(
            request, 503, "MODEL_UNAVAILABLE", "Tahmin islemi tamamlanamadi."
        )

    @app.exception_handler(Exception)
    async def unexpected_error(
        request: Request,
        exc: Exception,
    ) -> JSONResponse:
        return error_response(
            request, 503, "MODEL_UNAVAILABLE", "Tahmin islemi tamamlanamadi."
        )

    @app.post(
        "/internal/model/predictions",
        response_model=PredictionResult,
        dependencies=[Depends(internal_auth)],
    )
    def predictions(body: PredictionRequest) -> PredictionResult:
        return service.predict(body)

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
        "/internal/model/explanations",
        response_model=ExplanationResult,
        dependencies=[Depends(internal_auth)],
    )
    def explanations(body: ExplanationRequest) -> ExplanationResult:
        # Bundle varlığı kontrol edilir
        registry.get(body.modelBundleId)
        return explain_template(body)

    return app

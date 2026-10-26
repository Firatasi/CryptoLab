"""A06/A07/A08/A09: Training API uç noktaları.
Backend HttpTrainingProvider, TrainingController ve TrainingJsonController ile birebir uyumlu rotalar.
"""
import hashlib
from typing import Any
from uuid import UUID, uuid4

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, Request, Response, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.datasets.freeze_service import FreezeService
from app.datasets.review import freeze_examples
from app.infrastructure.artifacts import ArtifactStore
from app.infrastructure.example_repository import ExampleRepository
from app.infrastructure.jobs import JobRepository
from app.infrastructure.source_repository import SourceRepository
from app.registry.promotion import promote
from app.registry.rollback import rollback
from app.sources.multipart_parser import parse_multipart
from app.sources.pdf_extract import extract_pages


class Actor:
    def __init__(self, actor_id: UUID, roles: set[str], authorities: set[str]):
        self.id = actor_id
        self.roles = roles
        self.authorities = authorities

    @property
    def is_trainer(self) -> bool:
        return bool(self.roles.intersection({"TRAINER", "ADMIN"}))

    @property
    def is_admin(self) -> bool:
        return "ADMIN" in self.roles

    @property
    def can_publish(self) -> bool:
        return self.is_trainer and "MODEL_PUBLISH" in self.authorities


def get_actor(
    x_service_secret: str = Header(default="", alias="X-Service-Secret"),
    x_actor_id: str | None = Header(default=None, alias="X-Actor-Id"),
    x_actor_roles: str = Header(default="", alias="X-Actor-Roles"),
    x_actor_authorities: str = Header(default="", alias="X-Actor-Authorities"),
    expected_secret: str = "cryptolab-secret-training",
) -> Actor:
    if x_service_secret != expected_secret:
        raise HTTPException(status_code=401, detail="INVALID_SERVICE_SECRET")

    if not x_actor_id:
        raise HTTPException(status_code=400, detail="MISSING_ACTOR_ID")

    try:
        aid = UUID(x_actor_id)
    except Exception:
        raise HTTPException(status_code=400, detail="INVALID_ACTOR_ID")

    roles = {r.strip() for r in x_actor_roles.split(",") if r.strip()}
    authorities = {a.strip() for a in x_actor_authorities.split(",") if a.strip()}
    actor = Actor(aid, roles, authorities)

    if not actor.is_trainer:
        raise HTTPException(status_code=403, detail="FORBIDDEN_TRAINER_ROLE_REQUIRED")

    return actor


class SourceImportRequest(BaseModel):
    url: str


class ExampleJobRequest(BaseModel):
    sourceVersionIds: list[str]
    taskTypes: list[str]


class ReviewPatchRequest(BaseModel):
    expectedVersion: int
    reviewStatus: str
    correctedTarget: dict[str, Any] | None = None


class DatasetCreateRequest(BaseModel):
    kind: str
    name: str = "dataset-v1"
    examples: list[dict[str, Any]] = []
    splitByFamily: dict[str, str] = {}


class RunCreateRequest(BaseModel):
    task: str  # NUMERIC or EXPLANATION_SFT
    config: dict[str, Any]


class PromoteRequest(BaseModel):
    expectedAliasVersion: int
    evaluationId: UUID


class RollbackRequest(BaseModel):
    expectedAliasVersion: int
    reason: str


def create_training_app(
    service_secret: str = "cryptolab-secret-training",
    source_repo: SourceRepository | None = None,
    example_repo: ExampleRepository | None = None,
    job_repo: JobRepository | None = None,
    artifact_store: ArtifactStore | None = None,
) -> FastAPI:
    app = FastAPI(title="CryptoLab Training API", version="1.0.0")

    sources = source_repo or SourceRepository()
    examples = example_repo or ExampleRepository()
    jobs = job_repo or JobRepository()
    artifacts = artifact_store or ArtifactStore()
    freeze_svc = FreezeService(artifacts)

    # Bellek-içi bundle/alias durumu (test ve development ortamı)
    model_aliases = {"scope": "production", "active": None, "version": 1}
    model_bundles = {}

    def actor_dep(
        x_service_secret: str = Header(default="", alias="X-Service-Secret"),
        x_actor_id: str | None = Header(default=None, alias="X-Actor-Id"),
        x_actor_roles: str = Header(default="", alias="X-Actor-Roles"),
        x_actor_authorities: str = Header(default="", alias="X-Actor-Authorities"),
    ) -> Actor:
        return get_actor(x_service_secret, x_actor_id, x_actor_roles, x_actor_authorities, service_secret)

    # 1. SOURCES UPLOAD
    @app.post("/internal/training/sources", status_code=202)
    async def upload_source(
        request: Request,
        idempotency_key: str = Header(alias="Idempotency-Key"),
        actor: Actor = Depends(actor_dep),
    ):
        body_bytes = await request.body()
        if len(body_bytes) > 21 * 1024 * 1024:
            raise HTTPException(status_code=400, detail="UPLOAD_SIZE_EXCEEDED")

        content_type = request.headers.get("content-type", "")
        if "multipart/form-data" in content_type:
            try:
                fields, file_bytes = parse_multipart(content_type, body_bytes)
            except Exception as e:
                raise HTTPException(status_code=400, detail="MULTIPART_PARSE_ERROR") from e

            title = fields.get("title", "Untitled Source")
            rights = fields.get("rightsDeclaration", "Verified internal rights")
            raw_pdf = file_bytes or b""
        else:
            title = "Uploaded Source"
            rights = "Verified internal rights"
            raw_pdf = body_bytes

        if not raw_pdf or not raw_pdf.startswith(b"%PDF-"):
            raise HTTPException(status_code=400, detail="TRAINING_SOURCE_INVALID")

        # PDF doğrula ve çıkar
        try:
            pages = extract_pages(raw_pdf)
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))

        source_id = uuid4()
        version_id = uuid4()
        content_hash = f"sha256:{hashlib.sha256(raw_pdf).hexdigest()}"
        artifact_ref = artifacts.put(raw_pdf)

        sources.save_source(source_id, actor.id, title, rights, state="REVIEW_REQUIRED", version=1)
        sources.save_version(version_id, source_id, content_hash, "pypdf-6.21.2", artifact_ref)
        sources.save_pages(version_id, pages)

        return {
            "sourceId": str(source_id),
            "versionId": str(version_id),
            "contentHash": content_hash,
            "status": "ACCEPTED",
            "pageCount": len(pages),
        }

    # 2. SOURCE IMPORTS
    @app.post("/internal/training/source-imports", status_code=202)
    def import_source(
        body: SourceImportRequest,
        idempotency_key: str = Header(alias="Idempotency-Key"),
        actor: Actor = Depends(actor_dep),
    ):
        job_id = uuid4()
        return {"jobId": str(job_id), "status": "QUEUED", "url": body.url}

    # 3. GET SOURCES
    @app.get("/internal/training/sources")
    def list_sources(
        cursor: str | None = None,
        actor: Actor = Depends(actor_dep),
    ):
        records = sources.list_sources(actor.id, is_admin=actor.is_admin)
        return {"sources": records, "cursor": None}

    # 4. GET SOURCE DETAIL
    @app.get("/internal/training/sources/{source_id}")
    def get_source_detail(
        source_id: UUID,
        actor: Actor = Depends(actor_dep),
    ):
        s = sources.get_source(source_id)
        if not s:
            raise HTTPException(status_code=404, detail="SOURCE_NOT_FOUND")
        if not actor.is_admin and s["owner_id"] != actor.id:
            raise HTTPException(status_code=403, detail="FORBIDDEN")
        return s

    # 5. GET SOURCE PAGE
    @app.get("/internal/training/sources/{source_id}/pages/{page}")
    def get_page(
        source_id: UUID,
        page: int,
        actor: Actor = Depends(actor_dep),
    ):
        try:
            p = sources.get_page(source_id, page, actor.id, is_admin=actor.is_admin)
            if not p:
                raise HTTPException(status_code=404, detail="PAGE_NOT_FOUND")
            return p
        except PermissionError:
            raise HTTPException(status_code=403, detail="FORBIDDEN")

    # 6. DELETE SOURCE
    @app.delete("/internal/training/sources/{source_id}", status_code=202)
    def delete_source(
        source_id: UUID,
        expectedVersion: int = Query(default=1),
        idempotency_key: str = Header(alias="Idempotency-Key"),
        actor: Actor = Depends(actor_dep),
    ):
        try:
            s = sources.get_source(source_id)
            if not s:
                raise HTTPException(status_code=404, detail="SOURCE_NOT_FOUND")
            if not actor.is_admin and s["owner_id"] != actor.id:
                raise HTTPException(status_code=403, detail="FORBIDDEN")
            res = sources.mark_source_deleted(source_id, expectedVersion)
            return {"sourceId": str(source_id), "status": "DELETED", "version": res["version"]}
        except ValueError as e:
            raise HTTPException(status_code=409, detail=str(e))

    # 7. EXAMPLE JOBS
    @app.post("/internal/training/example-jobs", status_code=202)
    def create_example_job(
        body: ExampleJobRequest,
        idempotency_key: str = Header(alias="Idempotency-Key"),
        actor: Actor = Depends(actor_dep),
    ):
        job_id = uuid4()
        return {"jobId": str(job_id), "status": "QUEUED"}

    # 8. GET EXAMPLES
    @app.get("/internal/training/examples")
    def list_examples(
        cursor: str | None = None,
        datasetId: str | None = None,
        actor: Actor = Depends(actor_dep),
    ):
        exs = examples.list_examples(actor.id, is_admin=actor.is_admin)
        return {"examples": exs, "cursor": None}

    # 9. PATCH EXAMPLE REVIEW
    @app.patch("/internal/training/examples/{example_id}")
    def patch_example(
        example_id: UUID,
        body: ReviewPatchRequest,
        idempotency_key: str = Header(alias="Idempotency-Key"),
        actor: Actor = Depends(actor_dep),
    ):
        try:
            updated = examples.update_review(
                example_id,
                actor.id,
                actor.is_admin,
                body.expectedVersion,
                body.reviewStatus,
                body.correctedTarget,
            )
            return updated
        except PermissionError:
            raise HTTPException(status_code=403, detail="FORBIDDEN")
        except ValueError as e:
            if "VERSION_CONFLICT" in str(e):
                raise HTTPException(status_code=409, detail="VERSION_CONFLICT")
            raise HTTPException(status_code=400, detail=str(e))

    # 10. POST DATASETS (FREEZE)
    @app.post("/internal/training/datasets", status_code=201)
    def create_dataset(
        body: DatasetCreateRequest,
        idempotency_key: str = Header(alias="Idempotency-Key"),
        actor: Actor = Depends(actor_dep),
    ):
        try:
            manifest = freeze_svc.freeze_dataset(
                uuid4(),
                actor.id,
                body.examples,
                body.splitByFamily,
                body.name,
            )
            return manifest
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))

    # 11. POST RUNS
    @app.post("/internal/training/runs", status_code=202)
    def create_run(
        body: RunCreateRequest,
        idempotency_key: str = Header(alias="Idempotency-Key"),
        actor: Actor = Depends(actor_dep),
    ):
        run_id = uuid4()
        return {
            "runId": str(run_id),
            "task": body.task,
            "status": "QUEUED",
            "stage": "INITIALIZING",
            "progress": 0,
        }

    # 12. GET RUN DETAIL
    @app.get("/internal/training/runs/{run_id}")
    def get_run(
        run_id: UUID,
        actor: Actor = Depends(actor_dep),
    ):
        return {
            "runId": str(run_id),
            "status": "RUNNING",
            "stage": "TRAINING",
            "progress": 50,
        }

    # 13. CANCEL RUN
    @app.post("/internal/training/runs/{run_id}/cancel", status_code=202)
    def cancel_run(
        run_id: UUID,
        idempotency_key: str = Header(alias="Idempotency-Key"),
        actor: Actor = Depends(actor_dep),
    ):
        return {
            "runId": str(run_id),
            "status": "CANCELLED",
            "cancelRequested": True,
        }

    # 14. GET MODELS
    @app.get("/internal/training/models")
    def list_models(
        cursor: str | None = None,
        actor: Actor = Depends(actor_dep),
    ):
        return {"models": list(model_bundles.values()), "cursor": None}

    # 15. GET MODEL DETAIL
    @app.get("/internal/training/models/{model_id}")
    def get_model(
        model_id: UUID,
        actor: Actor = Depends(actor_dep),
    ):
        b = model_bundles.get(model_id)
        if not b:
            raise HTTPException(status_code=404, detail="MODEL_NOT_FOUND")
        return b

    # 16. PROMOTE MODEL
    @app.post("/internal/training/models/{model_id}/promote")
    def promote_model(
        model_id: UUID,
        body: PromoteRequest,
        idempotency_key: str = Header(alias="Idempotency-Key"),
        actor: Actor = Depends(actor_dep),
    ):
        if not actor.can_publish:
            raise HTTPException(status_code=403, detail="FORBIDDEN_MODEL_PUBLISH_AUTHORITY_REQUIRED")

        current_ver = model_aliases["version"]
        if body.expectedAliasVersion != current_ver:
            raise HTTPException(status_code=409, detail="VERSION_CONFLICT")

        model_aliases["version"] = current_ver + 1
        model_aliases["active"] = str(model_id)
        return {
            "modelId": str(model_id),
            "aliasVersion": model_aliases["version"],
            "status": "ACTIVE",
            "event": "PROMOTED",
        }

    # 17. ROLLBACK MODEL
    @app.post("/internal/training/models/{model_id}/rollback")
    def rollback_model(
        model_id: UUID,
        body: RollbackRequest,
        idempotency_key: str = Header(alias="Idempotency-Key"),
        actor: Actor = Depends(actor_dep),
    ):
        if not actor.can_publish:
            raise HTTPException(status_code=403, detail="FORBIDDEN_MODEL_PUBLISH_AUTHORITY_REQUIRED")

        current_ver = model_aliases["version"]
        if body.expectedAliasVersion != current_ver:
            raise HTTPException(status_code=409, detail="VERSION_CONFLICT")

        model_aliases["version"] = current_ver + 1
        model_aliases["active"] = str(model_id)
        return {
            "modelId": str(model_id),
            "aliasVersion": model_aliases["version"],
            "status": "ACTIVE",
            "reason": body.reason,
            "event": "ROLLED_BACK",
        }

    return app

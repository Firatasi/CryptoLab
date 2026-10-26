"""A04/A06: psycopg bağlantısı dışarıdan gelir.

İşlemler (transactions) kısa tutulur; uzun süren ML fit işlemleri sırasında
veritabanı transaction'ı açık tutulmaz.
Worker claim: FOR UPDATE SKIP LOCKED
Fencing: worker_token ve lease_until
Idempotency: actor_id, route, scope, key
"""
from hashlib import sha256
import json
from typing import Any
from uuid import UUID, uuid4


class JobRepository:
    def __init__(self, connection: Any = None) -> None:
        self.db = connection

    def enqueue(
        self,
        actor: UUID,
        route: str,
        scope: str,
        key: str,
        task: str,
        request: dict,
    ) -> UUID:
        if not key or len(key) > 128:
            raise ValueError("INVALID_KEY")

        raw = json.dumps(
            request,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        h = sha256(raw.encode("utf-8")).hexdigest()

        with self.db.transaction():
            # Aynı idempotency scope için eşzamanlı istekleri sıraya sokar
            lock = json.dumps([str(actor), route, scope, key])
            self.db.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                (lock,),
            )
            cursor = self.db.execute(
                "SELECT request_hash, result_id FROM idempotency_records "
                "WHERE actor_id=%s AND route=%s AND scope=%s AND key=%s",
                (actor, route, scope, key),
            )
            row = cursor.fetchone()
            if row:
                if row[0] != h:
                    raise ValueError("IDEMPOTENCY_CONFLICT")
                return UUID(str(row[1]))

            job = uuid4()
            self.db.execute(
                "INSERT INTO training_runs(id, owner_id, task, request, status, stage) "
                "VALUES(%s, %s, %s, %s::jsonb, 'QUEUED', 'DATA_VALIDATION')",
                (job, actor, task, raw),
            )
            self.db.execute(
                "INSERT INTO idempotency_records(actor_id, route, scope, key, request_hash, result_id) "
                "VALUES(%s, %s, %s, %s, %s, %s)",
                (actor, route, scope, key, h, job),
            )
            self.event(job, task, "QUEUED", "DATA_VALIDATION", 1)

        return job  # 202 Accepted dönebilir: commit tamamlandı

    def event(
        self,
        job: UUID,
        task: str,
        status: str,
        stage: str,
        version: int,
    ) -> None:
        payload = json.dumps({
            "jobId": str(job),
            "task": task,
            "status": status,
            "stage": stage,
            "progress": None,
        })
        self.db.execute(
            "INSERT INTO outbox(event_id, topic, event_key, aggregate_version, payload) "
            "VALUES(%s, 'training.jobs.v1', %s, %s, %s::jsonb)",
            (uuid4(), str(job), version, payload),
        )

    def claim(self, lease_seconds: int = 60) -> tuple[Any, ...] | None:
        token = uuid4()
        with self.db.transaction():
            cursor = self.db.execute(
                """WITH candidate AS (
                  SELECT id FROM training_runs WHERE status='QUEUED' OR
                    (status='RUNNING' AND lease_until < now())
                  ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
                  UPDATE training_runs r SET status='RUNNING', worker_token=%s,
                    lease_until=now() + (%s * interval '1 second'), version=version+1
                  FROM candidate c WHERE r.id=c.id
                  RETURNING r.id, r.task, r.request, r.version""",
                (token, lease_seconds),
            )
            row = cursor.fetchone()
            if row:
                self.event(row[0], row[1], "RUNNING", "DATA_VALIDATION", row[3])
        return None if not row else (*row, token)

    def heartbeat(self, job: UUID, token: UUID, seconds: int = 60) -> bool:
        with self.db.transaction():
            cursor = self.db.execute(
                """UPDATE training_runs SET lease_until=now() + (%s * interval '1 second')
                  WHERE id=%s AND worker_token=%s AND status='RUNNING' AND lease_until > now()
                  RETURNING cancel_requested""",
                (seconds, job, token),
            )
            row = cursor.fetchone()
            if row is None:
                raise RuntimeError("LEASE_LOST")
            return bool(row[0])

    def finish(
        self,
        job: UUID,
        token: UUID,
        status: str,
        result_ref: str | None = None,
    ) -> None:
        if status not in ("SUCCEEDED", "FAILED", "CANCELLED"):
            raise ValueError("INVALID_TERMINAL_STATUS")

        with self.db.transaction():
            cursor = self.db.execute(
                """UPDATE training_runs SET
                   status=CASE WHEN cancel_requested THEN 'CANCELLED' ELSE %s END,
                   result_ref=CASE WHEN cancel_requested THEN NULL ELSE %s END,
                   lease_until=NULL, version=version+1, updated_at=now()
                   WHERE id=%s AND worker_token=%s AND status='RUNNING' AND lease_until > now()
                   RETURNING task, status, stage, version""",
                (status, result_ref, job, token),
            )
            row = cursor.fetchone()
            if row is None:
                raise RuntimeError("LEASE_LOST")
            self.event(job, *row)


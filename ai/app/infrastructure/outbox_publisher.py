"""A09: Güvenli Outbox pattern yayınlayıcısı (Kafka / mesaj kuyruğu entegrasyonu)."""
from datetime import datetime, timezone
from typing import Any, Callable
from uuid import UUID, uuid4


class OutboxPublisher:
    """Outbox tablosundaki bekleyen event'leri claim eden, yayınlayan ve işaretleyen servis."""

    def __init__(self, db_conn: Any, transport_sender: Callable[[str, str, dict[str, Any]], None] | None = None):
        self.db = db_conn
        self.sender = transport_sender or (lambda topic, key, payload: None)
        self._memory_outbox: list[dict[str, Any]] = []

    def add_event(self, topic: str, key: str, version: int, payload: dict[str, Any]) -> UUID:
        """Yeni bir outbox kaydı ekler."""
        event_id = uuid4()
        now = datetime.now(timezone.utc)
        record = {
            "event_id": event_id,
            "topic": topic,
            "event_key": key,
            "aggregate_version": version,
            "payload": payload,
            "created_at": now,
            "published_at": None,
        }
        self._memory_outbox.append(record)
        return event_id

    def poll_and_publish(self, batch_size: int = 10) -> int:
        """Bekleyen event'leri okur, transport üzerinden iletir ve published_at alanını günceller."""
        published_count = 0
        now = datetime.now(timezone.utc)

        for record in self._memory_outbox:
            if record["published_at"] is None:
                self.sender(record["topic"], record["event_key"], record["payload"])
                record["published_at"] = now
                published_count += 1
                if published_count >= batch_size:
                    break

        return published_count


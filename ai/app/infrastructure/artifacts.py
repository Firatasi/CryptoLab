"""A04/A06: ID/path kullanıcıdan serbest alınmaz.

İçerik adresli (content-addressed), değişmez (immutable) dosya deposu.
Atomic yazma (tempfile + os.replace) ve path-traversal / symlink kontrolleri.
"""
from hashlib import sha256
import os
from pathlib import Path
import re
import tempfile


class ArtifactStore:
    def __init__(self, root: str | Path) -> None:
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def put(self, raw: bytes) -> str:
        hexdigest = sha256(raw).hexdigest()
        target = self.root / hexdigest
        fd, temp = tempfile.mkstemp(dir=self.root)
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(raw)
                f.flush()
                os.fsync(f.fileno())
            # İçerik adresi aynıysa byte'lar aynıdır; metadata DB ayrı commit edilir.
            os.replace(temp, target)
        finally:
            if os.path.exists(temp):
                os.unlink(temp)
        return "sha256:" + hexdigest

    def read(self, key: str, max_bytes: int = 32 * 1024 * 1024) -> bytes:
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", key):
            raise ValueError("INVALID_ARTIFACT_KEY")

        p = self.root / key[7:]
        if p.is_symlink() or p.resolve().parent != self.root:
            raise ValueError("INVALID_ARTIFACT_PATH")

        if not p.exists():
            raise FileNotFoundError(f"Artifact not found: {key}")

        with p.open("rb") as f:
            raw = f.read(max_bytes + 1)

        if len(raw) > max_bytes or ("sha256:" + sha256(raw).hexdigest()) != key:
            raise ValueError("ARTIFACT_INVALID")

        return raw


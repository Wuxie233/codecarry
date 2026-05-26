from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    token: str
    storage_dir: Path
    max_bytes: int
    host: str
    port: int


def _required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


def _integer(name: str, default: int) -> int:
    raw_value = os.getenv(name, str(default)).strip()
    try:
        return int(raw_value)
    except ValueError as exc:
        raise RuntimeError(f"Invalid integer for {name}") from exc


def load_settings() -> Settings:
    return Settings(
        token=_required("DIAG_UPLOAD_TOKEN"),
        storage_dir=Path(os.getenv("DIAG_UPLOAD_STORAGE_DIR", "./diagnostics_uploads")).expanduser(),
        max_bytes=_integer("DIAG_UPLOAD_MAX_BYTES", 10_485_760),
        host=os.getenv("DIAG_UPLOAD_HOST", "127.0.0.1").strip() or "127.0.0.1",
        port=_integer("DIAG_UPLOAD_PORT", 8765),
    )

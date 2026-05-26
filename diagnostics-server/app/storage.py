from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import hashlib
import json
import re
from pathlib import Path
from typing import TypedDict, cast
from uuid import uuid4

from fastapi import UploadFile


ALLOWED_EXTENSIONS = {".json", ".log", ".txt", ".zip"}


class UploadRecordData(TypedDict):
    id: str
    filename: str
    size: int
    stored_at: str
    sha256: str


@dataclass(frozen=True)
class UploadRecord:
    id: str
    filename: str
    size: int
    stored_at: str
    sha256: str

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


class DiagnosticsStorage:
    storage_dir: Path
    max_bytes: int

    def __init__(self, storage_dir: Path, max_bytes: int) -> None:
        self.storage_dir = storage_dir
        self.max_bytes = max_bytes
        self.storage_dir.mkdir(parents=True, exist_ok=True)

    @staticmethod
    def _sanitize_filename(filename: str) -> str:
        safe_name = Path(filename).name.strip()
        safe_name = re.sub(r"[^A-Za-z0-9._-]+", "_", safe_name)
        return safe_name.strip("._") or "upload"

    @staticmethod
    def _validate_extension(filename: str) -> str:
        extension = Path(filename).suffix.lower()
        if extension not in ALLOWED_EXTENSIONS:
            raise ValueError("unsupported extension")
        return extension

    async def save_upload(self, upload: UploadFile) -> UploadRecord:
        if not upload.filename:
            raise ValueError("missing filename")

        filename = self._sanitize_filename(upload.filename)
        extension = self._validate_extension(filename)

        upload_id = str(uuid4())
        stored_dir = self.storage_dir / upload_id
        stored_dir.mkdir(parents=True, exist_ok=False)
        stored_path = stored_dir / f"upload{extension}"
        metadata_path = stored_dir / "metadata.json"

        sha256 = hashlib.sha256()
        size = 0
        started_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

        try:
            with stored_path.open("xb") as output_file:
                while True:
                    chunk = await upload.read(1024 * 1024)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > self.max_bytes:
                        raise ValueError("too large")
                    sha256.update(chunk)
                    _ = output_file.write(chunk)
        except ValueError:
            stored_path.unlink(missing_ok=True)
            metadata_path.unlink(missing_ok=True)
            stored_dir.rmdir()
            raise
        except OSError as exc:
            stored_path.unlink(missing_ok=True)
            metadata_path.unlink(missing_ok=True)
            stored_dir.rmdir()
            raise RuntimeError("disk write failure") from exc
        finally:
            await upload.close()

        if size == 0:
            stored_path.unlink(missing_ok=True)
            stored_dir.rmdir()
            raise ValueError("empty file")

        record = UploadRecord(
            id=upload_id,
            filename=filename,
            size=size,
            stored_at=started_at,
            sha256=sha256.hexdigest(),
        )
        _ = metadata_path.write_text(json.dumps(record.to_dict(), indent=2, sort_keys=True), encoding="utf-8")
        return record

    def list_reports(self) -> list[UploadRecord]:
        records: list[UploadRecord] = []
        for metadata_path in sorted(self.storage_dir.glob("*/metadata.json"), reverse=True):
            data = cast(UploadRecordData, json.loads(metadata_path.read_text(encoding="utf-8")))
            records.append(UploadRecord(**data))
        return records

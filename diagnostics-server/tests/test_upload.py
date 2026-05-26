from __future__ import annotations

import json
from pathlib import Path
from typing import cast

from fastapi.testclient import TestClient


JsonObject = dict[str, object]


def test_upload_route_accepts_bearer_and_stores_metadata_only_report(diagnostics_client: TestClient, storage_dir: Path) -> None:
    response = diagnostics_client.post(
        "/upload",
        headers={"Authorization": "Bearer test-token"},
        files={"file": ("diagnostic.log", b"hello diagnostics\n", "text/plain")},
    )

    assert response.status_code == 201
    body = cast(JsonObject, response.json())
    assert set(body) == {"id", "filename", "size", "stored_at", "sha256"}
    assert body["filename"] == "diagnostic.log"
    assert body["size"] == len(b"hello diagnostics\n")
    upload_id = str(body["id"])
    stored_dir = storage_dir / upload_id
    metadata = cast(JsonObject, json.loads((stored_dir / "metadata.json").read_text(encoding="utf-8")))
    assert metadata == body
    assert (stored_dir / "upload.log").read_bytes() == b"hello diagnostics\n"
    assert not (stored_dir / "diagnostic.log").exists()

    reports = diagnostics_client.get("/reports", headers={"Authorization": "Bearer test-token"})
    assert reports.status_code == 200
    reports_body = cast(list[JsonObject], reports.json())
    assert reports_body == [body]


def test_upload_rejects_empty_file(diagnostics_client: TestClient, storage_dir: Path) -> None:
    response = diagnostics_client.post(
        "/upload",
        headers={"Authorization": "Bearer test-token"},
        files={"file": ("empty.log", b"", "text/plain")},
    )

    assert response.status_code == 400
    assert response.json() == {"detail": "Empty file"}
    assert list(storage_dir.glob("*")) == []


def test_upload_rejects_unsupported_extension(diagnostics_client: TestClient, storage_dir: Path) -> None:
    response = diagnostics_client.post(
        "/upload",
        headers={"Authorization": "Bearer test-token"},
        files={"file": ("diagnostic.exe", b"hello", "application/octet-stream")},
    )

    assert response.status_code == 415
    assert response.json() == {"detail": "Unsupported file type"}
    assert list(storage_dir.glob("*")) == []


def test_upload_rejects_oversized_file(diagnostics_client: TestClient, storage_dir: Path) -> None:
    response = diagnostics_client.post(
        "/upload",
        headers={"Authorization": "Bearer test-token"},
        files={"file": ("too-large.txt", b"x" * 10_485_761, "text/plain")},
    )

    assert response.status_code == 413
    assert response.json() == {"detail": "File too large"}
    assert list(storage_dir.glob("*")) == []


def test_upload_sanitizes_path_traversal_filename(diagnostics_client: TestClient, storage_dir: Path) -> None:
    response = diagnostics_client.post(
        "/upload",
        headers={"Authorization": "Bearer test-token"},
        files={"file": ("../../evil.log", b"safe", "text/plain")},
    )

    assert response.status_code == 201
    body = cast(JsonObject, response.json())
    assert body["filename"] == "evil.log"
    assert not (storage_dir.parent / "evil.log").exists()
    assert not (storage_dir.parent.parent / "evil.log").exists()
    stored_dirs = list(storage_dir.iterdir())
    assert len(stored_dirs) == 1
    assert (stored_dirs[0] / "upload.log").read_bytes() == b"safe"


def test_duplicate_original_filenames_create_distinct_upload_ids(diagnostics_client: TestClient, storage_dir: Path) -> None:
    first = diagnostics_client.post(
        "/upload",
        headers={"Authorization": "Bearer test-token"},
        files={"file": ("same.json", b'{"first": true}', "application/json")},
    )
    second = diagnostics_client.post(
        "/upload",
        headers={"Authorization": "Bearer test-token"},
        files={"file": ("same.json", b'{"second": true}', "application/json")},
    )

    assert first.status_code == 201
    assert second.status_code == 201
    first_body = cast(JsonObject, first.json())
    second_body = cast(JsonObject, second.json())
    assert first_body["filename"] == "same.json"
    assert second_body["filename"] == "same.json"
    assert first_body["id"] != second_body["id"]
    assert (storage_dir / str(first_body["id"]) / "upload.json").read_bytes() == b'{"first": true}'
    assert (storage_dir / str(second_body["id"]) / "upload.json").read_bytes() == b'{"second": true}'


def test_upload_rejects_missing_and_bad_token(diagnostics_client: TestClient) -> None:
    missing = diagnostics_client.post(
        "/upload",
        files={"file": ("diagnostic.log", b"hello", "text/plain")},
    )
    bad = diagnostics_client.post(
        "/upload",
        headers={"Authorization": "Bearer wrong-token"},
        files={"file": ("diagnostic.log", b"hello", "text/plain")},
    )

    assert missing.status_code == 401
    assert bad.status_code == 401
    assert missing.headers["www-authenticate"] == "Bearer"
    assert bad.headers["www-authenticate"] == "Bearer"

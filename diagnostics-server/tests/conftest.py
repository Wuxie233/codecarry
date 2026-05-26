from __future__ import annotations

import importlib
import sys
from pathlib import Path
from typing import cast

import pytest
from fastapi.testclient import TestClient
from fastapi import FastAPI


@pytest.fixture()
def storage_dir(tmp_path: Path) -> Path:
    return tmp_path / "uploads"


@pytest.fixture()
def diagnostics_client(monkeypatch: pytest.MonkeyPatch, storage_dir: Path) -> TestClient:
    monkeypatch.setenv("DIAG_UPLOAD_TOKEN", "test-token")
    monkeypatch.setenv("DIAG_UPLOAD_STORAGE_DIR", str(storage_dir))
    monkeypatch.setenv("DIAG_UPLOAD_MAX_BYTES", "10485760")
    monkeypatch.setenv("DIAG_UPLOAD_HOST", "127.0.0.1")
    monkeypatch.setenv("DIAG_UPLOAD_PORT", "8765")
    for module_name in ["app.main", "app.config", "app.storage"]:
        _ = sys.modules.pop(module_name, None)
    app_module = importlib.import_module("app.main")
    return TestClient(cast(FastAPI, app_module.app))

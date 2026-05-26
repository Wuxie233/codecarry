from __future__ import annotations

from pathlib import Path

import pytest

from importlib import import_module


def test_missing_token_raises(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("DIAG_UPLOAD_TOKEN", raising=False)
    monkeypatch.setenv("DIAG_UPLOAD_STORAGE_DIR", "./diagnostics_uploads")
    monkeypatch.setenv("DIAG_UPLOAD_MAX_BYTES", "10485760")
    monkeypatch.setenv("DIAG_UPLOAD_HOST", "127.0.0.1")
    monkeypatch.setenv("DIAG_UPLOAD_PORT", "8765")

    load_settings = import_module("app.config").load_settings
    with pytest.raises(RuntimeError, match="DIAG_UPLOAD_TOKEN"):
        _ = load_settings()

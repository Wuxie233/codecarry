from __future__ import annotations

from fastapi.testclient import TestClient


def test_health_endpoint_returns_ok(diagnostics_client: TestClient) -> None:
    response = diagnostics_client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}

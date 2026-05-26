from __future__ import annotations

from fastapi.testclient import TestClient


def test_reports_requires_bearer_token(diagnostics_client: TestClient) -> None:
    response = diagnostics_client.get("/reports")
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"


def test_reports_rejects_bad_bearer_token(diagnostics_client: TestClient) -> None:
    response = diagnostics_client.get("/reports", headers={"Authorization": "Bearer wrong-token"})

    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"

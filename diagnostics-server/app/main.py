from __future__ import annotations

from typing import Annotated

from fastapi import Depends, FastAPI, File, HTTPException, Request, UploadFile, status

from .config import Settings, load_settings
from .storage import DiagnosticsStorage


def _require_bearer(request: Request, settings: Settings) -> None:
    authorization = request.headers.get("authorization", "")
    prefix = "Bearer "
    if not authorization.startswith(prefix):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Unauthorized", headers={"WWW-Authenticate": "Bearer"})
    if authorization[len(prefix):].strip() != settings.token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Unauthorized", headers={"WWW-Authenticate": "Bearer"})


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved_settings = settings or load_settings()
    storage = DiagnosticsStorage(resolved_settings.storage_dir, resolved_settings.max_bytes)
    app = FastAPI(title="Diagnostics Upload Backend")

    def auth_dependency(request: Request) -> None:
        _require_bearer(request, resolved_settings)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/upload", status_code=status.HTTP_201_CREATED, dependencies=[Depends(auth_dependency)])
    async def upload(file: Annotated[UploadFile | None, File()] = None) -> dict[str, object]:
        if file is None:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Missing file")
        try:
            record = await storage.save_upload(file)
        except ValueError as exc:
            message = str(exc)
            if message == "missing filename":
                raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Missing file") from exc
            if message == "empty file":
                raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Empty file") from exc
            if message == "unsupported extension":
                raise HTTPException(status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, detail="Unsupported file type") from exc
            if message == "too large":
                raise HTTPException(status_code=status.HTTP_413_CONTENT_TOO_LARGE, detail="File too large") from exc
            raise
        except RuntimeError as exc:
            raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="Storage failure") from exc
        return record.to_dict()

    @app.get("/reports", dependencies=[Depends(auth_dependency)])
    def reports() -> list[dict[str, object]]:
        return [record.to_dict() for record in storage.list_reports()]

    _ = (health, upload, reports)

    return app


app = create_app()

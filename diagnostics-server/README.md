# Diagnostics Upload Backend

Minimal FastAPI service for local diagnostics upload handling.

This backend is intentionally provider-neutral: it stores files on the local filesystem and can be exposed publicly through any HTTPS tunnel or reverse proxy you choose.

## What it does

- Public `GET /health` endpoint for liveness checks.
- Bearer-protected `POST /upload` endpoint for multipart file uploads.
- Bearer-protected `GET /reports` endpoint that returns upload metadata only.
- Local filesystem storage with no dashboard or web UI.

## Requirements

- Python 3
- `python3 -m pip`

## Install

```bash
cd diagnostics-server
python3 -m pip install -r requirements.txt
```

## Environment

Set these environment variables before running the backend:

- `DIAG_UPLOAD_TOKEN` required bearer token for `/upload` and `/reports`
- `DIAG_UPLOAD_STORAGE_DIR` storage root, default `./diagnostics_uploads`
- `DIAG_UPLOAD_MAX_BYTES` maximum upload size in bytes, default `10485760` (10 MiB)
- `DIAG_UPLOAD_HOST` bind host for Uvicorn, default `127.0.0.1`
- `DIAG_UPLOAD_PORT` bind port for Uvicorn, default `8765`

## Run locally

```bash
cd diagnostics-server
DIAG_UPLOAD_TOKEN=test-token python3 -m uvicorn app.main:app --host 127.0.0.1 --port 8765
```

The backend binds only to the host and port you choose. For public HTTPS exposure, place it behind any tunnel or reverse proxy you control. No provider-specific service is required.

## Health check

```bash
curl http://127.0.0.1:8765/health
```

Expected response:

```json
{"status":"ok"}
```

## Upload API

Allowed upload extensions:

- `.zip`
- `.log`
- `.txt`
- `.json`

Uploads are stored under UUID directories as `upload<ext>`. The original filename is sanitized and preserved in metadata. The default size limit is 10 MiB (`DIAG_UPLOAD_MAX_BYTES=10485760`).

### Unauthorized upload

```bash
curl -i -F "file=@./sample.zip" http://127.0.0.1:8765/upload
```

Expected response: `401 Unauthorized`

### Authorized upload

```bash
curl -i \
  -H "Authorization: Bearer test-token" \
  -F "file=@./sample.zip" \
  http://127.0.0.1:8765/upload
```

Expected response: `201 Created` with JSON metadata.

## Reports API

`GET /reports` returns metadata only. It never returns file contents.

```bash
curl -i \
  -H "Authorization: Bearer test-token" \
  http://127.0.0.1:8765/reports
```

Expected response: `200 OK` with a JSON array of upload metadata entries.

## Storage

- Default storage directory: `./diagnostics_uploads`
- Each upload gets its own UUID directory
- Stored file name: `upload<ext>`
- Metadata is written alongside the file as `metadata.json`
- Reports are read from metadata files only
- The upload filename is sanitized before storage, but the server keeps the original name in metadata
- The service has no web UI or dashboard

## Tests

```bash
cd diagnostics-server
PYTHONDONTWRITEBYTECODE=1 python3 -m pytest
```

## Notes

- `DIAG_UPLOAD_TOKEN` is required.
- `DIAG_UPLOAD_STORAGE_DIR` defaults to `./diagnostics_uploads`.
- `DIAG_UPLOAD_MAX_BYTES` defaults to `10485760`.
- `DIAG_UPLOAD_HOST` defaults to `127.0.0.1`.
- `DIAG_UPLOAD_PORT` defaults to `8765`.
- The backend is provider-neutral and does not depend on any upload provider SDK or provider-specific configuration.
- Public exposure is done outside the app itself, via any tunnel or reverse proxy you control.
